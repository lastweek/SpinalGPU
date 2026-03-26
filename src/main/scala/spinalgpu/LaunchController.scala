package spinalgpu

import spinal.core._
import spinal.lib._

class LaunchController(config: SmConfig) extends Component {
  private object State extends SpinalEnum {
    val IDLE, CLEAR_SHARED, INIT_WARPS, RUNNING = newElement()
  }

  val io = new Bundle {
    val launch = in(KernelLaunchDesc(config))
    val start = in Bool()
    val clearDone = in Bool()
    val sharedClearBusy = in Bool()
    val sharedClearStart = out Bool()
    val warpInitWrite = master(Flow(WarpContextWrite(config)))
    val registerFileClear = master(Flow(UInt(config.warpIdWidth bits)))
    val kernelComplete = in Bool()
    val trapInfo = slave(Flow(TrapInfo(config)))
    val currentLaunch = out(KernelLaunchDesc(config))
    val currentGridId = out(UInt(64 bits))
    val status = out(LaunchStatus(config))
  }

  private val state = RegInit(State.IDLE)
  private val status = Reg(LaunchStatus(config))
  private val currentLaunch = Reg(KernelLaunchDesc(config))
  private val initIndex = Reg(UInt(config.warpIdWidth bits)) init (0)
  private val clearIssued = RegInit(False)
  private val trapPendingValid = RegInit(False)
  private val trapPendingPc = Reg(UInt(config.addressWidth bits)) init (0)
  private val trapPendingCode = Reg(UInt(config.faultCodeWidth bits)) init (FaultCode.None)
  private val currentGridId = Reg(UInt(64 bits)) init (0)
  private val nextGridId = Reg(UInt(64 bits)) init (0)

  status.busy.init(False)
  status.done.init(False)
  status.fault.init(False)
  status.faultPc.init(0)
  status.faultCode.init(FaultCode.None)

  currentLaunch.entryPc.init(0)
  currentLaunch.gridDimX.init(0)
  currentLaunch.blockDimX.init(0)
  currentLaunch.argBase.init(0)
  currentLaunch.sharedBytes.init(0)

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
  io.currentLaunch := currentLaunch
  io.currentGridId := currentGridId
  io.status := status

  private def laneMask(activeCount: UInt): Bits = {
    val mask = Bits(config.warpSize bits)
    for (lane <- 0 until config.warpSize) {
      mask(lane) := U(lane, activeCount.getWidth bits) < activeCount
    }
    mask
  }

  when(io.clearDone && !status.busy) {
    status.done := False
    status.fault := False
    status.faultPc := U(0, config.addressWidth bits)
    status.faultCode := U(FaultCode.None, config.faultCodeWidth bits)
    trapPendingValid := False
  }

  when(io.trapInfo.valid) {
    trapPendingValid := True
    trapPendingPc := io.trapInfo.pc
    trapPendingCode := io.trapInfo.faultCode
  }

  when(io.start && !status.busy && state === State.IDLE) {
    currentLaunch := io.launch
    status.done := False
    status.fault := False
    status.faultPc := U(0, config.addressWidth bits)
    status.faultCode := U(FaultCode.None, config.faultCodeWidth bits)
    trapPendingValid := False

    when(io.launch.gridDimX =/= 1 || io.launch.blockDimX === 0 || io.launch.blockDimX > config.maxBlockThreads || io.launch.sharedBytes > config.sharedMemoryBytes) {
      status.done := True
      status.fault := True
      status.faultPc := io.launch.entryPc
      status.faultCode := FaultCode.InvalidLaunch
    } otherwise {
      currentGridId := nextGridId
      nextGridId := nextGridId + 1
      status.busy := True
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
      when(currentLaunch.blockDimX > baseThread) {
        remaining := currentLaunch.blockDimX - baseThread
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
      io.warpInitWrite.payload.context.pc := currentLaunch.entryPc
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
        status.busy := False
        status.done := True
        status.fault := True
        status.faultPc := trapPendingPc
        status.faultCode := trapPendingCode
        trapPendingValid := False
        state := State.IDLE
      } elsewhen (io.kernelComplete) {
        status.busy := False
        status.done := True
        status.fault := False
        status.faultPc := U(0, config.addressWidth bits)
        status.faultCode := U(FaultCode.None, config.faultCodeWidth bits)
        state := State.IDLE
      }
    }
  }
}
