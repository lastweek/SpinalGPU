package spinalgpu

import spinal.core._
import spinal.lib._

class WarpRegisterFile(config: SmConfig, slotCount: Int = -1) extends Component {
  private val effectiveSlotCount = if (slotCount > 0) slotCount else config.residentWarpCount
  private val slotIdWidth = log2Up(effectiveSlotCount max 2)

  val io = new Bundle {
    val readSlotId = in(UInt(slotIdWidth bits))
    val readAddrA = in(UInt(config.registerAddressWidth bits))
    val readAddrB = in(UInt(config.registerAddressWidth bits))
    val readAddrC = in(UInt(config.registerAddressWidth bits))
    val readDataA = out(Vec(UInt(config.dataWidth bits), config.warpSize))
    val readDataB = out(Vec(UInt(config.dataWidth bits), config.warpSize))
    val readDataC = out(Vec(UInt(config.dataWidth bits), config.warpSize))
    val write = slave(Flow(LocalRegisterWriteback(config)))
    val clearSlot = slave(Flow(UInt(slotIdWidth bits)))
  }

  private val file =
    Vec.fill(effectiveSlotCount)(Vec.fill(config.warpSize)(Vec.fill(config.registerCount)(Reg(UInt(config.dataWidth bits)) init (0))))

  when(io.clearSlot.valid) {
    for (lane <- 0 until config.warpSize) {
      for (reg <- 0 until config.registerCount) {
        if (effectiveSlotCount == 1) {
          file(0)(lane)(reg) := U(0, config.dataWidth bits)
        } else {
          file(io.clearSlot.payload)(lane)(reg) := U(0, config.dataWidth bits)
        }
      }
    }
  }

  when(io.write.valid && io.write.payload.rd =/= 0) {
    for (lane <- 0 until config.warpSize) {
      when(io.write.payload.writeMask(lane)) {
        if (effectiveSlotCount == 1) {
          file(0)(lane)(io.write.payload.rd) := io.write.payload.data(lane).asUInt
        } else {
          file(io.write.payload.slotId)(lane)(io.write.payload.rd) := io.write.payload.data(lane).asUInt
        }
      }
    }
  }

  for (lane <- 0 until config.warpSize) {
    io.readDataA(lane) := U(0, config.dataWidth bits)
    io.readDataB(lane) := U(0, config.dataWidth bits)
    io.readDataC(lane) := U(0, config.dataWidth bits)

    when(io.readAddrA =/= 0) {
      if (effectiveSlotCount == 1) {
        io.readDataA(lane) := file(0)(lane)(io.readAddrA)
      } else {
        io.readDataA(lane) := file(io.readSlotId)(lane)(io.readAddrA)
      }
    }

    when(io.readAddrB =/= 0) {
      if (effectiveSlotCount == 1) {
        io.readDataB(lane) := file(0)(lane)(io.readAddrB)
      } else {
        io.readDataB(lane) := file(io.readSlotId)(lane)(io.readAddrB)
      }
    }

    when(io.readAddrC =/= 0) {
      if (effectiveSlotCount == 1) {
        io.readDataC(lane) := file(0)(lane)(io.readAddrC)
      } else {
        io.readDataC(lane) := file(io.readSlotId)(lane)(io.readAddrC)
      }
    }
  }
}
