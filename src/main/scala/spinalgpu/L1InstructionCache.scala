package spinalgpu

import spinal.core._
import spinal.lib._

class L1InstructionCache(config: SmConfig) extends Component {
  private val sourceIdWidth = log2Up(config.subSmCount max 2)

  val io = new Bundle {
    val subSmReq = Vec(slave(Stream(FetchMemReq(config))), config.subSmCount)
    val subSmRsp = Vec(master(Stream(FetchMemRsp(config))), config.subSmCount)
    val memoryReq = master(Stream(FetchMemReq(config)))
    val memoryRsp = slave(Stream(FetchMemRsp(config)))
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
    candidateHits(0) := !waitingForRsp && io.subSmReq(0).valid
    selectedValid := candidateHits(0)
    selectedSource := U(0, sourceIdWidth bits)

    io.memoryReq.valid := selectedValid
    io.memoryReq.payload := io.subSmReq(0).payload

    io.subSmReq(0).ready := selectedValid && io.memoryReq.ready
    io.subSmRsp(0).valid := waitingForRsp && io.memoryRsp.valid
    io.subSmRsp(0).payload := io.memoryRsp.payload
    io.memoryRsp.ready := io.subSmRsp(0).ready
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
      candidateHits(offset) := !waitingForRsp && io.subSmReq(candidate).valid
    }

    selectedValid := candidateHits.orR
    for (offset <- 0 until config.subSmCount) {
      val earlierCandidateHit =
        if (offset == 0) False else candidateHits(offset - 1 downto 0).orR
      when(candidateHits(offset) && !earlierCandidateHit) {
        selectedSource := candidateSources(offset)
      }
    }

    io.memoryReq.valid := selectedValid
    io.memoryReq.payload := io.subSmReq(selectedSource).payload

    for (subSm <- 0 until config.subSmCount) {
      io.subSmReq(subSm).ready := selectedValid && selectedSource === U(subSm, sourceIdWidth bits) && io.memoryReq.ready
      io.subSmRsp(subSm).valid := waitingForRsp && pendingSource === U(subSm, sourceIdWidth bits) && io.memoryRsp.valid
      io.subSmRsp(subSm).payload := io.memoryRsp.payload
    }

    io.memoryRsp.ready := False
    for (subSm <- 0 until config.subSmCount) {
      when(pendingSource === U(subSm, sourceIdWidth bits)) {
        io.memoryRsp.ready := io.subSmRsp(subSm).ready
      }
    }
  }

  when(io.memoryReq.fire) {
    waitingForRsp := True
    pendingSource := selectedSource

    val nextBaseWide = UInt((sourceIdWidth + 1) bits)
    nextBaseWide := selectedSource.resize(sourceIdWidth + 1) + U(1, sourceIdWidth + 1 bits)
    roundRobinBase := nextBaseWide.resized
    when(nextBaseWide >= U(config.subSmCount, sourceIdWidth + 1 bits)) {
      roundRobinBase := (nextBaseWide - U(config.subSmCount, sourceIdWidth + 1 bits)).resized
    }
  }

  when(io.memoryRsp.fire) {
    waitingForRsp := False
  }
}
