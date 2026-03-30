package spinalgpu

import spinal.core._
import spinal.lib._

class TensorCoreBlock(config: SmConfig) extends Component {
  require(config.warpSize == 32, "tensor core v1 requires a full 32-lane warp")

  private object State extends SpinalEnum {
    val IDLE, COLLECT0, COLLECT1, COLLECT2, SHARED_REQ, SHARED_WAIT, COMPUTE_INIT, COMPUTE_STEP, PACK_RESULTS, RESPOND =
      newElement()
  }

  val io = new Bundle {
    val issue = slave(Stream(TensorReq(config)))
    val response = master(Stream(TensorRsp(config)))
    val readAddrA = out(UInt(config.registerAddressWidth bits))
    val readAddrB = out(UInt(config.registerAddressWidth bits))
    val readAddrC = out(UInt(config.registerAddressWidth bits))
    val readDataA = in(Vec(UInt(config.dataWidth bits), config.warpSize))
    val readDataB = in(Vec(UInt(config.dataWidth bits), config.warpSize))
    val readDataC = in(Vec(UInt(config.dataWidth bits), config.warpSize))
    val sharedMemReq = master(Stream(SharedMemReq(config)))
    val sharedMemRsp = slave(Stream(SharedMemRsp(config)))
  }

  private val fullWarpMask = B(TensorCoreLayouts.FullWarpMask32, config.warpSize bits)

  private val state = RegInit(State.IDLE)
  private val pending = Reg(TensorReq(config))
  pending.warpId.init(0)
  pending.opcode.init(0)
  pending.activeMask.init(0)
  pending.rdBase.init(0)
  pending.rs0Base.init(0)
  pending.rs1Base.init(0)
  pending.rs2Base.init(0)
  private val pendingWriteCount = Reg(UInt(3 bits)) init (0)
  private val pendingFault = RegInit(False)
  private val pendingFaultCode = Reg(UInt(config.faultCodeWidth bits)) init (FaultCode.None)
  private val responseIndex = Reg(UInt(2 bits)) init (0)
  private val sharedMatrixIndex = Reg(UInt(2 bits)) init (0)
  private val sharedLaneIndex = Reg(UInt(log2Up(config.warpSize) bits)) init (0)
  private val computeRow = Reg(UInt(log2Up(TensorCoreLayouts.M) bits)) init (0)
  private val computeCol = Reg(UInt(log2Up(TensorCoreLayouts.N) bits)) init (0)
  private val computeK = Reg(UInt(log2Up(TensorCoreLayouts.K) bits)) init (0)

  private val capturedAddresses = Vec.fill(config.warpSize)(Reg(UInt(config.addressWidth bits)) init (0))
  private val capturedA = Vec.fill(4)(Vec.fill(config.warpSize)(Reg(Bits(config.dataWidth bits)) init (0)))
  private val capturedB = Vec.fill(2)(Vec.fill(config.warpSize)(Reg(Bits(config.dataWidth bits)) init (0)))
  private val capturedC = Vec.fill(2)(Vec.fill(config.warpSize)(Reg(Bits(config.dataWidth bits)) init (0)))
  private val sharedWords = Vec.fill(4)(Vec.fill(config.warpSize)(Reg(Bits(config.dataWidth bits)) init (0)))
  private val resultBanks = Vec.fill(4)(Vec.fill(config.warpSize)(Reg(Bits(config.dataWidth bits)) init (0)))
  private val accumMatrix =
    Vec.fill(TensorCoreLayouts.M)(Vec.fill(TensorCoreLayouts.N)(Reg(Bits(16 bits)) init (0)))

  private def lowHalf(word: Bits): Bits = word(15 downto 0)

  private def highHalf(word: Bits): Bits = word(31 downto 16)

  private def validateTensorAddressProviders(values: Vec[UInt], providerCount: Int): Bool = {
    val misaligned = Bool()
    misaligned := False
    for (lane <- 0 until providerCount) {
      when(values(lane)(3 downto 0) =/= 0) {
        misaligned := True
      }
    }
    misaligned
  }

  private val aMatrix = Vec.fill(TensorCoreLayouts.M)(Vec.fill(TensorCoreLayouts.K)(Bits(16 bits)))
  private val bMatrix = Vec.fill(TensorCoreLayouts.K)(Vec.fill(TensorCoreLayouts.N)(Bits(16 bits)))
  private val cMatrix = Vec.fill(TensorCoreLayouts.M)(Vec.fill(TensorCoreLayouts.N)(Bits(16 bits)))

  for (row <- 0 until TensorCoreLayouts.M) {
    for (col <- 0 until TensorCoreLayouts.K) {
      val word = capturedA(TensorCoreLayouts.aRegister(row, col))(TensorCoreLayouts.aLane(row, col))
      aMatrix(row)(col) := (if (TensorCoreLayouts.aHalf(col) == 0) lowHalf(word) else highHalf(word))
    }
  }

  for (row <- 0 until TensorCoreLayouts.K) {
    for (col <- 0 until TensorCoreLayouts.N) {
      val word = capturedB(TensorCoreLayouts.bRegister(row))(TensorCoreLayouts.bLane(row, col))
      bMatrix(row)(col) := (if (TensorCoreLayouts.bHalf(row) == 0) lowHalf(word) else highHalf(word))
    }
  }

  for (row <- 0 until TensorCoreLayouts.M) {
    for (col <- 0 until TensorCoreLayouts.N) {
      val word = capturedC(TensorCoreLayouts.cdRegister(row))(TensorCoreLayouts.cdLane(row, col))
      cMatrix(row)(col) := (if (TensorCoreLayouts.cdHalf(col) == 0) lowHalf(word) else highHalf(word))
    }
  }

  private val currentComputeA = aMatrix(computeRow)(computeK)
  private val currentComputeB = bMatrix(computeK)(computeCol)
  private val currentComputeAcc = accumMatrix(computeRow)(computeCol)
  private val currentComputeNext = Fp16Math.fma(currentComputeA, currentComputeB, currentComputeAcc)

  private val sharedRequestByteAddress = UInt(config.addressWidth bits)
  private val sharedRequestWordAddress = UInt(config.sharedAddressWidth bits)
  private val sharedMatrixBaseLane = UInt(log2Up(config.warpSize) bits)
  private val sharedLaneRow = UInt(log2Up(TensorCoreLayouts.TileRows) bits)
  private val sharedLaneWordOffset = UInt(config.addressWidth bits)
  private val lastWarpLane = U(config.warpSize - 1, sharedLaneIndex.getWidth bits)
  private val lastLdmatrixX4Bank = U(TensorCoreLayouts.LdmatrixX4WriteCount - 1, sharedMatrixIndex.getWidth bits)
  private val lastLdmatrixX2Bank = U(TensorCoreLayouts.LdmatrixX2WriteCount - 1, sharedMatrixIndex.getWidth bits)
  private val lastComputeRow = U(TensorCoreLayouts.M - 1, computeRow.getWidth bits)
  private val lastComputeCol = U(TensorCoreLayouts.N - 1, computeCol.getWidth bits)
  private val lastComputeK = U(TensorCoreLayouts.K - 1, computeK.getWidth bits)

  sharedMatrixBaseLane := (sharedMatrixIndex.resize(log2Up(config.warpSize)) |<< 3).resized
  sharedLaneRow := (sharedLaneIndex |>> 2).resized
  sharedLaneWordOffset := (sharedLaneIndex(1 downto 0).resize(config.addressWidth) |<< 2).resized
  sharedRequestByteAddress := 0
  sharedRequestWordAddress := sharedRequestByteAddress(config.sharedAddressWidth + 1 downto 2)

  io.issue.ready := state === State.IDLE

  io.readAddrA := U(0, config.registerAddressWidth bits)
  io.readAddrB := U(0, config.registerAddressWidth bits)
  io.readAddrC := U(0, config.registerAddressWidth bits)

  switch(state) {
    is(State.COLLECT0) {
      io.readAddrA := pending.rs0Base
      when(pending.opcode === B(Opcode.MMA_SYNC_F16_F16_F16_F16, 8 bits)) {
        io.readAddrB := pending.rs1Base
        io.readAddrC := pending.rs2Base
      } elsewhen (pending.opcode === B(Opcode.STMATRIX_X2, 8 bits)) {
        io.readAddrB := pending.rs1Base
        io.readAddrC := pending.rs1Base + 1
      }
    }
    is(State.COLLECT1) {
      io.readAddrA := pending.rs0Base + 1
      io.readAddrB := pending.rs1Base + 1
      io.readAddrC := pending.rs2Base + 1
    }
    is(State.COLLECT2) {
      io.readAddrA := pending.rs0Base + 2
      io.readAddrB := pending.rs0Base + 3
    }
    default {
    }
  }

  io.sharedMemReq.valid := False
  io.sharedMemReq.payload.warpId := pending.warpId
  io.sharedMemReq.payload.write := False
  io.sharedMemReq.payload.address := sharedRequestWordAddress
  io.sharedMemReq.payload.writeData := B(0, config.dataWidth bits)
  io.sharedMemReq.payload.byteMask := B(0xF, config.byteMaskWidth bits)
  io.sharedMemRsp.ready := state === State.SHARED_WAIT

  when(state === State.SHARED_REQ) {
    io.sharedMemReq.valid := True
    when(pending.opcode === B(Opcode.STMATRIX_X2, 8 bits)) {
      io.sharedMemReq.payload.write := True
      io.sharedMemReq.payload.writeData := sharedWords(sharedMatrixIndex)(sharedLaneIndex)
    }
  }

  io.response.valid := state === State.RESPOND
  io.response.payload.warpId := pending.warpId
  io.response.payload.completed := True
  io.response.payload.writeEnable := False
  io.response.payload.writeOffset := responseIndex
  io.response.payload.error := pendingFault
  io.response.payload.faultCode := pendingFaultCode
  for (lane <- 0 until config.warpSize) {
    io.response.payload.result(lane) := B(0, config.dataWidth bits)
  }

  when(state === State.RESPOND && !pendingFault && pendingWriteCount =/= 0) {
    io.response.payload.writeEnable := True
    io.response.payload.writeOffset := responseIndex
    io.response.payload.error := False
    io.response.payload.faultCode := FaultCode.None
    io.response.payload.completed := responseIndex.resized === (pendingWriteCount - 1)
    for (lane <- 0 until config.warpSize) {
      io.response.payload.result(lane) := resultBanks(responseIndex)(lane)
    }
  }

  when(io.issue.fire) {
    pending := io.issue.payload
    pendingWriteCount := 0
    pendingFault := False
    pendingFaultCode := FaultCode.None
    responseIndex := 0
    sharedMatrixIndex := 0
    sharedLaneIndex := 0
    computeRow := 0
    computeCol := 0
    computeK := 0
    for (lane <- 0 until config.warpSize) {
      capturedAddresses(lane) := U(0, config.addressWidth bits)
      for (bank <- 0 until 4) {
        capturedA(bank)(lane) := B(0, config.dataWidth bits)
        sharedWords(bank)(lane) := B(0, config.dataWidth bits)
        resultBanks(bank)(lane) := B(0, config.dataWidth bits)
      }
      for (bank <- 0 until 2) {
        capturedB(bank)(lane) := B(0, config.dataWidth bits)
        capturedC(bank)(lane) := B(0, config.dataWidth bits)
      }
    }

    when(io.issue.payload.activeMask =/= fullWarpMask) {
      pendingFault := True
      pendingFaultCode := FaultCode.TensorProtocol
      state := State.RESPOND
    } otherwise {
      switch(io.issue.payload.opcode) {
        is(B(Opcode.LDMATRIX_X4, 8 bits), B(Opcode.LDMATRIX_X2_TRANS, 8 bits), B(Opcode.LDMATRIX_X2, 8 bits), B(Opcode.STMATRIX_X2, 8 bits)) {
          state := State.COLLECT0
        }
        is(B(Opcode.MMA_SYNC_F16_F16_F16_F16, 8 bits)) {
          state := State.COLLECT0
        }
        default {
          pendingFault := True
          pendingFaultCode := FaultCode.TensorProtocol
          state := State.RESPOND
        }
      }
    }
  }

  when(state === State.COLLECT0) {
    for (lane <- 0 until config.warpSize) {
      capturedAddresses(lane) := io.readDataA(lane).resized
    }

    when(pending.opcode === B(Opcode.LDMATRIX_X4, 8 bits)) {
      when(validateTensorAddressProviders(io.readDataA, providerCount = 32)) {
        pendingFault := True
        pendingFaultCode := FaultCode.MisalignedLoadStore
        state := State.RESPOND
      } otherwise {
        state := State.SHARED_REQ
      }
    } elsewhen (pending.opcode === B(Opcode.LDMATRIX_X2_TRANS, 8 bits) || pending.opcode === B(Opcode.LDMATRIX_X2, 8 bits)) {
      when(validateTensorAddressProviders(io.readDataA, providerCount = 16)) {
        pendingFault := True
        pendingFaultCode := FaultCode.MisalignedLoadStore
        state := State.RESPOND
      } otherwise {
        state := State.SHARED_REQ
      }
    } elsewhen (pending.opcode === B(Opcode.STMATRIX_X2, 8 bits)) {
      when(validateTensorAddressProviders(io.readDataA, providerCount = 16)) {
        pendingFault := True
        pendingFaultCode := FaultCode.MisalignedLoadStore
        state := State.RESPOND
      } otherwise {
        for (lane <- 0 until config.warpSize) {
          capturedC(0)(lane) := io.readDataB(lane).asBits
          capturedC(1)(lane) := io.readDataC(lane).asBits
          sharedWords(0)(lane) := io.readDataB(lane).asBits
          sharedWords(1)(lane) := io.readDataC(lane).asBits
        }
        state := State.SHARED_REQ
      }
    } otherwise {
      for (lane <- 0 until config.warpSize) {
        capturedA(0)(lane) := io.readDataA(lane).asBits
        capturedB(0)(lane) := io.readDataB(lane).asBits
        capturedC(0)(lane) := io.readDataC(lane).asBits
      }
      state := State.COLLECT1
    }
  }

  when(state === State.COLLECT1) {
    for (lane <- 0 until config.warpSize) {
      capturedA(1)(lane) := io.readDataA(lane).asBits
      capturedB(1)(lane) := io.readDataB(lane).asBits
      capturedC(1)(lane) := io.readDataC(lane).asBits
    }
    state := State.COLLECT2
  }

  when(state === State.COLLECT2) {
    for (lane <- 0 until config.warpSize) {
      capturedA(2)(lane) := io.readDataA(lane).asBits
      capturedA(3)(lane) := io.readDataB(lane).asBits
    }
    state := State.COMPUTE_INIT
  }

  when(state === State.SHARED_REQ) {
    sharedRequestByteAddress := capturedAddresses((sharedMatrixBaseLane + sharedLaneRow).resized).resized + sharedLaneWordOffset

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
      } elsewhen (
        pending.opcode === B(Opcode.LDMATRIX_X4, 8 bits) ||
          pending.opcode === B(Opcode.LDMATRIX_X2, 8 bits) ||
          pending.opcode === B(Opcode.LDMATRIX_X2_TRANS, 8 bits)
      ) {
        sharedWords(sharedMatrixIndex)(sharedLaneIndex) := io.sharedMemRsp.payload.readData

        when(sharedLaneIndex === lastWarpLane) {
          sharedLaneIndex := 0
          when(sharedMatrixIndex === lastLdmatrixX4Bank && pending.opcode === B(Opcode.LDMATRIX_X4, 8 bits)) {
            state := State.PACK_RESULTS
          } elsewhen (
            sharedMatrixIndex === lastLdmatrixX2Bank &&
              (pending.opcode === B(Opcode.LDMATRIX_X2, 8 bits) || pending.opcode === B(Opcode.LDMATRIX_X2_TRANS, 8 bits))
          ) {
            state := State.PACK_RESULTS
          } otherwise {
            sharedMatrixIndex := sharedMatrixIndex + 1
            state := State.SHARED_REQ
          }
        } otherwise {
          sharedLaneIndex := sharedLaneIndex + 1
          state := State.SHARED_REQ
        }
      } otherwise {
        when(sharedLaneIndex === lastWarpLane) {
          sharedLaneIndex := 0
          when(sharedMatrixIndex === lastLdmatrixX2Bank) {
            pendingWriteCount := 0
            responseIndex := 0
            state := State.RESPOND
          } otherwise {
            sharedMatrixIndex := sharedMatrixIndex + 1
            state := State.SHARED_REQ
          }
        } otherwise {
          sharedLaneIndex := sharedLaneIndex + 1
          state := State.SHARED_REQ
        }
      }
    }
  }

  when(state === State.COMPUTE_INIT) {
    for (row <- 0 until TensorCoreLayouts.M) {
      for (col <- 0 until TensorCoreLayouts.N) {
        accumMatrix(row)(col) := cMatrix(row)(col)
      }
    }
    computeRow := 0
    computeCol := 0
    computeK := 0
    state := State.COMPUTE_STEP
  }

  when(state === State.COMPUTE_STEP) {
    accumMatrix(computeRow)(computeCol) := currentComputeNext

    val lastK = computeK === lastComputeK
    val lastCol = computeCol === lastComputeCol
    val lastRow = computeRow === lastComputeRow

    when(lastK && lastCol && lastRow) {
      state := State.PACK_RESULTS
    } elsewhen (lastK && lastCol) {
      computeK := 0
      computeCol := 0
      computeRow := computeRow + 1
    } elsewhen (lastK) {
      computeK := 0
      computeCol := computeCol + 1
    } otherwise {
      computeK := computeK + 1
    }
  }

  when(state === State.PACK_RESULTS) {
    switch(pending.opcode) {
      is(B(Opcode.LDMATRIX_X4, 8 bits)) {
        for (bank <- 0 until TensorCoreLayouts.LdmatrixX4WriteCount) {
          for (lane <- 0 until config.warpSize) {
            resultBanks(bank)(lane) := sharedWords(bank)(lane)
          }
        }
        pendingWriteCount := TensorCoreLayouts.LdmatrixX4WriteCount
      }
      is(B(Opcode.LDMATRIX_X2, 8 bits)) {
        for (bank <- 0 until TensorCoreLayouts.LdmatrixX2WriteCount) {
          for (lane <- 0 until config.warpSize) {
            resultBanks(bank)(lane) := sharedWords(bank)(lane)
          }
        }
        pendingWriteCount := TensorCoreLayouts.LdmatrixX2WriteCount
      }
      is(B(Opcode.LDMATRIX_X2_TRANS, 8 bits)) {
        for (lane <- 0 until config.warpSize) {
          resultBanks(0)(lane) := B(0, config.dataWidth bits)
          resultBanks(1)(lane) := B(0, config.dataWidth bits)
        }
        for (matrix <- 0 until TensorCoreLayouts.LdmatrixX2WriteCount) {
          for (row <- 0 until TensorCoreLayouts.TileRows) {
            for (col <- 0 until TensorCoreLayouts.TileCols) {
              val sourceLane = TensorCoreLayouts.rowMajorLane(row, col)
              val sourceWord = sharedWords(matrix)(sourceLane)
              val packedLane = (col * 4) + (row & 0x3)
              if (((row >> 2) & 0x1) == 0) {
                if (TensorCoreLayouts.rowMajorHalf(col) == 0) {
                  resultBanks(matrix)(packedLane)(15 downto 0) := lowHalf(sourceWord)
                } else {
                  resultBanks(matrix)(packedLane)(15 downto 0) := highHalf(sourceWord)
                }
              } else {
                if (TensorCoreLayouts.rowMajorHalf(col) == 0) {
                  resultBanks(matrix)(packedLane)(31 downto 16) := lowHalf(sourceWord)
                } else {
                  resultBanks(matrix)(packedLane)(31 downto 16) := highHalf(sourceWord)
                }
              }
            }
          }
        }
        pendingWriteCount := TensorCoreLayouts.LdmatrixX2WriteCount
      }
      default {
        for (lane <- 0 until config.warpSize) {
          resultBanks(0)(lane) := B(0, config.dataWidth bits)
          resultBanks(1)(lane) := B(0, config.dataWidth bits)
        }
        for (row <- 0 until TensorCoreLayouts.M) {
          for (col <- 0 until TensorCoreLayouts.N) {
            val lane = TensorCoreLayouts.cdLane(row, col)
            val reg = TensorCoreLayouts.cdRegister(row)
            if (TensorCoreLayouts.cdHalf(col) == 0) {
              resultBanks(reg)(lane)(15 downto 0) := accumMatrix(row)(col)
            } else {
              resultBanks(reg)(lane)(31 downto 16) := accumMatrix(row)(col)
            }
          }
        }
        pendingWriteCount := TensorCoreLayouts.MmaWriteCount
      }
    }
    responseIndex := 0
    state := State.RESPOND
  }

  when(io.response.fire) {
    when(pendingFault || pendingWriteCount === 0 || responseIndex.resized === (pendingWriteCount - 1)) {
      state := State.IDLE
    } otherwise {
      responseIndex := responseIndex + 1
    }
  }
}
