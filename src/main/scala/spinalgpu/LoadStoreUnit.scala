package spinalgpu

import spinal.core._
import spinal.lib._

class LoadStoreUnit(config: SmConfig) extends Component {
  val io = new Bundle {
    val issue = slave(Stream(LsuReq(config)))
    val response = master(Stream(LsuRsp(config)))
    val sharedMemReq = master(Stream(SharedMemReq(config)))
    val sharedMemRsp = slave(Stream(SharedMemRsp(config)))
    val externalMemReq = master(Stream(GlobalMemBurstReq(config)))
    val externalMemRsp = slave(Stream(GlobalMemBurstRsp(config)))
  }

  private object State extends SpinalEnum {
    val IDLE, ISSUE_SHARED, WAIT_SHARED, ISSUE_GLOBAL, WAIT_GLOBAL = newElement()
  }

  private val state = RegInit(State.IDLE)
  private val pending = Reg(LsuReq(config))
  private val rspValid = RegInit(False)
  private val rspPayload = Reg(LsuRsp(config))
  private val laneIndex = Reg(UInt(log2Up(config.warpSize) bits)) init (0)
  private val readBuffer = Vec.fill(config.warpSize)(Reg(Bits(config.dataWidth bits)) init (0))
  private val currentGroupBeatCount = Reg(UInt(config.globalBurstBeatCountWidth bits)) init (0)
  private val currentGroupNextLane = Reg(UInt(log2Up(config.warpSize) bits)) init (0)
  private val currentGroupHasNext = RegInit(False)
  private val currentGroupFaultAddress = Reg(UInt(config.addressWidth bits)) init (0)
  private val currentGroupLaneIndices = Vec.fill(config.cudaLaneCount)(Reg(UInt(log2Up(config.warpSize) bits)) init (0))

  pending.warpId.init(0)
  pending.addressSpace.init(AddressSpaceKind.GLOBAL)
  pending.accessWidth.init(MemoryAccessWidthKind.WORD)
  pending.write.init(False)
  pending.activeMask.init(0)
  pending.byteMask.init(0)
  for (lane <- 0 until config.warpSize) {
    pending.addresses(lane).init(0)
    pending.writeData(lane).init(0)
  }

  rspPayload.warpId.init(0)
  rspPayload.completed.init(False)
  rspPayload.error.init(False)
  rspPayload.faultCode.init(FaultCode.None)
  rspPayload.faultAddress.init(0)
  for (lane <- 0 until config.warpSize) {
    rspPayload.readData(lane).init(0)
  }

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
  private val currentAccessBytes = UInt(config.addressWidth bits)
  currentAccessBytes := U(config.byteCount, config.addressWidth bits)
  when(pending.accessWidth === MemoryAccessWidthKind.HALFWORD) {
    currentAccessBytes := U(2, config.addressWidth bits)
  }

  private val groupBeatCount = UInt(config.globalBurstBeatCountWidth bits)
  private val groupNextLane = UInt(log2Up(config.warpSize) bits)
  private val groupHasNext = Bool()
  private val groupFaultAddress = UInt(config.addressWidth bits)
  private val groupBaseAddress = UInt(config.addressWidth bits)
  private val groupLaneIndices = Vec(UInt(log2Up(config.warpSize) bits), config.cudaLaneCount)

  groupNextLane := laneIndex
  groupHasNext := False
  groupFaultAddress := currentAddress
  groupBaseAddress := currentAddress
  for (beat <- 0 until config.cudaLaneCount) {
    groupLaneIndices(beat) := laneIndex
  }

  groupBeatCount := 1
  var prefixAccepted: Bool = True
  for (offset <- 1 until config.cudaLaneCount) {
    val candidateLane = (laneIndex + U(offset, laneIndex.getWidth bits)).resized
    val candidateInRange = laneIndex <= U(config.warpSize - 1 - offset, laneIndex.getWidth bits)
    val candidateAccepted =
      prefixAccepted &&
      candidateInRange &&
        pending.activeMask(candidateLane) &&
        pending.addresses(candidateLane) === (currentAddress + (U(offset, config.addressWidth bits) * currentAccessBytes))
    when(candidateAccepted) {
      groupLaneIndices(offset) := candidateLane
      groupBeatCount := U(offset + 1, config.globalBurstBeatCountWidth bits)
    }
    prefixAccepted = candidateAccepted
  }

  val coalescedLastLane = (laneIndex + (groupBeatCount - 1).resize(laneIndex.getWidth bits)).resized
  var nextGroupRecorded: Bool = False
  for (lane <- 0 until config.warpSize) {
    val laneStartsNextGroup = pending.activeMask(lane) && U(lane, laneIndex.getWidth bits) > coalescedLastLane
    when(laneStartsNextGroup && !nextGroupRecorded) {
      groupHasNext := True
      groupNextLane := U(lane, laneIndex.getWidth bits)
    }
    nextGroupRecorded = nextGroupRecorded || laneStartsNextGroup
  }

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
  io.externalMemReq.payload.address := groupBaseAddress
  io.externalMemReq.payload.accessWidth := pending.accessWidth
  io.externalMemReq.payload.beatCount := groupBeatCount
  io.externalMemReq.payload.byteMask := pending.byteMask
  for (beat <- 0 until config.cudaLaneCount) {
    io.externalMemReq.payload.writeData(beat) := pending.writeData(groupLaneIndices(beat))
  }

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
      state := Mux(io.issue.payload.addressSpace === AddressSpaceKind.SHARED, State.ISSUE_SHARED, State.ISSUE_GLOBAL)
    } otherwise {
      rspValid := True
    }
  }

  when(state === State.ISSUE_SHARED) {
    when(pending.accessWidth =/= MemoryAccessWidthKind.WORD || currentAddress(1 downto 0) =/= 0) {
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
      io.sharedMemReq.valid := True
      when(io.sharedMemReq.ready) {
        state := State.WAIT_SHARED
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
        state := State.ISSUE_SHARED
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

  when(state === State.ISSUE_GLOBAL) {
    val misalignedGlobal = Bool()
    misalignedGlobal := groupBaseAddress(1 downto 0) =/= 0
    when(pending.accessWidth === MemoryAccessWidthKind.HALFWORD) {
      misalignedGlobal := groupBaseAddress(0)
    }

    when(misalignedGlobal) {
      rspPayload.warpId := pending.warpId
      rspPayload.completed := True
      rspPayload.error := True
      rspPayload.faultCode := FaultCode.MisalignedLoadStore
      rspPayload.faultAddress := groupFaultAddress
      for (lane <- 0 until config.warpSize) {
        rspPayload.readData(lane) := B(0, config.dataWidth bits)
      }
      rspValid := True
      state := State.IDLE
    } otherwise {
      io.externalMemReq.valid := True
      when(io.externalMemReq.ready) {
        currentGroupBeatCount := groupBeatCount
        currentGroupNextLane := groupNextLane
        currentGroupHasNext := groupHasNext
        currentGroupFaultAddress := groupFaultAddress
        for (beat <- 0 until config.cudaLaneCount) {
          currentGroupLaneIndices(beat) := groupLaneIndices(beat)
        }
        state := State.WAIT_GLOBAL
      }
    }
  }

  when(state === State.WAIT_GLOBAL) {
    io.externalMemRsp.ready := True
    when(io.externalMemRsp.valid) {
      when(!pending.write) {
        for (beat <- 0 until config.cudaLaneCount) {
          when(U(beat, config.globalBurstBeatCountWidth bits) < currentGroupBeatCount) {
            val laneAddress = pending.addresses(currentGroupLaneIndices(beat))
            val extractedRead = Bits(config.dataWidth bits)
            extractedRead := io.externalMemRsp.payload.readData(beat)
            when(pending.accessWidth === MemoryAccessWidthKind.HALFWORD) {
              extractedRead := B(0, config.dataWidth bits)
              when(laneAddress(1)) {
                extractedRead(15 downto 0) := io.externalMemRsp.payload.readData(beat)(31 downto 16)
              } otherwise {
                extractedRead(15 downto 0) := io.externalMemRsp.payload.readData(beat)(15 downto 0)
              }
            }
            readBuffer(currentGroupLaneIndices(beat)) := extractedRead
          }
        }
      }

      when(io.externalMemRsp.payload.error) {
        rspPayload.warpId := pending.warpId
        rspPayload.completed := True
        rspPayload.error := True
        rspPayload.faultCode := FaultCode.ExternalMemory
        rspPayload.faultAddress := currentGroupFaultAddress
        for (lane <- 0 until config.warpSize) {
          rspPayload.readData(lane) := B(0, config.dataWidth bits)
        }
        rspValid := True
        state := State.IDLE
      } elsewhen (currentGroupHasNext) {
        laneIndex := currentGroupNextLane
        state := State.ISSUE_GLOBAL
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
          for (beat <- 0 until config.cudaLaneCount) {
            when(U(beat, config.globalBurstBeatCountWidth bits) < currentGroupBeatCount) {
              val laneAddress = pending.addresses(currentGroupLaneIndices(beat))
              when(pending.accessWidth === MemoryAccessWidthKind.HALFWORD) {
                rspPayload.readData(currentGroupLaneIndices(beat)) := B(0, config.dataWidth bits)
                when(laneAddress(1)) {
                  rspPayload.readData(currentGroupLaneIndices(beat))(15 downto 0) := io.externalMemRsp.payload.readData(beat)(31 downto 16)
                } otherwise {
                  rspPayload.readData(currentGroupLaneIndices(beat))(15 downto 0) := io.externalMemRsp.payload.readData(beat)(15 downto 0)
                }
              } otherwise {
                rspPayload.readData(currentGroupLaneIndices(beat)) := io.externalMemRsp.payload.readData(beat)
              }
            }
          }
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
