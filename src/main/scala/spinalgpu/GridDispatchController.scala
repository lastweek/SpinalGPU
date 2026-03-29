package spinalgpu

import spinal.core._
import spinal.lib._

class GridDispatchController(config: GpuConfig) extends Component {
  private val smConfig = config.sm

  private object State extends SpinalEnum {
    val IDLE, RUNNING = newElement()
  }

  val io = new Bundle {
    val command = in(KernelCommandDesc(config))
    val start = in Bool()
    val clearDone = in Bool()
    val smExecutionStatus = in(Vec.fill(config.smCount)(KernelExecutionStatus(config)))
    val smStart = out(Bits(config.smCount bits))
    val smClearDone = out(Bits(config.smCount bits))
    val smCommand = out(Vec.fill(config.smCount)(CtaCommandDesc(smConfig)))
    val memoryFabricIdle = in Bool()
    val executionStatus = out(KernelExecutionStatus(config))
    val currentGridId = out(UInt(64 bits))
  }

  private val state = RegInit(State.IDLE)
  private val executionStatus = Reg(KernelExecutionStatus(config))
  private val currentCommand = Reg(KernelCommandDesc(config))
  private val currentGridId = Reg(UInt(64 bits)) init (0)
  private val nextGridId = Reg(UInt(64 bits)) init (0)
  private val dispatchSmBase = Reg(UInt(config.smIdWidth bits)) init (0)
  private val stopDispatch = RegInit(False)
  private val firstFaultValid = RegInit(False)
  private val firstFaultPc = Reg(UInt(config.addressWidth bits)) init (0)
  private val firstFaultCode = Reg(UInt(config.faultCodeWidth bits)) init (FaultCode.None)
  private val smDoneSeen = Vec.fill(config.smCount)(RegInit(False))

  private val dimensionCountWidth = config.dataWidth * 3
  private val gridCountWidth = config.dataWidth * 3
  private val gridDimProductWide = UInt(dimensionCountWidth bits)
  gridDimProductWide := (io.command.gridDimX.resize(dimensionCountWidth bits) *
    io.command.gridDimY.resize(dimensionCountWidth bits) *
    io.command.gridDimZ.resize(dimensionCountWidth bits)).resized
  private val blockThreadCountWide = UInt(dimensionCountWidth bits)
  blockThreadCountWide := (io.command.blockDimX.resize(dimensionCountWidth bits) *
    io.command.blockDimY.resize(dimensionCountWidth bits) *
    io.command.blockDimZ.resize(dimensionCountWidth bits)).resized

  private val totalCtaCount = Reg(UInt(gridCountWidth bits)) init (0)
  private val dispatchedCtaCount = Reg(UInt(gridCountWidth bits)) init (0)
  private val completedCtaCount = Reg(UInt(gridCountWidth bits)) init (0)
  private val nextCtaX = Reg(UInt(config.dataWidth bits)) init (0)
  private val nextCtaY = Reg(UInt(config.dataWidth bits)) init (0)
  private val nextCtaZ = Reg(UInt(config.dataWidth bits)) init (0)

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

  io.smStart := B(0, config.smCount bits)
  io.smClearDone := B(0, config.smCount bits)
  io.executionStatus := executionStatus
  io.currentGridId := currentGridId

  for (sm <- 0 until config.smCount) {
    io.smCommand(sm).entryPc := currentCommand.entryPc
    io.smCommand(sm).gridDimX := currentCommand.gridDimX
    io.smCommand(sm).gridDimY := currentCommand.gridDimY
    io.smCommand(sm).gridDimZ := currentCommand.gridDimZ
    io.smCommand(sm).blockDimX := currentCommand.blockDimX
    io.smCommand(sm).blockDimY := currentCommand.blockDimY
    io.smCommand(sm).blockDimZ := currentCommand.blockDimZ
    io.smCommand(sm).argBase := currentCommand.argBase
    io.smCommand(sm).sharedBytes := currentCommand.sharedBytes
    io.smCommand(sm).ctaidX := nextCtaX
    io.smCommand(sm).ctaidY := nextCtaY
    io.smCommand(sm).ctaidZ := nextCtaZ
    io.smCommand(sm).smId := U(sm, config.dataWidth bits)
    io.smCommand(sm).nsmId := U(config.smCount, config.dataWidth bits)
    io.smCommand(sm).gridId := currentGridId
  }

  private val invalidGridDimZero = io.command.gridDimX === 0 || io.command.gridDimY === 0 || io.command.gridDimZ === 0
  private val invalidBlockDimZero = io.command.blockDimX === 0 || io.command.blockDimY === 0 || io.command.blockDimZ === 0
  private val invalidBlockThreadCount =
    blockThreadCountWide === 0 || blockThreadCountWide > U(smConfig.maxBlockThreads, blockThreadCountWide.getWidth bits)
  private val invalidSharedBytes = io.command.sharedBytes > U(smConfig.sharedMemoryBytes, io.command.sharedBytes.getWidth bits)

  private val availableSmCandidates = Bits(config.smCount bits)
  for (sm <- 0 until config.smCount) {
    availableSmCandidates(sm) := !io.smExecutionStatus(sm).busy && !io.smExecutionStatus(sm).done
  }
  private val smSelection =
    RoundRobinSelect.firstFromBase(dispatchSmBase, availableSmCandidates, config.smCount, config.smIdWidth)
  private val dispatchSmValid = smSelection._1
  private val dispatchSmId = smSelection._2

  private val activeSm = io.smExecutionStatus.map(status => status.busy || status.done).foldLeft(False)(_ || _)

  when(io.clearDone && !executionStatus.busy) {
    executionStatus.done := False
    executionStatus.fault := False
    executionStatus.faultPc := U(0, config.addressWidth bits)
    executionStatus.faultCode := U(FaultCode.None, config.faultCodeWidth bits)
  }

  when(io.start && !executionStatus.busy && state === State.IDLE) {
    currentCommand := io.command
    executionStatus.done := False
    executionStatus.fault := False
    executionStatus.faultPc := U(0, config.addressWidth bits)
    executionStatus.faultCode := U(FaultCode.None, config.faultCodeWidth bits)
    firstFaultValid := False
    firstFaultPc := U(0, config.addressWidth bits)
    firstFaultCode := U(FaultCode.None, config.faultCodeWidth bits)
    stopDispatch := False
    dispatchedCtaCount := 0
    completedCtaCount := 0
    totalCtaCount := gridDimProductWide.resized
    nextCtaX := 0
    nextCtaY := 0
    nextCtaZ := 0
    dispatchSmBase := 0
    for (sm <- 0 until config.smCount) {
      smDoneSeen(sm) := False
    }

    when(invalidGridDimZero || invalidBlockDimZero || invalidBlockThreadCount || invalidSharedBytes) {
      executionStatus.done := True
      executionStatus.fault := True
      executionStatus.faultPc := io.command.entryPc
      executionStatus.faultCode := FaultCode.InvalidLaunch
    } otherwise {
      currentGridId := nextGridId
      nextGridId := nextGridId + 1
      executionStatus.busy := True
      state := State.RUNNING
    }
  }

  when(state === State.RUNNING) {
    for (sm <- 0 until config.smCount) {
      when(io.smExecutionStatus(sm).done && !smDoneSeen(sm)) {
        smDoneSeen(sm) := True
        completedCtaCount := completedCtaCount + 1
        when(io.smExecutionStatus(sm).fault && !firstFaultValid) {
          firstFaultValid := True
          firstFaultPc := io.smExecutionStatus(sm).faultPc
          firstFaultCode := io.smExecutionStatus(sm).faultCode
          stopDispatch := True
        }
      }

      when(io.smExecutionStatus(sm).done) {
        io.smClearDone(sm) := True
      }

      when(!io.smExecutionStatus(sm).done) {
        smDoneSeen(sm) := False
      }
    }

    val shouldDispatch = !stopDispatch && dispatchedCtaCount < totalCtaCount && dispatchSmValid
    when(shouldDispatch) {
      if (config.smCount == 1) {
        io.smStart(0) := True
      } else {
        io.smStart(dispatchSmId) := True
      }
      dispatchedCtaCount := dispatchedCtaCount + 1
      dispatchSmBase := RoundRobinSelect.nextAfter(dispatchSmId, config.smCount, config.smIdWidth)

      val (nextX, nextY, nextZ) = GridCoordinateLogic.increment(config, nextCtaX, nextCtaY, nextCtaZ, currentCommand.gridDimX, currentCommand.gridDimY, currentCommand.gridDimZ)
      nextCtaX := nextX
      nextCtaY := nextY
      nextCtaZ := nextZ
    }

    val successfulCompletion =
      !stopDispatch &&
        dispatchedCtaCount === totalCtaCount &&
        completedCtaCount === totalCtaCount &&
        !activeSm &&
        io.memoryFabricIdle
    val faultCompletion = stopDispatch && !activeSm && io.memoryFabricIdle

    when(successfulCompletion) {
      executionStatus.busy := False
      executionStatus.done := True
      executionStatus.fault := False
      executionStatus.faultPc := U(0, config.addressWidth bits)
      executionStatus.faultCode := U(FaultCode.None, config.faultCodeWidth bits)
      state := State.IDLE
    } elsewhen (faultCompletion) {
      executionStatus.busy := False
      executionStatus.done := True
      executionStatus.fault := firstFaultValid
      executionStatus.faultPc := firstFaultPc
      executionStatus.faultCode := firstFaultCode
      state := State.IDLE
    }
  }
}
