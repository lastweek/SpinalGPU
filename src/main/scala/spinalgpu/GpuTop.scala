package spinalgpu

import spinal.core._

class GpuTop extends Component {
  val io = new Bundle {}

  val coreClock = in Bool()
  val coreReset = in Bool()

  val coreClockDomain = ClockDomain(
    clock = coreClock,
    reset = coreReset,
    config = ClockDomainConfig(resetKind = SYNC)
  )

  val core = new ClockingArea(coreClockDomain) {}
}
