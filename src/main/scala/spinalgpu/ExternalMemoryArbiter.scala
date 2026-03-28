package spinalgpu

import spinal.core._
import spinal.lib._

class ExternalMemoryArbiter(config: SmConfig) extends Component {
  private object Source extends SpinalEnum {
    val FETCH, LSU = newElement()
  }

  val io = new Bundle {
    val fetchReq = slave(Stream(FetchMemReq(config)))
    val fetchRsp = master(Stream(FetchMemRsp(config)))
    val lsuReq = slave(Stream(GlobalMemBurstReq(config)))
    val lsuRsp = master(Stream(GlobalMemBurstRsp(config)))
    val memoryReq = master(Stream(ExternalMemBurstReq(config)))
    val memoryRsp = slave(Stream(ExternalMemBurstRsp(config)))
    val idle = out Bool()
  }

  private val pendingSource = Reg(Source()) init (Source.FETCH)
  private val waitingForRsp = RegInit(False)
  private val preferFetch = RegInit(True)

  private val chooseFetch =
    !waitingForRsp && ((io.fetchReq.valid && !io.lsuReq.valid) || (io.fetchReq.valid && io.lsuReq.valid && preferFetch))
  private val chooseLsu = !waitingForRsp && !chooseFetch && io.lsuReq.valid

  io.memoryReq.valid := chooseFetch || chooseLsu
  io.memoryReq.payload.warpId := io.fetchReq.payload.warpId
  io.memoryReq.payload.write := False
  io.memoryReq.payload.address := io.fetchReq.payload.address
  io.memoryReq.payload.beatCount := 1
  io.memoryReq.payload.byteMask := B((1 << config.byteMaskWidth) - 1, config.byteMaskWidth bits)
  for (beat <- 0 until config.cudaLaneCount) {
    io.memoryReq.payload.writeData(beat) := B(0, config.dataWidth bits)
  }
  when(chooseLsu) {
    io.memoryReq.payload.warpId := io.lsuReq.payload.warpId
    io.memoryReq.payload.write := io.lsuReq.payload.write
    io.memoryReq.payload.address := io.lsuReq.payload.address
    io.memoryReq.payload.beatCount := io.lsuReq.payload.beatCount
    io.memoryReq.payload.writeData := io.lsuReq.payload.writeData
    io.memoryReq.payload.byteMask := io.lsuReq.payload.byteMask
  }

  io.fetchReq.ready := chooseFetch && io.memoryReq.ready
  io.lsuReq.ready := chooseLsu && io.memoryReq.ready

  io.fetchRsp.valid := waitingForRsp && pendingSource === Source.FETCH && io.memoryRsp.valid
  io.fetchRsp.payload.warpId := io.memoryRsp.payload.warpId
  io.fetchRsp.payload.error := io.memoryRsp.payload.error
  io.fetchRsp.payload.readData := io.memoryRsp.payload.readData(0)
  io.lsuRsp.valid := waitingForRsp && pendingSource === Source.LSU && io.memoryRsp.valid
  io.lsuRsp.payload.warpId := io.memoryRsp.payload.warpId
  io.lsuRsp.payload.completed := io.memoryRsp.payload.completed
  io.lsuRsp.payload.error := io.memoryRsp.payload.error
  io.lsuRsp.payload.beatCount := io.memoryRsp.payload.beatCount
  io.lsuRsp.payload.readData := io.memoryRsp.payload.readData

  io.memoryRsp.ready := (pendingSource === Source.FETCH && io.fetchRsp.ready) || (pendingSource === Source.LSU && io.lsuRsp.ready)

  when(io.memoryReq.fire) {
    waitingForRsp := True
    pendingSource := Mux(chooseFetch, Source.FETCH, Source.LSU)
    preferFetch := !chooseFetch
  }

  when(io.memoryRsp.fire) {
    waitingForRsp := False
  }

  io.idle := !waitingForRsp && !io.memoryReq.valid
}
