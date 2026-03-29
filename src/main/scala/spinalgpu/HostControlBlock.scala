package spinalgpu

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axilite._

class HostControlBlock(config: GpuConfig) extends Component {
  val io = new Bundle {
    val axi = slave(AxiLite4(config.axiLiteConfig))
    val command = out(KernelCommandDesc(config))
    val start = out Bool()
    val clearDone = out Bool()
    val executionStatus = in(KernelExecutionStatus(config))
  }

  private val entryPcReg = Reg(UInt(config.addressWidth bits)) init (0)
  private val gridDimXReg = Reg(UInt(config.dataWidth bits)) init (1)
  private val gridDimYReg = Reg(UInt(config.dataWidth bits)) init (1)
  private val gridDimZReg = Reg(UInt(config.dataWidth bits)) init (1)
  private val blockDimXReg = Reg(UInt(config.threadCountWidth bits)) init (0)
  private val blockDimYReg = Reg(UInt(config.threadCountWidth bits)) init (1)
  private val blockDimZReg = Reg(UInt(config.threadCountWidth bits)) init (1)
  private val argBaseReg = Reg(UInt(config.addressWidth bits)) init (0)
  private val sharedBytesReg = Reg(UInt(config.sharedBytesWidth bits)) init (0)

  private val writeAddrValid = RegInit(False)
  private val writeAddrReg = Reg(UInt(config.controlAddressWidth bits)) init (0)
  private val writeDataValid = RegInit(False)
  private val writeDataReg = Reg(Bits(config.dataWidth bits)) init (0)
  private val writeStrbReg = Reg(Bits(config.byteMaskWidth bits)) init (0)
  private val writeRspValid = RegInit(False)
  private val writeRespReg = Reg(Bits(2 bits)) init (0)
  private val readRspValid = RegInit(False)
  private val readDataReg = Reg(Bits(config.dataWidth bits)) init (0)
  private val readRespReg = Reg(Bits(2 bits)) init (0)

  io.start := False
  io.clearDone := False

  io.command.entryPc := entryPcReg
  io.command.gridDimX := gridDimXReg
  io.command.gridDimY := gridDimYReg
  io.command.gridDimZ := gridDimZReg
  io.command.blockDimX := blockDimXReg
  io.command.blockDimY := blockDimYReg
  io.command.blockDimZ := blockDimZReg
  io.command.argBase := argBaseReg
  io.command.sharedBytes := sharedBytesReg

  io.axi.aw.ready := !writeAddrValid && !writeRspValid
  io.axi.w.ready := !writeDataValid && !writeRspValid
  io.axi.b.valid := writeRspValid
  io.axi.b.payload.resp := writeRespReg

  io.axi.ar.ready := !readRspValid
  io.axi.r.valid := readRspValid
  io.axi.r.payload.data := readDataReg
  io.axi.r.payload.resp := readRespReg

  private def applyWriteMask(current: Bits, data: Bits, strb: Bits): Bits = {
    val nextValue = Bits(current.getWidth bits)
    nextValue := current
    for (byte <- 0 until config.byteMaskWidth) {
      when(strb(byte)) {
        nextValue((byte * 8) + 7 downto byte * 8) := data((byte * 8) + 7 downto byte * 8)
      }
    }
    nextValue
  }

  when(io.axi.aw.fire) {
    writeAddrValid := True
    writeAddrReg := io.axi.aw.addr.resized
  }

  when(io.axi.w.fire) {
    writeDataValid := True
    writeDataReg := io.axi.w.data
    writeStrbReg := io.axi.w.strb
  }

  when(writeAddrValid && writeDataValid && !writeRspValid) {
    writeRspValid := True
    writeRespReg := B"2'b00"
    writeAddrValid := False
    writeDataValid := False

    switch(writeAddrReg(config.controlAddressWidth - 1 downto 2)) {
      is(U(ControlRegisters.Control >> 2, config.controlAddressWidth - 2 bits)) {
        when(writeStrbReg.orR) {
          io.start := writeDataReg(0)
          io.clearDone := writeDataReg(1)
        }
      }
      is(U(ControlRegisters.EntryPc >> 2, config.controlAddressWidth - 2 bits)) {
        entryPcReg := applyWriteMask(entryPcReg.asBits, writeDataReg, writeStrbReg).asUInt
      }
      is(U(ControlRegisters.GridDimX >> 2, config.controlAddressWidth - 2 bits)) {
        gridDimXReg := applyWriteMask(gridDimXReg.asBits, writeDataReg, writeStrbReg).asUInt
      }
      is(U(ControlRegisters.GridDimY >> 2, config.controlAddressWidth - 2 bits)) {
        gridDimYReg := applyWriteMask(gridDimYReg.asBits, writeDataReg, writeStrbReg).asUInt
      }
      is(U(ControlRegisters.GridDimZ >> 2, config.controlAddressWidth - 2 bits)) {
        gridDimZReg := applyWriteMask(gridDimZReg.asBits, writeDataReg, writeStrbReg).asUInt
      }
      is(U(ControlRegisters.BlockDimX >> 2, config.controlAddressWidth - 2 bits)) {
        blockDimXReg := applyWriteMask(blockDimXReg.resize(config.dataWidth).asBits, writeDataReg, writeStrbReg).asUInt.resized
      }
      is(U(ControlRegisters.BlockDimY >> 2, config.controlAddressWidth - 2 bits)) {
        blockDimYReg := applyWriteMask(blockDimYReg.resize(config.dataWidth).asBits, writeDataReg, writeStrbReg).asUInt.resized
      }
      is(U(ControlRegisters.BlockDimZ >> 2, config.controlAddressWidth - 2 bits)) {
        blockDimZReg := applyWriteMask(blockDimZReg.resize(config.dataWidth).asBits, writeDataReg, writeStrbReg).asUInt.resized
      }
      is(U(ControlRegisters.ArgBase >> 2, config.controlAddressWidth - 2 bits)) {
        argBaseReg := applyWriteMask(argBaseReg.asBits, writeDataReg, writeStrbReg).asUInt
      }
      is(U(ControlRegisters.SharedBytes >> 2, config.controlAddressWidth - 2 bits)) {
        sharedBytesReg := applyWriteMask(sharedBytesReg.resize(config.dataWidth).asBits, writeDataReg, writeStrbReg).asUInt.resized
      }
      default {
        writeRespReg := B"2'b11"
      }
    }
  }

  when(io.axi.b.fire) {
    writeRspValid := False
  }

  when(io.axi.ar.fire) {
    readRspValid := True
    readRespReg := B"2'b00"
    readDataReg := B(0, config.dataWidth bits)

    switch(io.axi.ar.addr(config.controlAddressWidth - 1 downto 2)) {
      is(U(ControlRegisters.Control >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := B(0, config.dataWidth bits)
      }
      is(U(ControlRegisters.Status >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := B(0, config.dataWidth bits)
        readDataReg(0) := io.executionStatus.busy
        readDataReg(1) := io.executionStatus.done
        readDataReg(2) := io.executionStatus.fault
      }
      is(U(ControlRegisters.EntryPc >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := entryPcReg.asBits
      }
      is(U(ControlRegisters.GridDimX >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := gridDimXReg.asBits
      }
      is(U(ControlRegisters.GridDimY >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := gridDimYReg.asBits
      }
      is(U(ControlRegisters.GridDimZ >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := gridDimZReg.asBits
      }
      is(U(ControlRegisters.BlockDimX >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := blockDimXReg.resize(config.dataWidth).asBits
      }
      is(U(ControlRegisters.BlockDimY >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := blockDimYReg.resize(config.dataWidth).asBits
      }
      is(U(ControlRegisters.BlockDimZ >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := blockDimZReg.resize(config.dataWidth).asBits
      }
      is(U(ControlRegisters.ArgBase >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := argBaseReg.asBits
      }
      is(U(ControlRegisters.SharedBytes >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := sharedBytesReg.resize(config.dataWidth).asBits
      }
      is(U(ControlRegisters.FaultPc >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := io.executionStatus.faultPc.asBits
      }
      is(U(ControlRegisters.FaultCode >> 2, config.controlAddressWidth - 2 bits)) {
        readDataReg := io.executionStatus.faultCode.resize(config.dataWidth).asBits
      }
      default {
        readRespReg := B"2'b11"
      }
    }
  }

  when(io.axi.r.fire) {
    readRspValid := False
  }
}
