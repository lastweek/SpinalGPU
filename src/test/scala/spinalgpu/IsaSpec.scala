package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class IsaSpec extends AnyFunSuite with Matchers {
  test("encodes and decodes representative instructions for every format") {
    val rrr = Isa.encodeRrr(Opcode.ADD, rd = 3, rs0 = 1, rs1 = 2)
    val rri = Isa.encodeRri(Opcode.ADDI, rd = 4, rs0 = 5, immediate = -7)
    val mem = Isa.encodeMem(Opcode.LDG, reg = 6, base = 7, offset = 12)
    val br = Isa.encodeBr(Opcode.BRNZ, rs0 = 8, offset = -4)
    val sys = Isa.encodeSys(Opcode.S2R, rd = 9, specialRegister = SpecialRegisterKind.TidX)

    val decodedRrr = Isa.decodeWord(rrr)
    decodedRrr.format shouldBe InstructionFormat.Rrr
    decodedRrr.opcode shouldBe Opcode.ADD
    decodedRrr.reg shouldBe 3
    decodedRrr.rs0 shouldBe 1
    decodedRrr.rs1 shouldBe 2

    val decodedRri = Isa.decodeWord(rri)
    decodedRri.format shouldBe InstructionFormat.Rri
    decodedRri.opcode shouldBe Opcode.ADDI
    decodedRri.reg shouldBe 4
    decodedRri.rs0 shouldBe 5
    decodedRri.immediate shouldBe -7

    val decodedMem = Isa.decodeWord(mem)
    decodedMem.format shouldBe InstructionFormat.Mem
    decodedMem.opcode shouldBe Opcode.LDG
    decodedMem.reg shouldBe 6
    decodedMem.rs0 shouldBe 7
    decodedMem.immediate shouldBe 12

    val decodedBr = Isa.decodeWord(br)
    decodedBr.format shouldBe InstructionFormat.Br
    decodedBr.opcode shouldBe Opcode.BRNZ
    decodedBr.reg shouldBe 8
    decodedBr.immediate shouldBe -4

    val decodedSys = Isa.decodeWord(sys)
    decodedSys.format shouldBe InstructionFormat.Sys
    decodedSys.opcode shouldBe Opcode.S2R
    decodedSys.reg shouldBe 9
    decodedSys.specialRegister shouldBe SpecialRegisterKind.TidX
  }

  test("disassembler formats representative instructions") {
    Isa.disassemble(Isa.encodeRrr(Opcode.ADD, rd = 3, rs0 = 1, rs1 = 2)) shouldBe "add r3, r1, r2"
    Isa.disassemble(Isa.encodeRri(Opcode.ADDI, rd = 4, rs0 = 5, immediate = -7)) shouldBe "addi r4, r5, -7"
    Isa.disassemble(Isa.encodeMem(Opcode.LDG, reg = 6, base = 7, offset = 12)) shouldBe "ldg r6, [r7 + 12]"
    Isa.disassemble(Isa.encodeMem(Opcode.STG, reg = 6, base = 7, offset = 4)) shouldBe "stg [r7 + 4], r6"
    Isa.disassemble(Isa.encodeBr(Opcode.BRNZ, rs0 = 8, offset = -4)) shouldBe "brnz r8, -4"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 9, specialRegister = SpecialRegisterKind.TidX)) shouldBe "s2r r9, %tid.x"
    Isa.disassemble(Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)) shouldBe "exit"
  }

  test("decoder rejects unknown opcodes") {
    an[IllegalArgumentException] shouldBe thrownBy {
      Isa.decodeWord(0x7F000000)
    }
  }
}
