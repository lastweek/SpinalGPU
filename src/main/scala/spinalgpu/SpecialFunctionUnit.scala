package spinalgpu

import spinal.core._
import spinal.lib._

class SpecialFunctionUnit(config: SmConfig) extends Component {
  private object State extends SpinalEnum {
    val IDLE, WAIT_SLICE = newElement()
  }

  val io = new Bundle {
    val issue = slave(Stream(SfuReq(config)))
    val response = master(Stream(SfuRsp(config)))
  }

  private val state = RegInit(State.IDLE)
  private val pending = Reg(SfuReq(config))
  private val rspValid = RegInit(False)
  private val rspPayload = Reg(SfuRsp(config))
  private val latencyCounter = Reg(UInt(log2Up(config.sfuLatency + 1) bits)) init (0)
  private val subwarpBase = Reg(UInt(log2Up(config.warpSize + 1) bits)) init (0)
  private val resultBuffer = Vec.fill(config.warpSize)(Reg(Bits(config.dataWidth bits)) init (0))

  io.issue.ready := state === State.IDLE && !rspValid
  io.response.valid := rspValid
  io.response.payload := rspPayload

  when(io.issue.fire) {
    for (lane <- 0 until config.warpSize) {
      resultBuffer(lane) := B(0, config.dataWidth bits)
    }
    pending := io.issue.payload
    rspPayload.warpId := io.issue.payload.warpId
    rspPayload.completed := True
    subwarpBase := 0
    latencyCounter := U(config.sfuLatency - 1, latencyCounter.getWidth bits)
    state := State.WAIT_SLICE
  }

  when(state === State.WAIT_SLICE) {
    when(latencyCounter === 0) {
      val finishingWarp = subwarpBase + config.cudaLaneCount >= config.warpSize

      when(finishingWarp) {
        rspPayload.warpId := pending.warpId
        rspPayload.completed := True
        for (lane <- 0 until config.warpSize) {
          rspPayload.result(lane) := resultBuffer(lane)
        }
      }

      for (laneOffset <- 0 until config.cudaLaneCount) {
        val laneIndex = (subwarpBase + U(laneOffset, subwarpBase.getWidth bits)).resized
        when(laneIndex < config.warpSize && pending.activeMask(laneIndex)) {
          val laneResult = SfuMath.applyOpcode(pending.opcode, pending.operand(laneIndex))
          resultBuffer(laneIndex) := laneResult
          when(finishingWarp) {
            rspPayload.result(laneIndex) := laneResult
          }
        }
      }

      when(finishingWarp) {
        rspValid := True
        state := State.IDLE
      } otherwise {
        subwarpBase := subwarpBase + config.cudaLaneCount
        latencyCounter := U(config.sfuLatency - 1, latencyCounter.getWidth bits)
      }
    } otherwise {
      latencyCounter := latencyCounter - 1
    }
  }

  when(io.response.fire) {
    rspValid := False
  }
}
