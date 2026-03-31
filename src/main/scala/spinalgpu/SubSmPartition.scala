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
  val tcgen05Busy = out(Bool())
  val tcgen05State = out(UInt(4 bits))
}

class SubSmPartition(config: SmConfig) extends Component {
  private object EngineState extends SpinalEnum {
    val IDLE, WAIT_FETCH, ISSUE, WAIT_CUDA, WAIT_LSU, WAIT_SFU, WAIT_TENSOR = newElement()
  }

  private object Tcgen05WaitKind extends SpinalEnum {
    val NONE, WAIT_LD, WAIT_ST, COMMIT = newElement()
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
    val tensorMemReq = master(Stream(TensorMemReq(config)))
    val tensorMemRsp = slave(Stream(TensorMemRsp(config)))
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
  private val tcgen05Block = new Tcgen05Block(config)

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

  private val tcgen05LdPending = Vec.fill(config.residentWarpsPerSubSm)(RegInit(False))
  private val tcgen05StPending = Vec.fill(config.residentWarpsPerSubSm)(RegInit(False))
  private val tcgen05MmaPending = Vec.fill(config.residentWarpsPerSubSm)(RegInit(False))
  private val tcgen05WaitKind = Vec.fill(config.residentWarpsPerSubSm)(Reg(Tcgen05WaitKind()) init (Tcgen05WaitKind.NONE))
  private val tcgen05WaitPc = Vec.fill(config.residentWarpsPerSubSm)(Reg(UInt(config.addressWidth bits)) init (0))
  private val tcgen05WaitNextPc = Vec.fill(config.residentWarpsPerSubSm)(Reg(UInt(config.addressWidth bits)) init (0))
  private val tcgen05LdRdBase = Vec.fill(config.residentWarpsPerSubSm)(Reg(UInt(config.registerAddressWidth bits)) init (0))

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
  io.debug.tcgen05Busy := tcgen05Block.io.busy
  io.debug.tcgen05State := tcgen05Block.io.debugState

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
    for (slot <- 0 until config.residentWarpsPerSubSm) {
      tcgen05LdPending(slot) := False
      tcgen05StPending(slot) := False
      tcgen05MmaPending(slot) := False
      tcgen05WaitKind(slot) := Tcgen05WaitKind.NONE
      tcgen05WaitPc(slot) := U(0, config.addressWidth bits)
      tcgen05WaitNextPc(slot) := U(0, config.addressWidth bits)
      tcgen05LdRdBase(slot) := U(0, config.registerAddressWidth bits)
    }
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
    fetchUnit.io.request.valid :=
      io.kernelBusy && engineState === EngineState.IDLE && selectedLocalSlotValid && !tcgen05Block.io.ownsRegisterReads && !tcgen05Block.io.event.valid
    fetchUnit.io.request.payload.warpId := selectedWarpId
    fetchUnit.io.request.payload.pc := selectedContext.pc

    fetchUnit.io.memoryReq <> io.fetchMemReq
    fetchUnit.io.memoryRsp <> io.fetchMemRsp
    fetchUnit.io.response.ready := engineState === EngineState.WAIT_FETCH && !tcgen05Block.io.event.valid

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

  private val legacyTensorOwnsRegisterReads = engineState === EngineState.WAIT_TENSOR
  private val tcgen05OwnsRegisterReads = !legacyTensorOwnsRegisterReads && tcgen05Block.io.ownsRegisterReads
  registerFile.io.readSlotId := selectedLocalSlotReg
  registerFile.io.readAddrA := Mux(legacyTensorOwnsRegisterReads, tensorCoreBlock.io.readAddrA, Mux(tcgen05OwnsRegisterReads, tcgen05Block.io.readAddrA, readAddrA))
  registerFile.io.readAddrB := Mux(legacyTensorOwnsRegisterReads, tensorCoreBlock.io.readAddrB, Mux(tcgen05OwnsRegisterReads, tcgen05Block.io.readAddrB, readAddrB))
  registerFile.io.readAddrC := Mux(legacyTensorOwnsRegisterReads, tensorCoreBlock.io.readAddrC, Mux(tcgen05OwnsRegisterReads, tcgen05Block.io.readAddrC, readAddrC))

  specialRegisterUnit.io.decoded := decodeUnit.io.decoded
  specialRegisterUnit.io.selectedWarpId := selectedWarpIdReg
  specialRegisterUnit.io.selectedContext := selectedContextReg
  specialRegisterUnit.io.currentCommand := io.currentCommand

  private val immediateUInt = decodeUnit.io.decoded.immediate.asUInt
  private val advancePc = (selectedContextReg.pc + U(4, config.addressWidth bits)).resized
  private val branchTarget = (advancePc + immediateUInt.resized).resized
  private val selectedTcgen05LdPending = tcgen05LdPending(selectedLocalSlotReg)
  private val selectedTcgen05StPending = tcgen05StPending(selectedLocalSlotReg)
  private val selectedTcgen05MmaPending = tcgen05MmaPending(selectedLocalSlotReg)
  private val fullWriteMask = B((BigInt(1) << config.warpSize) - 1, config.warpSize bits)
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
  cudaCoreArray.io.response.ready := engineState === EngineState.WAIT_CUDA && !tcgen05Block.io.event.valid

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
  loadStoreUnit.io.externalMemReq <> io.externalMemReq
  loadStoreUnit.io.externalMemRsp <> io.externalMemRsp
  loadStoreUnit.io.response.ready := engineState === EngineState.WAIT_LSU && !tcgen05Block.io.event.valid

  specialFunctionUnit.io.issue.valid := False
  specialFunctionUnit.io.issue.payload.warpId := selectedWarpIdReg
  specialFunctionUnit.io.issue.payload.opcode := decodeUnit.io.decoded.opcode
  specialFunctionUnit.io.issue.payload.activeMask := selectedContextReg.activeMask
  for (lane <- 0 until config.warpSize) {
    specialFunctionUnit.io.issue.payload.operand(lane) := registerFile.io.readDataA(lane).asBits
  }
  specialFunctionUnit.io.response.ready := engineState === EngineState.WAIT_SFU && !tcgen05Block.io.event.valid

  tensorCoreBlock.io.issue.valid := False
  tensorCoreBlock.io.issue.payload.warpId := selectedWarpIdReg
  tensorCoreBlock.io.issue.payload.opcode := decodeUnit.io.decoded.opcode
  tensorCoreBlock.io.issue.payload.activeMask := selectedContextReg.activeMask
  tensorCoreBlock.io.issue.payload.rdBase := decodeUnit.io.decoded.rd
  tensorCoreBlock.io.issue.payload.rs0Base := decodeUnit.io.decoded.rs0
  tensorCoreBlock.io.issue.payload.rs1Base := decodeUnit.io.decoded.rs1
  tensorCoreBlock.io.issue.payload.rs2Base := decodeUnit.io.decoded.rs2
  tensorCoreBlock.io.readDataA := registerFile.io.readDataA
  tensorCoreBlock.io.readDataB := registerFile.io.readDataB
  tensorCoreBlock.io.readDataC := registerFile.io.readDataC
  tensorCoreBlock.io.response.ready := engineState === EngineState.WAIT_TENSOR && !tcgen05Block.io.event.valid

  tcgen05Block.io.launch.valid := False
  tcgen05Block.io.launch.payload.warpId := selectedWarpIdReg
  tcgen05Block.io.launch.payload.localSlotId := selectedLocalSlotReg
  tcgen05Block.io.launch.payload.opcode := decodeUnit.io.decoded.opcode
  tcgen05Block.io.launch.payload.activeMask := selectedContextReg.activeMask
  tcgen05Block.io.launch.payload.rdBase := decodeUnit.io.decoded.rd
  tcgen05Block.io.launch.payload.rs0Base := decodeUnit.io.decoded.rs0
  tcgen05Block.io.launch.payload.rs1Base := decodeUnit.io.decoded.rs1
  tcgen05Block.io.launch.payload.rs2Base := decodeUnit.io.decoded.rs2
  tcgen05Block.io.readDataA := registerFile.io.readDataA
  tcgen05Block.io.readDataB := registerFile.io.readDataB
  tcgen05Block.io.readDataC := registerFile.io.readDataC
  tcgen05Block.io.event.ready := engineState =/= EngineState.ISSUE

  private val legacyTensorOwnsSharedMemory = engineState === EngineState.WAIT_TENSOR
  private val tcgen05OwnsSharedMemory = !legacyTensorOwnsSharedMemory && (tcgen05Block.io.sharedMemReq.valid || tcgen05Block.io.sharedMemRsp.ready)
  io.sharedMemReq.valid := False
  io.sharedMemReq.payload.warpId := U(0, config.warpIdWidth bits)
  io.sharedMemReq.payload.write := False
  io.sharedMemReq.payload.address := U(0, config.sharedAddressWidth bits)
  io.sharedMemReq.payload.writeData := B(0, config.dataWidth bits)
  io.sharedMemReq.payload.byteMask := B(0, config.byteMaskWidth bits)

  loadStoreUnit.io.sharedMemReq.ready := False
  loadStoreUnit.io.sharedMemRsp.valid := False
  loadStoreUnit.io.sharedMemRsp.payload := io.sharedMemRsp.payload

  tensorCoreBlock.io.sharedMemReq.ready := False
  tensorCoreBlock.io.sharedMemRsp.valid := False
  tensorCoreBlock.io.sharedMemRsp.payload := io.sharedMemRsp.payload

  tcgen05Block.io.sharedMemReq.ready := False
  tcgen05Block.io.sharedMemRsp.valid := False
  tcgen05Block.io.sharedMemRsp.payload := io.sharedMemRsp.payload

  when(legacyTensorOwnsSharedMemory) {
    io.sharedMemReq.valid := tensorCoreBlock.io.sharedMemReq.valid
    io.sharedMemReq.payload := tensorCoreBlock.io.sharedMemReq.payload
    tensorCoreBlock.io.sharedMemReq.ready := io.sharedMemReq.ready
    tensorCoreBlock.io.sharedMemRsp.valid := io.sharedMemRsp.valid
  } elsewhen (tcgen05OwnsSharedMemory) {
    io.sharedMemReq.valid := tcgen05Block.io.sharedMemReq.valid
    io.sharedMemReq.payload := tcgen05Block.io.sharedMemReq.payload
    tcgen05Block.io.sharedMemReq.ready := io.sharedMemReq.ready
    tcgen05Block.io.sharedMemRsp.valid := io.sharedMemRsp.valid
  } otherwise {
    io.sharedMemReq.valid := loadStoreUnit.io.sharedMemReq.valid
    io.sharedMemReq.payload := loadStoreUnit.io.sharedMemReq.payload
    loadStoreUnit.io.sharedMemReq.ready := io.sharedMemReq.ready
    loadStoreUnit.io.sharedMemRsp.valid := io.sharedMemRsp.valid
  }
  io.sharedMemRsp.ready := Mux(legacyTensorOwnsSharedMemory, tensorCoreBlock.io.sharedMemRsp.ready, Mux(tcgen05OwnsSharedMemory, tcgen05Block.io.sharedMemRsp.ready, loadStoreUnit.io.sharedMemRsp.ready))

  io.tensorMemReq <> tcgen05Block.io.tensorMemReq
  tcgen05Block.io.tensorMemRsp <> io.tensorMemRsp

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
      when(decodeUnit.io.decoded.opcode === B(Opcode.TCGEN05_WAIT_LD, 8 bits)) {
        when(selectedTcgen05LdPending) {
          tcgen05WaitKind(selectedLocalSlotReg) := Tcgen05WaitKind.WAIT_LD
          tcgen05WaitPc(selectedLocalSlotReg) := selectedContextReg.pc
          tcgen05WaitNextPc(selectedLocalSlotReg) := advancePc
          emitContextUpdate(
            index = selectedWarpIdReg,
            source = selectedContextReg,
            pc = selectedContextReg.pc,
            runnable = True,
            outstanding = True,
            exited = selectedContextReg.exited,
            faulted = selectedContextReg.faulted
          )
          engineState := EngineState.IDLE
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
        clearPendingOp()
      } elsewhen (decodeUnit.io.decoded.opcode === B(Opcode.TCGEN05_WAIT_ST, 8 bits)) {
        when(selectedTcgen05StPending) {
          tcgen05WaitKind(selectedLocalSlotReg) := Tcgen05WaitKind.WAIT_ST
          tcgen05WaitPc(selectedLocalSlotReg) := selectedContextReg.pc
          tcgen05WaitNextPc(selectedLocalSlotReg) := advancePc
          emitContextUpdate(
            index = selectedWarpIdReg,
            source = selectedContextReg,
            pc = selectedContextReg.pc,
            runnable = True,
            outstanding = True,
            exited = selectedContextReg.exited,
            faulted = selectedContextReg.faulted
          )
          engineState := EngineState.IDLE
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
        clearPendingOp()
      } elsewhen (decodeUnit.io.decoded.opcode === B(Opcode.TCGEN05_COMMIT_CTA1, 8 bits)) {
        when(selectedTcgen05MmaPending) {
          tcgen05WaitKind(selectedLocalSlotReg) := Tcgen05WaitKind.COMMIT
          tcgen05WaitPc(selectedLocalSlotReg) := selectedContextReg.pc
          tcgen05WaitNextPc(selectedLocalSlotReg) := advancePc
          emitContextUpdate(
            index = selectedWarpIdReg,
            source = selectedContextReg,
            pc = selectedContextReg.pc,
            runnable = True,
            outstanding = True,
            exited = selectedContextReg.exited,
            faulted = selectedContextReg.faulted
          )
          engineState := EngineState.IDLE
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
        clearPendingOp()
      } elsewhen (decodeUnit.io.decoded.opcode === B(Opcode.TCGEN05_LD_32X32B_X2, 8 bits)) {
        when(selectedTcgen05LdPending) {
          emitContextUpdate(
            index = selectedWarpIdReg,
            source = selectedContextReg,
            pc = selectedContextReg.pc,
            runnable = False,
            outstanding = False,
            exited = selectedContextReg.exited,
            faulted = True
          )
          emitTrap(selectedWarpIdReg, selectedContextReg.pc, FaultCode.TensorProtocol)
          clearPendingOp()
          engineState := EngineState.IDLE
        } otherwise {
          tcgen05Block.io.launch.valid := True
          when(tcgen05Block.io.launch.ready) {
            tcgen05LdPending(selectedLocalSlotReg) := True
            tcgen05LdRdBase(selectedLocalSlotReg) := decodeUnit.io.decoded.rd
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
      } elsewhen (decodeUnit.io.decoded.opcode === B(Opcode.TCGEN05_ST_32X32B_X2, 8 bits)) {
        when(selectedTcgen05StPending) {
          emitContextUpdate(
            index = selectedWarpIdReg,
            source = selectedContextReg,
            pc = selectedContextReg.pc,
            runnable = False,
            outstanding = False,
            exited = selectedContextReg.exited,
            faulted = True
          )
          emitTrap(selectedWarpIdReg, selectedContextReg.pc, FaultCode.TensorProtocol)
          clearPendingOp()
          engineState := EngineState.IDLE
        } otherwise {
          tcgen05Block.io.launch.valid := True
          when(tcgen05Block.io.launch.ready) {
            tcgen05StPending(selectedLocalSlotReg) := True
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
      } elsewhen (decodeUnit.io.decoded.opcode === B(Opcode.TCGEN05_MMA_CTA1_F16, 8 bits)) {
        when(selectedTcgen05MmaPending) {
          emitContextUpdate(
            index = selectedWarpIdReg,
            source = selectedContextReg,
            pc = selectedContextReg.pc,
            runnable = False,
            outstanding = False,
            exited = selectedContextReg.exited,
            faulted = True
          )
          emitTrap(selectedWarpIdReg, selectedContextReg.pc, FaultCode.TensorProtocol)
          clearPendingOp()
          engineState := EngineState.IDLE
        } otherwise {
          tcgen05Block.io.launch.valid := True
          when(tcgen05Block.io.launch.ready) {
            tcgen05MmaPending(selectedLocalSlotReg) := True
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
      } otherwise {
        tensorCoreBlock.io.issue.valid := !tcgen05Block.io.busy
        when(tensorCoreBlock.io.issue.ready && !tcgen05Block.io.busy) {
          capturePendingOp(decodeUnit.io.decoded)
          engineState := EngineState.WAIT_TENSOR
        }
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

  private val tcgen05EventLocalSlot =
    if (config.residentWarpsPerSubSm == 1) U(0, config.localSlotIdWidth bits) else tcgen05Block.io.event.payload.localSlotId
  private val tcgen05EventContext =
    if (config.residentWarpsPerSubSm == 1) io.localContexts(0).context else io.localContexts(tcgen05EventLocalSlot).context
  private val tcgen05EventWarpId =
    if (config.residentWarpsPerSubSm == 1) io.localContexts(0).warpId else io.localContexts(tcgen05EventLocalSlot).warpId

  when(tcgen05Block.io.event.fire) {
    val eventWriteRd =
      (tcgen05LdRdBase(tcgen05EventLocalSlot) + tcgen05Block.io.event.payload.writeOffset.resize(config.registerAddressWidth)).resized
    emitWriteback(
      slotId = tcgen05EventLocalSlot,
      warpId = tcgen05Block.io.event.payload.warpId,
      rd = eventWriteRd,
      writeMask = fullWriteMask,
      data = tcgen05Block.io.event.payload.result,
      enable = tcgen05Block.io.event.payload.writeEnable
    )

    when(tcgen05Block.io.event.payload.error) {
      when(tcgen05Block.io.event.payload.opClass === Tcgen05OpClass.LD) {
        tcgen05LdPending(tcgen05EventLocalSlot) := False
      } elsewhen (tcgen05Block.io.event.payload.opClass === Tcgen05OpClass.ST) {
        tcgen05StPending(tcgen05EventLocalSlot) := False
      } elsewhen (tcgen05Block.io.event.payload.opClass === Tcgen05OpClass.MMA) {
        tcgen05MmaPending(tcgen05EventLocalSlot) := False
      }

      val faultPc = UInt(config.addressWidth bits)
      faultPc := tcgen05EventContext.pc
      when(tcgen05WaitKind(tcgen05EventLocalSlot) =/= Tcgen05WaitKind.NONE) {
        faultPc := tcgen05WaitPc(tcgen05EventLocalSlot)
      }

      emitContextUpdate(
        index = tcgen05EventWarpId,
        source = tcgen05EventContext,
        pc = faultPc,
        runnable = False,
        outstanding = False,
        exited = tcgen05EventContext.exited,
        faulted = True
      )
      emitTrap(tcgen05EventWarpId, faultPc, tcgen05Block.io.event.payload.faultCode)
      tcgen05WaitKind(tcgen05EventLocalSlot) := Tcgen05WaitKind.NONE
    } elsewhen (tcgen05Block.io.event.payload.completed) {
      when(tcgen05Block.io.event.payload.opClass === Tcgen05OpClass.LD) {
        tcgen05LdPending(tcgen05EventLocalSlot) := False
        when(tcgen05WaitKind(tcgen05EventLocalSlot) === Tcgen05WaitKind.WAIT_LD) {
          emitContextUpdate(
            index = tcgen05EventWarpId,
            source = tcgen05EventContext,
            pc = tcgen05WaitNextPc(tcgen05EventLocalSlot),
            runnable = True,
            outstanding = False,
            exited = tcgen05EventContext.exited,
            faulted = tcgen05EventContext.faulted
          )
          tcgen05WaitKind(tcgen05EventLocalSlot) := Tcgen05WaitKind.NONE
        }
      } elsewhen (tcgen05Block.io.event.payload.opClass === Tcgen05OpClass.ST) {
        tcgen05StPending(tcgen05EventLocalSlot) := False
        when(tcgen05WaitKind(tcgen05EventLocalSlot) === Tcgen05WaitKind.WAIT_ST) {
          emitContextUpdate(
            index = tcgen05EventWarpId,
            source = tcgen05EventContext,
            pc = tcgen05WaitNextPc(tcgen05EventLocalSlot),
            runnable = True,
            outstanding = False,
            exited = tcgen05EventContext.exited,
            faulted = tcgen05EventContext.faulted
          )
          tcgen05WaitKind(tcgen05EventLocalSlot) := Tcgen05WaitKind.NONE
        }
      } elsewhen (tcgen05Block.io.event.payload.opClass === Tcgen05OpClass.MMA) {
        tcgen05MmaPending(tcgen05EventLocalSlot) := False
        when(tcgen05WaitKind(tcgen05EventLocalSlot) === Tcgen05WaitKind.COMMIT) {
          emitContextUpdate(
            index = tcgen05EventWarpId,
            source = tcgen05EventContext,
            pc = tcgen05WaitNextPc(tcgen05EventLocalSlot),
            runnable = True,
            outstanding = False,
            exited = tcgen05EventContext.exited,
            faulted = tcgen05EventContext.faulted
          )
          tcgen05WaitKind(tcgen05EventLocalSlot) := Tcgen05WaitKind.NONE
        }
      }
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
      val tensorWriteRd = (pendingOp.rd + tensorCoreBlock.io.response.payload.writeOffset.resize(config.registerAddressWidth)).resized
      emitWriteback(
        slotId = pendingOp.localSlotId,
        warpId = pendingOp.warpId,
        rd = tensorWriteRd,
        writeMask = pendingOp.activeMask,
        data = tensorCoreBlock.io.response.payload.result,
        enable = pendingOp.valid && tensorCoreBlock.io.response.payload.writeEnable
      )

      when(tensorCoreBlock.io.response.payload.error) {
        emitContextUpdate(
          index = pendingOp.warpId,
          source = selectedContextReg,
          pc = pendingOp.pc,
          runnable = False,
          outstanding = False,
          exited = selectedContextReg.exited,
          faulted = True
        )
        emitTrap(pendingOp.warpId, pendingOp.pc, tensorCoreBlock.io.response.payload.faultCode)
        clearPendingOp()
        engineState := EngineState.IDLE
      } elsewhen (tensorCoreBlock.io.response.payload.completed) {
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
}
