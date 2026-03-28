package spinalgpu

import spinal.core._

class LocalWarpScheduler(config: SmConfig) extends Component {
  val io = new Bundle {
    val clear = in Bool()
    val readySlots = in(Bits(config.residentWarpsPerSubSm bits))
    val advance = in Bool()
    val selectedValid = out Bool()
    val selectedSlotId = out(UInt(config.localSlotIdWidth bits))
  }

  private val schedulerBase = Reg(UInt(config.localSlotIdWidth bits)) init (0)
  private val (selectedValid, selectedSlotId) =
    RoundRobinSelect.firstFromBase(schedulerBase, io.readySlots, config.residentWarpsPerSubSm, config.localSlotIdWidth)

  io.selectedValid := selectedValid
  io.selectedSlotId := selectedSlotId

  when(io.clear) {
    schedulerBase := U(0, config.localSlotIdWidth bits)
  } elsewhen (io.advance && selectedValid) {
    schedulerBase := RoundRobinSelect.nextAfter(selectedSlotId, config.residentWarpsPerSubSm, config.localSlotIdWidth)
  }
}
