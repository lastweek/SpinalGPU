package spinalgpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

case class GpuTopIo(config: GpuConfig) extends Bundle {
  val memory = master(Axi4(config.axiConfig))
  val hostControl = slave(AxiLite4(config.axiLiteConfig))
  val debugExecutionStatus = out(KernelExecutionStatus(config))
}

class GpuTop(val config: GpuConfig = GpuConfig.default) extends Component {
  val io = GpuTopIo(config)

  val coreClock = in Bool()
  val coreReset = in Bool()

  val coreClockDomain = ClockDomain(
    clock = coreClock,
    reset = coreReset,
    config = ClockDomainConfig(resetKind = SYNC)
  )

  val core = new ClockingArea(coreClockDomain) {
    val hostControlBlock = new HostControlBlock(config)
    val gpuCluster = new GpuCluster(config)

    hostControlBlock.io.axi <> io.hostControl
    gpuCluster.io.command.command := hostControlBlock.io.command
    gpuCluster.io.command.start := hostControlBlock.io.start
    gpuCluster.io.command.clearDone := hostControlBlock.io.clearDone
    hostControlBlock.io.executionStatus := gpuCluster.io.command.executionStatus
    io.debugExecutionStatus := gpuCluster.io.command.executionStatus
    io.memory <> gpuCluster.io.memory
  }
}
