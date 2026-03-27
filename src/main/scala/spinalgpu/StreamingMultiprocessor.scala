package spinalgpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

case class StreamingMultiprocessorCommandIo(config: SmConfig) extends Bundle {
  val command = in(KernelCommandDesc(config))
  val start = in Bool()
  val clearDone = in Bool()
  val executionStatus = out(KernelExecutionStatus(config))
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
  val launchInvalidGridDim = out(Bool())
  val launchInvalidBlockDimZero = out(Bool())
  val launchInvalidBlockThreadCount = out(Bool())
  val launchInvalidSharedBytes = out(Bool())
  val launchRequestedBlockThreads = out(UInt((config.threadCountWidth * 3) bits))
  val subSmEngineStates = out(Vec(UInt(3 bits), config.subSmCount))
  val subSmSelectedWarpIds = out(Vec(UInt(config.warpIdWidth bits), config.subSmCount))
  val subSmSelectedPcs = out(Vec(UInt(config.addressWidth bits), config.subSmCount))
  val subSmSlotOccupied = out(Vec(Bits(config.residentWarpsPerSubSm bits), config.subSmCount))
  val subSmBoundWarpIds = out(Vec(Vec(UInt(config.warpIdWidth bits), config.residentWarpsPerSubSm), config.subSmCount))
}

case class StreamingMultiprocessorIo(config: SmConfig) extends Bundle {
  val memory = master(Axi4(config.axiConfig))
  val command = StreamingMultiprocessorCommandIo(config)
  val debug = StreamingMultiprocessorDebugIo(config)
}

class StreamingMultiprocessor(val config: SmConfig = SmConfig.default) extends Component {
  val io = StreamingMultiprocessorIo(config)

  private val smAdmissionController = new SmAdmissionController(config)
  private val warpStateTable = new WarpStateTable(config)
  private val warpBinder = new WarpBinder(config)
  private val subSms = Array.fill(config.subSmCount)(new SubSmPartition(config))
  private val l0InstructionCaches = Array.fill(config.subSmCount)(new L0InstructionCache(config))
  private val l1InstructionCache = new L1InstructionCache(config)
  private val l1DataSharedMemory = new L1DataSharedMemory(config)
  private val sharedMemory = new SharedMemory(config)
  private val externalMemoryArbiter = new ExternalMemoryArbiter(config)
  private val externalMemoryAdapter = new ExternalMemoryAxiAdapter(config)

  private val bindingTable = Vec.fill(config.residentWarpCount)(Reg(WarpBindingInfo(config)))
  bindingTable.foreach { binding =>
    binding.bound.init(False)
    binding.subSmId.init(0)
    binding.localSlotId.init(0)
  }

  smAdmissionController.io.command := io.command.command
  smAdmissionController.io.start := io.command.start
  smAdmissionController.io.clearDone := io.command.clearDone
  io.command.executionStatus := smAdmissionController.io.executionStatus

  warpStateTable.io.launchWrite <> smAdmissionController.io.warpInitWrite
  for (subSm <- 0 until config.subSmCount) {
    warpStateTable.io.updateWrites(subSm).valid := subSms(subSm).io.contextUpdate.valid
    warpStateTable.io.updateWrites(subSm).payload := subSms(subSm).io.contextUpdate.payload
  }

  sharedMemory.io.request <> l1DataSharedMemory.io.sharedMemReq
  l1DataSharedMemory.io.sharedMemRsp <> sharedMemory.io.response
  smAdmissionController.io.sharedClearBusy := sharedMemory.io.clear.busy
  sharedMemory.io.clear.start := smAdmissionController.io.sharedClearStart

  for (subSm <- 0 until config.subSmCount) {
    l0InstructionCaches(subSm).io.request <> subSms(subSm).io.fetchMemReq
    subSms(subSm).io.fetchMemRsp <> l0InstructionCaches(subSm).io.response
    l1InstructionCache.io.subSmReq(subSm) <> l0InstructionCaches(subSm).io.l1Req
    l0InstructionCaches(subSm).io.l1Rsp <> l1InstructionCache.io.subSmRsp(subSm)

    l1DataSharedMemory.io.sharedReq(subSm) <> subSms(subSm).io.sharedMemReq
    subSms(subSm).io.sharedMemRsp <> l1DataSharedMemory.io.sharedRsp(subSm)
    l1DataSharedMemory.io.externalReq(subSm) <> subSms(subSm).io.externalMemReq
    subSms(subSm).io.externalMemRsp <> l1DataSharedMemory.io.externalRsp(subSm)
  }

  externalMemoryArbiter.io.fetchReq <> l1InstructionCache.io.memoryReq
  l1InstructionCache.io.memoryRsp <> externalMemoryArbiter.io.fetchRsp
  externalMemoryArbiter.io.lsuReq <> l1DataSharedMemory.io.memoryReq
  l1DataSharedMemory.io.memoryRsp <> externalMemoryArbiter.io.lsuRsp
  externalMemoryAdapter.io.request <> externalMemoryArbiter.io.memoryReq
  externalMemoryArbiter.io.memoryRsp <> externalMemoryAdapter.io.response
  io.memory <> externalMemoryAdapter.io.axi

  private val partitionKernelBusy = smAdmissionController.io.executionStatus.busy && !smAdmissionController.io.warpInitWrite.valid
  private val clearBindingsPulse = smAdmissionController.io.warpInitWrite.valid && smAdmissionController.io.warpInitWrite.payload.index === 0

  warpBinder.io.warpContexts := warpStateTable.io.states
  warpBinder.io.bindings := bindingTable
  for (subSm <- 0 until config.subSmCount) {
    subSms(subSm).io.kernelBusy := partitionKernelBusy
    subSms(subSm).io.clearBindings := clearBindingsPulse
    subSms(subSm).io.warpContexts := warpStateTable.io.states
    subSms(subSm).io.currentCommand := smAdmissionController.io.currentCommand
    subSms(subSm).io.currentGridId := smAdmissionController.io.currentGridId
    warpBinder.io.subSmRequest(subSm) := subSms(subSm).io.bindRequest
    warpBinder.io.freeLocalSlotId(subSm) := subSms(subSm).io.bindLocalSlotId
    subSms(subSm).io.bind.valid := warpBinder.io.bind.valid && warpBinder.io.bind.payload.subSmId === U(subSm, config.subSmIdWidth bits)
    subSms(subSm).io.bind.payload := warpBinder.io.bind.payload
  }

  when(smAdmissionController.io.warpInitWrite.valid) {
    if (config.residentWarpCount == 1) {
      bindingTable(0).bound := False
      bindingTable(0).subSmId := U(0, config.subSmIdWidth bits)
      bindingTable(0).localSlotId := U(0, config.localSlotIdWidth bits)
    } else {
      bindingTable(smAdmissionController.io.warpInitWrite.payload.index).bound := False
      bindingTable(smAdmissionController.io.warpInitWrite.payload.index).subSmId := U(0, config.subSmIdWidth bits)
      bindingTable(smAdmissionController.io.warpInitWrite.payload.index).localSlotId := U(0, config.localSlotIdWidth bits)
    }
  }

  when(warpBinder.io.bind.valid) {
    if (config.residentWarpCount == 1) {
      bindingTable(0).bound := True
      bindingTable(0).subSmId := warpBinder.io.bind.payload.subSmId
      bindingTable(0).localSlotId := warpBinder.io.bind.payload.localSlotId
    } else {
      bindingTable(warpBinder.io.bind.payload.warpId).bound := True
      bindingTable(warpBinder.io.bind.payload.warpId).subSmId := warpBinder.io.bind.payload.subSmId
      bindingTable(warpBinder.io.bind.payload.warpId).localSlotId := warpBinder.io.bind.payload.localSlotId
    }
  }

  private val allWarpTerminal = warpStateTable.io.states.map(context => !context.valid || context.exited || context.faulted).foldLeft(True)(_ && _)
  private val allSubSmsIdle = subSms.map(_.io.debug.engineState === 0).foldLeft(True)(_ && _)
  smAdmissionController.io.kernelComplete := smAdmissionController.io.executionStatus.busy && allWarpTerminal && allSubSmsIdle

  smAdmissionController.io.trapInfo.valid := False
  smAdmissionController.io.trapInfo.payload.warpId := U(0, config.warpIdWidth bits)
  smAdmissionController.io.trapInfo.payload.pc := U(0, config.addressWidth bits)
  smAdmissionController.io.trapInfo.payload.faultCode := FaultCode.None

  io.debug.scheduledWarp.valid := False
  io.debug.scheduledWarp.payload.warpId := U(0, config.warpIdWidth bits)
  io.debug.scheduledWarp.payload.context := warpStateTable.io.states(0)
  io.debug.fetchResponse.valid := False
  io.debug.fetchResponse.payload := subSms(0).io.debug.fetchResponse.payload
  io.debug.decodedInstruction.valid := False
  io.debug.decodedInstruction.payload := subSms(0).io.debug.decodedInstruction.payload
  io.debug.writeback.valid := False
  io.debug.writeback.payload := subSms(0).io.debug.writeback.payload
  io.debug.trap.valid := False
  io.debug.trap.payload := subSms(0).io.debug.trap.payload
  io.debug.engineState := U(0, 3 bits)
  io.debug.selectedWarpId := U(0, config.warpIdWidth bits)
  io.debug.selectedPc := U(0, config.addressWidth bits)

  private val scheduledWarpCandidates = Bits(config.subSmCount bits)
  private val fetchResponseCandidates = Bits(config.subSmCount bits)
  private val decodedInstructionCandidates = Bits(config.subSmCount bits)
  private val writebackCandidates = Bits(config.subSmCount bits)
  private val trapCandidates = Bits(config.subSmCount bits)
  private val activeEngineCandidates = Bits(config.subSmCount bits)

  for (subSm <- 0 until config.subSmCount) {
    io.debug.subSmEngineStates(subSm) := subSms(subSm).io.debug.engineState
    io.debug.subSmSelectedWarpIds(subSm) := subSms(subSm).io.debug.selectedWarpId
    io.debug.subSmSelectedPcs(subSm) := subSms(subSm).io.debug.selectedPc
    io.debug.subSmSlotOccupied(subSm) := subSms(subSm).io.debug.slotOccupied
    io.debug.subSmBoundWarpIds(subSm) := subSms(subSm).io.debug.boundWarpIds
    scheduledWarpCandidates(subSm) := subSms(subSm).io.debug.scheduledWarp.valid
    fetchResponseCandidates(subSm) := subSms(subSm).io.debug.fetchResponse.valid
    decodedInstructionCandidates(subSm) := subSms(subSm).io.debug.decodedInstruction.valid
    writebackCandidates(subSm) := subSms(subSm).io.debug.writeback.valid
    trapCandidates(subSm) := subSms(subSm).io.debug.trap.valid
    activeEngineCandidates(subSm) := subSms(subSm).io.debug.engineState =/= 0
  }

  for (subSm <- 0 until config.subSmCount) {
    val earlierScheduledWarp =
      if (subSm == 0) False else scheduledWarpCandidates(subSm - 1 downto 0).orR
    when(scheduledWarpCandidates(subSm) && !earlierScheduledWarp) {
      io.debug.scheduledWarp.valid := True
      io.debug.scheduledWarp.payload.warpId := subSms(subSm).io.debug.scheduledWarp.payload.warpId
      io.debug.scheduledWarp.payload.context := subSms(subSm).io.debug.scheduledWarp.payload.context
    }

    val earlierFetchResponse =
      if (subSm == 0) False else fetchResponseCandidates(subSm - 1 downto 0).orR
    when(fetchResponseCandidates(subSm) && !earlierFetchResponse) {
      io.debug.fetchResponse.valid := True
      io.debug.fetchResponse.payload := subSms(subSm).io.debug.fetchResponse.payload
    }

    val earlierDecodedInstruction =
      if (subSm == 0) False else decodedInstructionCandidates(subSm - 1 downto 0).orR
    when(decodedInstructionCandidates(subSm) && !earlierDecodedInstruction) {
      io.debug.decodedInstruction.valid := True
      io.debug.decodedInstruction.payload := subSms(subSm).io.debug.decodedInstruction.payload
    }

    val earlierWriteback =
      if (subSm == 0) False else writebackCandidates(subSm - 1 downto 0).orR
    when(writebackCandidates(subSm) && !earlierWriteback) {
      io.debug.writeback.valid := True
      io.debug.writeback.payload := subSms(subSm).io.debug.writeback.payload
    }

    val earlierTrap = if (subSm == 0) False else trapCandidates(subSm - 1 downto 0).orR
    when(trapCandidates(subSm) && !earlierTrap) {
      smAdmissionController.io.trapInfo.valid := True
      smAdmissionController.io.trapInfo.payload := subSms(subSm).io.debug.trap.payload
      io.debug.trap.valid := True
      io.debug.trap.payload := subSms(subSm).io.debug.trap.payload
    }

    val earlierActiveEngine =
      if (subSm == 0) False else activeEngineCandidates(subSm - 1 downto 0).orR
    when(activeEngineCandidates(subSm) && !earlierActiveEngine) {
      io.debug.engineState := subSms(subSm).io.debug.engineState
      io.debug.selectedWarpId := subSms(subSm).io.debug.selectedWarpId
      io.debug.selectedPc := subSms(subSm).io.debug.selectedPc
    }
  }

  io.debug.fetchMemoryReqValid := l1InstructionCache.io.memoryReq.valid
  io.debug.fetchMemoryReqReady := l1InstructionCache.io.memoryReq.ready
  io.debug.fetchMemoryRspValid := l1InstructionCache.io.memoryRsp.valid
  io.debug.fetchMemoryRspReady := l1InstructionCache.io.memoryRsp.ready
  io.debug.lsuIssueValid := l1DataSharedMemory.io.sharedMemReq.valid || l1DataSharedMemory.io.memoryReq.valid
  io.debug.lsuResponseValid := l1DataSharedMemory.io.sharedMemRsp.valid || l1DataSharedMemory.io.memoryRsp.valid
  io.debug.lsuExternalReqValid := l1DataSharedMemory.io.memoryReq.valid
  io.debug.lsuExternalReqReady := l1DataSharedMemory.io.memoryReq.ready
  io.debug.lsuExternalRspValid := l1DataSharedMemory.io.memoryRsp.valid
  io.debug.lsuExternalRspReady := l1DataSharedMemory.io.memoryRsp.ready
  io.debug.launchInvalidGridDim := smAdmissionController.io.invalidGridDim
  io.debug.launchInvalidBlockDimZero := smAdmissionController.io.invalidBlockDimZero
  io.debug.launchInvalidBlockThreadCount := smAdmissionController.io.invalidBlockThreadCount
  io.debug.launchInvalidSharedBytes := smAdmissionController.io.invalidSharedBytes
  io.debug.launchRequestedBlockThreads := smAdmissionController.io.requestedBlockThreadCount
}
