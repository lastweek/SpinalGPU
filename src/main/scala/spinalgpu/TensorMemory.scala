package spinalgpu

import spinal.core._
import spinal.lib._

class TensorMemory(config: SmConfig) extends Component {
  val io = new Bundle {
    val request = slave(Stream(TensorMemReq(config)))
    val response = master(Stream(TensorMemRsp(config)))
    val clear = slave(TensorMemoryClearIo(config))
  }

  private val memory = Mem(Bits(config.dataWidth bits), config.tensorWordCount)
  private val rspValid = RegInit(False)
  private val rspPayload = Reg(TensorMemRsp(config))
  private val clearBusy = RegInit(False)
  private val clearAddress = Reg(UInt(config.tensorAddressWidth bits)) init (0)
  private val readPendingValid = RegInit(False)
  private val readPendingWarpId = Reg(UInt(config.warpIdWidth bits)) init (0)
  rspPayload.warpId.init(0)
  rspPayload.completed.init(False)
  rspPayload.error.init(False)
  rspPayload.readData.init(0)

  private val readData = memory.readSync(
    address = io.request.payload.address,
    enable = io.request.fire && !io.request.payload.write && !clearBusy
  )

  io.clear.busy := clearBusy
  io.request.ready := !rspValid && !readPendingValid && !clearBusy
  io.response.valid := rspValid
  io.response.payload := rspPayload

  when(io.clear.start && !clearBusy) {
    clearBusy := True
    clearAddress := U(0, config.tensorAddressWidth bits)
  }

  when(clearBusy) {
    memory.write(clearAddress, B(0, config.dataWidth bits))
    when(clearAddress === config.tensorWordCount - 1) {
      clearBusy := False
    } otherwise {
      clearAddress := clearAddress + 1
    }
  }

  when(io.request.fire) {
    when(io.request.payload.write) {
      rspValid := True
      rspPayload.warpId := io.request.payload.warpId
      rspPayload.completed := True
      rspPayload.error := False
      rspPayload.readData := B(0, config.dataWidth bits)
      memory.write(io.request.payload.address, io.request.payload.writeData)
    } otherwise {
      readPendingValid := True
      readPendingWarpId := io.request.payload.warpId
    }
  }

  when(readPendingValid) {
    rspValid := True
    rspPayload.warpId := readPendingWarpId
    rspPayload.completed := True
    rspPayload.error := False
    rspPayload.readData := readData
    readPendingValid := False
  }

  when(io.response.fire) {
    rspValid := False
  }
}
