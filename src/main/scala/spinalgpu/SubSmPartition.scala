package spinalgpu

import spinal.core._
import spinal.lib._

case class SubSmPartitionDebugIo(config: SmConfig) extends Bundle {
  val scheduledWarp = master(Flow(SubSmScheduleReq(config)))
  val fetchResponse = master(Flow(FetchRsp(config)))
  val decodedInstruction = master(Flow(DecodedInstruction(config)))
  val writeback = master(Flow(WritebackPacket(config)))
  val trap = master(Flow(TrapInfo(config)))
  val engineState = out(UInt(3 bits))
  val selectedWarpId = out(UInt(config.warpIdWidth bits))
  val selectedPc = out(UInt(config.addressWidth bits))
  val slotOccupied = out(Bits(config.residentWarpsPerSubSm bits))
  val boundWarpIds = out(Vec(UInt(config.warpIdWidth bits), config.residentWarpsPerSubSm))
}

class SubSmPartition(config: SmConfig) extends Component {
  private object EngineState extends SpinalEnum {
    val IDLE, WAIT_FETCH, ISSUE, WAIT_CUDA, WAIT_LSU, WAIT_SFU, WAIT_TENSOR = newElement()
  }

  val io = new Bundle {
    val kernelBusy = in Bool()
    val clearBindings = in Bool()
    val bind = slave(Flow(SubSmBindReq(config)))
    val bindRequest = out Bool()
    val bindLocalSlotId = out(UInt(config.localSlotIdWidth bits))
    val warpContexts = in(Vec.fill(config.residentWarpCount)(WarpContext(config)))
    val currentCommand = in(KernelCommandDesc(config))
    val currentGridId = in(UInt(64 bits))
    val contextUpdate = master(Flow(WarpContextWrite(config)))
    val fetchMemReq = master(Stream(FetchMemReq(config)))
    val fetchMemRsp = slave(Stream(FetchMemRsp(config)))
    val sharedMemReq = master(Stream(SharedMemReq(config)))
    val sharedMemRsp = slave(Stream(SharedMemRsp(config)))
    val externalMemReq = master(Stream(GlobalMemBurstReq(config)))
    val externalMemRsp = slave(Stream(GlobalMemBurstRsp(config)))
    val debug = SubSmPartitionDebugIo(config)
  }

  private val registerFile = new WarpRegisterFile(config, slotCount = config.residentWarpsPerSubSm)
  private val fetchUnit = new InstructionFetchUnit(config)
  private val decodeUnit = new DecodeUnit(config)
  private val cudaCoreArray = new CudaCoreArray(config)
  private val loadStoreUnit = new LoadStoreUnit(config)
  private val specialFunctionUnit = new SpecialFunctionUnit(config)
  private val tensorCoreBlock = new TensorCoreBlock(config)

  private val engineState = RegInit(EngineState.IDLE)
  private val slotOccupied = Reg(Bits(config.residentWarpsPerSubSm bits)) init (0)
  private val slotWarpIds = Vec.fill(config.residentWarpsPerSubSm)(Reg(UInt(config.warpIdWidth bits)) init (0))
  private val schedulerBase = Reg(UInt(config.localSlotIdWidth bits)) init (0)

  private val selectedLocalSlotReg = Reg(UInt(config.localSlotIdWidth bits)) init (0)
  private val selectedWarpIdReg = Reg(UInt(config.warpIdWidth bits)) init (0)
  private val selectedContextReg = Reg(WarpContext(config))
  private val instructionReg = Reg(Bits(config.instructionWidth bits)) init (0)
  private val blockThreadCountWidth = config.threadCountWidth * 3

  selectedContextReg.valid.init(False)
  selectedContextReg.runnable.init(False)
  selectedContextReg.pc.init(0)
  selectedContextReg.activeMask.init(0)
  selectedContextReg.threadBase.init(0)
  selectedContextReg.threadBaseX.init(0)
  selectedContextReg.threadBaseY.init(0)
  selectedContextReg.threadBaseZ.init(0)
  selectedContextReg.threadCount.init(0)
  selectedContextReg.outstanding.init(False)
  selectedContextReg.exited.init(False)
  selectedContextReg.faulted.init(False)

  registerFile.io.clearWarp.valid := False
  registerFile.io.clearWarp.payload := U(0, config.warpIdWidth bits)
  registerFile.io.write.valid := False
  registerFile.io.write.payload.warpId := U(0, config.warpIdWidth bits)
  registerFile.io.write.payload.rd := U(0, config.registerAddressWidth bits)
  registerFile.io.write.payload.writeMask := B(0, config.warpSize bits)
  for (lane <- 0 until config.warpSize) {
    registerFile.io.write.payload.data(lane) := B(0, config.dataWidth bits)
  }

  io.contextUpdate.valid := False
  io.contextUpdate.payload.index := selectedWarpIdReg
  io.contextUpdate.payload.context := selectedContextReg

  io.debug.scheduledWarp.valid := False
  io.debug.scheduledWarp.payload.warpId := U(0, config.warpIdWidth bits)
  io.debug.scheduledWarp.payload.localSlotId := U(0, config.localSlotIdWidth bits)
  io.debug.scheduledWarp.payload.context := selectedContextReg
  io.debug.fetchResponse.valid := False
  io.debug.fetchResponse.payload := fetchUnit.io.response.payload
  io.debug.decodedInstruction.valid := engineState === EngineState.ISSUE
  io.debug.decodedInstruction.payload := decodeUnit.io.decoded
  io.debug.writeback.valid := False
  io.debug.writeback.payload := registerFile.io.write.payload
  io.debug.trap.valid := False
  io.debug.trap.payload.warpId := selectedWarpIdReg
  io.debug.trap.payload.pc := selectedContextReg.pc
  io.debug.trap.payload.faultCode := FaultCode.None
  io.debug.engineState := engineState.asBits.asUInt.resized
  io.debug.selectedWarpId := selectedWarpIdReg
  io.debug.selectedPc := selectedContextReg.pc
  io.debug.slotOccupied := slotOccupied
  io.debug.boundWarpIds := slotWarpIds

  private def localSlotWarpId(slotId: UInt): UInt =
    if (config.residentWarpsPerSubSm == 1) slotWarpIds(0) else slotWarpIds(slotId)

  private def globalWarpContextFor(warpId: UInt): WarpContext =
    if (config.residentWarpCount == 1) io.warpContexts(0) else io.warpContexts(warpId)

  when(io.clearBindings) {
    slotOccupied := B(0, config.residentWarpsPerSubSm bits)
    engineState := EngineState.IDLE
  }

  when(io.bind.valid) {
    if (config.residentWarpsPerSubSm == 1) {
      slotOccupied(0) := True
      slotWarpIds(0) := io.bind.payload.warpId
    } else {
      slotOccupied(io.bind.payload.localSlotId) := True
      slotWarpIds(io.bind.payload.localSlotId) := io.bind.payload.warpId
    }
    registerFile.io.clearWarp.valid := True
    registerFile.io.clearWarp.payload :=
      (if (config.residentWarpsPerSubSm == 1) U(0, config.warpIdWidth bits) else io.bind.payload.localSlotId.resized)
  }

  private val freeSlotFound = Bool()
  private val freeSlotId = UInt(config.localSlotIdWidth bits)
  private val freeSlotCandidates = Bits(config.residentWarpsPerSubSm bits)
  for (slot <- 0 until config.residentWarpsPerSubSm) {
    freeSlotCandidates(slot) := !slotOccupied(slot)
  }
  freeSlotFound := freeSlotCandidates.orR
  freeSlotId := U(0, config.localSlotIdWidth bits)
  for (slot <- 0 until config.residentWarpsPerSubSm) {
    val earlierCandidateHit =
      if (slot == 0) False else freeSlotCandidates(slot - 1 downto 0).orR
    when(freeSlotCandidates(slot) && !earlierCandidateHit) {
      freeSlotId := U(slot, config.localSlotIdWidth bits)
    }
  }
  io.bindRequest := io.kernelBusy && freeSlotFound
  io.bindLocalSlotId := freeSlotId

  private val slotContexts = Vec(WarpContext(config), config.residentWarpsPerSubSm)
  private val slotReady = Bits(config.residentWarpsPerSubSm bits)
  for (slot <- 0 until config.residentWarpsPerSubSm) {
    slotContexts(slot) := globalWarpContextFor(slotWarpIds(slot))
    slotReady(slot) :=
      slotOccupied(slot) && slotContexts(slot).valid && slotContexts(slot).runnable && !slotContexts(slot).outstanding &&
        !slotContexts(slot).exited && !slotContexts(slot).faulted
  }

  private val selectedLocalSlot = UInt(config.localSlotIdWidth bits)
  private val selectedLocalSlotValid = Bool()
  private val schedulerCandidateHits = Bits(config.residentWarpsPerSubSm bits)
  private val schedulerCandidateIds = Vec(UInt(config.localSlotIdWidth bits), config.residentWarpsPerSubSm)
  selectedLocalSlot := schedulerBase

  for (offset <- 0 until config.residentWarpsPerSubSm) {
    val candidateWide = UInt((config.localSlotIdWidth + 1) bits)
    candidateWide := schedulerBase.resize(config.localSlotIdWidth + 1) + U(offset, config.localSlotIdWidth + 1 bits)

    val candidate = UInt(config.localSlotIdWidth bits)
    candidate := candidateWide.resized
    when(candidateWide >= U(config.residentWarpsPerSubSm, config.localSlotIdWidth + 1 bits)) {
      candidate := (candidateWide - U(config.residentWarpsPerSubSm, config.localSlotIdWidth + 1 bits)).resized
    }
    schedulerCandidateIds(offset) := candidate
    schedulerCandidateHits(offset) :=
      (if (config.residentWarpsPerSubSm == 1) slotReady(0) else slotReady(candidate))
  }

  selectedLocalSlotValid := schedulerCandidateHits.orR
  for (offset <- 0 until config.residentWarpsPerSubSm) {
    val earlierCandidateHit =
      if (offset == 0) False else schedulerCandidateHits(offset - 1 downto 0).orR
    when(schedulerCandidateHits(offset) && !earlierCandidateHit) {
      selectedLocalSlot := schedulerCandidateIds(offset)
    }
  }

  private val selectedContext = if (config.residentWarpsPerSubSm == 1) slotContexts(0) else slotContexts(selectedLocalSlot)
  private val selectedWarpId = if (config.residentWarpsPerSubSm == 1) slotWarpIds(0) else slotWarpIds(selectedLocalSlot)

  private def emitContextUpdate(
      index: UInt,
      source: WarpContext,
      pc: UInt,
      runnable: Bool,
      outstanding: Bool,
      exited: Bool,
      faulted: Bool
  ): Unit = {
    io.contextUpdate.valid := True
    io.contextUpdate.payload.index := index
    io.contextUpdate.payload.context.valid := source.valid
    io.contextUpdate.payload.context.runnable := runnable
    io.contextUpdate.payload.context.pc := pc
    io.contextUpdate.payload.context.activeMask := source.activeMask
    io.contextUpdate.payload.context.threadBase := source.threadBase
    io.contextUpdate.payload.context.threadBaseX := source.threadBaseX
    io.contextUpdate.payload.context.threadBaseY := source.threadBaseY
    io.contextUpdate.payload.context.threadBaseZ := source.threadBaseZ
    io.contextUpdate.payload.context.threadCount := source.threadCount
    io.contextUpdate.payload.context.outstanding := outstanding
    io.contextUpdate.payload.context.exited := exited
    io.contextUpdate.payload.context.faulted := faulted
  }

  fetchUnit.io.request.valid := io.kernelBusy && engineState === EngineState.IDLE && selectedLocalSlotValid
  fetchUnit.io.request.payload.warpId := selectedWarpId
  fetchUnit.io.request.payload.pc := selectedContext.pc

  when(fetchUnit.io.request.fire) {
    selectedLocalSlotReg := selectedLocalSlot
    selectedWarpIdReg := selectedWarpId
    selectedContextReg := selectedContext
    emitContextUpdate(
      index = selectedWarpId,
      source = selectedContext,
      pc = selectedContext.pc,
      runnable = False,
      outstanding = True,
      exited = selectedContext.exited,
      faulted = selectedContext.faulted
    )
    engineState := EngineState.WAIT_FETCH

    io.debug.scheduledWarp.valid := True
    io.debug.scheduledWarp.payload.warpId := selectedWarpId
    io.debug.scheduledWarp.payload.localSlotId := selectedLocalSlot
    io.debug.scheduledWarp.payload.context := selectedContext

    val nextBaseWide = UInt((config.localSlotIdWidth + 1) bits)
    nextBaseWide := selectedLocalSlot.resize(config.localSlotIdWidth + 1) + U(1, config.localSlotIdWidth + 1 bits)
    schedulerBase := nextBaseWide.resized
    when(nextBaseWide >= U(config.residentWarpsPerSubSm, config.localSlotIdWidth + 1 bits)) {
      schedulerBase := (nextBaseWide - U(config.residentWarpsPerSubSm, config.localSlotIdWidth + 1 bits)).resized
    }
  }

  fetchUnit.io.memoryReq <> io.fetchMemReq
  fetchUnit.io.memoryRsp <> io.fetchMemRsp
  fetchUnit.io.response.ready := engineState === EngineState.WAIT_FETCH

  when(fetchUnit.io.response.fire) {
    io.debug.fetchResponse.valid := True
    io.debug.fetchResponse.payload := fetchUnit.io.response.payload
    when(fetchUnit.io.response.payload.fault) {
      emitContextUpdate(
        index = selectedWarpIdReg,
        source = selectedContextReg,
        pc = selectedContextReg.pc,
        runnable = False,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = True
      )
      io.debug.trap.valid := True
      io.debug.trap.payload.warpId := selectedWarpIdReg
      io.debug.trap.payload.pc := fetchUnit.io.response.payload.pc
      io.debug.trap.payload.faultCode := fetchUnit.io.response.payload.faultCode
      engineState := EngineState.IDLE
    } otherwise {
      instructionReg := fetchUnit.io.response.payload.instruction
      engineState := EngineState.ISSUE
    }
  }

  decodeUnit.io.instruction := instructionReg

  private val readAddrA = UInt(config.registerAddressWidth bits)
  private val readAddrB = UInt(config.registerAddressWidth bits)
  private val readAddrC = UInt(config.registerAddressWidth bits)
  readAddrA := U(0, config.registerAddressWidth bits)
  readAddrB := U(0, config.registerAddressWidth bits)
  readAddrC := U(0, config.registerAddressWidth bits)

  when(decodeUnit.io.decoded.usesRs0 || decodeUnit.io.decoded.isStore || decodeUnit.io.decoded.isBranch) {
    readAddrA := decodeUnit.io.decoded.rs0
  }
  when(decodeUnit.io.decoded.isStore) {
    readAddrB := decodeUnit.io.decoded.rd
  } elsewhen (decodeUnit.io.decoded.usesRs1) {
    readAddrB := decodeUnit.io.decoded.rs1
  }
  when(decodeUnit.io.decoded.usesRs2) {
    readAddrC := decodeUnit.io.decoded.rs2
  }

  registerFile.io.readWarpId := selectedLocalSlotReg.resized
  registerFile.io.readAddrA := readAddrA
  registerFile.io.readAddrB := readAddrB
  registerFile.io.readAddrC := readAddrC

  private val immediateUInt = decodeUnit.io.decoded.immediate.asUInt
  private val advancePc = (selectedContextReg.pc + U(4, config.addressWidth bits)).resized
  private val branchTarget = (advancePc + immediateUInt.resized).resized
  private val currentBlockThreadCount =
    (io.currentCommand.blockDimX.resize(blockThreadCountWidth bits) *
      io.currentCommand.blockDimY.resize(blockThreadCountWidth bits) *
      io.currentCommand.blockDimZ.resize(blockThreadCountWidth bits)).resized
  private val blockWarpCount =
    ((currentBlockThreadCount.resized.resize(config.dataWidth bits) + U(config.warpSize - 1, config.dataWidth bits)) /
      U(config.warpSize, config.dataWidth bits)).resized
  private val gridIdLow = io.currentGridId(31 downto 0).resize(config.dataWidth)
  private val gridIdHigh = io.currentGridId(63 downto 32).resize(config.dataWidth)

  private val laneTidX = Vec(UInt(config.threadCountWidth bits), config.warpSize)
  private val laneTidY = Vec(UInt(config.threadCountWidth bits), config.warpSize)
  private val laneTidZ = Vec(UInt(config.threadCountWidth bits), config.warpSize)
  laneTidX(0) := selectedContextReg.threadBaseX
  laneTidY(0) := selectedContextReg.threadBaseY
  laneTidZ(0) := selectedContextReg.threadBaseZ
  for (lane <- 1 until config.warpSize) {
    val (nextX, nextY, nextZ) = ThreadCoordinateLogic.increment(
      config,
      laneTidX(lane - 1),
      laneTidY(lane - 1),
      laneTidZ(lane - 1),
      io.currentCommand.blockDimX,
      io.currentCommand.blockDimY,
      io.currentCommand.blockDimZ
    )
    laneTidX(lane) := nextX
    laneTidY(lane) := nextY
    laneTidZ(lane) := nextZ
  }

  private val specialValues = Vec(UInt(config.dataWidth bits), config.warpSize)
  private val branchTrueBits = Bits(config.warpSize bits)
  private val branchFalseBits = Bits(config.warpSize bits)
  for (lane <- 0 until config.warpSize) {
    specialValues(lane) := U(0, config.dataWidth bits)
    switch(decodeUnit.io.decoded.specialRegister) {
      is(U(SpecialRegisterKind.TidX, config.specialRegisterWidth bits)) {
        specialValues(lane) := laneTidX(lane).resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.TidY, config.specialRegisterWidth bits)) {
        specialValues(lane) := laneTidY(lane).resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.TidZ, config.specialRegisterWidth bits)) {
        specialValues(lane) := laneTidZ(lane).resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.LaneId, config.specialRegisterWidth bits)) {
        specialValues(lane) := U(lane, config.dataWidth bits)
      }
      is(U(SpecialRegisterKind.WarpId, config.specialRegisterWidth bits)) {
        specialValues(lane) := selectedWarpIdReg.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.NtidX, config.specialRegisterWidth bits)) {
        specialValues(lane) := io.currentCommand.blockDimX.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.NtidY, config.specialRegisterWidth bits)) {
        specialValues(lane) := io.currentCommand.blockDimY.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.NtidZ, config.specialRegisterWidth bits)) {
        specialValues(lane) := io.currentCommand.blockDimZ.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.CtaidX, config.specialRegisterWidth bits)) {
        specialValues(lane) := 0
      }
      is(U(SpecialRegisterKind.CtaidY, config.specialRegisterWidth bits)) {
        specialValues(lane) := 0
      }
      is(U(SpecialRegisterKind.CtaidZ, config.specialRegisterWidth bits)) {
        specialValues(lane) := 0
      }
      is(U(SpecialRegisterKind.NctaidX, config.specialRegisterWidth bits)) {
        specialValues(lane) := 1
      }
      is(U(SpecialRegisterKind.NctaidY, config.specialRegisterWidth bits)) {
        specialValues(lane) := 1
      }
      is(U(SpecialRegisterKind.NctaidZ, config.specialRegisterWidth bits)) {
        specialValues(lane) := 1
      }
      is(U(SpecialRegisterKind.NwarpId, config.specialRegisterWidth bits)) {
        specialValues(lane) := blockWarpCount
      }
      is(U(SpecialRegisterKind.SmId, config.specialRegisterWidth bits)) {
        specialValues(lane) := 0
      }
      is(U(SpecialRegisterKind.NsmId, config.specialRegisterWidth bits)) {
        specialValues(lane) := 1
      }
      is(U(SpecialRegisterKind.GridIdLo, config.specialRegisterWidth bits)) {
        specialValues(lane) := gridIdLow
      }
      is(U(SpecialRegisterKind.GridIdHi, config.specialRegisterWidth bits)) {
        specialValues(lane) := gridIdHigh
      }
      is(U(SpecialRegisterKind.ArgBase, config.specialRegisterWidth bits)) {
        specialValues(lane) := io.currentCommand.argBase.resize(config.dataWidth)
      }
    }

    val laneActive = selectedContextReg.activeMask(lane)
    val cond = Bool()
    cond := registerFile.io.readDataA(lane) =/= 0
    when(decodeUnit.io.decoded.branchOnZero) {
      cond := registerFile.io.readDataA(lane) === 0
    }
    branchTrueBits(lane) := laneActive && cond
    branchFalseBits(lane) := laneActive && !cond
  }

  cudaCoreArray.io.issue.valid := False
  cudaCoreArray.io.issue.payload.warpId := selectedWarpIdReg
  cudaCoreArray.io.issue.payload.opcode := decodeUnit.io.decoded.opcode
  cudaCoreArray.io.issue.payload.activeMask := selectedContextReg.activeMask
  for (lane <- 0 until config.warpSize) {
    cudaCoreArray.io.issue.payload.operandA(lane) := registerFile.io.readDataA(lane).asBits
    cudaCoreArray.io.issue.payload.operandB(lane) := registerFile.io.readDataB(lane).asBits
    cudaCoreArray.io.issue.payload.operandC(lane) := registerFile.io.readDataC(lane).asBits
    when(decodeUnit.io.decoded.opcode === B(Opcode.MOVI, 8 bits) || decodeUnit.io.decoded.opcode === B(Opcode.ADDI, 8 bits)) {
      cudaCoreArray.io.issue.payload.operandB(lane) := immediateUInt.resized.asBits
    }
  }
  cudaCoreArray.io.response.ready := engineState === EngineState.WAIT_CUDA

  loadStoreUnit.io.issue.valid := False
  loadStoreUnit.io.issue.payload.warpId := selectedWarpIdReg
  loadStoreUnit.io.issue.payload.addressSpace := decodeUnit.io.decoded.addressSpace
  loadStoreUnit.io.issue.payload.write := decodeUnit.io.decoded.isStore
  loadStoreUnit.io.issue.payload.activeMask := selectedContextReg.activeMask
  loadStoreUnit.io.issue.payload.byteMask := B((1 << config.byteMaskWidth) - 1, config.byteMaskWidth bits)
  for (lane <- 0 until config.warpSize) {
    loadStoreUnit.io.issue.payload.addresses(lane) := (registerFile.io.readDataA(lane) + immediateUInt.resized).resized
    loadStoreUnit.io.issue.payload.writeData(lane) := registerFile.io.readDataB(lane).asBits
  }
  loadStoreUnit.io.sharedMemReq <> io.sharedMemReq
  loadStoreUnit.io.sharedMemRsp <> io.sharedMemRsp
  loadStoreUnit.io.externalMemReq <> io.externalMemReq
  loadStoreUnit.io.externalMemRsp <> io.externalMemRsp
  loadStoreUnit.io.response.ready := engineState === EngineState.WAIT_LSU

  specialFunctionUnit.io.issue.valid := False
  specialFunctionUnit.io.issue.payload.warpId := selectedWarpIdReg
  specialFunctionUnit.io.issue.payload.opcode := decodeUnit.io.decoded.opcode
  specialFunctionUnit.io.issue.payload.activeMask := selectedContextReg.activeMask
  for (lane <- 0 until config.warpSize) {
    specialFunctionUnit.io.issue.payload.operand(lane) := registerFile.io.readDataA(lane)
  }
  specialFunctionUnit.io.response.ready := engineState === EngineState.WAIT_SFU

  tensorCoreBlock.io.issue.valid := False
  tensorCoreBlock.io.issue.payload.warpId := selectedWarpIdReg
  tensorCoreBlock.io.issue.payload.opcode := decodeUnit.io.decoded.opcode
  tensorCoreBlock.io.issue.payload.activeMask := selectedContextReg.activeMask
  for (lane <- 0 until config.warpSize) {
    tensorCoreBlock.io.issue.payload.operandA(lane) := registerFile.io.readDataA(lane)
    tensorCoreBlock.io.issue.payload.operandB(lane) := registerFile.io.readDataB(lane)
  }
  tensorCoreBlock.io.response.ready := engineState === EngineState.WAIT_TENSOR

  when(engineState === EngineState.ISSUE) {
    when(decodeUnit.io.decoded.illegal) {
      emitContextUpdate(
        index = selectedWarpIdReg,
        source = selectedContextReg,
        pc = selectedContextReg.pc,
        runnable = False,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = True
      )
      io.debug.trap.valid := True
      io.debug.trap.payload.warpId := selectedWarpIdReg
      io.debug.trap.payload.pc := selectedContextReg.pc
      io.debug.trap.payload.faultCode := FaultCode.IllegalOpcode
      engineState := EngineState.IDLE
    } elsewhen (decodeUnit.io.decoded.isTrap) {
      emitContextUpdate(
        index = selectedWarpIdReg,
        source = selectedContextReg,
        pc = selectedContextReg.pc,
        runnable = False,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = True
      )
      io.debug.trap.valid := True
      io.debug.trap.payload.warpId := selectedWarpIdReg
      io.debug.trap.payload.pc := selectedContextReg.pc
      io.debug.trap.payload.faultCode := FaultCode.Trap
      engineState := EngineState.IDLE
    } elsewhen (decodeUnit.io.decoded.isExit) {
      emitContextUpdate(
        index = selectedWarpIdReg,
        source = selectedContextReg,
        pc = advancePc,
        runnable = False,
        outstanding = False,
        exited = True,
        faulted = selectedContextReg.faulted
      )
      engineState := EngineState.IDLE
    } elsewhen (decodeUnit.io.decoded.isBranch) {
      val nonUniform = branchTrueBits.orR && branchFalseBits.orR

      when(decodeUnit.io.decoded.opcode === B(Opcode.BRA, 8 bits)) {
        emitContextUpdate(
          index = selectedWarpIdReg,
          source = selectedContextReg,
          pc = branchTarget,
          runnable = True,
          outstanding = False,
          exited = selectedContextReg.exited,
          faulted = selectedContextReg.faulted
        )
        engineState := EngineState.IDLE
      } elsewhen (nonUniform) {
        emitContextUpdate(
          index = selectedWarpIdReg,
          source = selectedContextReg,
          pc = selectedContextReg.pc,
          runnable = False,
          outstanding = False,
          exited = selectedContextReg.exited,
          faulted = True
        )
        io.debug.trap.valid := True
        io.debug.trap.payload.warpId := selectedWarpIdReg
        io.debug.trap.payload.pc := selectedContextReg.pc
        io.debug.trap.payload.faultCode := FaultCode.NonUniformBranch
        engineState := EngineState.IDLE
      } otherwise {
        val nextPc = UInt(config.addressWidth bits)
        nextPc := advancePc
        when(branchTrueBits.orR) {
          nextPc := branchTarget
        }
        emitContextUpdate(
          index = selectedWarpIdReg,
          source = selectedContextReg,
          pc = nextPc,
          runnable = True,
          outstanding = False,
          exited = selectedContextReg.exited,
          faulted = selectedContextReg.faulted
        )
        engineState := EngineState.IDLE
      }
    } elsewhen (decodeUnit.io.decoded.isS2r) {
      registerFile.io.write.valid := decodeUnit.io.decoded.writesRd
      registerFile.io.write.payload.warpId := selectedLocalSlotReg.resized
      registerFile.io.write.payload.rd := decodeUnit.io.decoded.rd
      registerFile.io.write.payload.writeMask := selectedContextReg.activeMask
      for (lane <- 0 until config.warpSize) {
        registerFile.io.write.payload.data(lane) := specialValues(lane).asBits
      }
      io.debug.writeback.valid := decodeUnit.io.decoded.writesRd
      io.debug.writeback.payload.warpId := selectedWarpIdReg
      io.debug.writeback.payload.rd := decodeUnit.io.decoded.rd
      io.debug.writeback.payload.writeMask := selectedContextReg.activeMask
      io.debug.writeback.payload.data := registerFile.io.write.payload.data

      emitContextUpdate(
        index = selectedWarpIdReg,
        source = selectedContextReg,
        pc = advancePc,
        runnable = True,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = selectedContextReg.faulted
      )
      engineState := EngineState.IDLE
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.CUDA) {
      cudaCoreArray.io.issue.valid := True
      when(cudaCoreArray.io.issue.ready) {
        engineState := EngineState.WAIT_CUDA
      }
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.LSU) {
      loadStoreUnit.io.issue.valid := True
      when(loadStoreUnit.io.issue.ready) {
        engineState := EngineState.WAIT_LSU
      }
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.SFU) {
      specialFunctionUnit.io.issue.valid := True
      when(specialFunctionUnit.io.issue.ready) {
        engineState := EngineState.WAIT_SFU
      }
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.TENSOR) {
      tensorCoreBlock.io.issue.valid := True
      when(tensorCoreBlock.io.issue.ready) {
        engineState := EngineState.WAIT_TENSOR
      }
    } otherwise {
      emitContextUpdate(
        index = selectedWarpIdReg,
        source = selectedContextReg,
        pc = advancePc,
        runnable = True,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = selectedContextReg.faulted
      )
      engineState := EngineState.IDLE
    }
  }

  when(cudaCoreArray.io.response.fire) {
    registerFile.io.write.valid := decodeUnit.io.decoded.writesRd
    registerFile.io.write.payload.warpId := selectedLocalSlotReg.resized
    registerFile.io.write.payload.rd := decodeUnit.io.decoded.rd
    registerFile.io.write.payload.writeMask := selectedContextReg.activeMask
    registerFile.io.write.payload.data := cudaCoreArray.io.response.payload.result
    io.debug.writeback.valid := decodeUnit.io.decoded.writesRd
    io.debug.writeback.payload.warpId := selectedWarpIdReg
    io.debug.writeback.payload.rd := decodeUnit.io.decoded.rd
    io.debug.writeback.payload.writeMask := selectedContextReg.activeMask
    io.debug.writeback.payload.data := cudaCoreArray.io.response.payload.result

    emitContextUpdate(
      index = selectedWarpIdReg,
      source = selectedContextReg,
      pc = advancePc,
      runnable = True,
      outstanding = False,
      exited = selectedContextReg.exited,
      faulted = selectedContextReg.faulted
    )
    engineState := EngineState.IDLE
  }

  when(loadStoreUnit.io.response.fire) {
    when(loadStoreUnit.io.response.payload.error) {
      emitContextUpdate(
        index = selectedWarpIdReg,
        source = selectedContextReg,
        pc = selectedContextReg.pc,
        runnable = False,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = True
      )
      io.debug.trap.valid := True
      io.debug.trap.payload.warpId := selectedWarpIdReg
      io.debug.trap.payload.pc := selectedContextReg.pc
      io.debug.trap.payload.faultCode := loadStoreUnit.io.response.payload.faultCode
    } otherwise {
      registerFile.io.write.valid := decodeUnit.io.decoded.isLoad
      registerFile.io.write.payload.warpId := selectedLocalSlotReg.resized
      registerFile.io.write.payload.rd := decodeUnit.io.decoded.rd
      registerFile.io.write.payload.writeMask := selectedContextReg.activeMask
      registerFile.io.write.payload.data := loadStoreUnit.io.response.payload.readData
      io.debug.writeback.valid := decodeUnit.io.decoded.isLoad
      io.debug.writeback.payload.warpId := selectedWarpIdReg
      io.debug.writeback.payload.rd := decodeUnit.io.decoded.rd
      io.debug.writeback.payload.writeMask := selectedContextReg.activeMask
      io.debug.writeback.payload.data := loadStoreUnit.io.response.payload.readData

      emitContextUpdate(
        index = selectedWarpIdReg,
        source = selectedContextReg,
        pc = advancePc,
        runnable = True,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = selectedContextReg.faulted
      )
    }
    engineState := EngineState.IDLE
  }

  when(specialFunctionUnit.io.response.fire) {
    registerFile.io.write.valid := decodeUnit.io.decoded.writesRd
    registerFile.io.write.payload.warpId := selectedLocalSlotReg.resized
    registerFile.io.write.payload.rd := decodeUnit.io.decoded.rd
    registerFile.io.write.payload.writeMask := selectedContextReg.activeMask
    registerFile.io.write.payload.data := specialFunctionUnit.io.response.payload.result
    io.debug.writeback.valid := decodeUnit.io.decoded.writesRd
    io.debug.writeback.payload.warpId := selectedWarpIdReg
    io.debug.writeback.payload.rd := decodeUnit.io.decoded.rd
    io.debug.writeback.payload.writeMask := selectedContextReg.activeMask
    io.debug.writeback.payload.data := specialFunctionUnit.io.response.payload.result

    emitContextUpdate(
      index = selectedWarpIdReg,
      source = selectedContextReg,
      pc = advancePc,
      runnable = True,
      outstanding = False,
      exited = selectedContextReg.exited,
      faulted = selectedContextReg.faulted
    )
    engineState := EngineState.IDLE
  }

  when(tensorCoreBlock.io.response.fire) {
    registerFile.io.write.valid := decodeUnit.io.decoded.writesRd
    registerFile.io.write.payload.warpId := selectedLocalSlotReg.resized
    registerFile.io.write.payload.rd := decodeUnit.io.decoded.rd
    registerFile.io.write.payload.writeMask := selectedContextReg.activeMask
    registerFile.io.write.payload.data := tensorCoreBlock.io.response.payload.result
    io.debug.writeback.valid := decodeUnit.io.decoded.writesRd
    io.debug.writeback.payload.warpId := selectedWarpIdReg
    io.debug.writeback.payload.rd := decodeUnit.io.decoded.rd
    io.debug.writeback.payload.writeMask := selectedContextReg.activeMask
    io.debug.writeback.payload.data := tensorCoreBlock.io.response.payload.result

    emitContextUpdate(
      index = selectedWarpIdReg,
      source = selectedContextReg,
      pc = advancePc,
      runnable = True,
      outstanding = False,
      exited = selectedContextReg.exited,
      faulted = selectedContextReg.faulted
    )
    engineState := EngineState.IDLE
  }
}
