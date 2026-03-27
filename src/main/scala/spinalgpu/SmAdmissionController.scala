package spinalgpu

import spinal.core._
import spinal.lib._

class SmAdmissionController(config: SmConfig) extends Component {
  private object State extends SpinalEnum {
    val IDLE, CLEAR_SHARED, INIT_WARPS, RUNNING = newElement()
  }

  val io = new Bundle {
    val command = in(KernelCommandDesc(config))
    val start = in Bool()
    val clearDone = in Bool()
    val sharedClearBusy = in Bool()
    val sharedClearStart = out Bool()
    val warpInitWrite = master(Flow(WarpContextWrite(config)))
    val registerFileClear = master(Flow(UInt(config.warpIdWidth bits)))
    val kernelComplete = in Bool()
    val trapInfo = slave(Flow(TrapInfo(config)))
    val currentCommand = out(KernelCommandDesc(config))
    val currentGridId = out(UInt(64 bits))
    val executionStatus = out(KernelExecutionStatus(config))
  }

  private val state = RegInit(State.IDLE)
  private val executionStatus = Reg(KernelExecutionStatus(config))
  private val currentCommand = Reg(KernelCommandDesc(config))
  private val initIndex = Reg(UInt(config.warpIdWidth bits)) init (0)
  private val clearIssued = RegInit(False)
  private val trapPendingValid = RegInit(False)
  private val trapPendingPc = Reg(UInt(config.addressWidth bits)) init (0)
  private val trapPendingCode = Reg(UInt(config.faultCodeWidth bits)) init (FaultCode.None)
  private val currentGridId = Reg(UInt(64 bits)) init (0)
  private val nextGridId = Reg(UInt(64 bits)) init (0)

  executionStatus.busy.init(False)
  executionStatus.done.init(False)
  executionStatus.fault.init(False)
  executionStatus.faultPc.init(0)
  executionStatus.faultCode.init(FaultCode.None)

  currentCommand.entryPc.init(0)
  currentCommand.gridDimX.init(0)
  currentCommand.blockDimX.init(0)
  currentCommand.argBase.init(0)
  currentCommand.sharedBytes.init(0)

  io.sharedClearStart := False
  io.warpInitWrite.valid := False
  io.warpInitWrite.payload.index := initIndex
  io.warpInitWrite.payload.context.valid := False
  io.warpInitWrite.payload.context.runnable := False
  io.warpInitWrite.payload.context.pc := 0
  io.warpInitWrite.payload.context.activeMask := 0
  io.warpInitWrite.payload.context.threadBase := 0
  io.warpInitWrite.payload.context.threadCount := 0
  io.warpInitWrite.payload.context.outstanding := False
  io.warpInitWrite.payload.context.exited := False
  io.warpInitWrite.payload.context.faulted := False
  io.registerFileClear.valid := False
  io.registerFileClear.payload := initIndex
  io.currentCommand := currentCommand
  io.currentGridId := currentGridId
  io.executionStatus := executionStatus

  private def laneMask(activeCount: UInt): Bits = {
    val mask = Bits(config.warpSize bits)
    for (lane <- 0 until config.warpSize) {
      mask(lane) := U(lane, activeCount.getWidth bits) < activeCount
    }
    mask
  }

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

    when(io.command.gridDimX =/= 1 || io.command.blockDimX === 0 || io.command.blockDimX > config.maxBlockThreads || io.command.sharedBytes > config.sharedMemoryBytes) {
      executionStatus.done := True
      executionStatus.fault := True
      executionStatus.faultPc := io.command.entryPc
      executionStatus.faultCode := FaultCode.InvalidLaunch
    } otherwise {
      currentGridId := nextGridId
      nextGridId := nextGridId + 1
      executionStatus.busy := True
      initIndex := U(0, config.warpIdWidth bits)
      clearIssued := False
      state := State.CLEAR_SHARED
    }
  }

  switch(state) {
    is(State.CLEAR_SHARED) {
      io.sharedClearStart := !clearIssued
      when(io.sharedClearStart) {
        clearIssued := True
      }
      when(clearIssued && !io.sharedClearBusy) {
        initIndex := U(0, config.warpIdWidth bits)
        state := State.INIT_WARPS
      }
    }

    is(State.INIT_WARPS) {
      val baseThread = (initIndex.resize(config.threadCountWidth bits) * U(config.warpSize, config.threadCountWidth bits)).resized
      val remaining = UInt(config.threadCountWidth bits)
      when(currentCommand.blockDimX > baseThread) {
        remaining := currentCommand.blockDimX - baseThread
      } otherwise {
        remaining := U(0, config.threadCountWidth bits)
      }

      val laneCount = UInt(config.threadCountWidth bits)
      laneCount := remaining
      when(remaining > config.warpSize) {
        laneCount := config.warpSize
      }

      io.warpInitWrite.valid := True
      io.warpInitWrite.payload.index := initIndex
      io.warpInitWrite.payload.context.valid := laneCount =/= 0
      io.warpInitWrite.payload.context.runnable := laneCount =/= 0
      io.warpInitWrite.payload.context.pc := currentCommand.entryPc
      io.warpInitWrite.payload.context.activeMask := laneMask(laneCount)
      io.warpInitWrite.payload.context.threadBase := baseThread
      io.warpInitWrite.payload.context.threadCount := laneCount
      io.warpInitWrite.payload.context.outstanding := False
      io.warpInitWrite.payload.context.exited := False
      io.warpInitWrite.payload.context.faulted := False

      io.registerFileClear.valid := True
      io.registerFileClear.payload := initIndex

      when(initIndex === config.residentWarpCount - 1) {
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
