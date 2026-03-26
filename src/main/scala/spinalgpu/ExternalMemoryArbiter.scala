package spinalgpu

import spinal.core._
import spinal.lib._

class ExternalMemoryArbiter(config: SmConfig) extends Component {
  private object Source extends SpinalEnum {
    val FETCH, LSU = newElement()
  }

  val io = new Bundle {
    val fetchReq = slave(Stream(ExternalMemReq(config)))
    val fetchRsp = master(Stream(ExternalMemRsp(config)))
    val lsuReq = slave(Stream(ExternalMemReq(config)))
    val lsuRsp = master(Stream(ExternalMemRsp(config)))
    val memoryReq = master(Stream(ExternalMemReq(config)))
    val memoryRsp = slave(Stream(ExternalMemRsp(config)))
  }

  private val pendingSource = Reg(Source()) init (Source.FETCH)
  private val waitingForRsp = RegInit(False)
  private val preferFetch = RegInit(True)

  private val chooseFetch =
    !waitingForRsp && ((io.fetchReq.valid && !io.lsuReq.valid) || (io.fetchReq.valid && io.lsuReq.valid && preferFetch))
  private val chooseLsu = !waitingForRsp && !chooseFetch && io.lsuReq.valid

  io.memoryReq.valid := chooseFetch || chooseLsu
  io.memoryReq.payload := io.fetchReq.payload
  when(chooseLsu) {
    io.memoryReq.payload := io.lsuReq.payload
  }

  io.fetchReq.ready := chooseFetch && io.memoryReq.ready
  io.lsuReq.ready := chooseLsu && io.memoryReq.ready

  io.fetchRsp.valid := waitingForRsp && pendingSource === Source.FETCH && io.memoryRsp.valid
  io.fetchRsp.payload := io.memoryRsp.payload
  io.lsuRsp.valid := waitingForRsp && pendingSource === Source.LSU && io.memoryRsp.valid
  io.lsuRsp.payload := io.memoryRsp.payload

  io.memoryRsp.ready := (pendingSource === Source.FETCH && io.fetchRsp.ready) || (pendingSource === Source.LSU && io.lsuRsp.ready)

  when(io.memoryReq.fire) {
    waitingForRsp := True
    pendingSource := Mux(chooseFetch, Source.FETCH, Source.LSU)
    preferFetch := !chooseFetch
  }

  when(io.memoryRsp.fire) {
    waitingForRsp := False
  }
}
