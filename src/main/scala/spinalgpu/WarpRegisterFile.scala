package spinalgpu

import spinal.core._
import spinal.lib._

class WarpRegisterFile(config: SmConfig) extends Component {
  val io = new Bundle {
    val readWarpId = in(UInt(config.warpIdWidth bits))
    val readAddrA = in(UInt(config.registerAddressWidth bits))
    val readAddrB = in(UInt(config.registerAddressWidth bits))
    val readDataA = out(Vec(UInt(config.dataWidth bits), config.warpSize))
    val readDataB = out(Vec(UInt(config.dataWidth bits), config.warpSize))
    val write = slave(Flow(WritebackPacket(config)))
    val clearWarp = slave(Flow(UInt(config.warpIdWidth bits)))
  }

  private val file =
    Vec.fill(config.residentWarpCount)(Vec.fill(config.warpSize)(Vec.fill(config.registerCount)(Reg(UInt(config.dataWidth bits)) init (0))))

  when(io.clearWarp.valid) {
    for (lane <- 0 until config.warpSize) {
      for (reg <- 0 until config.registerCount) {
        file(io.clearWarp.payload)(lane)(reg) := U(0, config.dataWidth bits)
      }
    }
  }

  when(io.write.valid && io.write.payload.rd =/= 0) {
    for (lane <- 0 until config.warpSize) {
      when(io.write.payload.writeMask(lane)) {
        file(io.write.payload.warpId)(lane)(io.write.payload.rd) := io.write.payload.data(lane).asUInt
      }
    }
  }

  for (lane <- 0 until config.warpSize) {
    io.readDataA(lane) := U(0, config.dataWidth bits)
    io.readDataB(lane) := U(0, config.dataWidth bits)

    when(io.readAddrA =/= 0) {
      io.readDataA(lane) := file(io.readWarpId)(lane)(io.readAddrA)
    }

    when(io.readAddrB =/= 0) {
      io.readDataB(lane) := file(io.readWarpId)(lane)(io.readAddrB)
    }
  }
}
