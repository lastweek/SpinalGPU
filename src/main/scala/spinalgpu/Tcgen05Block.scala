package spinalgpu

import spinal.core._
import spinal.lib._

class Tcgen05Block(config: SmConfig) extends Component {
  require(config.warpSize == 32, "tcgen05 v1.5 requires a full 32-lane warp")

  private object State extends SpinalEnum {
    val IDLE, COLLECT0, COLLECT1, COLLECT2, SHARED_REQ, SHARED_WAIT, TENSOR_REQ, TENSOR_WAIT, COMPUTE_INIT, COMPUTE_STEP, PACK_RESULTS, RESPOND =
      newElement()
  }

  private object MmaPhase extends SpinalEnum {
    val NONE, LOAD_A, LOAD_B, LOAD_D, WRITE_D = newElement()
  }

  val io = new Bundle {
    val launch = slave(Stream(Tcgen05LaunchReq(config)))
    val event = master(Stream(Tcgen05Event(config)))
    val readAddrA = out(UInt(config.registerAddressWidth bits))
    val readAddrB = out(UInt(config.registerAddressWidth bits))
    val readAddrC = out(UInt(config.registerAddressWidth bits))
    val readDataA = in(Vec(UInt(config.dataWidth bits), config.warpSize))
    val readDataB = in(Vec(UInt(config.dataWidth bits), config.warpSize))
    val readDataC = in(Vec(UInt(config.dataWidth bits), config.warpSize))
    val sharedMemReq = master(Stream(SharedMemReq(config)))
    val sharedMemRsp = slave(Stream(SharedMemRsp(config)))
    val tensorMemReq = master(Stream(TensorMemReq(config)))
    val tensorMemRsp = slave(Stream(TensorMemRsp(config)))
    val ownsRegisterReads = out Bool()
    val busy = out Bool()
    val debugState = out(UInt(4 bits))
  }

  private val fullWarpMask = B(Tcgen05Layouts.FullWarpMask32, config.warpSize bits)
  private val launchClass = Tcgen05OpClass()
  launchClass := Tcgen05OpClass.NONE
  switch(io.launch.payload.opcode) {
    is(B(Opcode.TCGEN05_LD_32X32B_X2, 8 bits)) {
      launchClass := Tcgen05OpClass.LD
    }
    is(B(Opcode.TCGEN05_ST_32X32B_X2, 8 bits)) {
      launchClass := Tcgen05OpClass.ST
    }
    is(B(Opcode.TCGEN05_MMA_CTA1_F16, 8 bits)) {
      launchClass := Tcgen05OpClass.MMA
    }
  }

  private val state = RegInit(State.IDLE)
  private val pending = Reg(Tcgen05LaunchReq(config))
  pending.warpId.init(0)
  pending.localSlotId.init(0)
  pending.opcode.init(0)
  pending.activeMask.init(0)
  pending.rdBase.init(0)
  pending.rs0Base.init(0)
  pending.rs1Base.init(0)
  pending.rs2Base.init(0)

  private val pendingClass = Reg(Tcgen05OpClass()) init (Tcgen05OpClass.NONE)
  private val pendingFault = RegInit(False)
  private val pendingFaultCode = Reg(UInt(config.faultCodeWidth bits)) init (FaultCode.None)
  private val responseIndex = Reg(UInt(2 bits)) init (0)
  private val transferIndex = Reg(UInt(log2Up(Tcgen05Layouts.MaxWordCount) bits)) init (0)
  private val computeRow = Reg(UInt(log2Up(Tcgen05Layouts.M) bits)) init (0)
  private val computeCol = Reg(UInt(log2Up(Tcgen05Layouts.N) bits)) init (0)
  private val computeK = Reg(UInt(log2Up(Tcgen05Layouts.K) bits)) init (0)
  private val mmaPhase = Reg(MmaPhase()) init (MmaPhase.NONE)

  private val capturedTmemBaseByte = Reg(UInt(config.addressWidth bits)) init (0)
  private val capturedADesc = Vec.fill(Tcgen05Layouts.DescriptorRegisterCount)(Reg(UInt(config.dataWidth bits)) init (0))
  private val capturedBDesc = Vec.fill(Tcgen05Layouts.DescriptorRegisterCount)(Reg(UInt(config.dataWidth bits)) init (0))
  private val capturedControl = Vec.fill(Tcgen05Layouts.ControlRegisterCount)(Reg(UInt(config.dataWidth bits)) init (0))
  private val transferWords = Vec.fill(Tcgen05Layouts.TransferShapeWords)(Reg(Bits(config.dataWidth bits)) init (0))
  private val aWords = Vec.fill(Tcgen05Layouts.AWordCount)(Reg(Bits(config.dataWidth bits)) init (0))
  private val bWords = Vec.fill(Tcgen05Layouts.BWordCount)(Reg(Bits(config.dataWidth bits)) init (0))
  private val accumMatrix = Vec.fill(Tcgen05Layouts.M)(Vec.fill(Tcgen05Layouts.N)(Reg(Bits(16 bits)) init (0)))

  private def lowHalf(word: Bits): Bits = word(15 downto 0)

  private def highHalf(word: Bits): Bits = word(31 downto 16)

  private def uniformMismatch(values: Vec[UInt]): Bool = {
    val mismatch = Bool()
    mismatch := False
    for (lane <- 1 until config.warpSize) {
      when(values(lane) =/= values(0)) {
        mismatch := True
      }
    }
    mismatch
  }

  private def sharedDescriptorMisaligned(desc: UInt): Bool = desc(1 downto 0) =/= 0

  private def sharedDescriptorOutOfRange(desc: UInt, wordCount: Int): Bool = {
    val finalWordExclusive = UInt((config.sharedAddressWidth + 1) bits)
    finalWordExclusive := desc(config.sharedAddressWidth + 1 downto 2).resize(config.sharedAddressWidth + 1) + U(wordCount, config.sharedAddressWidth + 1 bits)
    finalWordExclusive > U(config.sharedWordCount, config.sharedAddressWidth + 1 bits)
  }

  private def tensorAddressMisaligned(baseByte: UInt): Bool = baseByte(3 downto 0) =/= 0

  private def tensorAddressOutOfRange(baseByte: UInt): Bool = {
    val finalWordExclusive = UInt((config.tensorLocalAddressWidth + 1) bits)
    finalWordExclusive := baseByte(config.tensorLocalAddressWidth + 1 downto 2).resize(config.tensorLocalAddressWidth + 1) +
      U(Tcgen05Layouts.TransferShapeWords, config.tensorLocalAddressWidth + 1 bits)
    finalWordExclusive > U(config.tensorWordsPerWarp, config.tensorLocalAddressWidth + 1 bits)
  }

  private def wordLane(index: UInt): UInt = index(4 downto 0)

  private def wordAddressBase(baseByte: UInt): UInt =
    baseByte(config.tensorLocalAddressWidth + 1 downto 2).resize(config.tensorLocalAddressWidth)

  private def sharedWordAddressBase(baseByte: UInt): UInt =
    baseByte(config.sharedAddressWidth + 1 downto 2).resize(config.sharedAddressWidth)

  private val currentComputeA = Bits(16 bits)
  private val currentComputeB = Bits(16 bits)
  private val currentComputeAcc = accumMatrix(computeRow)(computeCol)
  currentComputeA := B(0, 16 bits)
  currentComputeB := B(0, 16 bits)

  for (row <- 0 until Tcgen05Layouts.M) {
    for (col <- 0 until Tcgen05Layouts.K) {
      when(computeRow === row && computeK === col) {
        val word = aWords(Tcgen05Layouts.aWordIndex(row, col))
        currentComputeA := (if (Tcgen05Layouts.matrixHalfIndex(col) == 0) lowHalf(word) else highHalf(word))
      }
    }
  }

  for (row <- 0 until Tcgen05Layouts.K) {
    for (col <- 0 until Tcgen05Layouts.N) {
      when(computeK === row && computeCol === col) {
        val word = bWords(Tcgen05Layouts.bWordIndex(row, col))
        currentComputeB := (if (Tcgen05Layouts.matrixHalfIndex(col) == 0) lowHalf(word) else highHalf(word))
      }
    }
  }

  private val currentComputeNext = Fp16Math.fma(currentComputeA, currentComputeB, currentComputeAcc)

  io.launch.ready := state === State.IDLE
  io.ownsRegisterReads := state === State.COLLECT0 || state === State.COLLECT1 || state === State.COLLECT2
  io.busy := state =/= State.IDLE
  io.debugState := state.asBits.asUInt.resized

  io.readAddrA := U(0, config.registerAddressWidth bits)
  io.readAddrB := U(0, config.registerAddressWidth bits)
  io.readAddrC := U(0, config.registerAddressWidth bits)

  switch(state) {
    is(State.COLLECT0) {
      when(pending.opcode === B(Opcode.TCGEN05_LD_32X32B_X2, 8 bits)) {
        io.readAddrA := pending.rs0Base
      } elsewhen (pending.opcode === B(Opcode.TCGEN05_ST_32X32B_X2, 8 bits)) {
        io.readAddrA := pending.rs0Base
        io.readAddrB := pending.rs1Base
        io.readAddrC := pending.rs1Base + 1
      } otherwise {
        io.readAddrA := pending.rdBase
        io.readAddrB := pending.rs0Base
        io.readAddrC := pending.rs0Base + 1
      }
    }
    is(State.COLLECT1) {
      io.readAddrA := pending.rs1Base
      io.readAddrB := pending.rs1Base + 1
      io.readAddrC := pending.rs2Base
    }
    is(State.COLLECT2) {
      io.readAddrA := pending.rs2Base + 1
    }
    default {
    }
  }

  private val sharedRequestByteAddress = UInt(config.addressWidth bits)
  private val tensorRequestAddress = UInt(config.tensorAddressWidth bits)
  sharedRequestByteAddress := 0

  io.sharedMemReq.valid := False
  io.sharedMemReq.payload.warpId := pending.warpId
  io.sharedMemReq.payload.write := False
  io.sharedMemReq.payload.address := U(0, config.sharedAddressWidth bits)
  io.sharedMemReq.payload.writeData := B(0, config.dataWidth bits)
  io.sharedMemReq.payload.byteMask := B(0xF, config.byteMaskWidth bits)
  io.sharedMemRsp.ready := state === State.SHARED_WAIT

  when(mmaPhase === MmaPhase.LOAD_A) {
    sharedRequestByteAddress := capturedADesc(0).resized + (transferIndex.resize(config.addressWidth bits) |<< 2)
  } elsewhen (mmaPhase === MmaPhase.LOAD_B) {
    sharedRequestByteAddress := capturedBDesc(0).resized + (transferIndex.resize(config.addressWidth bits) |<< 2)
  }

  when(state === State.SHARED_REQ) {
    io.sharedMemReq.valid := True
    io.sharedMemReq.payload.address := sharedWordAddressBase(sharedRequestByteAddress)
  }

  private val warpWindowBase = UInt((config.tensorAddressWidth + 1) bits)
  private val tensorLocalBase = UInt(config.tensorLocalAddressWidth bits)
  warpWindowBase := (pending.warpId.resize(config.tensorAddressWidth + 1) * U(config.tensorWordsPerWarp, config.tensorAddressWidth + 1 bits)).resized
  tensorLocalBase := wordAddressBase(capturedTmemBaseByte)
  tensorRequestAddress := (warpWindowBase + tensorLocalBase.resize(config.tensorAddressWidth + 1) + transferIndex.resize(config.tensorAddressWidth + 1)).resized

  io.tensorMemReq.valid := False
  io.tensorMemReq.payload.warpId := pending.warpId
  io.tensorMemReq.payload.write := False
  io.tensorMemReq.payload.address := tensorRequestAddress
  io.tensorMemReq.payload.writeData := transferWords(transferIndex.resize(log2Up(Tcgen05Layouts.TransferShapeWords) bits))
  io.tensorMemRsp.ready := state === State.TENSOR_WAIT

  when(state === State.TENSOR_REQ) {
    io.tensorMemReq.valid := True
    when(pendingClass === Tcgen05OpClass.ST || mmaPhase === MmaPhase.WRITE_D) {
      io.tensorMemReq.payload.write := True
    }
  }

  io.event.valid := state === State.RESPOND
  io.event.payload.warpId := pending.warpId
  io.event.payload.localSlotId := pending.localSlotId
  io.event.payload.opClass := pendingClass
  io.event.payload.completed := True
  io.event.payload.writeEnable := False
  io.event.payload.writeOffset := responseIndex
  io.event.payload.error := pendingFault
  io.event.payload.faultCode := pendingFaultCode
  for (lane <- 0 until config.warpSize) {
    io.event.payload.result(lane) := B(0, config.dataWidth bits)
  }

  when(state === State.RESPOND && !pendingFault && pendingClass === Tcgen05OpClass.LD) {
    io.event.payload.writeEnable := True
    io.event.payload.completed := responseIndex === 1
    for (lane <- 0 until config.warpSize) {
      when(responseIndex === 0) {
        io.event.payload.result(lane) := transferWords(lane)
      } otherwise {
        io.event.payload.result(lane) := transferWords(config.warpSize + lane)
      }
    }
  }

  when(io.launch.fire) {
    pending := io.launch.payload
    pendingFault := False
    pendingFaultCode := FaultCode.None
    responseIndex := 0
    transferIndex := 0
    computeRow := 0
    computeCol := 0
    computeK := 0
    mmaPhase := MmaPhase.NONE
    capturedTmemBaseByte := 0
    for (index <- 0 until Tcgen05Layouts.DescriptorRegisterCount) {
      capturedADesc(index) := 0
      capturedBDesc(index) := 0
      capturedControl(index) := 0
    }
    for (index <- 0 until Tcgen05Layouts.TransferShapeWords) {
      transferWords(index) := B(0, config.dataWidth bits)
    }
    for (index <- 0 until Tcgen05Layouts.AWordCount) {
      aWords(index) := B(0, config.dataWidth bits)
    }
    for (index <- 0 until Tcgen05Layouts.BWordCount) {
      bWords(index) := B(0, config.dataWidth bits)
    }

    pendingClass := launchClass

    when(io.launch.payload.activeMask =/= fullWarpMask) {
      pendingFault := True
      pendingFaultCode := FaultCode.TensorProtocol
      state := State.RESPOND
    } elsewhen (launchClass === Tcgen05OpClass.NONE) {
      pendingFault := True
      pendingFaultCode := FaultCode.TensorProtocol
      state := State.RESPOND
    } otherwise {
      state := State.COLLECT0
    }
  }

  when(state === State.COLLECT0) {
    when(pendingClass === Tcgen05OpClass.LD) {
      when(uniformMismatch(io.readDataA)) {
        pendingFault := True
        pendingFaultCode := FaultCode.TensorProtocol
        state := State.RESPOND
      } otherwise {
        capturedTmemBaseByte := io.readDataA(0).resized
        when(tensorAddressMisaligned(io.readDataA(0).resized)) {
          pendingFault := True
          pendingFaultCode := FaultCode.MisalignedLoadStore
          state := State.RESPOND
        } elsewhen (tensorAddressOutOfRange(io.readDataA(0).resized)) {
          pendingFault := True
          pendingFaultCode := FaultCode.TensorMemory
          state := State.RESPOND
        } otherwise {
          transferIndex := 0
          state := State.TENSOR_REQ
        }
      }
    } elsewhen (pendingClass === Tcgen05OpClass.ST) {
      when(uniformMismatch(io.readDataA)) {
        pendingFault := True
        pendingFaultCode := FaultCode.TensorProtocol
        state := State.RESPOND
      } otherwise {
        capturedTmemBaseByte := io.readDataA(0).resized
        for (lane <- 0 until config.warpSize) {
          transferWords(lane) := io.readDataB(lane).asBits
          transferWords(config.warpSize + lane) := io.readDataC(lane).asBits
        }
        when(tensorAddressMisaligned(io.readDataA(0).resized)) {
          pendingFault := True
          pendingFaultCode := FaultCode.MisalignedLoadStore
          state := State.RESPOND
        } elsewhen (tensorAddressOutOfRange(io.readDataA(0).resized)) {
          pendingFault := True
          pendingFaultCode := FaultCode.TensorMemory
          state := State.RESPOND
        } otherwise {
          transferIndex := 0
          state := State.TENSOR_REQ
        }
      }
    } otherwise {
      when(uniformMismatch(io.readDataA) || uniformMismatch(io.readDataB) || uniformMismatch(io.readDataC)) {
        pendingFault := True
        pendingFaultCode := FaultCode.TensorProtocol
        state := State.RESPOND
      } otherwise {
        capturedTmemBaseByte := io.readDataA(0).resized
        capturedADesc(0) := io.readDataB(0)
        capturedADesc(1) := io.readDataC(0)
        state := State.COLLECT1
      }
    }
  }

  when(state === State.COLLECT1) {
    when(uniformMismatch(io.readDataA) || uniformMismatch(io.readDataB) || uniformMismatch(io.readDataC)) {
      pendingFault := True
      pendingFaultCode := FaultCode.TensorProtocol
      state := State.RESPOND
    } otherwise {
      capturedBDesc(0) := io.readDataA(0)
      capturedBDesc(1) := io.readDataB(0)
      capturedControl(0) := io.readDataC(0)
      state := State.COLLECT2
    }
  }

  when(state === State.COLLECT2) {
    when(uniformMismatch(io.readDataA)) {
      pendingFault := True
      pendingFaultCode := FaultCode.TensorProtocol
      state := State.RESPOND
    } otherwise {
      capturedControl(1) := io.readDataA(0)
      when(tensorAddressMisaligned(capturedTmemBaseByte) || sharedDescriptorMisaligned(capturedADesc(0)) || sharedDescriptorMisaligned(capturedBDesc(0))) {
        pendingFault := True
        pendingFaultCode := FaultCode.MisalignedLoadStore
        state := State.RESPOND
      } elsewhen (
        tensorAddressOutOfRange(capturedTmemBaseByte) ||
          sharedDescriptorOutOfRange(capturedADesc(0), Tcgen05Layouts.AWordCount) ||
          sharedDescriptorOutOfRange(capturedBDesc(0), Tcgen05Layouts.BWordCount)
      ) {
        pendingFault := True
        pendingFaultCode := FaultCode.TensorMemory
        state := State.RESPOND
      } elsewhen (
        capturedADesc(1) =/= 0 ||
          capturedBDesc(1) =/= 0 ||
          capturedControl(1) =/= 0 ||
          (capturedControl(0) & U(BigInt("FFFFFFFE", 16), config.dataWidth bits)) =/= U(Tcgen05Layouts.F16InstructionDescriptor, config.dataWidth bits)
      ) {
        pendingFault := True
        pendingFaultCode := FaultCode.TensorProtocol
        state := State.RESPOND
      } otherwise {
        mmaPhase := MmaPhase.LOAD_A
        transferIndex := 0
        state := State.SHARED_REQ
      }
    }
  }

  when(state === State.SHARED_REQ) {
    when(io.sharedMemReq.ready) {
      state := State.SHARED_WAIT
    }
  }

  when(state === State.SHARED_WAIT) {
    when(io.sharedMemRsp.valid) {
      when(io.sharedMemRsp.payload.error) {
        pendingFault := True
        pendingFaultCode := FaultCode.ExternalMemory
        state := State.RESPOND
      } otherwise {
        when(mmaPhase === MmaPhase.LOAD_A) {
          aWords(transferIndex) := io.sharedMemRsp.payload.readData
        } otherwise {
          bWords(transferIndex.resize(log2Up(Tcgen05Layouts.BWordCount) bits)) := io.sharedMemRsp.payload.readData
        }

        val lastIndex = UInt(transferIndex.getWidth bits)
        lastIndex := U(Tcgen05Layouts.BWordCount - 1, transferIndex.getWidth bits)
        when(mmaPhase === MmaPhase.LOAD_A) {
          lastIndex := U(Tcgen05Layouts.AWordCount - 1, transferIndex.getWidth bits)
        }

        when(transferIndex === lastIndex) {
          transferIndex := 0
          when(mmaPhase === MmaPhase.LOAD_A) {
            mmaPhase := MmaPhase.LOAD_B
            state := State.SHARED_REQ
          } otherwise {
            when(capturedControl(0)(0)) {
              mmaPhase := MmaPhase.LOAD_D
              state := State.TENSOR_REQ
            } otherwise {
              for (index <- 0 until Tcgen05Layouts.TransferShapeWords) {
                transferWords(index) := B(0, config.dataWidth bits)
              }
              mmaPhase := MmaPhase.NONE
              state := State.COMPUTE_INIT
            }
          }
        } otherwise {
          transferIndex := transferIndex + 1
          state := State.SHARED_REQ
        }
      }
    }
  }

  when(state === State.TENSOR_REQ) {
    when(io.tensorMemReq.ready) {
      state := State.TENSOR_WAIT
    }
  }

  when(state === State.TENSOR_WAIT) {
    when(io.tensorMemRsp.valid) {
      when(io.tensorMemRsp.payload.error) {
        pendingFault := True
        pendingFaultCode := FaultCode.ExternalMemory
        state := State.RESPOND
      } otherwise {
        when(pendingClass === Tcgen05OpClass.ST || mmaPhase === MmaPhase.WRITE_D) {
          when(transferIndex === U(Tcgen05Layouts.TransferShapeWords - 1, transferIndex.getWidth bits)) {
            responseIndex := 0
            mmaPhase := MmaPhase.NONE
            state := State.RESPOND
          } otherwise {
            transferIndex := transferIndex + 1
            state := State.TENSOR_REQ
          }
        } otherwise {
          transferWords(transferIndex.resize(log2Up(Tcgen05Layouts.TransferShapeWords) bits)) := io.tensorMemRsp.payload.readData
          when(transferIndex === U(Tcgen05Layouts.TransferShapeWords - 1, transferIndex.getWidth bits)) {
            transferIndex := 0
            when(pendingClass === Tcgen05OpClass.LD) {
              responseIndex := 0
              state := State.RESPOND
            } otherwise {
              mmaPhase := MmaPhase.NONE
              state := State.COMPUTE_INIT
            }
          } otherwise {
            transferIndex := transferIndex + 1
            state := State.TENSOR_REQ
          }
        }
      }
    }
  }

  when(state === State.COMPUTE_INIT) {
    for (row <- 0 until Tcgen05Layouts.M) {
      for (col <- 0 until Tcgen05Layouts.N) {
        val word = transferWords(Tcgen05Layouts.dWordIndex(row, col))
        accumMatrix(row)(col) := (if (Tcgen05Layouts.matrixHalfIndex(col) == 0) lowHalf(word) else highHalf(word))
      }
    }
    computeRow := 0
    computeCol := 0
    computeK := 0
    state := State.COMPUTE_STEP
  }

  when(state === State.COMPUTE_STEP) {
    accumMatrix(computeRow)(computeCol) := currentComputeNext

    when(computeK === Tcgen05Layouts.K - 1) {
      computeK := 0
      when(computeCol === Tcgen05Layouts.N - 1) {
        computeCol := 0
        when(computeRow === Tcgen05Layouts.M - 1) {
          state := State.PACK_RESULTS
        } otherwise {
          computeRow := computeRow + 1
        }
      } otherwise {
        computeCol := computeCol + 1
      }
    } otherwise {
      computeK := computeK + 1
    }
  }

  when(state === State.PACK_RESULTS) {
    for (row <- 0 until Tcgen05Layouts.M) {
      for (col <- 0 until Tcgen05Layouts.N by 2) {
        val wordIndex = Tcgen05Layouts.dWordIndex(row, col)
        transferWords(wordIndex) := accumMatrix(row)(col + 1) ## accumMatrix(row)(col)
      }
    }
    mmaPhase := MmaPhase.WRITE_D
    transferIndex := 0
    state := State.TENSOR_REQ
  }

  when(state === State.RESPOND && io.event.ready) {
    when(pendingFault || pendingClass =/= Tcgen05OpClass.LD || responseIndex === 1) {
      state := State.IDLE
      pendingClass := Tcgen05OpClass.NONE
    } otherwise {
      responseIndex := responseIndex + 1
    }
  }
}
