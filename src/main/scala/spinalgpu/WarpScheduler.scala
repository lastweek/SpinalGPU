package spinalgpu

import spinal.core._
import spinal.lib._

class WarpScheduler(config: SmConfig) extends Component {
  val io = new Bundle {
    val warpContexts = in(Vec.fill(config.residentWarpCount)(WarpContext(config)))
    val schedule = master(Stream(WarpScheduleReq(config)))
  }

  private val readyVec = Vec(Bool(), config.residentWarpCount)
  for (index <- 0 until config.residentWarpCount) {
    val context = io.warpContexts(index)
    readyVec(index) := context.valid && context.runnable && !context.outstanding && !context.exited && !context.faulted
  }

  private val selectedIndex = UInt(config.warpIdWidth bits)
  selectedIndex := 0
  for (index <- config.residentWarpCount - 1 downto 0) {
    when(readyVec(index)) {
      selectedIndex := index
    }
  }

  private val selectedContext = io.warpContexts(selectedIndex)

  io.schedule.valid := readyVec.asBits.orR
  io.schedule.payload.warpId := selectedIndex
  io.schedule.payload.context := selectedContext
}
