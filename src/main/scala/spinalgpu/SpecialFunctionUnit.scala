package spinalgpu

import spinal.core._
import spinal.lib._

class SpecialFunctionUnit(config: SmConfig) extends Component {
  val io = new Bundle {
    val issue = slave(Stream(SfuReq(config)))
    val response = master(Stream(SfuRsp(config)))
  }

  private val rspValid = RegInit(False)
  private val rspPayload = Reg(SfuRsp(config))

  io.issue.ready := !rspValid || io.response.ready
  io.response.valid := rspValid
  io.response.payload := rspPayload

  when(io.issue.fire) {
    rspValid := True
    rspPayload.warpId := io.issue.payload.warpId
    rspPayload.completed := True
    for (lane <- 0 until config.warpSize) {
      rspPayload.result(lane) := B(0, config.dataWidth bits)
      when(io.issue.payload.activeMask(lane)) {
        rspPayload.result(lane) := (~io.issue.payload.operand(lane).asBits).asBits
      }
    }
  }

  when(io.response.fire) {
    rspValid := False
  }
}
