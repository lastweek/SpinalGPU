package spinalgpu

import spinal.core._
import spinal.lib._

case class SmExecutionCoreDebugIo(config: SmConfig) extends Bundle {
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
  val subSmEngineStates = out(Vec(UInt(3 bits), config.subSmCount))
  val subSmSelectedWarpIds = out(Vec(UInt(config.warpIdWidth bits), config.subSmCount))
  val subSmSelectedPcs = out(Vec(UInt(config.addressWidth bits), config.subSmCount))
  val subSmSlotOccupied = out(Vec(Bits(config.residentWarpsPerSubSm bits), config.subSmCount))
  val subSmBoundWarpIds = out(Vec(Vec(UInt(config.warpIdWidth bits), config.residentWarpsPerSubSm), config.subSmCount))
}

case class SmExecutionCoreIo(config: SmConfig) extends Bundle {
  val launchWrite = slave(Flow(WarpContextWrite(config)))
  val kernelBusy = in Bool()
  val clearBindings = in Bool()
  val sharedClearStart = in Bool()
  val sharedClearBusy = out Bool()
  val ctaCommand = in(CtaCommandDesc(config))
  val kernelComplete = out Bool()
  val trapInfo = master(Flow(TrapInfo(config)))
  val externalMemReq = master(Stream(ExternalMemBurstReq(config)))
  val externalMemRsp = slave(Stream(ExternalMemBurstRsp(config)))
  val debug = SmExecutionCoreDebugIo(config)
}

class SmExecutionCore(val config: SmConfig = SmConfig.default) extends Component {
  val io = SmExecutionCoreIo(config)

  private val warpStateTable = new WarpStateTable(config)
  private val warpBinder = new WarpBinder(config)
  private val subSms = Array.fill(config.subSmCount)(new SubSmPartition(config))
  private val l0InstructionCaches = Array.fill(config.subSmCount)(new L0InstructionCache(config))
  private val l1InstructionCache = new L1InstructionCache(config)
  private val l1DataSharedMemory = new L1DataSharedMemory(config)
  private val sharedMemory = new SharedMemory(config)
  private val externalMemoryArbiter = new ExternalMemoryArbiter(config)

  private val bindingTable = Vec.fill(config.residentWarpCount)(Reg(WarpBindingInfo(config)))
  bindingTable.foreach { binding =>
    binding.bound.init(False)
    binding.subSmId.init(0)
    binding.localSlotId.init(0)
  }

  private def clearWarpContext(target: WarpContext): Unit = {
    target.valid := False
    target.runnable := False
    target.pc := U(0, config.addressWidth bits)
    target.activeMask := B(0, config.warpSize bits)
    target.threadBase := U(0, config.threadCountWidth bits)
    target.threadBaseX := U(0, config.threadCountWidth bits)
    target.threadBaseY := U(0, config.threadCountWidth bits)
    target.threadBaseZ := U(0, config.threadCountWidth bits)
    target.threadCount := U(0, config.threadCountWidth bits)
    target.outstanding := False
    target.exited := False
    target.faulted := False
  }

  private val localContextViews =
    Vec.fill(config.subSmCount)(Vec.fill(config.residentWarpsPerSubSm)(LocalSlotContextView(config)))

  warpStateTable.io.launchWrite <> io.launchWrite
  for (subSm <- 0 until config.subSmCount) {
    warpStateTable.io.updateWrites(subSm).valid := subSms(subSm).io.contextUpdate.valid
    warpStateTable.io.updateWrites(subSm).payload := subSms(subSm).io.contextUpdate.payload
  }

  sharedMemory.io.request <> l1DataSharedMemory.io.sharedMemReq
  l1DataSharedMemory.io.sharedMemRsp <> sharedMemory.io.response
  sharedMemory.io.clear.start := io.sharedClearStart
  io.sharedClearBusy := sharedMemory.io.clear.busy

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
  io.externalMemReq <> externalMemoryArbiter.io.memoryReq
  externalMemoryArbiter.io.memoryRsp <> io.externalMemRsp

  private val partitionKernelBusy = io.kernelBusy && !io.launchWrite.valid

  warpBinder.io.warpContexts := warpStateTable.io.states
  warpBinder.io.bindings := bindingTable
  for (subSm <- 0 until config.subSmCount) {
    subSms(subSm).io.kernelBusy := partitionKernelBusy
    subSms(subSm).io.clearBindings := io.clearBindings
    subSms(subSm).io.currentCommand := io.ctaCommand
    warpBinder.io.subSmRequest(subSm) := subSms(subSm).io.bindRequest
    warpBinder.io.freeLocalSlotId(subSm) := subSms(subSm).io.bindLocalSlotId
    subSms(subSm).io.bind.valid := warpBinder.io.bind.valid && warpBinder.io.bind.payload.subSmId === U(subSm, config.subSmIdWidth bits)
    subSms(subSm).io.bind.payload := warpBinder.io.bind.payload
  }

  for (subSm <- 0 until config.subSmCount) {
    for (slot <- 0 until config.residentWarpsPerSubSm) {
      localContextViews(subSm)(slot).occupied := False
      localContextViews(subSm)(slot).warpId := U(0, config.warpIdWidth bits)
      clearWarpContext(localContextViews(subSm)(slot).context)
    }
  }

  for (warpId <- 0 until config.residentWarpCount) {
    val binding = bindingTable(warpId)
    for (subSm <- 0 until config.subSmCount) {
      for (slot <- 0 until config.residentWarpsPerSubSm) {
        when(binding.bound && binding.subSmId === U(subSm, config.subSmIdWidth bits) && binding.localSlotId === U(slot, config.localSlotIdWidth bits)) {
          localContextViews(subSm)(slot).occupied := True
          localContextViews(subSm)(slot).warpId := U(warpId, config.warpIdWidth bits)
          localContextViews(subSm)(slot).context := warpStateTable.io.states(warpId)
        }
      }
    }
  }

  for (subSm <- 0 until config.subSmCount) {
    subSms(subSm).io.localContexts := localContextViews(subSm)
  }

  when(io.launchWrite.valid) {
    if (config.residentWarpCount == 1) {
      bindingTable(0).bound := False
      bindingTable(0).subSmId := U(0, config.subSmIdWidth bits)
      bindingTable(0).localSlotId := U(0, config.localSlotIdWidth bits)
    } else {
      bindingTable(io.launchWrite.payload.index).bound := False
      bindingTable(io.launchWrite.payload.index).subSmId := U(0, config.subSmIdWidth bits)
      bindingTable(io.launchWrite.payload.index).localSlotId := U(0, config.localSlotIdWidth bits)
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
  private val allSubSmsIdle = subSms.map(_.io.status.idle).foldLeft(True)(_ && _)
  private val sharedFabricsIdle =
    l1InstructionCache.io.idle &&
      l1DataSharedMemory.io.idle &&
      externalMemoryArbiter.io.idle &&
      !sharedMemory.io.clear.busy
  io.kernelComplete := io.kernelBusy && allWarpTerminal && allSubSmsIdle && sharedFabricsIdle

  io.trapInfo.valid := False
  io.trapInfo.payload.warpId := U(0, config.warpIdWidth bits)
  io.trapInfo.payload.pc := U(0, config.addressWidth bits)
  io.trapInfo.payload.faultCode := FaultCode.None

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
    io.debug.subSmEngineStates(subSm) := subSms(subSm).io.status.engineState
    io.debug.subSmSelectedWarpIds(subSm) := subSms(subSm).io.status.selectedWarpId
    io.debug.subSmSelectedPcs(subSm) := subSms(subSm).io.status.selectedPc
    io.debug.subSmSlotOccupied(subSm) := subSms(subSm).io.debug.slotOccupied
    io.debug.subSmBoundWarpIds(subSm) := subSms(subSm).io.debug.boundWarpIds
    scheduledWarpCandidates(subSm) := subSms(subSm).io.debug.scheduledWarp.valid
    fetchResponseCandidates(subSm) := subSms(subSm).io.debug.fetchResponse.valid
    decodedInstructionCandidates(subSm) := subSms(subSm).io.debug.decodedInstruction.valid
    writebackCandidates(subSm) := subSms(subSm).io.debug.writeback.valid
    trapCandidates(subSm) := subSms(subSm).io.status.trapValid
    activeEngineCandidates(subSm) := !subSms(subSm).io.status.idle
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
      io.trapInfo.valid := True
      io.trapInfo.payload := subSms(subSm).io.status.trapInfo
      io.debug.trap.valid := True
      io.debug.trap.payload := subSms(subSm).io.status.trapInfo
    }

    val earlierActiveEngine =
      if (subSm == 0) False else activeEngineCandidates(subSm - 1 downto 0).orR
    when(activeEngineCandidates(subSm) && !earlierActiveEngine) {
      io.debug.engineState := subSms(subSm).io.status.engineState
      io.debug.selectedWarpId := subSms(subSm).io.status.selectedWarpId
      io.debug.selectedPc := subSms(subSm).io.status.selectedPc
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
}
