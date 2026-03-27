package spinalgpu

import spinal.core._
import spinal.lib._

class L0InstructionCache(config: SmConfig) extends Component {
  val io = new Bundle {
    val request = slave(Stream(FetchMemReq(config)))
    val response = master(Stream(FetchMemRsp(config)))
    val l1Req = master(Stream(FetchMemReq(config)))
    val l1Rsp = slave(Stream(FetchMemRsp(config)))
  }

  io.l1Req <> io.request
  io.response <> io.l1Rsp
}
