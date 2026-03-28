package spinalgpu

import spinal.core._

class DecodeUnit(config: SmConfig) extends Component {
  val io = new Bundle {
    val instruction = in(Bits(config.instructionWidth bits))
    val decoded = out(DecodedInstruction(config))
  }

  private val opcode = io.instruction(31 downto 24)
  private val opcodeUInt = opcode.asUInt
  private val reg = io.instruction(23 downto 19).asUInt
  private val rs0 = io.instruction(18 downto 14).asUInt
  private val rs1 = io.instruction(13 downto 9).asUInt
  private val rs2 = io.instruction(8 downto 4).asUInt
  private val imm = io.instruction(13 downto 0).asSInt.resize(config.dataWidth)

  io.decoded.valid := True
  io.decoded.illegal := False
  io.decoded.opcode := opcode
  io.decoded.target := ExecutionUnitKind.CONTROL
  io.decoded.rd := reg
  io.decoded.rs0 := rs0
  io.decoded.rs1 := rs1
  io.decoded.rs2 := rs2
  io.decoded.immediate := imm
  io.decoded.specialRegister := rs0.resized
  io.decoded.addressSpace := AddressSpaceKind.GLOBAL
  io.decoded.writesRd := False
  io.decoded.usesRs0 := False
  io.decoded.usesRs1 := False
  io.decoded.usesRs2 := False
  io.decoded.isStore := False
  io.decoded.isLoad := False
  io.decoded.isBranch := False
  io.decoded.branchOnZero := False
  io.decoded.isExit := False
  io.decoded.isTrap := False
  io.decoded.isS2r := False

  switch(opcode) {
    is(B(Opcode.NOP, 8 bits)) {
    }
    is(B(Opcode.MOV, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.CUDA
      io.decoded.writesRd := True
      io.decoded.usesRs0 := True
    }
    is(B(Opcode.MOVI, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.CUDA
      io.decoded.writesRd := True
    }
    is(B(Opcode.S2R, 8 bits)) {
      io.decoded.isS2r := True
      io.decoded.writesRd := True
    }
    is(B(Opcode.ADD, 8 bits), B(Opcode.SUB, 8 bits), B(Opcode.MULLO, 8 bits), B(Opcode.AND, 8 bits), B(Opcode.OR, 8 bits),
      B(Opcode.XOR, 8 bits), B(Opcode.SHL, 8 bits), B(Opcode.SHR, 8 bits), B(Opcode.SETEQ, 8 bits), B(Opcode.SETLT, 8 bits),
      B(Opcode.SETLTS, 8 bits), B(Opcode.FADD, 8 bits), B(Opcode.FMUL, 8 bits), B(Opcode.FSUB, 8 bits),
      B(Opcode.FSETEQ, 8 bits), B(Opcode.FSETLT, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.CUDA
      io.decoded.writesRd := True
      io.decoded.usesRs0 := True
      io.decoded.usesRs1 := True
    }
    is(B(Opcode.FNEG, 8 bits), B(Opcode.FABS, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.CUDA
      io.decoded.writesRd := True
      io.decoded.usesRs0 := True
    }
    is(B(Opcode.FFMA, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.CUDA
      io.decoded.writesRd := True
      io.decoded.usesRs0 := True
      io.decoded.usesRs1 := True
      io.decoded.usesRs2 := True
    }
    is(B(Opcode.SEL, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.CUDA
      io.decoded.writesRd := True
      io.decoded.usesRs0 := True
      io.decoded.usesRs1 := True
      io.decoded.usesRs2 := True
    }
    is(B(Opcode.ADDI, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.CUDA
      io.decoded.writesRd := True
      io.decoded.usesRs0 := True
    }
    is(B(Opcode.LDG, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.LSU
      io.decoded.addressSpace := AddressSpaceKind.GLOBAL
      io.decoded.writesRd := True
      io.decoded.usesRs0 := True
      io.decoded.isLoad := True
    }
    is(B(Opcode.STG, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.LSU
      io.decoded.addressSpace := AddressSpaceKind.GLOBAL
      io.decoded.usesRs0 := True
      io.decoded.isStore := True
    }
    is(B(Opcode.LDS, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.LSU
      io.decoded.addressSpace := AddressSpaceKind.SHARED
      io.decoded.writesRd := True
      io.decoded.usesRs0 := True
      io.decoded.isLoad := True
    }
    is(B(Opcode.STS, 8 bits)) {
      io.decoded.target := ExecutionUnitKind.LSU
      io.decoded.addressSpace := AddressSpaceKind.SHARED
      io.decoded.usesRs0 := True
      io.decoded.isStore := True
    }
    is(B(Opcode.BRA, 8 bits)) {
      io.decoded.isBranch := True
    }
    is(B(Opcode.BRZ, 8 bits)) {
      io.decoded.isBranch := True
      io.decoded.branchOnZero := True
      io.decoded.usesRs0 := True
      io.decoded.rs0 := reg
    }
    is(B(Opcode.BRNZ, 8 bits)) {
      io.decoded.isBranch := True
      io.decoded.usesRs0 := True
      io.decoded.rs0 := reg
    }
    is(B(Opcode.EXIT, 8 bits)) {
      io.decoded.isExit := True
    }
    is(B(Opcode.TRAP, 8 bits)) {
      io.decoded.isTrap := True
    }
    default {
      when(opcodeUInt >= Opcode.sfuBase && opcodeUInt <= Opcode.sfuLast) {
        io.decoded.target := ExecutionUnitKind.SFU
        io.decoded.writesRd := True
        io.decoded.usesRs0 := True
      } elsewhen (opcodeUInt >= Opcode.tensorBase && opcodeUInt <= Opcode.tensorLast) {
        io.decoded.target := ExecutionUnitKind.TENSOR
        io.decoded.writesRd := True
        io.decoded.usesRs0 := True
        io.decoded.usesRs1 := True
      } otherwise {
        io.decoded.illegal := True
      }
    }
  }
}
