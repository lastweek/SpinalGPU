package spinalgpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

class ExternalMemoryAxiAdapter(config: SmConfig) extends Component {
  val io = new Bundle {
    val request = slave(Stream(ExternalMemBurstReq(config)))
    val response = master(Stream(ExternalMemBurstRsp(config)))
    val axi = master(Axi4(config.axiConfig))
  }

  private object AdapterState extends SpinalEnum {
    val IDLE, WRITE_ADDR, WRITE_RSP, READ_ADDR, READ_RSP = newElement()
  }

  private val state = RegInit(AdapterState.IDLE)
  private val pending = Reg(ExternalMemBurstReq(config))
  private val rspValid = RegInit(False)
  private val rspPayload = Reg(ExternalMemBurstRsp(config))
  private val writeAddressSent = RegInit(False)
  private val beatIndexWidth = log2Up(config.cudaLaneCount)
  private val writeBeatIndex = Reg(UInt(config.globalBurstBeatCountWidth bits)) init (0)
  private val readBeatIndex = Reg(UInt(config.globalBurstBeatCountWidth bits)) init (0)
  private val readError = RegInit(False)

  pending.warpId.init(0)
  pending.write.init(False)
  pending.address.init(0)
  pending.beatCount.init(0)
  pending.byteMask.init(0)
  for (beat <- 0 until config.cudaLaneCount) {
    pending.writeData(beat).init(0)
  }

  rspPayload.warpId.init(0)
  rspPayload.completed.init(False)
  rspPayload.error.init(False)
  rspPayload.beatCount.init(0)
  for (beat <- 0 until config.cudaLaneCount) {
    rspPayload.readData(beat).init(0)
  }

  io.request.ready := state === AdapterState.IDLE && !rspValid

  io.response.valid := rspValid
  io.response.payload := rspPayload

  io.axi.aw.valid := False
  io.axi.aw.addr := pending.address
  io.axi.aw.len := (pending.beatCount - 1).resized
  io.axi.aw.setFullSize()
  io.axi.aw.setBurstINCR()

  io.axi.w.valid := False
  io.axi.w.data := pending.writeData(writeBeatIndex.resize(beatIndexWidth bits))
  io.axi.w.strb := pending.byteMask
  io.axi.w.last := writeBeatIndex === pending.beatCount - 1

  io.axi.b.ready := False

  io.axi.ar.valid := False
  io.axi.ar.addr := pending.address
  io.axi.ar.len := (pending.beatCount - 1).resized
  io.axi.ar.setFullSize()
  io.axi.ar.setBurstINCR()

  io.axi.r.ready := False

  when(io.request.fire) {
    pending := io.request.payload
    rspPayload.warpId := io.request.payload.warpId
    rspPayload.completed := True
    rspPayload.error := False
    rspPayload.beatCount := io.request.payload.beatCount
    for (beat <- 0 until config.cudaLaneCount) {
      rspPayload.readData(beat) := B(0, config.dataWidth bits)
    }
    when(io.request.payload.write) {
      writeAddressSent := False
      writeBeatIndex := 0
      state := AdapterState.WRITE_ADDR
    } otherwise {
      readBeatIndex := 0
      readError := False
      state := AdapterState.READ_ADDR
    }
  }

  switch(state) {
    is(AdapterState.IDLE) {
      when(io.response.fire) {
        rspValid := False
      }
    }

    is(AdapterState.WRITE_ADDR) {
      io.axi.aw.valid := !writeAddressSent
      io.axi.w.valid := writeBeatIndex < pending.beatCount

      when(io.axi.aw.fire) {
        writeAddressSent := True
      }

      when(io.axi.w.fire) {
        writeBeatIndex := writeBeatIndex + 1
      }

      when((writeAddressSent || io.axi.aw.fire) && (writeBeatIndex === pending.beatCount || (io.axi.w.fire && writeBeatIndex === pending.beatCount - 1))) {
        state := AdapterState.WRITE_RSP
      }
    }

    is(AdapterState.WRITE_RSP) {
      io.axi.b.ready := True
      when(io.axi.b.valid) {
        rspValid := True
        rspPayload.error := io.axi.b.isSLVERR() || io.axi.b.isDECERR()
        state := AdapterState.IDLE
      }
    }

    is(AdapterState.READ_ADDR) {
      io.axi.ar.valid := True
      when(io.axi.ar.ready) {
        state := AdapterState.READ_RSP
      }
    }

    is(AdapterState.READ_RSP) {
      io.axi.r.ready := True
      when(io.axi.r.valid) {
        rspPayload.readData(readBeatIndex.resize(beatIndexWidth bits)) := io.axi.r.data
        readError := readError || io.axi.r.isSLVERR() || io.axi.r.isDECERR()
        readBeatIndex := readBeatIndex + 1
        when(io.axi.r.last) {
          rspValid := True
          rspPayload.completed := True
          rspPayload.error := readError || io.axi.r.isSLVERR() || io.axi.r.isDECERR()
          state := AdapterState.IDLE
        }
      }
    }
  }

  when(io.response.fire) {
    rspValid := False
  }
}
