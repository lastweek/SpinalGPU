package spinalgpu

import spinal.core._
import spinal.lib._

class SmCtaController(config: GpuConfig) extends Component {
  private val smConfig = config.sm

  private object State extends SpinalEnum {
    val IDLE, CLEAR_SHARED, INIT_WARPS, RUNNING = newElement()
  }

  val io = new Bundle {
    val command = in(CtaCommandDesc(smConfig))
    val start = in Bool()
    val clearDone = in Bool()
    val sharedClearBusy = in Bool()
    val sharedClearStart = out Bool()
    val warpInitWrite = master(Flow(WarpContextWrite(smConfig)))
    val kernelComplete = in Bool()
    val trapInfo = slave(Flow(TrapInfo(smConfig)))
    val currentCommand = out(CtaCommandDesc(smConfig))
    val executionStatus = out(KernelExecutionStatus(config))
  }

  private val state = RegInit(State.IDLE)
  private val executionStatus = Reg(KernelExecutionStatus(config))
  private val currentCommand = Reg(CtaCommandDesc(smConfig))
  private val initIndex = Reg(UInt(smConfig.warpIdWidth bits)) init (0)
  private val clearIssued = RegInit(False)
  private val trapPendingValid = RegInit(False)
  private val trapPendingPc = Reg(UInt(smConfig.addressWidth bits)) init (0)
  private val trapPendingCode = Reg(UInt(config.faultCodeWidth bits)) init (FaultCode.None)
  private val blockThreadCountWidth = smConfig.threadCountWidth * 3

  executionStatus.busy.init(False)
  executionStatus.done.init(False)
  executionStatus.fault.init(False)
  executionStatus.faultPc.init(0)
  executionStatus.faultCode.init(FaultCode.None)

  currentCommand.entryPc.init(0)
  currentCommand.gridDimX.init(1)
  currentCommand.gridDimY.init(1)
  currentCommand.gridDimZ.init(1)
  currentCommand.blockDimX.init(0)
  currentCommand.blockDimY.init(1)
  currentCommand.blockDimZ.init(1)
  currentCommand.argBase.init(0)
  currentCommand.sharedBytes.init(0)
  currentCommand.ctaidX.init(0)
  currentCommand.ctaidY.init(0)
  currentCommand.ctaidZ.init(0)
  currentCommand.smId.init(0)
  currentCommand.nsmId.init(0)
  currentCommand.gridId.init(0)

  io.sharedClearStart := False
  io.warpInitWrite.valid := False
  io.warpInitWrite.payload.index := initIndex
  io.warpInitWrite.payload.context.valid := False
  io.warpInitWrite.payload.context.runnable := False
  io.warpInitWrite.payload.context.pc := 0
  io.warpInitWrite.payload.context.activeMask := 0
  io.warpInitWrite.payload.context.threadBase := 0
  io.warpInitWrite.payload.context.threadBaseX := 0
  io.warpInitWrite.payload.context.threadBaseY := 0
  io.warpInitWrite.payload.context.threadBaseZ := 0
  io.warpInitWrite.payload.context.threadCount := 0
  io.warpInitWrite.payload.context.outstanding := False
  io.warpInitWrite.payload.context.exited := False
  io.warpInitWrite.payload.context.faulted := False
  io.currentCommand := currentCommand
  io.executionStatus := executionStatus

  private def laneMask(activeCount: UInt): Bits = {
    val mask = Bits(smConfig.warpSize bits)
    for (lane <- 0 until smConfig.warpSize) {
      mask(lane) := U(lane, activeCount.getWidth bits) < activeCount
    }
    mask
  }

  private val currentBlockThreadCountWide = UInt(blockThreadCountWidth bits)
  currentBlockThreadCountWide := (currentCommand.blockDimX.resize(blockThreadCountWidth bits) *
    currentCommand.blockDimY.resize(blockThreadCountWidth bits) *
    currentCommand.blockDimZ.resize(blockThreadCountWidth bits)).resized
  private val currentBlockThreadCount = UInt(smConfig.threadCountWidth bits)
  currentBlockThreadCount := currentBlockThreadCountWide.resized

  when(io.clearDone && !executionStatus.busy) {
    executionStatus.done := False
    executionStatus.fault := False
    executionStatus.faultPc := U(0, config.addressWidth bits)
    executionStatus.faultCode := U(FaultCode.None, config.faultCodeWidth bits)
    trapPendingValid := False
  }

  when(io.trapInfo.valid) {
    trapPendingValid := True
    trapPendingPc := io.trapInfo.pc
    trapPendingCode := io.trapInfo.faultCode
  }

  when(io.start && !executionStatus.busy && state === State.IDLE) {
    currentCommand := io.command
    executionStatus.done := False
    executionStatus.fault := False
    executionStatus.faultPc := U(0, config.addressWidth bits)
    executionStatus.faultCode := U(FaultCode.None, config.faultCodeWidth bits)
    trapPendingValid := False
    executionStatus.busy := True
    initIndex := U(0, smConfig.warpIdWidth bits)
    clearIssued := False
    state := State.CLEAR_SHARED
  }

  switch(state) {
    is(State.CLEAR_SHARED) {
      io.sharedClearStart := !clearIssued
      when(io.sharedClearStart) {
        clearIssued := True
      }
      when(clearIssued && !io.sharedClearBusy) {
        initIndex := U(0, smConfig.warpIdWidth bits)
        state := State.INIT_WARPS
      }
    }

    is(State.INIT_WARPS) {
      val baseThread = UInt(smConfig.threadCountWidth bits)
      baseThread := (initIndex.resize(smConfig.threadCountWidth bits) * U(smConfig.warpSize, smConfig.threadCountWidth bits)).resized
      val (baseThreadX, baseThreadY, baseThreadZ) =
        ThreadCoordinateLogic.linearToCoords(smConfig, baseThread, currentCommand.blockDimX, currentCommand.blockDimY, currentCommand.blockDimZ)
      val remaining = UInt(smConfig.threadCountWidth bits)
      when(currentBlockThreadCount > baseThread) {
        remaining := currentBlockThreadCount - baseThread
      } otherwise {
        remaining := U(0, smConfig.threadCountWidth bits)
      }

      val laneCount = UInt(smConfig.threadCountWidth bits)
      laneCount := remaining
      when(remaining > smConfig.warpSize) {
        laneCount := smConfig.warpSize
      }

      io.warpInitWrite.valid := True
      io.warpInitWrite.payload.index := initIndex
      io.warpInitWrite.payload.context.valid := laneCount =/= 0
      io.warpInitWrite.payload.context.runnable := laneCount =/= 0
      io.warpInitWrite.payload.context.pc := currentCommand.entryPc
      io.warpInitWrite.payload.context.activeMask := laneMask(laneCount)
      io.warpInitWrite.payload.context.threadBase := baseThread
      io.warpInitWrite.payload.context.threadBaseX := baseThreadX
      io.warpInitWrite.payload.context.threadBaseY := baseThreadY
      io.warpInitWrite.payload.context.threadBaseZ := baseThreadZ
      io.warpInitWrite.payload.context.threadCount := laneCount
      io.warpInitWrite.payload.context.outstanding := False
      io.warpInitWrite.payload.context.exited := False
      io.warpInitWrite.payload.context.faulted := False

      when(initIndex === smConfig.residentWarpCount - 1) {
        state := State.RUNNING
      } otherwise {
        initIndex := initIndex + 1
      }
    }

    is(State.RUNNING) {
      when(trapPendingValid) {
        executionStatus.busy := False
        executionStatus.done := True
        executionStatus.fault := True
        executionStatus.faultPc := trapPendingPc
        executionStatus.faultCode := trapPendingCode
        trapPendingValid := False
        state := State.IDLE
      } elsewhen (io.kernelComplete) {
        executionStatus.busy := False
        executionStatus.done := True
        executionStatus.fault := False
        executionStatus.faultPc := U(0, config.addressWidth bits)
        executionStatus.faultCode := U(FaultCode.None, config.faultCodeWidth bits)
        state := State.IDLE
      }
    }
  }
}
