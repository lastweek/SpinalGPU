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
    val localContexts = in(Vec.fill(config.residentWarpsPerSubSm)(LocalSlotContextView(config)))
    val currentCommand = in(CtaCommandDesc(config))
    val contextUpdate = master(Flow(WarpContextWrite(config)))
    val status = out(SubSmStatus(config))
    val fetchMemReq = master(Stream(FetchMemReq(config)))
    val fetchMemRsp = slave(Stream(FetchMemRsp(config)))
    val sharedMemReq = master(Stream(SharedMemReq(config)))
    val sharedMemRsp = slave(Stream(SharedMemRsp(config)))
    val externalMemReq = master(Stream(GlobalMemBurstReq(config)))
    val externalMemRsp = slave(Stream(GlobalMemBurstRsp(config)))
    val debug = SubSmPartitionDebugIo(config)
  }

  private val registerFile = new WarpRegisterFile(config, slotCount = config.residentWarpsPerSubSm)
  private val slotTable = new LocalWarpSlotTable(config)
  private val scheduler = new LocalWarpScheduler(config)
  private val fetchUnit = new InstructionFetchUnit(config)
  private val decodeUnit = new DecodeUnit(config)
  private val specialRegisterUnit = new SpecialRegisterReadUnit(config)
  private val cudaCoreArray = new CudaCoreArray(config)
  private val loadStoreUnit = new LoadStoreUnit(config)
  private val specialFunctionUnit = new SpecialFunctionUnit(config)
  private val tensorCoreBlock = new TensorCoreBlock(config)

  private val engineState = RegInit(EngineState.IDLE)
  private val selectedLocalSlotReg = Reg(UInt(config.localSlotIdWidth bits)) init (0)
  private val selectedWarpIdReg = Reg(UInt(config.warpIdWidth bits)) init (0)
  private val selectedContextReg = Reg(WarpContext(config))
  private val instructionReg = Reg(Bits(config.instructionWidth bits)) init (0)
  private val pendingOp = Reg(PendingWarpOp(config))

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

  pendingOp.valid.init(False)
  pendingOp.warpId.init(0)
  pendingOp.localSlotId.init(0)
  pendingOp.pc.init(0)
  pendingOp.nextPc.init(0)
  pendingOp.writesRd.init(False)
  pendingOp.rd.init(0)
  pendingOp.isLoad.init(False)
  pendingOp.activeMask.init(0)
  pendingOp.decoded.valid.init(False)
  pendingOp.decoded.illegal.init(False)
  pendingOp.decoded.opcode.init(0)
  pendingOp.decoded.target.init(ExecutionUnitKind.CONTROL)
  pendingOp.decoded.rd.init(0)
  pendingOp.decoded.rs0.init(0)
  pendingOp.decoded.rs1.init(0)
  pendingOp.decoded.rs2.init(0)
  pendingOp.decoded.immediate.init(0)
  pendingOp.decoded.specialRegister.init(0)
  pendingOp.decoded.addressSpace.init(AddressSpaceKind.GLOBAL)
  pendingOp.decoded.memoryAccessWidth.init(MemoryAccessWidthKind.WORD)
  pendingOp.decoded.writesRd.init(False)
  pendingOp.decoded.usesRs0.init(False)
  pendingOp.decoded.usesRs1.init(False)
  pendingOp.decoded.usesRs2.init(False)
  pendingOp.decoded.isStore.init(False)
  pendingOp.decoded.isLoad.init(False)
  pendingOp.decoded.isBranch.init(False)
  pendingOp.decoded.branchOnZero.init(False)
  pendingOp.decoded.isExit.init(False)
  pendingOp.decoded.isTrap.init(False)
  pendingOp.decoded.isS2r.init(False)

  private def clearSelectedContext(): Unit = {
    selectedContextReg.valid := False
    selectedContextReg.runnable := False
    selectedContextReg.pc := U(0, config.addressWidth bits)
    selectedContextReg.activeMask := B(0, config.warpSize bits)
    selectedContextReg.threadBase := U(0, config.threadCountWidth bits)
    selectedContextReg.threadBaseX := U(0, config.threadCountWidth bits)
    selectedContextReg.threadBaseY := U(0, config.threadCountWidth bits)
    selectedContextReg.threadBaseZ := U(0, config.threadCountWidth bits)
    selectedContextReg.threadCount := U(0, config.threadCountWidth bits)
    selectedContextReg.outstanding := False
    selectedContextReg.exited := False
    selectedContextReg.faulted := False
  }

  private def clearPendingOp(): Unit = {
    pendingOp.valid := False
    pendingOp.warpId := U(0, config.warpIdWidth bits)
    pendingOp.localSlotId := U(0, config.localSlotIdWidth bits)
    pendingOp.pc := U(0, config.addressWidth bits)
    pendingOp.nextPc := U(0, config.addressWidth bits)
    pendingOp.writesRd := False
    pendingOp.rd := U(0, config.registerAddressWidth bits)
    pendingOp.isLoad := False
    pendingOp.activeMask := B(0, config.warpSize bits)
    pendingOp.decoded.valid := False
    pendingOp.decoded.illegal := False
    pendingOp.decoded.opcode := B(0, 8 bits)
    pendingOp.decoded.target := ExecutionUnitKind.CONTROL
    pendingOp.decoded.rd := U(0, config.registerAddressWidth bits)
    pendingOp.decoded.rs0 := U(0, config.registerAddressWidth bits)
    pendingOp.decoded.rs1 := U(0, config.registerAddressWidth bits)
    pendingOp.decoded.rs2 := U(0, config.registerAddressWidth bits)
    pendingOp.decoded.immediate := S(0, config.dataWidth bits)
    pendingOp.decoded.specialRegister := U(0, config.specialRegisterWidth bits)
    pendingOp.decoded.addressSpace := AddressSpaceKind.GLOBAL
    pendingOp.decoded.memoryAccessWidth := MemoryAccessWidthKind.WORD
    pendingOp.decoded.writesRd := False
    pendingOp.decoded.usesRs0 := False
    pendingOp.decoded.usesRs1 := False
    pendingOp.decoded.usesRs2 := False
    pendingOp.decoded.isStore := False
    pendingOp.decoded.isLoad := False
    pendingOp.decoded.isBranch := False
    pendingOp.decoded.branchOnZero := False
    pendingOp.decoded.isExit := False
    pendingOp.decoded.isTrap := False
    pendingOp.decoded.isS2r := False
  }

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

  private def emitTrap(warpId: UInt, pc: UInt, faultCode: UInt): Unit = {
    io.status.trapValid := True
    io.status.trapInfo.warpId := warpId
    io.status.trapInfo.pc := pc
    io.status.trapInfo.faultCode := faultCode.resized
    io.debug.trap.valid := True
    io.debug.trap.payload.warpId := warpId
    io.debug.trap.payload.pc := pc
    io.debug.trap.payload.faultCode := faultCode.resized
  }

  private def emitWriteback(
      slotId: UInt,
      warpId: UInt,
      rd: UInt,
      writeMask: Bits,
      data: Vec[Bits],
      enable: Bool
  ): Unit = {
    registerFile.io.write.valid := enable
    registerFile.io.write.payload.slotId := slotId
    registerFile.io.write.payload.rd := rd
    registerFile.io.write.payload.writeMask := writeMask
    registerFile.io.write.payload.data := data

    io.debug.writeback.valid := enable
    io.debug.writeback.payload.warpId := warpId
    io.debug.writeback.payload.rd := rd
    io.debug.writeback.payload.writeMask := writeMask
    io.debug.writeback.payload.data := data
  }

  private def capturePendingOp(decoded: DecodedInstruction): Unit = {
    pendingOp.valid := True
    pendingOp.warpId := selectedWarpIdReg
    pendingOp.localSlotId := selectedLocalSlotReg
    pendingOp.pc := selectedContextReg.pc
    pendingOp.nextPc := advancePc
    pendingOp.writesRd := decoded.writesRd
    pendingOp.rd := decoded.rd
    pendingOp.isLoad := decoded.isLoad
    pendingOp.activeMask := selectedContextReg.activeMask
    pendingOp.decoded := decoded
  }

  registerFile.io.write.valid := False
  registerFile.io.write.payload.slotId := U(0, config.localSlotIdWidth bits)
  registerFile.io.write.payload.rd := U(0, config.registerAddressWidth bits)
  registerFile.io.write.payload.writeMask := B(0, config.warpSize bits)
  for (lane <- 0 until config.warpSize) {
    registerFile.io.write.payload.data(lane) := B(0, config.dataWidth bits)
  }

  io.contextUpdate.valid := False
  io.contextUpdate.payload.index := selectedWarpIdReg
  io.contextUpdate.payload.context := selectedContextReg

  io.status.idle := engineState === EngineState.IDLE
  io.status.engineState := engineState.asBits.asUInt.resized
  io.status.trapValid := False
  io.status.trapInfo.warpId := U(0, config.warpIdWidth bits)
  io.status.trapInfo.pc := U(0, config.addressWidth bits)
  io.status.trapInfo.faultCode := FaultCode.None
  io.status.selectedWarpId := selectedWarpIdReg
  io.status.selectedPc := selectedContextReg.pc

  io.debug.scheduledWarp.valid := False
  io.debug.scheduledWarp.payload.warpId := U(0, config.warpIdWidth bits)
  io.debug.scheduledWarp.payload.localSlotId := U(0, config.localSlotIdWidth bits)
  io.debug.scheduledWarp.payload.context := selectedContextReg
  io.debug.fetchResponse.valid := False
  io.debug.fetchResponse.payload := fetchUnit.io.response.payload
  io.debug.decodedInstruction.valid := engineState === EngineState.ISSUE
  io.debug.decodedInstruction.payload := decodeUnit.io.decoded
  io.debug.writeback.valid := False
  io.debug.writeback.payload.warpId := U(0, config.warpIdWidth bits)
  io.debug.writeback.payload.rd := U(0, config.registerAddressWidth bits)
  io.debug.writeback.payload.writeMask := B(0, config.warpSize bits)
  for (lane <- 0 until config.warpSize) {
    io.debug.writeback.payload.data(lane) := B(0, config.dataWidth bits)
  }
  io.debug.trap.valid := False
  io.debug.trap.payload.warpId := U(0, config.warpIdWidth bits)
  io.debug.trap.payload.pc := U(0, config.addressWidth bits)
  io.debug.trap.payload.faultCode := FaultCode.None
  io.debug.engineState := io.status.engineState
  io.debug.selectedWarpId := io.status.selectedWarpId
  io.debug.selectedPc := io.status.selectedPc

  slotTable.io.clearBindings := io.clearBindings
  slotTable.io.bind <> io.bind
  io.bindRequest := io.kernelBusy && slotTable.io.freeSlotFound
  io.bindLocalSlotId := slotTable.io.freeSlotId
  registerFile.io.clearSlot <> slotTable.io.clearSlot
  io.debug.slotOccupied := slotTable.io.slotOccupied
  io.debug.boundWarpIds := slotTable.io.boundWarpIds

  when(io.clearBindings) {
    engineState := EngineState.IDLE
    selectedLocalSlotReg := U(0, config.localSlotIdWidth bits)
    selectedWarpIdReg := U(0, config.warpIdWidth bits)
    instructionReg := B(0, config.instructionWidth bits)
    clearSelectedContext()
    clearPendingOp()
  }

  private val slotReady = Bits(config.residentWarpsPerSubSm bits)
  for (slot <- 0 until config.residentWarpsPerSubSm) {
    val slotContext = io.localContexts(slot).context
    slotReady(slot) :=
      io.localContexts(slot).occupied &&
        slotContext.valid &&
        slotContext.runnable &&
        !slotContext.outstanding &&
        !slotContext.exited &&
        !slotContext.faulted
  }

  scheduler.io.clear := io.clearBindings
  scheduler.io.readySlots := slotReady
  scheduler.io.advance := fetchUnit.io.request.fire

  private val selectedLocalSlot = scheduler.io.selectedSlotId
  private val selectedLocalSlotValid = scheduler.io.selectedValid
  private val selectedSlotView =
    if (config.residentWarpsPerSubSm == 1) io.localContexts(0) else io.localContexts(selectedLocalSlot)
  private val selectedContext = selectedSlotView.context
  private val selectedWarpId = selectedSlotView.warpId

  private val frontend = new Area {
    fetchUnit.io.request.valid := io.kernelBusy && engineState === EngineState.IDLE && selectedLocalSlotValid
    fetchUnit.io.request.payload.warpId := selectedWarpId
    fetchUnit.io.request.payload.pc := selectedContext.pc

    fetchUnit.io.memoryReq <> io.fetchMemReq
    fetchUnit.io.memoryRsp <> io.fetchMemRsp
    fetchUnit.io.response.ready := engineState === EngineState.WAIT_FETCH

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
    }

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
        emitTrap(selectedWarpIdReg, fetchUnit.io.response.payload.pc, fetchUnit.io.response.payload.faultCode)
        clearPendingOp()
        engineState := EngineState.IDLE
      } otherwise {
        instructionReg := fetchUnit.io.response.payload.instruction
        engineState := EngineState.ISSUE
      }
    }

    decodeUnit.io.instruction := instructionReg
  }

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

  registerFile.io.readSlotId := selectedLocalSlotReg
  registerFile.io.readAddrA := readAddrA
  registerFile.io.readAddrB := readAddrB
  registerFile.io.readAddrC := readAddrC

  specialRegisterUnit.io.decoded := decodeUnit.io.decoded
  specialRegisterUnit.io.selectedWarpId := selectedWarpIdReg
  specialRegisterUnit.io.selectedContext := selectedContextReg
  specialRegisterUnit.io.currentCommand := io.currentCommand

  private val immediateUInt = decodeUnit.io.decoded.immediate.asUInt
  private val advancePc = (selectedContextReg.pc + U(4, config.addressWidth bits)).resized
  private val branchTarget = (advancePc + immediateUInt.resized).resized
  private val branchTrueBits = Bits(config.warpSize bits)
  private val branchFalseBits = Bits(config.warpSize bits)

  for (lane <- 0 until config.warpSize) {
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
  loadStoreUnit.io.issue.payload.accessWidth := decodeUnit.io.decoded.memoryAccessWidth
  loadStoreUnit.io.issue.payload.write := decodeUnit.io.decoded.isStore
  loadStoreUnit.io.issue.payload.activeMask := selectedContextReg.activeMask
  loadStoreUnit.io.issue.payload.byteMask := B(0xF, config.byteMaskWidth bits)
  when(decodeUnit.io.decoded.memoryAccessWidth === MemoryAccessWidthKind.HALFWORD) {
    loadStoreUnit.io.issue.payload.byteMask := B(0x3, config.byteMaskWidth bits)
  }
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
      emitTrap(selectedWarpIdReg, selectedContextReg.pc, FaultCode.IllegalOpcode)
      clearPendingOp()
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
      emitTrap(selectedWarpIdReg, selectedContextReg.pc, FaultCode.Trap)
      clearPendingOp()
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
      clearPendingOp()
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
        clearPendingOp()
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
        emitTrap(selectedWarpIdReg, selectedContextReg.pc, FaultCode.NonUniformBranch)
        clearPendingOp()
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
        clearPendingOp()
        engineState := EngineState.IDLE
      }
    } elsewhen (decodeUnit.io.decoded.isS2r) {
      val specialData = Vec(Bits(config.dataWidth bits), config.warpSize)
      for (lane <- 0 until config.warpSize) {
        specialData(lane) := specialRegisterUnit.io.values(lane).asBits
      }
      emitWriteback(
        slotId = selectedLocalSlotReg,
        warpId = selectedWarpIdReg,
        rd = decodeUnit.io.decoded.rd,
        writeMask = selectedContextReg.activeMask,
        data = specialData,
        enable = decodeUnit.io.decoded.writesRd
      )
      emitContextUpdate(
        index = selectedWarpIdReg,
        source = selectedContextReg,
        pc = advancePc,
        runnable = True,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = selectedContextReg.faulted
      )
      clearPendingOp()
      engineState := EngineState.IDLE
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.CUDA) {
      cudaCoreArray.io.issue.valid := True
      when(cudaCoreArray.io.issue.ready) {
        capturePendingOp(decodeUnit.io.decoded)
        engineState := EngineState.WAIT_CUDA
      }
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.LSU) {
      loadStoreUnit.io.issue.valid := True
      when(loadStoreUnit.io.issue.ready) {
        capturePendingOp(decodeUnit.io.decoded)
        engineState := EngineState.WAIT_LSU
      }
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.SFU) {
      specialFunctionUnit.io.issue.valid := True
      when(specialFunctionUnit.io.issue.ready) {
        capturePendingOp(decodeUnit.io.decoded)
        engineState := EngineState.WAIT_SFU
      }
    } elsewhen (decodeUnit.io.decoded.target === ExecutionUnitKind.TENSOR) {
      tensorCoreBlock.io.issue.valid := True
      when(tensorCoreBlock.io.issue.ready) {
        capturePendingOp(decodeUnit.io.decoded)
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
      clearPendingOp()
      engineState := EngineState.IDLE
    }
  }

  private val completionStage = new Area {
    when(cudaCoreArray.io.response.fire) {
      emitWriteback(
        slotId = pendingOp.localSlotId,
        warpId = pendingOp.warpId,
        rd = pendingOp.rd,
        writeMask = pendingOp.activeMask,
        data = cudaCoreArray.io.response.payload.result,
        enable = pendingOp.valid && pendingOp.writesRd
      )
      emitContextUpdate(
        index = pendingOp.warpId,
        source = selectedContextReg,
        pc = pendingOp.nextPc,
        runnable = True,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = selectedContextReg.faulted
      )
      clearPendingOp()
      engineState := EngineState.IDLE
    }

    when(loadStoreUnit.io.response.fire) {
      when(loadStoreUnit.io.response.payload.error) {
        emitContextUpdate(
          index = pendingOp.warpId,
          source = selectedContextReg,
          pc = pendingOp.pc,
          runnable = False,
          outstanding = False,
          exited = selectedContextReg.exited,
          faulted = True
        )
        emitTrap(pendingOp.warpId, pendingOp.pc, loadStoreUnit.io.response.payload.faultCode)
      } otherwise {
        emitWriteback(
          slotId = pendingOp.localSlotId,
          warpId = pendingOp.warpId,
          rd = pendingOp.rd,
          writeMask = pendingOp.activeMask,
          data = loadStoreUnit.io.response.payload.readData,
          enable = pendingOp.valid && pendingOp.isLoad
        )
        emitContextUpdate(
          index = pendingOp.warpId,
          source = selectedContextReg,
          pc = pendingOp.nextPc,
          runnable = True,
          outstanding = False,
          exited = selectedContextReg.exited,
          faulted = selectedContextReg.faulted
        )
      }
      clearPendingOp()
      engineState := EngineState.IDLE
    }

    when(specialFunctionUnit.io.response.fire) {
      emitWriteback(
        slotId = pendingOp.localSlotId,
        warpId = pendingOp.warpId,
        rd = pendingOp.rd,
        writeMask = pendingOp.activeMask,
        data = specialFunctionUnit.io.response.payload.result,
        enable = pendingOp.valid && pendingOp.writesRd
      )
      emitContextUpdate(
        index = pendingOp.warpId,
        source = selectedContextReg,
        pc = pendingOp.nextPc,
        runnable = True,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = selectedContextReg.faulted
      )
      clearPendingOp()
      engineState := EngineState.IDLE
    }

    when(tensorCoreBlock.io.response.fire) {
      emitWriteback(
        slotId = pendingOp.localSlotId,
        warpId = pendingOp.warpId,
        rd = pendingOp.rd,
        writeMask = pendingOp.activeMask,
        data = tensorCoreBlock.io.response.payload.result,
        enable = pendingOp.valid && pendingOp.writesRd
      )
      emitContextUpdate(
        index = pendingOp.warpId,
        source = selectedContextReg,
        pc = pendingOp.nextPc,
        runnable = True,
        outstanding = False,
        exited = selectedContextReg.exited,
        faulted = selectedContextReg.faulted
      )
      clearPendingOp()
      engineState := EngineState.IDLE
    }
  }
}
