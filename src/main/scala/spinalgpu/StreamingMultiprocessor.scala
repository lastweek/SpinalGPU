package spinalgpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

case class StreamingMultiprocessorCommandIo(config: GpuConfig) extends Bundle {
  val command = in(KernelCommandDesc(config))
  val start = in Bool()
  val clearDone = in Bool()
  val executionStatus = out(KernelExecutionStatus(config))
}

case class StreamingMultiprocessorDebugIo(config: GpuConfig) extends Bundle {
  val scheduledWarp = master(Flow(WarpScheduleReq(config.sm)))
  val fetchResponse = master(Flow(FetchRsp(config.sm)))
  val decodedInstruction = master(Flow(DecodedInstruction(config.sm)))
  val writeback = master(Flow(WritebackPacket(config.sm)))
  val trap = master(Flow(TrapInfo(config.sm)))
  val engineState = out(UInt(3 bits))
  val selectedWarpId = out(UInt(config.sm.warpIdWidth bits))
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
  val subSmEngineStates = out(Vec(UInt(3 bits), config.sm.subSmCount))
  val subSmSelectedWarpIds = out(Vec(UInt(config.sm.warpIdWidth bits), config.sm.subSmCount))
  val subSmSelectedPcs = out(Vec(UInt(config.addressWidth bits), config.sm.subSmCount))
  val subSmSlotOccupied = out(Vec(Bits(config.sm.residentWarpsPerSubSm bits), config.sm.subSmCount))
  val subSmBoundWarpIds =
    out(Vec(Vec(UInt(config.sm.warpIdWidth bits), config.sm.residentWarpsPerSubSm), config.sm.subSmCount))
  val subSmTcgen05Busy = out(Vec(Bool(), config.sm.subSmCount))
  val subSmTcgen05States = out(Vec(UInt(4 bits), config.sm.subSmCount))
}

case class StreamingMultiprocessorIo(config: GpuConfig) extends Bundle {
  val memory = master(Axi4(config.axiConfig))
  val command = StreamingMultiprocessorCommandIo(config)
  val debug = StreamingMultiprocessorDebugIo(config)
}

class StreamingMultiprocessor(val config: GpuConfig = GpuConfig.default) extends Component {
  require(config.smCount == 1, "StreamingMultiprocessor is the single-SM compatibility wrapper; use GpuCluster for multi-SM configs")
  val io = StreamingMultiprocessorIo(config)

  private val smAdmissionController = new SmAdmissionController(config)
  private val executionCore = new SmExecutionCore(config.sm)
  private val externalMemoryAdapter = new ExternalMemoryAxiAdapter(config)

  smAdmissionController.io.command := io.command.command
  smAdmissionController.io.start := io.command.start
  smAdmissionController.io.clearDone := io.command.clearDone
  io.command.executionStatus := smAdmissionController.io.executionStatus

  executionCore.io.launchWrite <> smAdmissionController.io.warpInitWrite
  executionCore.io.kernelBusy := smAdmissionController.io.executionStatus.busy && !smAdmissionController.io.warpInitWrite.valid
  executionCore.io.clearBindings := smAdmissionController.io.warpInitWrite.valid && smAdmissionController.io.warpInitWrite.payload.index === 0
  executionCore.io.sharedClearStart := smAdmissionController.io.sharedClearStart
  smAdmissionController.io.sharedClearBusy := executionCore.io.sharedClearBusy
  executionCore.io.ctaCommand.entryPc := smAdmissionController.io.currentCommand.entryPc
  executionCore.io.ctaCommand.gridDimX := smAdmissionController.io.currentCommand.gridDimX
  executionCore.io.ctaCommand.gridDimY := smAdmissionController.io.currentCommand.gridDimY
  executionCore.io.ctaCommand.gridDimZ := smAdmissionController.io.currentCommand.gridDimZ
  executionCore.io.ctaCommand.blockDimX := smAdmissionController.io.currentCommand.blockDimX
  executionCore.io.ctaCommand.blockDimY := smAdmissionController.io.currentCommand.blockDimY
  executionCore.io.ctaCommand.blockDimZ := smAdmissionController.io.currentCommand.blockDimZ
  executionCore.io.ctaCommand.argBase := smAdmissionController.io.currentCommand.argBase
  executionCore.io.ctaCommand.sharedBytes := smAdmissionController.io.currentCommand.sharedBytes
  executionCore.io.ctaCommand.ctaidX := 0
  executionCore.io.ctaCommand.ctaidY := 0
  executionCore.io.ctaCommand.ctaidZ := 0
  executionCore.io.ctaCommand.smId := 0
  executionCore.io.ctaCommand.nsmId := 1
  executionCore.io.ctaCommand.gridId := smAdmissionController.io.currentGridId
  smAdmissionController.io.kernelComplete := executionCore.io.kernelComplete
  smAdmissionController.io.trapInfo <> executionCore.io.trapInfo

  executionCore.io.externalMemReq <> externalMemoryAdapter.io.request
  externalMemoryAdapter.io.response <> executionCore.io.externalMemRsp
  io.memory <> externalMemoryAdapter.io.axi

  io.debug.scheduledWarp.valid := executionCore.io.debug.scheduledWarp.valid
  io.debug.scheduledWarp.payload := executionCore.io.debug.scheduledWarp.payload
  io.debug.fetchResponse.valid := executionCore.io.debug.fetchResponse.valid
  io.debug.fetchResponse.payload := executionCore.io.debug.fetchResponse.payload
  io.debug.decodedInstruction.valid := executionCore.io.debug.decodedInstruction.valid
  io.debug.decodedInstruction.payload := executionCore.io.debug.decodedInstruction.payload
  io.debug.writeback.valid := executionCore.io.debug.writeback.valid
  io.debug.writeback.payload := executionCore.io.debug.writeback.payload
  io.debug.trap.valid := executionCore.io.debug.trap.valid
  io.debug.trap.payload := executionCore.io.debug.trap.payload
  io.debug.engineState := executionCore.io.debug.engineState
  io.debug.selectedWarpId := executionCore.io.debug.selectedWarpId
  io.debug.selectedPc := executionCore.io.debug.selectedPc
  io.debug.fetchMemoryReqValid := executionCore.io.debug.fetchMemoryReqValid
  io.debug.fetchMemoryReqReady := executionCore.io.debug.fetchMemoryReqReady
  io.debug.fetchMemoryRspValid := executionCore.io.debug.fetchMemoryRspValid
  io.debug.fetchMemoryRspReady := executionCore.io.debug.fetchMemoryRspReady
  io.debug.lsuIssueValid := executionCore.io.debug.lsuIssueValid
  io.debug.lsuResponseValid := executionCore.io.debug.lsuResponseValid
  io.debug.lsuExternalReqValid := executionCore.io.debug.lsuExternalReqValid
  io.debug.lsuExternalReqReady := executionCore.io.debug.lsuExternalReqReady
  io.debug.lsuExternalRspValid := executionCore.io.debug.lsuExternalRspValid
  io.debug.lsuExternalRspReady := executionCore.io.debug.lsuExternalRspReady
  io.debug.launchInvalidGridDim := smAdmissionController.io.invalidGridDim
  io.debug.launchInvalidBlockDimZero := smAdmissionController.io.invalidBlockDimZero
  io.debug.launchInvalidBlockThreadCount := smAdmissionController.io.invalidBlockThreadCount
  io.debug.launchInvalidSharedBytes := smAdmissionController.io.invalidSharedBytes
  io.debug.launchRequestedBlockThreads := smAdmissionController.io.requestedBlockThreadCount
  io.debug.subSmEngineStates := executionCore.io.debug.subSmEngineStates
  io.debug.subSmSelectedWarpIds := executionCore.io.debug.subSmSelectedWarpIds
  io.debug.subSmSelectedPcs := executionCore.io.debug.subSmSelectedPcs
  io.debug.subSmSlotOccupied := executionCore.io.debug.subSmSlotOccupied
  io.debug.subSmBoundWarpIds := executionCore.io.debug.subSmBoundWarpIds
  io.debug.subSmTcgen05Busy := executionCore.io.debug.subSmTcgen05Busy
  io.debug.subSmTcgen05States := executionCore.io.debug.subSmTcgen05States
}
