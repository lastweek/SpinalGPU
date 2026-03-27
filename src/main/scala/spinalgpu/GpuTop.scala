package spinalgpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

case class GpuTopIo(config: SmConfig) extends Bundle {
  val memory = master(Axi4(config.axiConfig))
  val hostControl = slave(AxiLite4(config.axiLiteConfig))
  val debugExecutionStatus = out(KernelExecutionStatus(config))
}

class GpuTop(val config: SmConfig = SmConfig.default) extends Component {
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
    val streamingMultiprocessor = new StreamingMultiprocessor(config)

    hostControlBlock.io.axi <> io.hostControl
    streamingMultiprocessor.io.command.command := hostControlBlock.io.command
    streamingMultiprocessor.io.command.start := hostControlBlock.io.start
    streamingMultiprocessor.io.command.clearDone := hostControlBlock.io.clearDone
    hostControlBlock.io.executionStatus := streamingMultiprocessor.io.command.executionStatus
    io.debugExecutionStatus := streamingMultiprocessor.io.command.executionStatus
    io.memory <> streamingMultiprocessor.io.memory
  }
}
