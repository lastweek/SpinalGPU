package spinalgpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

class ExternalMemoryAxiAdapter(config: SmConfig) extends Component {
  val io = new Bundle {
    val request = slave(Stream(ExternalMemReq(config)))
    val response = master(Stream(ExternalMemRsp(config)))
    val axi = master(Axi4(config.axiConfig))
  }

  private object AdapterState extends SpinalEnum {
    val IDLE, WRITE_ADDR, WRITE_RSP, READ_ADDR, READ_RSP = newElement()
  }

  private val state = RegInit(AdapterState.IDLE)
  private val pending = Reg(ExternalMemReq(config))
  private val rspValid = RegInit(False)
  private val rspPayload = Reg(ExternalMemRsp(config))
  private val writeAddressSent = RegInit(False)
  private val writeDataSent = RegInit(False)

  io.request.ready := state === AdapterState.IDLE && !rspValid

  io.response.valid := rspValid
  io.response.payload := rspPayload

  io.axi.aw.valid := False
  io.axi.aw.addr := pending.address
  io.axi.aw.len := 0
  io.axi.aw.setFullSize()
  io.axi.aw.setBurstINCR()

  io.axi.w.valid := False
  io.axi.w.data := pending.writeData
  io.axi.w.strb := pending.byteMask
  io.axi.w.last := True

  io.axi.b.ready := False

  io.axi.ar.valid := False
  io.axi.ar.addr := pending.address
  io.axi.ar.len := 0
  io.axi.ar.setFullSize()
  io.axi.ar.setBurstINCR()

  io.axi.r.ready := False

  when(io.request.fire) {
    pending := io.request.payload
    when(io.request.payload.write) {
      writeAddressSent := False
      writeDataSent := False
      state := AdapterState.WRITE_ADDR
    } otherwise {
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
      io.axi.w.valid := !writeDataSent

      when(io.axi.aw.fire) {
        writeAddressSent := True
      }

      when(io.axi.w.fire) {
        writeDataSent := True
      }

      when((writeAddressSent || io.axi.aw.fire) && (writeDataSent || io.axi.w.fire)) {
        state := AdapterState.WRITE_RSP
      }
    }

    is(AdapterState.WRITE_RSP) {
      io.axi.b.ready := True
      when(io.axi.b.valid) {
        rspValid := True
        rspPayload.warpId := pending.warpId
        rspPayload.completed := True
        rspPayload.error := io.axi.b.isSLVERR() || io.axi.b.isDECERR()
        rspPayload.readData := 0
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
        rspValid := True
        rspPayload.warpId := pending.warpId
        rspPayload.completed := io.axi.r.last
        rspPayload.error := io.axi.r.isSLVERR() || io.axi.r.isDECERR()
        rspPayload.readData := io.axi.r.data
        when(io.axi.r.last) {
          state := AdapterState.IDLE
        }
      }
    }
  }

  when(io.response.fire) {
    rspValid := False
  }
}
