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
  private val subSmCandidateHits = Bits(config.subSmCount bits)
  private val subSmCandidateIds = Vec(UInt(config.subSmIdWidth bits), config.subSmCount)

  if (config.subSmCount == 1) {
    subSmCandidateIds(0) := U(0, config.subSmIdWidth bits)
    subSmCandidateHits(0) := io.subSmRequest(0)
    selectedSubSmValid := subSmCandidateHits(0)
    selectedSubSmId := U(0, config.subSmIdWidth bits)
  } else {
    selectedSubSmId := subSmBase
    for (offset <- 0 until config.subSmCount) {
      val candidateWide = UInt((config.subSmIdWidth + 1) bits)
      candidateWide := subSmBase.resize(config.subSmIdWidth + 1) + U(offset, config.subSmIdWidth + 1 bits)

      val candidate = UInt(config.subSmIdWidth bits)
      candidate := candidateWide.resized
      when(candidateWide >= U(config.subSmCount, config.subSmIdWidth + 1 bits)) {
        candidate := (candidateWide - U(config.subSmCount, config.subSmIdWidth + 1 bits)).resized
      }
      subSmCandidateIds(offset) := candidate
      subSmCandidateHits(offset) := io.subSmRequest(candidate)
    }
    selectedSubSmValid := subSmCandidateHits.orR
    for (offset <- 0 until config.subSmCount) {
      val earlierCandidateHit =
        if (offset == 0) False else subSmCandidateHits(offset - 1 downto 0).orR
      when(subSmCandidateHits(offset) && !earlierCandidateHit) {
        selectedSubSmId := subSmCandidateIds(offset)
      }
    }
  }

  private val selectedWarpValid = Bool()
  private val selectedWarpId = UInt(config.warpIdWidth bits)
  private val warpCandidateHits = Bits(config.residentWarpCount bits)
  private val warpCandidateIds = Vec(UInt(config.warpIdWidth bits), config.residentWarpCount)

  if (config.residentWarpCount == 1) {
    val context = io.warpContexts(0)
    val ready =
      context.valid && context.runnable && !context.outstanding && !context.exited && !context.faulted && !io.bindings(0).bound
    warpCandidateIds(0) := U(0, config.warpIdWidth bits)
    warpCandidateHits(0) := ready
    selectedWarpValid := ready
    selectedWarpId := U(0, config.warpIdWidth bits)
  } else {
    selectedWarpId := warpBase
    for (offset <- 0 until config.residentWarpCount) {
      val candidateWide = UInt((config.warpIdWidth + 1) bits)
      candidateWide := warpBase.resize(config.warpIdWidth + 1) + U(offset, config.warpIdWidth + 1 bits)

      val candidate = UInt(config.warpIdWidth bits)
      candidate := candidateWide.resized
      when(candidateWide >= U(config.residentWarpCount, config.warpIdWidth + 1 bits)) {
        candidate := (candidateWide - U(config.residentWarpCount, config.warpIdWidth + 1 bits)).resized
      }

      val context = io.warpContexts(candidate)
      val ready =
        context.valid && context.runnable && !context.outstanding && !context.exited && !context.faulted && !io.bindings(candidate).bound
      warpCandidateIds(offset) := candidate
      warpCandidateHits(offset) := ready
    }
    selectedWarpValid := warpCandidateHits.orR
    for (offset <- 0 until config.residentWarpCount) {
      val earlierCandidateHit =
        if (offset == 0) False else warpCandidateHits(offset - 1 downto 0).orR
      when(warpCandidateHits(offset) && !earlierCandidateHit) {
        selectedWarpId := warpCandidateIds(offset)
      }
    }
  }

  io.bind.valid := selectedSubSmValid && selectedWarpValid
  io.bind.payload.warpId := selectedWarpId
  io.bind.payload.subSmId := selectedSubSmId
  if (config.subSmCount == 1) {
    io.bind.payload.localSlotId := io.freeLocalSlotId(0)
  } else {
    io.bind.payload.localSlotId := io.freeLocalSlotId(selectedSubSmId)
  }

  when(io.bind.valid) {
    val nextSubSmWide = UInt((config.subSmIdWidth + 1) bits)
    nextSubSmWide := selectedSubSmId.resize(config.subSmIdWidth + 1) + U(1, config.subSmIdWidth + 1 bits)
    subSmBase := nextSubSmWide.resized
    when(nextSubSmWide >= U(config.subSmCount, config.subSmIdWidth + 1 bits)) {
      subSmBase := (nextSubSmWide - U(config.subSmCount, config.subSmIdWidth + 1 bits)).resized
    }

    val nextWarpWide = UInt((config.warpIdWidth + 1) bits)
    nextWarpWide := selectedWarpId.resize(config.warpIdWidth + 1) + U(1, config.warpIdWidth + 1 bits)
    warpBase := nextWarpWide.resized
    when(nextWarpWide >= U(config.residentWarpCount, config.warpIdWidth + 1 bits)) {
      warpBase := (nextWarpWide - U(config.residentWarpCount, config.warpIdWidth + 1 bits)).resized
    }
  }
}
