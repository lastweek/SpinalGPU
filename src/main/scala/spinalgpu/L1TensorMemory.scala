package spinalgpu

import spinal.core._
import spinal.lib._

class L1TensorMemory(config: SmConfig) extends Component {
  private val sourceIdWidth = log2Up(config.subSmCount max 2)

  val io = new Bundle {
    val request = Vec(slave(Stream(TensorMemReq(config))), config.subSmCount)
    val response = Vec(master(Stream(TensorMemRsp(config))), config.subSmCount)
    val tensorMemReq = master(Stream(TensorMemReq(config)))
    val tensorMemRsp = slave(Stream(TensorMemRsp(config)))
    val idle = out Bool()
  }

  private val pendingSource = Reg(UInt(sourceIdWidth bits)) init (0)
  private val waitingForRsp = RegInit(False)
  private val roundRobinBase = Reg(UInt(sourceIdWidth bits)) init (0)

  private val selectedValid = Bool()
  private val selectedSource = UInt(sourceIdWidth bits)
  private val candidateHits = Bits(config.subSmCount bits)
  private val candidateSources = Vec(UInt(sourceIdWidth bits), config.subSmCount)

  if (config.subSmCount == 1) {
    candidateSources(0) := U(0, sourceIdWidth bits)
    candidateHits(0) := !waitingForRsp && io.request(0).valid
    selectedValid := candidateHits(0)
    selectedSource := U(0, sourceIdWidth bits)

    io.tensorMemReq.valid := selectedValid
    io.tensorMemReq.payload := io.request(0).payload
    io.request(0).ready := selectedValid && io.tensorMemReq.ready
    io.response(0).valid := waitingForRsp && io.tensorMemRsp.valid
    io.response(0).payload := io.tensorMemRsp.payload
    io.tensorMemRsp.ready := io.response(0).ready
  } else {
    selectedSource := roundRobinBase
    for (offset <- 0 until config.subSmCount) {
      val candidateWide = UInt((sourceIdWidth + 1) bits)
      candidateWide := roundRobinBase.resize(sourceIdWidth + 1) + U(offset, sourceIdWidth + 1 bits)

      val candidate = UInt(sourceIdWidth bits)
      candidate := candidateWide.resized
      when(candidateWide >= U(config.subSmCount, sourceIdWidth + 1 bits)) {
        candidate := (candidateWide - U(config.subSmCount, sourceIdWidth + 1 bits)).resized
      }
      candidateSources(offset) := candidate
      candidateHits(offset) := !waitingForRsp && io.request(candidate).valid
    }

    selectedValid := candidateHits.orR
    for (offset <- 0 until config.subSmCount) {
      val earlierCandidateHit = if (offset == 0) False else candidateHits(offset - 1 downto 0).orR
      when(candidateHits(offset) && !earlierCandidateHit) {
        selectedSource := candidateSources(offset)
      }
    }

    io.tensorMemReq.valid := selectedValid
    io.tensorMemReq.payload.warpId := U(0, config.warpIdWidth bits)
    io.tensorMemReq.payload.write := False
    io.tensorMemReq.payload.address := U(0, config.tensorAddressWidth bits)
    io.tensorMemReq.payload.writeData := B(0, config.dataWidth bits)

    for (subSm <- 0 until config.subSmCount) {
      when(selectedSource === U(subSm, sourceIdWidth bits)) {
        io.tensorMemReq.payload := io.request(subSm).payload
      }
      io.request(subSm).ready := selectedValid && selectedSource === U(subSm, sourceIdWidth bits) && io.tensorMemReq.ready
      io.response(subSm).valid := waitingForRsp && pendingSource === U(subSm, sourceIdWidth bits) && io.tensorMemRsp.valid
      io.response(subSm).payload := io.tensorMemRsp.payload
    }

    io.tensorMemRsp.ready := False
    for (subSm <- 0 until config.subSmCount) {
      when(pendingSource === U(subSm, sourceIdWidth bits)) {
        io.tensorMemRsp.ready := io.response(subSm).ready
      }
    }
  }

  when(io.tensorMemReq.fire) {
    waitingForRsp := True
    pendingSource := selectedSource

    val nextBaseWide = UInt((sourceIdWidth + 1) bits)
    nextBaseWide := selectedSource.resize(sourceIdWidth + 1) + U(1, sourceIdWidth + 1 bits)
    roundRobinBase := nextBaseWide.resized
    when(nextBaseWide >= U(config.subSmCount, sourceIdWidth + 1 bits)) {
      roundRobinBase := (nextBaseWide - U(config.subSmCount, sourceIdWidth + 1 bits)).resized
    }
  }

  when(io.tensorMemRsp.fire) {
    waitingForRsp := False
  }

  io.idle := !waitingForRsp && !io.tensorMemReq.valid
}
