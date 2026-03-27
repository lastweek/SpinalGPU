package spinalgpu

import spinal.core._
import spinal.lib._

class WarpStateTable(config: SmConfig) extends Component {
  val io = new Bundle {
    val launchWrite = slave(Flow(WarpContextWrite(config)))
    val updateWrite = slave(Flow(WarpContextWrite(config)))
    val states = out(Vec.fill(config.residentWarpCount)(WarpContext(config)))
  }

  private val table = Vec.fill(config.residentWarpCount)(Reg(WarpContext(config)))

  table.foreach { entry =>
    entry.valid.init(False)
    entry.runnable.init(False)
    entry.pc.init(0)
    entry.activeMask.init(0)
    entry.threadBase.init(0)
    entry.threadBaseX.init(0)
    entry.threadBaseY.init(0)
    entry.threadBaseZ.init(0)
    entry.threadCount.init(0)
    entry.outstanding.init(False)
    entry.exited.init(False)
    entry.faulted.init(False)
  }

  when(io.launchWrite.valid) {
    table(io.launchWrite.payload.index) := io.launchWrite.payload.context
  }

  when(io.updateWrite.valid) {
    table(io.updateWrite.payload.index) := io.updateWrite.payload.context
  }

  for (index <- 0 until config.residentWarpCount) {
    io.states(index) := table(index)
  }
}
