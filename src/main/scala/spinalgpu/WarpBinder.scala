package spinalgpu

import spinal.core._
import spinal.lib._

class WarpBinder(config: SmConfig) extends Component {
  val io = new Bundle {
    val warpContexts = in(Vec.fill(config.residentWarpCount)(WarpContext(config)))
    val bindings = in(Vec.fill(config.residentWarpCount)(WarpBindingInfo(config)))
    val subSmRequest = in(Bits(config.subSmCount bits))
    val freeLocalSlotId = in(Vec(UInt(config.localSlotIdWidth bits), config.subSmCount))
    val bind = master(Flow(SubSmBindReq(config)))
  }

  private val subSmBase = Reg(UInt(config.subSmIdWidth bits)) init (0)
  private val warpBase = Reg(UInt(config.warpIdWidth bits)) init (0)

  private val selectedSubSmValid = Bool()
  private val selectedSubSmId = UInt(config.subSmIdWidth bits)
  private val subSmCandidates = Bits(config.subSmCount bits)

  for (subSm <- 0 until config.subSmCount) {
    subSmCandidates(subSm) := io.subSmRequest(subSm)
  }
  private val subSmSelection =
    RoundRobinSelect.firstFromBase(subSmBase, subSmCandidates, config.subSmCount, config.subSmIdWidth)
  selectedSubSmValid := subSmSelection._1
  selectedSubSmId := subSmSelection._2

  private val selectedWarpValid = Bool()
  private val selectedWarpId = UInt(config.warpIdWidth bits)
  private val warpCandidates = Bits(config.residentWarpCount bits)

  for (warpId <- 0 until config.residentWarpCount) {
    val context = io.warpContexts(warpId)
    warpCandidates(warpId) :=
      context.valid &&
        context.runnable &&
        !context.outstanding &&
        !context.exited &&
        !context.faulted &&
        !io.bindings(warpId).bound
  }
  private val warpSelection =
    RoundRobinSelect.firstFromBase(warpBase, warpCandidates, config.residentWarpCount, config.warpIdWidth)
  selectedWarpValid := warpSelection._1
  selectedWarpId := warpSelection._2

  io.bind.valid := selectedSubSmValid && selectedWarpValid
  io.bind.payload.warpId := selectedWarpId
  io.bind.payload.subSmId := selectedSubSmId
  if (config.subSmCount == 1) {
    io.bind.payload.localSlotId := io.freeLocalSlotId(0)
  } else {
    io.bind.payload.localSlotId := io.freeLocalSlotId(selectedSubSmId)
  }

  when(io.bind.valid) {
    subSmBase := RoundRobinSelect.nextAfter(selectedSubSmId, config.subSmCount, config.subSmIdWidth)
    warpBase := RoundRobinSelect.nextAfter(selectedWarpId, config.residentWarpCount, config.warpIdWidth)
  }
}
