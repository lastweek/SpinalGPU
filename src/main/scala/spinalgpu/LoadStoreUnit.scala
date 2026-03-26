package spinalgpu

import spinal.core._
import spinal.lib._

class LoadStoreUnit(config: SmConfig) extends Component {
  val io = new Bundle {
    val issue = slave(Stream(LsuReq(config)))
    val response = master(Stream(LsuRsp(config)))
    val sharedMemReq = master(Stream(SharedMemReq(config)))
    val sharedMemRsp = slave(Stream(SharedMemRsp(config)))
    val externalMemReq = master(Stream(ExternalMemReq(config)))
    val externalMemRsp = slave(Stream(ExternalMemRsp(config)))
  }

  private object State extends SpinalEnum {
    val IDLE, ISSUE_MEMORY, WAIT_SHARED, WAIT_EXTERNAL = newElement()
  }

  private val state = RegInit(State.IDLE)
  private val pending = Reg(LsuReq(config))
  private val rspValid = RegInit(False)
  private val rspPayload = Reg(LsuRsp(config))
  private val laneIndex = Reg(UInt(log2Up(config.warpSize) bits)) init (0)
  private val readBuffer = Vec.fill(config.warpSize)(Reg(Bits(config.dataWidth bits)) init (0))

  private val nextActiveValid = Bool()
  private val nextActiveIndex = UInt(log2Up(config.warpSize) bits)
  nextActiveIndex := laneIndex
  var nextActiveSeen: Bool = False
  for (lane <- 0 until config.warpSize) {
    val laneIsNext = pending.activeMask(lane) && U(lane, laneIndex.getWidth bits) > laneIndex
    when(laneIsNext && !nextActiveSeen) {
      nextActiveIndex := U(lane, laneIndex.getWidth bits)
    }
    nextActiveSeen = nextActiveSeen || laneIsNext
  }
  nextActiveValid := nextActiveSeen

  private val issueFirstActiveIndex = UInt(log2Up(config.warpSize) bits)
  issueFirstActiveIndex := U(0, issueFirstActiveIndex.getWidth bits)
  var issueHasActive: Bool = False
  for (lane <- 0 until config.warpSize) {
    val laneActive = io.issue.payload.activeMask(lane)
    when(laneActive && !issueHasActive) {
      issueFirstActiveIndex := U(lane, issueFirstActiveIndex.getWidth bits)
    }
    issueHasActive = issueHasActive || laneActive
  }

  private val currentAddress = pending.addresses(laneIndex)
  private val currentWordAddress = currentAddress(config.sharedAddressWidth + 1 downto 2)

  io.issue.ready := state === State.IDLE && !rspValid

  io.sharedMemReq.valid := False
  io.sharedMemReq.payload.warpId := pending.warpId
  io.sharedMemReq.payload.write := pending.write
  io.sharedMemReq.payload.address := currentWordAddress
  io.sharedMemReq.payload.writeData := pending.writeData(laneIndex)
  io.sharedMemReq.payload.byteMask := pending.byteMask

  io.externalMemReq.valid := False
  io.externalMemReq.payload.warpId := pending.warpId
  io.externalMemReq.payload.write := pending.write
  io.externalMemReq.payload.address := currentAddress
  io.externalMemReq.payload.writeData := pending.writeData(laneIndex)
  io.externalMemReq.payload.byteMask := pending.byteMask

  io.sharedMemRsp.ready := False
  io.externalMemRsp.ready := False

  io.response.valid := rspValid
  io.response.payload := rspPayload

  private def loadResponseDefaults(): Unit = {
    rspPayload.completed := True
    rspPayload.error := False
    rspPayload.faultCode := FaultCode.None
    rspPayload.faultAddress := U(0, config.addressWidth bits)
    for (lane <- 0 until config.warpSize) {
      rspPayload.readData(lane) := B(0, config.dataWidth bits)
    }
  }

  when(io.issue.fire) {
    pending := io.issue.payload
    rspPayload.warpId := io.issue.payload.warpId
    loadResponseDefaults()
    for (lane <- 0 until config.warpSize) {
      readBuffer(lane) := B(0, config.dataWidth bits)
    }

    when(io.issue.payload.activeMask.orR) {
      laneIndex := issueFirstActiveIndex
      state := State.ISSUE_MEMORY
    } otherwise {
      rspValid := True
    }
  }

  when(state === State.ISSUE_MEMORY) {
    when(currentAddress(1 downto 0) =/= 0) {
      rspPayload.warpId := pending.warpId
      rspPayload.completed := True
      rspPayload.error := True
      rspPayload.faultCode := FaultCode.MisalignedLoadStore
      rspPayload.faultAddress := currentAddress
      for (lane <- 0 until config.warpSize) {
        rspPayload.readData(lane) := B(0, config.dataWidth bits)
      }
      rspValid := True
      state := State.IDLE
    } otherwise {
      switch(pending.addressSpace) {
        is(AddressSpaceKind.SHARED) {
          io.sharedMemReq.valid := True
          when(io.sharedMemReq.ready) {
            state := State.WAIT_SHARED
          }
        }
        is(AddressSpaceKind.GLOBAL) {
          io.externalMemReq.valid := True
          when(io.externalMemReq.ready) {
            state := State.WAIT_EXTERNAL
          }
        }
      }
    }
  }

  when(state === State.WAIT_SHARED) {
    io.sharedMemRsp.ready := True
    when(io.sharedMemRsp.valid) {
      when(!pending.write) {
        readBuffer(laneIndex) := io.sharedMemRsp.payload.readData
      }

      when(io.sharedMemRsp.payload.error) {
        rspPayload.warpId := pending.warpId
        rspPayload.completed := True
        rspPayload.error := True
        rspPayload.faultCode := FaultCode.ExternalMemory
        rspPayload.faultAddress := currentAddress
        for (lane <- 0 until config.warpSize) {
          rspPayload.readData(lane) := B(0, config.dataWidth bits)
        }
        rspValid := True
        state := State.IDLE
      } elsewhen (nextActiveValid) {
        laneIndex := nextActiveIndex
        state := State.ISSUE_MEMORY
      } otherwise {
        rspPayload.warpId := pending.warpId
        rspPayload.completed := True
        rspPayload.error := False
        rspPayload.faultCode := FaultCode.None
        rspPayload.faultAddress := U(0, config.addressWidth bits)
        for (lane <- 0 until config.warpSize) {
          rspPayload.readData(lane) := readBuffer(lane)
        }
        when(!pending.write) {
          rspPayload.readData(laneIndex) := io.sharedMemRsp.payload.readData
        }
        rspValid := True
        state := State.IDLE
      }
    }
  }

  when(state === State.WAIT_EXTERNAL) {
    io.externalMemRsp.ready := True
    when(io.externalMemRsp.valid) {
      when(!pending.write) {
        readBuffer(laneIndex) := io.externalMemRsp.payload.readData
      }

      when(io.externalMemRsp.payload.error) {
        rspPayload.warpId := pending.warpId
        rspPayload.completed := True
        rspPayload.error := True
        rspPayload.faultCode := FaultCode.ExternalMemory
        rspPayload.faultAddress := currentAddress
        for (lane <- 0 until config.warpSize) {
          rspPayload.readData(lane) := B(0, config.dataWidth bits)
        }
        rspValid := True
        state := State.IDLE
      } elsewhen (nextActiveValid) {
        laneIndex := nextActiveIndex
        state := State.ISSUE_MEMORY
      } otherwise {
        rspPayload.warpId := pending.warpId
        rspPayload.completed := True
        rspPayload.error := False
        rspPayload.faultCode := FaultCode.None
        rspPayload.faultAddress := U(0, config.addressWidth bits)
        for (lane <- 0 until config.warpSize) {
          rspPayload.readData(lane) := readBuffer(lane)
        }
        when(!pending.write) {
          rspPayload.readData(laneIndex) := io.externalMemRsp.payload.readData
        }
        rspValid := True
        state := State.IDLE
      }
    }
  }

  when(io.response.fire) {
    rspValid := False
  }
}
