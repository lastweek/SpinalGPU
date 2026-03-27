package spinalgpu

import spinal.core._
import spinal.lib._

class L1DataSharedMemory(config: SmConfig) extends Component {
  private val sourceIdWidth = log2Up(config.subSmCount max 2)

  val io = new Bundle {
    val sharedReq = Vec(slave(Stream(SharedMemReq(config))), config.subSmCount)
    val sharedRsp = Vec(master(Stream(SharedMemRsp(config))), config.subSmCount)
    val externalReq = Vec(slave(Stream(GlobalMemBurstReq(config))), config.subSmCount)
    val externalRsp = Vec(master(Stream(GlobalMemBurstRsp(config))), config.subSmCount)
    val sharedMemReq = master(Stream(SharedMemReq(config)))
    val sharedMemRsp = slave(Stream(SharedMemRsp(config)))
    val memoryReq = master(Stream(GlobalMemBurstReq(config)))
    val memoryRsp = slave(Stream(GlobalMemBurstRsp(config)))
  }

  private val sharedPendingSource = Reg(UInt(sourceIdWidth bits)) init (0)
  private val sharedWaitingForRsp = RegInit(False)
  private val sharedRoundRobinBase = Reg(UInt(sourceIdWidth bits)) init (0)

  private val sharedSelectedValid = Bool()
  private val sharedSelectedSource = UInt(sourceIdWidth bits)
  private val sharedCandidateHits = Bits(config.subSmCount bits)
  private val sharedCandidateSources = Vec(UInt(sourceIdWidth bits), config.subSmCount)

  if (config.subSmCount == 1) {
    sharedCandidateSources(0) := U(0, sourceIdWidth bits)
    sharedCandidateHits(0) := !sharedWaitingForRsp && io.sharedReq(0).valid
    sharedSelectedValid := sharedCandidateHits(0)
    sharedSelectedSource := U(0, sourceIdWidth bits)

    io.sharedMemReq.valid := sharedSelectedValid
    io.sharedMemReq.payload := io.sharedReq(0).payload
    io.sharedReq(0).ready := sharedSelectedValid && io.sharedMemReq.ready
    io.sharedRsp(0).valid := sharedWaitingForRsp && io.sharedMemRsp.valid
    io.sharedRsp(0).payload := io.sharedMemRsp.payload
    io.sharedMemRsp.ready := io.sharedRsp(0).ready
  } else {
    sharedSelectedSource := sharedRoundRobinBase
    for (offset <- 0 until config.subSmCount) {
      val candidateWide = UInt((sourceIdWidth + 1) bits)
      candidateWide := sharedRoundRobinBase.resize(sourceIdWidth + 1) + U(offset, sourceIdWidth + 1 bits)

      val candidate = UInt(sourceIdWidth bits)
      candidate := candidateWide.resized
      when(candidateWide >= U(config.subSmCount, sourceIdWidth + 1 bits)) {
        candidate := (candidateWide - U(config.subSmCount, sourceIdWidth + 1 bits)).resized
      }
      sharedCandidateSources(offset) := candidate
      sharedCandidateHits(offset) := !sharedWaitingForRsp && io.sharedReq(candidate).valid
    }

    sharedSelectedValid := sharedCandidateHits.orR
    for (offset <- 0 until config.subSmCount) {
      val earlierCandidateHit =
        if (offset == 0) False else sharedCandidateHits(offset - 1 downto 0).orR
      when(sharedCandidateHits(offset) && !earlierCandidateHit) {
        sharedSelectedSource := sharedCandidateSources(offset)
      }
    }

    io.sharedMemReq.valid := sharedSelectedValid
    io.sharedMemReq.payload.warpId := U(0, config.warpIdWidth bits)
    io.sharedMemReq.payload.write := False
    io.sharedMemReq.payload.address := U(0, config.sharedAddressWidth bits)
    io.sharedMemReq.payload.writeData := B(0, config.dataWidth bits)
    io.sharedMemReq.payload.byteMask := B(0, config.byteMaskWidth bits)

    for (subSm <- 0 until config.subSmCount) {
      when(sharedSelectedSource === U(subSm, sourceIdWidth bits)) {
        io.sharedMemReq.payload.warpId := io.sharedReq(subSm).payload.warpId
        io.sharedMemReq.payload.write := io.sharedReq(subSm).payload.write
        io.sharedMemReq.payload.address := io.sharedReq(subSm).payload.address
        io.sharedMemReq.payload.writeData := io.sharedReq(subSm).payload.writeData
        io.sharedMemReq.payload.byteMask := io.sharedReq(subSm).payload.byteMask
      }
      io.sharedReq(subSm).ready := sharedSelectedValid && sharedSelectedSource === U(subSm, sourceIdWidth bits) && io.sharedMemReq.ready
      io.sharedRsp(subSm).valid := sharedWaitingForRsp && sharedPendingSource === U(subSm, sourceIdWidth bits) && io.sharedMemRsp.valid
      io.sharedRsp(subSm).payload := io.sharedMemRsp.payload
    }

    io.sharedMemRsp.ready := False
    for (subSm <- 0 until config.subSmCount) {
      when(sharedPendingSource === U(subSm, sourceIdWidth bits)) {
        io.sharedMemRsp.ready := io.sharedRsp(subSm).ready
      }
    }
  }

  when(io.sharedMemReq.fire) {
    sharedWaitingForRsp := True
    sharedPendingSource := sharedSelectedSource

    val nextBaseWide = UInt((sourceIdWidth + 1) bits)
    nextBaseWide := sharedSelectedSource.resize(sourceIdWidth + 1) + U(1, sourceIdWidth + 1 bits)
    sharedRoundRobinBase := nextBaseWide.resized
    when(nextBaseWide >= U(config.subSmCount, sourceIdWidth + 1 bits)) {
      sharedRoundRobinBase := (nextBaseWide - U(config.subSmCount, sourceIdWidth + 1 bits)).resized
    }
  }

  when(io.sharedMemRsp.fire) {
    sharedWaitingForRsp := False
  }

  private val externalPendingSource = Reg(UInt(sourceIdWidth bits)) init (0)
  private val externalWaitingForRsp = RegInit(False)
  private val externalRoundRobinBase = Reg(UInt(sourceIdWidth bits)) init (0)

  private val externalSelectedValid = Bool()
  private val externalSelectedSource = UInt(sourceIdWidth bits)
  private val externalCandidateHits = Bits(config.subSmCount bits)
  private val externalCandidateSources = Vec(UInt(sourceIdWidth bits), config.subSmCount)

  if (config.subSmCount == 1) {
    externalCandidateSources(0) := U(0, sourceIdWidth bits)
    externalCandidateHits(0) := !externalWaitingForRsp && io.externalReq(0).valid
    externalSelectedValid := externalCandidateHits(0)
    externalSelectedSource := U(0, sourceIdWidth bits)

    io.memoryReq.valid := externalSelectedValid
    io.memoryReq.payload := io.externalReq(0).payload
    io.externalReq(0).ready := externalSelectedValid && io.memoryReq.ready
    io.externalRsp(0).valid := externalWaitingForRsp && io.memoryRsp.valid
    io.externalRsp(0).payload := io.memoryRsp.payload
    io.memoryRsp.ready := io.externalRsp(0).ready
  } else {
    externalSelectedSource := externalRoundRobinBase
    for (offset <- 0 until config.subSmCount) {
      val candidateWide = UInt((sourceIdWidth + 1) bits)
      candidateWide := externalRoundRobinBase.resize(sourceIdWidth + 1) + U(offset, sourceIdWidth + 1 bits)

      val candidate = UInt(sourceIdWidth bits)
      candidate := candidateWide.resized
      when(candidateWide >= U(config.subSmCount, sourceIdWidth + 1 bits)) {
        candidate := (candidateWide - U(config.subSmCount, sourceIdWidth + 1 bits)).resized
      }
      externalCandidateSources(offset) := candidate
      externalCandidateHits(offset) := !externalWaitingForRsp && io.externalReq(candidate).valid
    }

    externalSelectedValid := externalCandidateHits.orR
    for (offset <- 0 until config.subSmCount) {
      val earlierCandidateHit =
        if (offset == 0) False else externalCandidateHits(offset - 1 downto 0).orR
      when(externalCandidateHits(offset) && !earlierCandidateHit) {
        externalSelectedSource := externalCandidateSources(offset)
      }
    }

    io.memoryReq.valid := externalSelectedValid
    io.memoryReq.payload.warpId := U(0, config.warpIdWidth bits)
    io.memoryReq.payload.write := False
    io.memoryReq.payload.address := U(0, config.addressWidth bits)
    io.memoryReq.payload.beatCount := U(0, config.globalBurstBeatCountWidth bits)
    io.memoryReq.payload.byteMask := B(0, config.byteMaskWidth bits)
    for (beat <- 0 until config.cudaLaneCount) {
      io.memoryReq.payload.writeData(beat) := B(0, config.dataWidth bits)
    }

    for (subSm <- 0 until config.subSmCount) {
      when(externalSelectedSource === U(subSm, sourceIdWidth bits)) {
        io.memoryReq.payload.warpId := io.externalReq(subSm).payload.warpId
        io.memoryReq.payload.write := io.externalReq(subSm).payload.write
        io.memoryReq.payload.address := io.externalReq(subSm).payload.address
        io.memoryReq.payload.beatCount := io.externalReq(subSm).payload.beatCount
        io.memoryReq.payload.byteMask := io.externalReq(subSm).payload.byteMask
        for (beat <- 0 until config.cudaLaneCount) {
          io.memoryReq.payload.writeData(beat) := io.externalReq(subSm).payload.writeData(beat)
        }
      }
      io.externalReq(subSm).ready := externalSelectedValid && externalSelectedSource === U(subSm, sourceIdWidth bits) && io.memoryReq.ready
      io.externalRsp(subSm).valid := externalWaitingForRsp && externalPendingSource === U(subSm, sourceIdWidth bits) && io.memoryRsp.valid
      io.externalRsp(subSm).payload := io.memoryRsp.payload
    }

    io.memoryRsp.ready := False
    for (subSm <- 0 until config.subSmCount) {
      when(externalPendingSource === U(subSm, sourceIdWidth bits)) {
        io.memoryRsp.ready := io.externalRsp(subSm).ready
      }
    }
  }

  when(io.memoryReq.fire) {
    externalWaitingForRsp := True
    externalPendingSource := externalSelectedSource

    val nextBaseWide = UInt((sourceIdWidth + 1) bits)
    nextBaseWide := externalSelectedSource.resize(sourceIdWidth + 1) + U(1, sourceIdWidth + 1 bits)
    externalRoundRobinBase := nextBaseWide.resized
    when(nextBaseWide >= U(config.subSmCount, sourceIdWidth + 1 bits)) {
      externalRoundRobinBase := (nextBaseWide - U(config.subSmCount, sourceIdWidth + 1 bits)).resized
    }
  }

  when(io.memoryRsp.fire) {
    externalWaitingForRsp := False
  }
}
