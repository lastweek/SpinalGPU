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
    val signedCompare = Isa.encodeRrr(Opcode.SETLTS, rd = 14, rs0 = 15, rs1 = 16)
    val select = Isa.encodeRrrr(Opcode.SEL, rd = 17, rs0 = 18, rs1 = 19, rs2 = 20)
    val fpSub = Isa.encodeRrr(Opcode.FSUB, rd = 21, rs0 = 22, rs1 = 23)
    val fpAbs = Isa.encodeRrr(Opcode.FABS, rd = 24, rs0 = 25, rs1 = 0)
    val fpNeg = Isa.encodeRrr(Opcode.FNEG, rd = 26, rs0 = 27, rs1 = 0)
    val fpSetEq = Isa.encodeRrr(Opcode.FSETEQ, rd = 28, rs0 = 29, rs1 = 30)
    val fpSetLt = Isa.encodeRrr(Opcode.FSETLT, rd = 31, rs0 = 1, rs1 = 2)

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

    val decodedSignedCompare = Isa.decodeWord(signedCompare)
    decodedSignedCompare.format shouldBe InstructionFormat.Rrr
    decodedSignedCompare.opcode shouldBe Opcode.SETLTS
    decodedSignedCompare.reg shouldBe 14

    val decodedSelect = Isa.decodeWord(select)
    decodedSelect.format shouldBe InstructionFormat.Rrrr
    decodedSelect.opcode shouldBe Opcode.SEL
    decodedSelect.reg shouldBe 17
    decodedSelect.rs2 shouldBe 20

    Isa.decodeWord(fpSub).opcode shouldBe Opcode.FSUB
    Isa.decodeWord(fpAbs).opcode shouldBe Opcode.FABS
    Isa.decodeWord(fpNeg).opcode shouldBe Opcode.FNEG
    Isa.decodeWord(fpSetEq).opcode shouldBe Opcode.FSETEQ
    Isa.decodeWord(fpSetLt).opcode shouldBe Opcode.FSETLT
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

  test("encodes, decodes, and disassembles low-precision opcodes") {
    val ldg16 = Isa.encodeMem(Opcode.LDG16, reg = 3, base = 4, offset = 2)
    val stg16 = Isa.encodeMem(Opcode.STG16, reg = 5, base = 6, offset = 10)
    val hadd = Isa.encodeRrr(Opcode.HADD, rd = 7, rs0 = 8, rs1 = 9)
    val hfma = Isa.encodeRrrr(Opcode.HFMA, rd = 10, rs0 = 11, rs1 = 12, rs2 = 13)
    val hadd2 = Isa.encodeRrr(Opcode.HADD2, rd = 14, rs0 = 15, rs1 = 16)
    val hmul2 = Isa.encodeRrr(Opcode.HMUL2, rd = 17, rs0 = 18, rs1 = 19)
    val cvtf32f16 = Isa.encodeRrr(Opcode.CVTF32F16, rd = 20, rs0 = 21, rs1 = 0)
    val cvtf16f32 = Isa.encodeRrr(Opcode.CVTF16F32, rd = 22, rs0 = 23, rs1 = 0)
    val cvtf16x2e4m3x2 = Isa.encodeRrr(Opcode.CVTF16X2E4M3X2, rd = 24, rs0 = 25, rs1 = 0)
    val cvte5m2x2f16x2 = Isa.encodeRrr(Opcode.CVTE5M2X2F16X2, rd = 26, rs0 = 27, rs1 = 0)

    Isa.decodeWord(ldg16).opcode shouldBe Opcode.LDG16
    Isa.decodeWord(stg16).opcode shouldBe Opcode.STG16
    Isa.decodeWord(hadd).opcode shouldBe Opcode.HADD
    Isa.decodeWord(hfma).opcode shouldBe Opcode.HFMA
    Isa.decodeWord(hadd2).opcode shouldBe Opcode.HADD2
    Isa.decodeWord(hmul2).opcode shouldBe Opcode.HMUL2
    Isa.decodeWord(cvtf32f16).opcode shouldBe Opcode.CVTF32F16
    Isa.decodeWord(cvtf16f32).opcode shouldBe Opcode.CVTF16F32
    Isa.decodeWord(cvtf16x2e4m3x2).opcode shouldBe Opcode.CVTF16X2E4M3X2
    Isa.decodeWord(cvte5m2x2f16x2).opcode shouldBe Opcode.CVTE5M2X2F16X2

    Isa.disassemble(ldg16) shouldBe "ldg16 r3, [r4 + 2]"
    Isa.disassemble(stg16) shouldBe "stg16 [r6 + 10], r5"
    Isa.disassemble(hadd) shouldBe "hadd r7, r8, r9"
    Isa.disassemble(hfma) shouldBe "hfma r10, r11, r12, r13"
    Isa.disassemble(hadd2) shouldBe "hadd2 r14, r15, r16"
    Isa.disassemble(hmul2) shouldBe "hmul2 r17, r18, r19"
    Isa.disassemble(cvtf32f16) shouldBe "cvtf32f16 r20, r21, r0"
    Isa.disassemble(cvtf16f32) shouldBe "cvtf16f32 r22, r23, r0"
    Isa.disassemble(cvtf16x2e4m3x2) shouldBe "cvtf16x2e4m3x2 r24, r25, r0"
    Isa.disassemble(cvte5m2x2f16x2) shouldBe "cvte5m2x2f16x2 r26, r27, r0"
  }

  test("encodes, decodes, and disassembles tensor-core opcodes") {
    val ldmatrixX4 = Isa.encodeRrrr(Opcode.LDMATRIX_X4, rd = 5, rs0 = 6, rs1 = 0, rs2 = 0)
    val ldmatrixX2Trans = Isa.encodeRrrr(Opcode.LDMATRIX_X2_TRANS, rd = 7, rs0 = 8, rs1 = 0, rs2 = 0)
    val mma = Isa.encodeRrrr(Opcode.MMA_SYNC_F16_F16_F16_F16, rd = 9, rs0 = 10, rs1 = 11, rs2 = 12)
    val stmatrix = Isa.encodeRrrr(Opcode.STMATRIX_X2, rd = 0, rs0 = 13, rs1 = 14, rs2 = 0)

    Isa.decodeWord(ldmatrixX4).format shouldBe InstructionFormat.Rrrr
    Isa.decodeWord(ldmatrixX4).opcode shouldBe Opcode.LDMATRIX_X4
    Isa.decodeWord(ldmatrixX2Trans).opcode shouldBe Opcode.LDMATRIX_X2_TRANS
    Isa.decodeWord(mma).opcode shouldBe Opcode.MMA_SYNC_F16_F16_F16_F16
    Isa.decodeWord(stmatrix).opcode shouldBe Opcode.STMATRIX_X2

    Isa.disassemble(ldmatrixX4) shouldBe "ldmatrix_x4 r5, r6, r0, r0"
    Isa.disassemble(ldmatrixX2Trans) shouldBe "ldmatrix_x2_trans r7, r8, r0, r0"
    Isa.disassemble(mma) shouldBe "mma_sync_f16 r9, r10, r11, r12"
    Isa.disassemble(stmatrix) shouldBe "stmatrix_x2 r0, r13, r14, r0"
  }

  test("encodes, decodes, and disassembles unary SFU opcodes") {
    val frcp = Isa.encodeRrr(Opcode.FRCP, rd = 1, rs0 = 2, rs1 = 0)
    val fsqrt = Isa.encodeRrr(Opcode.FSQRT, rd = 3, rs0 = 4, rs1 = 0)
    val frsqrt = Isa.encodeRrr(Opcode.FRSQRT, rd = 5, rs0 = 6, rs1 = 0)
    val fsin = Isa.encodeRrr(Opcode.FSIN, rd = 7, rs0 = 8, rs1 = 0)
    val fcos = Isa.encodeRrr(Opcode.FCOS, rd = 9, rs0 = 10, rs1 = 0)
    val flg2 = Isa.encodeRrr(Opcode.FLG2, rd = 11, rs0 = 12, rs1 = 0)
    val fex2 = Isa.encodeRrr(Opcode.FEX2, rd = 13, rs0 = 14, rs1 = 0)
    val ftanh = Isa.encodeRrr(Opcode.FTANH, rd = 15, rs0 = 16, rs1 = 0)
    val hex2 = Isa.encodeRrr(Opcode.HEX2, rd = 17, rs0 = 18, rs1 = 0)
    val htanh = Isa.encodeRrr(Opcode.HTANH, rd = 19, rs0 = 20, rs1 = 0)
    val hex2x2 = Isa.encodeRrr(Opcode.HEX2X2, rd = 21, rs0 = 22, rs1 = 0)
    val htanhx2 = Isa.encodeRrr(Opcode.HTANHX2, rd = 23, rs0 = 24, rs1 = 0)

    Isa.decodeWord(frcp).opcode shouldBe Opcode.FRCP
    Isa.decodeWord(fsqrt).opcode shouldBe Opcode.FSQRT
    Isa.decodeWord(frsqrt).opcode shouldBe Opcode.FRSQRT
    Isa.decodeWord(fsin).opcode shouldBe Opcode.FSIN
    Isa.decodeWord(fcos).opcode shouldBe Opcode.FCOS
    Isa.decodeWord(flg2).opcode shouldBe Opcode.FLG2
    Isa.decodeWord(fex2).opcode shouldBe Opcode.FEX2
    Isa.decodeWord(ftanh).opcode shouldBe Opcode.FTANH
    Isa.decodeWord(hex2).opcode shouldBe Opcode.HEX2
    Isa.decodeWord(htanh).opcode shouldBe Opcode.HTANH
    Isa.decodeWord(hex2x2).opcode shouldBe Opcode.HEX2X2
    Isa.decodeWord(htanhx2).opcode shouldBe Opcode.HTANHX2

    Isa.disassemble(frcp) shouldBe "frcp r1, r2, r0"
    Isa.disassemble(fsqrt) shouldBe "fsqrt r3, r4, r0"
    Isa.disassemble(frsqrt) shouldBe "frsqrt r5, r6, r0"
    Isa.disassemble(fsin) shouldBe "fsin r7, r8, r0"
    Isa.disassemble(fcos) shouldBe "fcos r9, r10, r0"
    Isa.disassemble(flg2) shouldBe "flg2 r11, r12, r0"
    Isa.disassemble(fex2) shouldBe "fex2 r13, r14, r0"
    Isa.disassemble(ftanh) shouldBe "ftanh r15, r16, r0"
    Isa.disassemble(hex2) shouldBe "hex2 r17, r18, r0"
    Isa.disassemble(htanh) shouldBe "htanh r19, r20, r0"
    Isa.disassemble(hex2x2) shouldBe "hex2x2 r21, r22, r0"
    Isa.disassemble(htanhx2) shouldBe "htanhx2 r23, r24, r0"
  }

  test("machine-code disassembler formats representative instructions") {
    Isa.disassemble(Isa.encodeRrr(Opcode.ADD, rd = 3, rs0 = 1, rs1 = 2)) shouldBe "add r3, r1, r2"
    Isa.disassemble(Isa.encodeRrr(Opcode.FADD, rd = 6, rs0 = 7, rs1 = 8)) shouldBe "fadd r6, r7, r8"
    Isa.disassemble(Isa.encodeRrr(Opcode.SETLTS, rd = 5, rs0 = 6, rs1 = 7)) shouldBe "setlts r5, r6, r7"
    Isa.disassemble(Isa.encodeRrr(Opcode.FSUB, rd = 8, rs0 = 9, rs1 = 10)) shouldBe "fsub r8, r9, r10"
    Isa.disassemble(Isa.encodeRrr(Opcode.FABS, rd = 11, rs0 = 12, rs1 = 0)) shouldBe "fabs r11, r12, r0"
    Isa.disassemble(Isa.encodeRrr(Opcode.FNEG, rd = 13, rs0 = 14, rs1 = 0)) shouldBe "fneg r13, r14, r0"
    Isa.disassemble(Isa.encodeRrr(Opcode.FSETEQ, rd = 15, rs0 = 16, rs1 = 17)) shouldBe "fseteq r15, r16, r17"
    Isa.disassemble(Isa.encodeRrr(Opcode.FSETLT, rd = 18, rs0 = 19, rs1 = 20)) shouldBe "fsetlt r18, r19, r20"
    Isa.disassemble(Isa.encodeRrrr(Opcode.FFMA, rd = 9, rs0 = 10, rs1 = 11, rs2 = 12)) shouldBe "ffma r9, r10, r11, r12"
    Isa.disassemble(Isa.encodeRrrr(Opcode.SEL, rd = 21, rs0 = 22, rs1 = 23, rs2 = 24)) shouldBe "sel r21, r22, r23, r24"
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
