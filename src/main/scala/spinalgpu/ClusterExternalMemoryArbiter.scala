package spinalgpu

import spinal.core._
import spinal.lib._

class ClusterExternalMemoryArbiter(config: GpuConfig) extends Component {
  private val smConfig = config.sm

  val io = new Bundle {
    val smReq = Vec(slave(Stream(ExternalMemBurstReq(smConfig))), config.smCount)
    val smRsp = Vec(master(Stream(ExternalMemBurstRsp(smConfig))), config.smCount)
    val memoryReq = master(Stream(ExternalMemBurstReq(smConfig)))
    val memoryRsp = slave(Stream(ExternalMemBurstRsp(smConfig)))
    val idle = out Bool()
  }

  private val pendingSm = Reg(UInt(config.smIdWidth bits)) init (0)
  private val waitingForRsp = RegInit(False)
  private val smBase = Reg(UInt(config.smIdWidth bits)) init (0)

  private val requestCandidates = Bits(config.smCount bits)
  for (sm <- 0 until config.smCount) {
    requestCandidates(sm) := !waitingForRsp && io.smReq(sm).valid
  }
  private val smSelection =
    RoundRobinSelect.firstFromBase(smBase, requestCandidates, config.smCount, config.smIdWidth)
  private val selectedSmValid = smSelection._1
  private val selectedSm = smSelection._2

  if (config.smCount == 1) {
    io.memoryReq.valid := selectedSmValid
    io.memoryReq.payload := io.smReq(0).payload
    io.smReq(0).ready := selectedSmValid && io.memoryReq.ready
    io.smRsp(0).valid := waitingForRsp && io.memoryRsp.valid
    io.smRsp(0).payload := io.memoryRsp.payload
    io.memoryRsp.ready := io.smRsp(0).ready
  } else {
    io.memoryReq.valid := selectedSmValid
    io.memoryReq.payload.warpId := U(0, smConfig.warpIdWidth bits)
    io.memoryReq.payload.write := False
    io.memoryReq.payload.address := U(0, config.addressWidth bits)
    io.memoryReq.payload.accessWidth := MemoryAccessWidthKind.WORD
    io.memoryReq.payload.beatCount := U(0, smConfig.globalBurstBeatCountWidth bits)
    io.memoryReq.payload.byteMask := B(0, config.byteMaskWidth bits)
    for (beat <- 0 until smConfig.cudaLaneCount) {
      io.memoryReq.payload.writeData(beat) := B(0, config.dataWidth bits)
    }

    for (sm <- 0 until config.smCount) {
      when(selectedSm === U(sm, config.smIdWidth bits)) {
        io.memoryReq.payload.warpId := io.smReq(sm).payload.warpId
        io.memoryReq.payload.write := io.smReq(sm).payload.write
        io.memoryReq.payload.address := io.smReq(sm).payload.address
        io.memoryReq.payload.accessWidth := io.smReq(sm).payload.accessWidth
        io.memoryReq.payload.beatCount := io.smReq(sm).payload.beatCount
        io.memoryReq.payload.byteMask := io.smReq(sm).payload.byteMask
        for (beat <- 0 until smConfig.cudaLaneCount) {
          io.memoryReq.payload.writeData(beat) := io.smReq(sm).payload.writeData(beat)
        }
      }
      io.smReq(sm).ready := selectedSmValid && selectedSm === U(sm, config.smIdWidth bits) && io.memoryReq.ready
      io.smRsp(sm).valid := waitingForRsp && pendingSm === U(sm, config.smIdWidth bits) && io.memoryRsp.valid
      io.smRsp(sm).payload := io.memoryRsp.payload
    }

    io.memoryRsp.ready := False
    for (sm <- 0 until config.smCount) {
      when(pendingSm === U(sm, config.smIdWidth bits)) {
        io.memoryRsp.ready := io.smRsp(sm).ready
      }
    }
  }

  when(io.memoryReq.fire) {
    waitingForRsp := True
    pendingSm := selectedSm
    smBase := RoundRobinSelect.nextAfter(selectedSm, config.smCount, config.smIdWidth)
  }

  when(io.memoryRsp.fire) {
    waitingForRsp := False
  }

  io.idle := !waitingForRsp && !io.memoryReq.valid
}
