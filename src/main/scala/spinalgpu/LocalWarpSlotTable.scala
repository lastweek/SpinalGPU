package spinalgpu

import spinal.core._
import spinal.lib._

class LocalWarpSlotTable(config: SmConfig) extends Component {
  val io = new Bundle {
    val clearBindings = in Bool()
    val bind = slave(Flow(SubSmBindReq(config)))
    val freeSlotFound = out Bool()
    val freeSlotId = out(UInt(config.localSlotIdWidth bits))
    val slotOccupied = out(Bits(config.residentWarpsPerSubSm bits))
    val boundWarpIds = out(Vec(UInt(config.warpIdWidth bits), config.residentWarpsPerSubSm))
    val clearSlot = master(Flow(UInt(config.localSlotIdWidth bits)))
  }

  private val slotOccupied = Reg(Bits(config.residentWarpsPerSubSm bits)) init (0)
  private val slotWarpIds = Vec.fill(config.residentWarpsPerSubSm)(Reg(UInt(config.warpIdWidth bits)) init (0))

  io.slotOccupied := slotOccupied
  io.boundWarpIds := slotWarpIds

  private val freeSlotCandidates = Bits(config.residentWarpsPerSubSm bits)
  for (slot <- 0 until config.residentWarpsPerSubSm) {
    freeSlotCandidates(slot) := !slotOccupied(slot)
  }
  private val (freeSlotFound, freeSlotId) =
    RoundRobinSelect.first(freeSlotCandidates, config.residentWarpsPerSubSm, config.localSlotIdWidth)
  io.freeSlotFound := freeSlotFound
  io.freeSlotId := freeSlotId

  io.clearSlot.valid := io.bind.valid
  io.clearSlot.payload := (if (config.residentWarpsPerSubSm == 1) U(0, config.localSlotIdWidth bits) else io.bind.payload.localSlotId)

  when(io.clearBindings) {
    slotOccupied := B(0, config.residentWarpsPerSubSm bits)
    for (slot <- 0 until config.residentWarpsPerSubSm) {
      slotWarpIds(slot) := U(0, config.warpIdWidth bits)
    }
  }

  when(io.bind.valid) {
    val bindSlotId = if (config.residentWarpsPerSubSm == 1) U(0, config.localSlotIdWidth bits) else io.bind.payload.localSlotId
    if (config.residentWarpsPerSubSm == 1) {
      slotOccupied(0) := True
      slotWarpIds(0) := io.bind.payload.warpId
    } else {
      slotOccupied(bindSlotId) := True
      slotWarpIds(bindSlotId) := io.bind.payload.warpId
    }
  }
}
