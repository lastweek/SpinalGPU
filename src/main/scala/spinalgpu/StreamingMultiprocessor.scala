package spinalgpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

case class StreamingMultiprocessorControlIo(config: SmConfig) extends Bundle {
  val launch = in(KernelLaunchDesc(config))
  val start = in Bool()
  val clearDone = in Bool()
  val status = out(LaunchStatus(config))
}

case class StreamingMultiprocessorDebugIo(config: SmConfig) extends Bundle {
  val scheduledWarp = master(Flow(WarpScheduleReq(config)))
  val fetchResponse = master(Flow(FetchRsp(config)))
  val decodedInstruction = master(Flow(DecodedInstruction(config)))
  val writeback = master(Flow(WritebackPacket(config)))
  val trap = master(Flow(TrapInfo(config)))
  val engineState = out(UInt(3 bits))
  val selectedWarpId = out(UInt(config.warpIdWidth bits))
  val selectedPc = out(UInt(config.addressWidth bits))
  val fetchMemoryReqValid = out(Bool())
  val fetchMemoryReqReady = out(Bool())
  val fetchMemoryRspValid = out(Bool())
  val fetchMemoryRspReady = out(Bool())
  val lsuIssueValid = out(Bool())
  val lsuResponseValid = out(Bool())
  val lsuExternalReqValid = out(Bool())
  val lsuExternalReqReady = out(Bool())
  val lsuExternalRspValid = out(Bool())
  val lsuExternalRspReady = out(Bool())
}

case class StreamingMultiprocessorIo(config: SmConfig) extends Bundle {
  val memory = master(Axi4(config.axiConfig))
  val control = StreamingMultiprocessorControlIo(config)
  val debug = StreamingMultiprocessorDebugIo(config)
}

class StreamingMultiprocessor(val config: SmConfig = SmConfig.default) extends Component {
  private object EngineState extends SpinalEnum {
    val IDLE, WAIT_FETCH, ISSUE, WAIT_CUDA, WAIT_LSU, WAIT_SFU, WAIT_TENSOR = newElement()
  }

  val io = StreamingMultiprocessorIo(config)

  private val launchController = new LaunchController(config)
  private val warpStateTable = new WarpStateTable(config)
  private val warpScheduler = new WarpScheduler(config)
  private val registerFile = new WarpRegisterFile(config)
  private val fetchUnit = new InstructionFetchUnit(config)
  private val decodeUnit = new DecodeUnit(config)
  private val cudaCoreArray = new CudaCoreArray(config)
  private val loadStoreUnit = new LoadStoreUnit(config)
  private val specialFunctionUnit = new SpecialFunctionUnit(config)
  private val tensorCoreBlock = new TensorCoreBlock(config)
  private val sharedMemory = new SharedMemory(config)
  private val externalMemoryArbiter = new ExternalMemoryArbiter(config)
  private val externalMemoryAdapter = new ExternalMemoryAxiAdapter(config)

  private val engineState = RegInit(EngineState.IDLE)
  private val selectedWarpIdReg = Reg(UInt(config.warpIdWidth bits)) init (0)
  private val selectedContextReg = Reg(WarpContext(config))
  private val instructionReg = Reg(Bits(config.instructionWidth bits)) init (0)

  selectedContextReg.valid.init(False)
  selectedContextReg.runnable.init(False)
  selectedContextReg.pc.init(0)
  selectedContextReg.activeMask.init(0)
  selectedContextReg.threadBase.init(0)
  selectedContextReg.threadCount.init(0)
  selectedContextReg.outstanding.init(False)
  selectedContextReg.exited.init(False)
  selectedContextReg.faulted.init(False)

  launchController.io.launch := io.control.launch
  launchController.io.start := io.control.start
  launchController.io.clearDone := io.control.clearDone
  io.control.status := launchController.io.status

  warpStateTable.io.launchWrite <> launchController.io.warpInitWrite
  private val updateWriteValid = Bool()
  private val updateWriteIndex = UInt(config.warpIdWidth bits)
  private val updateWriteContext = WarpContext(config)
  updateWriteValid := False
  updateWriteIndex := selectedWarpIdReg
  updateWriteContext := selectedContextReg
  warpStateTable.io.updateWrite.valid := updateWriteValid
  warpStateTable.io.updateWrite.payload.index := updateWriteIndex
  warpStateTable.io.updateWrite.payload.context := updateWriteContext

  registerFile.io.clearWarp <> launchController.io.registerFileClear
  registerFile.io.write.valid := False
  registerFile.io.write.payload.warpId := selectedWarpIdReg
  registerFile.io.write.payload.rd := U(0, config.registerAddressWidth bits)
  registerFile.io.write.payload.writeMask := B(0, config.warpSize bits)
  for (lane <- 0 until config.warpSize) {
    registerFile.io.write.payload.data(lane) := B(0, config.dataWidth bits)
  }

  launchController.io.sharedClearBusy := sharedMemory.io.clear.busy
  sharedMemory.io.clear.start := launchController.io.sharedClearStart

  warpScheduler.io.warpContexts := warpStateTable.io.states

  sharedMemory.io.request <> loadStoreUnit.io.sharedMemReq
  loadStoreUnit.io.sharedMemRsp <> sharedMemory.io.response

  externalMemoryArbiter.io.fetchReq <> fetchUnit.io.memoryReq
  fetchUnit.io.memoryRsp <> externalMemoryArbiter.io.fetchRsp
  externalMemoryArbiter.io.lsuReq <> loadStoreUnit.io.externalMemReq
  loadStoreUnit.io.externalMemRsp <> externalMemoryArbiter.io.lsuRsp
  externalMemoryAdapter.io.request <> externalMemoryArbiter.io.memoryReq
  externalMemoryArbiter.io.memoryRsp <> externalMemoryAdapter.io.response
  io.memory <> externalMemoryAdapter.io.axi

  decodeUnit.io.instruction := instructionReg

  private val readAddrA = UInt(config.registerAddressWidth bits)
  private val readAddrB = UInt(config.registerAddressWidth bits)
  readAddrA := U(0, config.registerAddressWidth bits)
  readAddrB := U(0, config.registerAddressWidth bits)

  when(decodeUnit.io.decoded.usesRs0 || decodeUnit.io.decoded.isStore || decodeUnit.io.decoded.isBranch) {
    readAddrA := decodeUnit.io.decoded.rs0
  }
  when(decodeUnit.io.decoded.isStore) {
    readAddrB := decodeUnit.io.decoded.rd
  } elsewhen (decodeUnit.io.decoded.usesRs1) {
    readAddrB := decodeUnit.io.decoded.rs1
  }

  registerFile.io.readWarpId := selectedWarpIdReg
  registerFile.io.readAddrA := readAddrA
  registerFile.io.readAddrB := readAddrB

  private val immediateUInt = decodeUnit.io.decoded.immediate.asUInt
  private val advancePc = (selectedContextReg.pc + U(4, config.addressWidth bits)).resized
  private val branchTarget = (advancePc + immediateUInt.resized).resized
  private val blockWarpCount =
    ((launchController.io.currentLaunch.blockDimX.resize(config.dataWidth) + U(config.warpSize - 1, config.dataWidth bits)) /
      U(config.warpSize, config.dataWidth bits)).resized
  private val gridIdLow = launchController.io.currentGridId(31 downto 0).resize(config.dataWidth)
  private val gridIdHigh = launchController.io.currentGridId(63 downto 32).resize(config.dataWidth)

  private val specialValues = Vec(UInt(config.dataWidth bits), config.warpSize)
  for (lane <- 0 until config.warpSize) {
    val threadId = (selectedContextReg.threadBase.resize(config.dataWidth) + U(lane, config.dataWidth bits)).resized
    specialValues(lane) := U(0, config.dataWidth bits)
    switch(decodeUnit.io.decoded.specialRegister) {
      is(U(SpecialRegisterKind.TidX, config.specialRegisterWidth bits)) {
        specialValues(lane) := threadId
      }
      is(U(SpecialRegisterKind.LaneId, config.specialRegisterWidth bits)) {
        specialValues(lane) := U(lane, config.dataWidth bits)
      }
      is(U(SpecialRegisterKind.WarpId, config.specialRegisterWidth bits)) {
        specialValues(lane) := selectedWarpIdReg.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.NtidX, config.specialRegisterWidth bits)) {
        specialValues(lane) := launchController.io.currentLaunch.blockDimX.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.CtaidX, config.specialRegisterWidth bits)) {
        specialValues(lane) := U(0, config.dataWidth bits)
      }
      is(U(SpecialRegisterKind.NctaidX, config.specialRegisterWidth bits)) {
        specialValues(lane) := 1
      }
      is(U(SpecialRegisterKind.NwarpId, config.specialRegisterWidth bits)) {
        specialValues(lane) := blockWarpCount
      }
      is(U(SpecialRegisterKind.SmId, config.specialRegisterWidth bits)) {
        specialValues(lane) := 0
      }
      is(U(SpecialRegisterKind.NsmId, config.specialRegisterWidth bits)) {
        specialValues(lane) := 1
      }
      is(U(SpecialRegisterKind.GridIdLo, config.specialRegisterWidth bits)) {
        specialValues(lane) := gridIdLow
      }
      is(U(SpecialRegisterKind.GridIdHi, config.specialRegisterWidth bits)) {
        specialValues(lane) := gridIdHigh
      }
      is(U(SpecialRegisterKind.ArgBase, config.specialRegisterWidth bits)) {
        specialValues(lane) := launchController.io.currentLaunch.argBase.resize(config.dataWidth)
      }
    }
  }

  fetchUnit.io.request.valid := launchController.io.status.busy && engineState === EngineState.IDLE && warpScheduler.io.schedule.valid
  fetchUnit.io.request.payload.warpId := warpScheduler.io.schedule.payload.warpId
  fetchUnit.io.request.payload.pc := warpScheduler.io.schedule.payload.context.pc
  warpScheduler.io.schedule.ready := fetchUnit.io.request.ready && launchController.io.status.busy && engineState === EngineState.IDLE

  cudaCoreArray.io.issue.valid := False
  cudaCoreArray.io.issue.payload.warpId := selectedWarpIdReg
  cudaCoreArray.io.issue.payload.opcode := decodeUnit.io.decoded.opcode
  cudaCoreArray.io.issue.payload.activeMask := selectedContextReg.activeMask
  for (lane <- 0 until config.warpSize) {
    cudaCoreArray.io.issue.payload.operandA(lane) := registerFile.io.readDataA(lane)
    cudaCoreArray.io.issue.payload.operandB(lane) := registerFile.io.readDataB(lane)
    when(decodeUnit.io.decoded.opcode === B(Opcode.MOVI, 8 bits) || decodeUnit.io.decoded.opcode === B(Opcode.ADDI, 8 bits)) {
      cudaCoreArray.io.issue.payload.operandB(lane) := immediateUInt.resized
    }
  }
  cudaCoreArray.io.response.ready := engineState === EngineState.WAIT_CUDA

  loadStoreUnit.io.issue.valid := False
  loadStoreUnit.io.issue.payload.warpId := selectedWarpIdReg
  loadStoreUnit.io.issue.payload.addressSpace := decodeUnit.io.decoded.addressSpace
  loadStoreUnit.io.issue.payload.write := decodeUnit.io.decoded.isStore
  loadStoreUnit.io.issue.payload.activeMask := selectedContextReg.activeMask
  loadStoreUnit.io.issue.payload.byteMask := B((1 << config.byteMaskWidth) - 1, config.byteMaskWidth bits)
  for (lane <- 0 until config.warpSize) {
    loadStoreUnit.io.issue.payload.addresses(lane) := (registerFile.io.readDataA(lane) + immediateUInt.resized).resized
    loadStoreUnit.io.issue.payload.writeData(lane) := registerFile.io.readDataB(lane).asBits
  }
  loadStoreUnit.io.response.ready := engineState === EngineState.WAIT_LSU

  specialFunctionUnit.io.issue.valid := False
  specialFunctionUnit.io.issue.payload.warpId := selectedWarpIdReg
  specialFunctionUnit.io.issue.payload.opcode := decodeUnit.io.decoded.opcode
  specialFunctionUnit.io.issue.payload.activeMask := selectedContextReg.activeMask
  for (lane <- 0 until config.warpSize) {
    specialFunctionUnit.io.issue.payload.operand(lane) := registerFile.io.readDataA(lane)
  }
  specialFunctionUnit.io.response.ready := engineState === EngineState.WAIT_SFU

  tensorCoreBlock.io.issue.valid := False
  tensorCoreBlock.io.issue.payload.warpId := selectedWarpIdReg
  tensorCoreBlock.io.issue.payload.opcode := decodeUnit.io.decoded.opcode
  tensorCoreBlock.io.issue.payload.activeMask := selectedContextReg.activeMask
  for (lane <- 0 until config.warpSize) {
    tensorCoreBlock.io.issue.payload.operandA(lane) := registerFile.io.readDataA(lane)
    tensorCoreBlock.io.issue.payload.operandB(lane) := registerFile.io.readDataB(lane)
  }
  tensorCoreBlock.io.response.ready := engineState === EngineState.WAIT_TENSOR

  io.debug.scheduledWarp.valid := warpScheduler.io.schedule.fire
  io.debug.scheduledWarp.payload := warpScheduler.io.schedule.payload
  io.debug.fetchResponse.valid := fetchUnit.io.response.fire
  io.debug.fetchResponse.payload := fetchUnit.io.response.payload
  io.debug.decodedInstruction.valid := engineState === EngineState.ISSUE
  io.debug.decodedInstruction.payload := decodeUnit.io.decoded
  io.debug.writeback.valid := False
  io.debug.writeback.payload := registerFile.io.write.payload
  io.debug.trap.valid := False
  io.debug.trap.payload.warpId := selectedWarpIdReg
  io.debug.trap.payload.pc := selectedContextReg.pc
  io.debug.trap.payload.faultCode := FaultCode.None
  io.debug.engineState := engineState.asBits.asUInt.resized
  io.debug.selectedWarpId := selectedWarpIdReg
  io.debug.selectedPc := selectedContextReg.pc
  io.debug.fetchMemoryReqValid := fetchUnit.io.memoryReq.valid
  io.debug.fetchMemoryReqReady := fetchUnit.io.memoryReq.ready
  io.debug.fetchMemoryRspValid := fetchUnit.io.memoryRsp.valid
  io.debug.fetchMemoryRspReady := fetchUnit.io.memoryRsp.ready
  io.debug.lsuIssueValid := loadStoreUnit.io.issue.valid
  io.debug.lsuResponseValid := loadStoreUnit.io.response.valid
  io.debug.lsuExternalReqValid := loadStoreUnit.io.externalMemReq.valid
  io.debug.lsuExternalReqReady := loadStoreUnit.io.externalMemReq.ready
  io.debug.lsuExternalRspValid := loadStoreUnit.io.externalMemRsp.valid
  io.debug.lsuExternalRspReady := loadStoreUnit.io.externalMemRsp.ready

  private val branchTrueBits = Bits(config.warpSize bits)
  private val branchFalseBits = Bits(config.warpSize bits)
  for (lane <- 0 until config.warpSize) {
    val laneActive = selectedContextReg.activeMask(lane)
    val cond = Bool()
    cond := registerFile.io.readDataA(lane) =/= 0
    when(decodeUnit.io.decoded.branchOnZero) {
      cond := registerFile.io.readDataA(lane) === 0
    }
    branchTrueBits(lane) := laneActive && cond
    branchFalseBits(lane) := laneActive && !cond
  }

  private val allWarpTerminal = warpStateTable.io.states.map(context => !context.valid || context.exited || context.faulted).foldLeft(True)(_ && _)
  launchController.io.kernelComplete := launchController.io.status.busy && engineState === EngineState.IDLE && allWarpTerminal
  launchController.io.trapInfo.valid := io.debug.trap.valid
  launchController.io.trapInfo.payload := io.debug.trap.payload

  when(warpScheduler.io.schedule.fire) {
    selectedWarpIdReg := warpScheduler.io.schedule.payload.warpId
    selectedContextReg := warpScheduler.io.schedule.payload.context
    updateWriteValid := True
    updateWriteIndex := warpScheduler.io.schedule.payload.warpId
    updateWriteContext.valid := warpScheduler.io.schedule.payload.context.valid
    updateWriteContext.runnable := False
    updateWriteContext.pc := warpScheduler.io.schedule.payload.context.pc
    updateWriteContext.activeMask := warpScheduler.io.schedule.payload.context.activeMask
    updateWriteContext.threadBase := warpScheduler.io.schedule.payload.context.threadBase
    updateWriteContext.threadCount := warpScheduler.io.schedule.payload.context.threadCount
    updateWriteContext.outstanding := True
    updateWriteContext.exited := warpScheduler.io.schedule.payload.context.exited
    updateWriteContext.faulted := warpScheduler.io.schedule.payload.context.faulted
    engineState := EngineState.WAIT_FETCH
  }

  fetchUnit.io.response.ready := engineState === EngineState.WAIT_FETCH
  when(fetchUnit.io.response.fire) {
    when(fetchUnit.io.response.payload.fault) {
      updateWriteValid := True
      updateWriteContext.runnable := False
      updateWriteContext.outstanding := False
      updateWriteContext.faulted := True
      io.debug.trap.valid := True
      io.debug.trap.payload.warpId := selectedWarpIdReg
      io.debug.trap.payload.pc := fetchUnit.io.response.payload.pc
      io.debug.trap.payload.faultCode := fetchUnit.io.response.payload.faultCode
      engineState := EngineState.IDLE
    } otherwise {
      instructionReg := fetchUnit.io.response.payload.instruction
      engineState := EngineState.ISSUE
    }
  }

  when(engineState === EngineState.ISSUE) {
    when(decodeUnit.io.decoded.illegal) {
      updateWriteValid := True
      updateWriteContext.runnable := False
      updateWriteContext.outstanding := False
      updateWriteContext.faulted := True
      io.debug.trap.valid := True
      io.debug.trap.payload.faultCode := FaultCode.IllegalOpcode
      engineState := EngineState.IDLE
    } elsewhen (decodeUnit.io.decoded.isTrap) {
      updateWriteValid := True
      updateWriteContext.runnable := False
      updateWriteContext.outstanding := False
      updateWriteContext.faulted := True
      io.debug.trap.valid := True
      io.debug.trap.payload.faultCode := FaultCode.Trap
      engineState := EngineState.IDLE
    } elsewhen (decodeUnit.io.decoded.isExit) {
      updateWriteValid := True
      updateWriteContext.pc := advancePc
      updateWriteContext.runnable := False
      updateWriteContext.outstanding := False
      updateWriteContext.exited := True
      engineState := EngineState.IDLE
    } elsewhen (decodeUnit.io.decoded.isBranch) {
      val nonUniform = branchTrueBits.orR && branchFalseBits.orR
      updateWriteValid := True
      updateWriteContext.outstanding := False

      when(decodeUnit.io.decoded.opcode === B(Opcode.BRA, 8 bits)) {
        updateWriteContext.pc := branchTarget
        updateWriteContext.runnable := True
        engineState := EngineState.IDLE
      } elsewhen (nonUniform) {
        updateWriteContext.runnable := False
        updateWriteContext.faulted := True
        io.debug.trap.valid := True
        io.debug.trap.payload.faultCode := FaultCode.NonUniformBranch
        engineState := EngineState.IDLE
      } otherwise {
        updateWriteContext.pc := advancePc
        when(branchTrueBits.orR) {
          updateWriteContext.pc := branchTarget
        }
        updateWriteContext.runnable := True
        engineState := EngineState.IDLE
      }
    } elsewhen (decodeUnit.io.decoded.isS2r) {
      registerFile.io.write.valid := decodeUnit.io.decoded.writesRd
      registerFile.io.write.payload.warpId := selectedWarpIdReg
      registerFile.io.write.payload.rd := decodeUnit.io.decoded.rd
      registerFile.io.write.payload.writeMask := selectedContextReg.activeMask
      for (lane <- 0 until config.warpSize) {
        registerFile.io.write.payload.data(lane) := specialValues(lane).asBits
      }
      io.debug.writeback.valid := decodeUnit.io.decoded.writesRd
      io.debug.writeback.payload := registerFile.io.write.payload

      updateWriteValid := True
      updateWriteContext.pc := advancePc
      updateWriteContext.runnable := True
      updateWriteContext.outstanding := False
      engineState := EngineState.IDLE
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.CUDA) {
      cudaCoreArray.io.issue.valid := True
      when(cudaCoreArray.io.issue.ready) {
        engineState := EngineState.WAIT_CUDA
      }
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.LSU) {
      loadStoreUnit.io.issue.valid := True
      when(loadStoreUnit.io.issue.ready) {
        engineState := EngineState.WAIT_LSU
      }
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.SFU) {
      specialFunctionUnit.io.issue.valid := True
      when(specialFunctionUnit.io.issue.ready) {
        engineState := EngineState.WAIT_SFU
      }
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.TENSOR) {
      tensorCoreBlock.io.issue.valid := True
      when(tensorCoreBlock.io.issue.ready) {
        engineState := EngineState.WAIT_TENSOR
      }
    } otherwise {
      updateWriteValid := True
      updateWriteContext.pc := advancePc
      updateWriteContext.runnable := True
      updateWriteContext.outstanding := False
      engineState := EngineState.IDLE
    }
  }

  when(cudaCoreArray.io.response.fire) {
    registerFile.io.write.valid := decodeUnit.io.decoded.writesRd
    registerFile.io.write.payload.warpId := selectedWarpIdReg
    registerFile.io.write.payload.rd := decodeUnit.io.decoded.rd
    registerFile.io.write.payload.writeMask := selectedContextReg.activeMask
    registerFile.io.write.payload.data := cudaCoreArray.io.response.payload.result
    io.debug.writeback.valid := decodeUnit.io.decoded.writesRd
    io.debug.writeback.payload := registerFile.io.write.payload

    updateWriteValid := True
    updateWriteContext.pc := advancePc
    updateWriteContext.runnable := True
    updateWriteContext.outstanding := False
    engineState := EngineState.IDLE
  }

  when(loadStoreUnit.io.response.fire) {
    when(loadStoreUnit.io.response.payload.error) {
      updateWriteValid := True
      updateWriteContext.runnable := False
      updateWriteContext.outstanding := False
      updateWriteContext.faulted := True
      io.debug.trap.valid := True
      io.debug.trap.payload.faultCode := loadStoreUnit.io.response.payload.faultCode
    } otherwise {
      registerFile.io.write.valid := decodeUnit.io.decoded.isLoad
      registerFile.io.write.payload.warpId := selectedWarpIdReg
      registerFile.io.write.payload.rd := decodeUnit.io.decoded.rd
      registerFile.io.write.payload.writeMask := selectedContextReg.activeMask
      registerFile.io.write.payload.data := loadStoreUnit.io.response.payload.readData
      io.debug.writeback.valid := decodeUnit.io.decoded.isLoad
      io.debug.writeback.payload := registerFile.io.write.payload

      updateWriteValid := True
      updateWriteContext.pc := advancePc
      updateWriteContext.runnable := True
      updateWriteContext.outstanding := False
    }
    engineState := EngineState.IDLE
  }

  when(specialFunctionUnit.io.response.fire) {
    registerFile.io.write.valid := decodeUnit.io.decoded.writesRd
    registerFile.io.write.payload.warpId := selectedWarpIdReg
    registerFile.io.write.payload.rd := decodeUnit.io.decoded.rd
    registerFile.io.write.payload.writeMask := selectedContextReg.activeMask
    registerFile.io.write.payload.data := specialFunctionUnit.io.response.payload.result
    io.debug.writeback.valid := decodeUnit.io.decoded.writesRd
    io.debug.writeback.payload := registerFile.io.write.payload

    updateWriteValid := True
    updateWriteContext.pc := advancePc
    updateWriteContext.runnable := True
    updateWriteContext.outstanding := False
    engineState := EngineState.IDLE
  }

  when(tensorCoreBlock.io.response.fire) {
    registerFile.io.write.valid := decodeUnit.io.decoded.writesRd
    registerFile.io.write.payload.warpId := selectedWarpIdReg
    registerFile.io.write.payload.rd := decodeUnit.io.decoded.rd
    registerFile.io.write.payload.writeMask := selectedContextReg.activeMask
    registerFile.io.write.payload.data := tensorCoreBlock.io.response.payload.result
    io.debug.writeback.valid := decodeUnit.io.decoded.writesRd
    io.debug.writeback.payload := registerFile.io.write.payload

    updateWriteValid := True
    updateWriteContext.pc := advancePc
    updateWriteContext.runnable := True
    updateWriteContext.outstanding := False
    engineState := EngineState.IDLE
  }
}
