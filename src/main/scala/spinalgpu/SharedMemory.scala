package spinalgpu

import spinal.core._
import spinal.lib._

class SharedMemory(config: SmConfig) extends Component {
  val io = new Bundle {
    val request = slave(Stream(SharedMemReq(config)))
    val response = master(Stream(SharedMemRsp(config)))
    val clear = slave(SharedMemoryClearIo(config))
  }

  private val memory = Mem(Bits(config.dataWidth bits), config.sharedWordCount)
  private val rspValid = RegInit(False)
  private val rspPayload = Reg(SharedMemRsp(config))
  private val clearBusy = RegInit(False)
  private val clearAddress = Reg(UInt(config.sharedAddressWidth bits)) init (0)
  private val readPendingValid = RegInit(False)
  private val readPendingWarpId = Reg(UInt(config.warpIdWidth bits)) init (0)
  private val readPendingBankIndex = Reg(UInt(config.sharedBankIndexWidth bits)) init (0)
  rspPayload.warpId.init(0)
  rspPayload.completed.init(False)
  rspPayload.error.init(False)
  rspPayload.bankIndex.init(0)
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
    clearAddress := U(0, config.sharedAddressWidth bits)
  }

  when(clearBusy) {
    memory.write(clearAddress, B(0, config.dataWidth bits))
    when(clearAddress === config.sharedWordCount - 1) {
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
      rspPayload.bankIndex := io.request.payload.address(config.sharedBankIndexWidth - 1 downto 0)
      rspPayload.readData := B(0, config.dataWidth bits)
      memory.write(
        address = io.request.payload.address,
        data = io.request.payload.writeData
      )
    } otherwise {
      readPendingValid := True
      readPendingWarpId := io.request.payload.warpId
      readPendingBankIndex := io.request.payload.address(config.sharedBankIndexWidth - 1 downto 0)
    }
  }

  when(readPendingValid) {
    rspValid := True
    rspPayload.warpId := readPendingWarpId
    rspPayload.completed := True
    rspPayload.error := False
    rspPayload.bankIndex := readPendingBankIndex
    rspPayload.readData := readData
    readPendingValid := False
  }

  when(io.response.fire) {
    rspValid := False
  }
}
