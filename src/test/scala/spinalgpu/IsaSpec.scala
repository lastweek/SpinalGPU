package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class IsaSpec extends AnyFunSuite with Matchers {
  test("encodes and decodes representative machine instructions for every format") {
    val rrr = Isa.encodeRrr(Opcode.ADD, rd = 3, rs0 = 1, rs1 = 2)
    val rri = Isa.encodeRri(Opcode.ADDI, rd = 4, rs0 = 5, immediate = -7)
    val mem = Isa.encodeMem(Opcode.LDG, reg = 6, base = 7, offset = 12)
    val br = Isa.encodeBr(Opcode.BRNZ, rs0 = 8, offset = -4)
    val sys = Isa.encodeSys(Opcode.S2R, rd = 9, specialRegister = SpecialRegisterKind.TidX)
    val rrrr = Isa.encodeRrrr(Opcode.FFMA, rd = 10, rs0 = 11, rs1 = 12, rs2 = 13)

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

    val decodedRrrr = Isa.decodeWord(rrrr)
    decodedRrrr.format shouldBe InstructionFormat.Rrrr
    decodedRrrr.opcode shouldBe Opcode.FFMA
    decodedRrrr.reg shouldBe 10
    decodedRrrr.rs0 shouldBe 11
    decodedRrrr.rs1 shouldBe 12
    decodedRrrr.rs2 shouldBe 13
  }

  test("encodes and decodes the extended special-register set") {
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 1, specialRegister = SpecialRegisterKind.TidY)).specialRegister shouldBe
      SpecialRegisterKind.TidY
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 2, specialRegister = SpecialRegisterKind.TidZ)).specialRegister shouldBe
      SpecialRegisterKind.TidZ
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 3, specialRegister = SpecialRegisterKind.NtidY)).specialRegister shouldBe
      SpecialRegisterKind.NtidY
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 4, specialRegister = SpecialRegisterKind.NtidZ)).specialRegister shouldBe
      SpecialRegisterKind.NtidZ
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 5, specialRegister = SpecialRegisterKind.CtaidY)).specialRegister shouldBe
      SpecialRegisterKind.CtaidY
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 6, specialRegister = SpecialRegisterKind.CtaidZ)).specialRegister shouldBe
      SpecialRegisterKind.CtaidZ
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 7, specialRegister = SpecialRegisterKind.NctaidY)).specialRegister shouldBe
      SpecialRegisterKind.NctaidY
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 8, specialRegister = SpecialRegisterKind.NctaidZ)).specialRegister shouldBe
      SpecialRegisterKind.NctaidZ
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 10, specialRegister = SpecialRegisterKind.NwarpId)).specialRegister shouldBe
      SpecialRegisterKind.NwarpId
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 11, specialRegister = SpecialRegisterKind.SmId)).specialRegister shouldBe
      SpecialRegisterKind.SmId
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 12, specialRegister = SpecialRegisterKind.NsmId)).specialRegister shouldBe
      SpecialRegisterKind.NsmId
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 13, specialRegister = SpecialRegisterKind.GridIdLo)).specialRegister shouldBe
      SpecialRegisterKind.GridIdLo
    Isa.decodeWord(Isa.encodeSys(Opcode.S2R, rd = 14, specialRegister = SpecialRegisterKind.GridIdHi)).specialRegister shouldBe
      SpecialRegisterKind.GridIdHi
  }

  test("machine-code disassembler formats representative instructions") {
    Isa.disassemble(Isa.encodeRrr(Opcode.ADD, rd = 3, rs0 = 1, rs1 = 2)) shouldBe "add r3, r1, r2"
    Isa.disassemble(Isa.encodeRrr(Opcode.FADD, rd = 6, rs0 = 7, rs1 = 8)) shouldBe "fadd r6, r7, r8"
    Isa.disassemble(Isa.encodeRrrr(Opcode.FFMA, rd = 9, rs0 = 10, rs1 = 11, rs2 = 12)) shouldBe "ffma r9, r10, r11, r12"
    Isa.disassemble(Isa.encodeRri(Opcode.ADDI, rd = 4, rs0 = 5, immediate = -7)) shouldBe "addi r4, r5, -7"
    Isa.disassemble(Isa.encodeMem(Opcode.LDG, reg = 6, base = 7, offset = 12)) shouldBe "ldg r6, [r7 + 12]"
    Isa.disassemble(Isa.encodeMem(Opcode.STG, reg = 6, base = 7, offset = 4)) shouldBe "stg [r7 + 4], r6"
    Isa.disassemble(Isa.encodeBr(Opcode.BRNZ, rs0 = 8, offset = -4)) shouldBe "brnz r8, -4"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 9, specialRegister = SpecialRegisterKind.TidX)) shouldBe "s2r r9, %tid.x"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 1, specialRegister = SpecialRegisterKind.TidY)) shouldBe "s2r r1, %tid.y"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 2, specialRegister = SpecialRegisterKind.TidZ)) shouldBe "s2r r2, %tid.z"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 3, specialRegister = SpecialRegisterKind.NtidY)) shouldBe "s2r r3, %ntid.y"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 4, specialRegister = SpecialRegisterKind.NtidZ)) shouldBe "s2r r4, %ntid.z"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 10, specialRegister = SpecialRegisterKind.NwarpId)) shouldBe "s2r r10, %nwarpid"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 11, specialRegister = SpecialRegisterKind.SmId)) shouldBe "s2r r11, %smid"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 12, specialRegister = SpecialRegisterKind.NsmId)) shouldBe "s2r r12, %nsmid"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 13, specialRegister = SpecialRegisterKind.GridIdLo)) shouldBe "s2r r13, %gridid.lo"
    Isa.disassemble(Isa.encodeSys(Opcode.S2R, rd = 14, specialRegister = SpecialRegisterKind.GridIdHi)) shouldBe "s2r r14, %gridid.hi"
    Isa.disassemble(Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)) shouldBe "exit"
  }

  test("machine-code decoder rejects unknown opcodes") {
    an[IllegalArgumentException] shouldBe thrownBy {
      Isa.decodeWord(0x7F000000)
    }
  }
}
