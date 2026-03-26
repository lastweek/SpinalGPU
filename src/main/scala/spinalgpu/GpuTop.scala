package spinalgpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

case class GpuTopIo(config: SmConfig) extends Bundle {
  val memory = master(Axi4(config.axiConfig))
  val control = slave(AxiLite4(config.axiLiteConfig))
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
    val controlBlock = new KernelControlBlock(config)
    val streamingMultiprocessor = new StreamingMultiprocessor(config)

    controlBlock.io.axi <> io.control
    streamingMultiprocessor.io.control.launch := controlBlock.io.launch
    streamingMultiprocessor.io.control.start := controlBlock.io.start
    streamingMultiprocessor.io.control.clearDone := controlBlock.io.clearDone
    controlBlock.io.status := streamingMultiprocessor.io.control.status
    io.memory <> streamingMultiprocessor.io.memory
  }
}
