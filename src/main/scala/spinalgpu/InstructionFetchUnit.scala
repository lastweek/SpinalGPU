package spinalgpu

import spinal.core._
import spinal.lib._

class InstructionFetchUnit(config: SmConfig) extends Component {
  val io = new Bundle {
    val request = slave(Stream(FetchReq(config)))
    val response = master(Stream(FetchRsp(config)))
    val memoryReq = master(Stream(FetchMemReq(config)))
    val memoryRsp = slave(Stream(FetchMemRsp(config)))
  }

  private val pendingWarpId = Reg(UInt(config.warpIdWidth bits)) init (0)
  private val pendingPc = Reg(UInt(config.addressWidth bits)) init (0)
  private val waitingOnMemory = RegInit(False)
  private val rspValid = RegInit(False)
  private val rspPayload = Reg(FetchRsp(config))

  private val misaligned = io.request.payload.pc(1 downto 0) =/= 0
  private val canAcceptRequest = !waitingOnMemory && !rspValid

  io.request.ready := canAcceptRequest && (misaligned || io.memoryReq.ready)

  io.memoryReq.valid := io.request.valid && canAcceptRequest && !misaligned
  io.memoryReq.payload.warpId := io.request.payload.warpId
  io.memoryReq.payload.address := io.request.payload.pc

  io.memoryRsp.ready := waitingOnMemory && !rspValid

  io.response.valid := rspValid
  io.response.payload := rspPayload

  when(io.request.fire) {
    rspPayload.warpId := io.request.payload.warpId
    rspPayload.pc := io.request.payload.pc
    rspPayload.instruction := B(0, config.instructionWidth bits)
    rspPayload.fault := False
    rspPayload.faultCode := FaultCode.None

    when(misaligned) {
      rspValid := True
      rspPayload.fault := True
      rspPayload.faultCode := FaultCode.MisalignedFetch
    } otherwise {
      pendingWarpId := io.request.payload.warpId
      pendingPc := io.request.payload.pc
      waitingOnMemory := True
    }
  }

  when(io.memoryRsp.fire) {
    waitingOnMemory := False
    rspValid := True
    rspPayload.warpId := pendingWarpId
    rspPayload.pc := pendingPc
    rspPayload.instruction := io.memoryRsp.payload.readData
    rspPayload.fault := io.memoryRsp.payload.error
    rspPayload.faultCode := Mux(io.memoryRsp.payload.error, U(FaultCode.ExternalMemory, config.faultCodeWidth bits), U(FaultCode.None, config.faultCodeWidth bits))
  }

  when(io.response.fire) {
    rspValid := False
  }
}
