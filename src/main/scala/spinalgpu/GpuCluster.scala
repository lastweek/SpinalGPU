package spinalgpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

case class GpuClusterIo(config: GpuConfig) extends Bundle {
  val memory = master(Axi4(config.axiConfig))
  val command = StreamingMultiprocessorCommandIo(config)
}

class GpuCluster(val config: GpuConfig = GpuConfig.default) extends Component {
  val io = GpuClusterIo(config)

  private val gridDispatchController = new GridDispatchController(config)
  private val smControllers = Array.fill(config.smCount)(new SmCtaController(config))
  private val smCores = Array.fill(config.smCount)(new SmExecutionCore(config.sm))
  private val clusterMemoryArbiter = new ClusterExternalMemoryArbiter(config)
  private val externalMemoryAdapter = new ExternalMemoryAxiAdapter(config)

  gridDispatchController.io.command := io.command.command
  gridDispatchController.io.start := io.command.start
  gridDispatchController.io.clearDone := io.command.clearDone
  io.command.executionStatus := gridDispatchController.io.executionStatus

  for (sm <- 0 until config.smCount) {
    smControllers(sm).io.command := gridDispatchController.io.smCommand(sm)
    smControllers(sm).io.start := gridDispatchController.io.smStart(sm)
    smControllers(sm).io.clearDone := gridDispatchController.io.smClearDone(sm)
    gridDispatchController.io.smExecutionStatus(sm) := smControllers(sm).io.executionStatus

    smCores(sm).io.launchWrite <> smControllers(sm).io.warpInitWrite
    smCores(sm).io.kernelBusy := smControllers(sm).io.executionStatus.busy && !smControllers(sm).io.warpInitWrite.valid
    smCores(sm).io.clearBindings := smControllers(sm).io.warpInitWrite.valid && smControllers(sm).io.warpInitWrite.payload.index === 0
    smCores(sm).io.sharedClearStart := smControllers(sm).io.sharedClearStart
    smControllers(sm).io.sharedClearBusy := smCores(sm).io.sharedClearBusy
    smCores(sm).io.ctaCommand := smControllers(sm).io.currentCommand
    smControllers(sm).io.kernelComplete := smCores(sm).io.kernelComplete
    smControllers(sm).io.trapInfo <> smCores(sm).io.trapInfo

    clusterMemoryArbiter.io.smReq(sm) <> smCores(sm).io.externalMemReq
    smCores(sm).io.externalMemRsp <> clusterMemoryArbiter.io.smRsp(sm)
  }

  gridDispatchController.io.memoryFabricIdle := clusterMemoryArbiter.io.idle && externalMemoryAdapter.io.idle
  clusterMemoryArbiter.io.memoryReq <> externalMemoryAdapter.io.request
  externalMemoryAdapter.io.response <> clusterMemoryArbiter.io.memoryRsp
  io.memory <> externalMemoryAdapter.io.axi
}
