package spinalgpu

import spinal.core._
import spinal.lib._

class WarpStateTable(config: SmConfig) extends Component {
  val io = new Bundle {
    val launchWrite = slave(Flow(WarpContextWrite(config)))
    val updateWrites = Vec(slave(Flow(WarpContextWrite(config))), config.subSmCount)
    val states = out(Vec.fill(config.residentWarpCount)(WarpContext(config)))
  }

  private val table = Vec.fill(config.residentWarpCount)(Reg(WarpContext(config)))
  table.foreach { context =>
    context.valid.init(False)
    context.runnable.init(False)
    context.pc.init(0)
    context.activeMask.init(0)
    context.threadBase.init(0)
    context.threadBaseX.init(0)
    context.threadBaseY.init(0)
    context.threadBaseZ.init(0)
    context.threadCount.init(0)
    context.outstanding.init(False)
    context.exited.init(False)
    context.faulted.init(False)
  }

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

  if (config.residentWarpCount == 1) {
    when(io.launchWrite.valid) {
      table(0) := io.launchWrite.payload.context
    }

    for (port <- 0 until config.subSmCount) {
      when(io.updateWrites(port).valid) {
        table(0) := io.updateWrites(port).payload.context
      }
    }
  } else {
    when(io.launchWrite.valid) {
      table(io.launchWrite.payload.index) := io.launchWrite.payload.context
    }

    for (port <- 0 until config.subSmCount) {
      when(io.updateWrites(port).valid) {
        table(io.updateWrites(port).payload.index) := io.updateWrites(port).payload.context
      }
    }
  }

  for (index <- 0 until config.residentWarpCount) {
    io.states(index) := table(index)
  }
}
