package spinalgpu

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.BuildTcgen05KernelCorpus
import spinalgpu.toolchain.KernelBinaryIO
import spinalgpu.toolchain.PtxAssembler

class Tcgen05FrontendSpec extends AnyFunSuite with Matchers {
  test("encodes, decodes, and disassembles the narrow tcgen05 opcode slice") {
    val ld = Isa.encodeRrrr(Opcode.TCGEN05_LD_32X32B_X2, rd = 3, rs0 = 4, rs1 = 0, rs2 = 0)
    val st = Isa.encodeRrrr(Opcode.TCGEN05_ST_32X32B_X2, rd = 0, rs0 = 5, rs1 = 6, rs2 = 0)
    val waitLd = Isa.encodeRrrr(Opcode.TCGEN05_WAIT_LD, rd = 0, rs0 = 0, rs1 = 0, rs2 = 0)
    val waitSt = Isa.encodeRrrr(Opcode.TCGEN05_WAIT_ST, rd = 0, rs0 = 0, rs1 = 0, rs2 = 0)
    val mma = Isa.encodeRrrr(Opcode.TCGEN05_MMA_CTA1_F16, rd = 7, rs0 = 8, rs1 = 10, rs2 = 12)
    val commit = Isa.encodeRrrr(Opcode.TCGEN05_COMMIT_CTA1, rd = 0, rs0 = 0, rs1 = 0, rs2 = 0)

    Isa.decodeWord(ld).opcode shouldBe Opcode.TCGEN05_LD_32X32B_X2
    Isa.decodeWord(st).opcode shouldBe Opcode.TCGEN05_ST_32X32B_X2
    Isa.decodeWord(waitLd).opcode shouldBe Opcode.TCGEN05_WAIT_LD
    Isa.decodeWord(waitSt).opcode shouldBe Opcode.TCGEN05_WAIT_ST
    Isa.decodeWord(mma).opcode shouldBe Opcode.TCGEN05_MMA_CTA1_F16
    Isa.decodeWord(commit).opcode shouldBe Opcode.TCGEN05_COMMIT_CTA1

    Isa.disassemble(ld) shouldBe "tcgen05_ld_x2 r3, r4, r0, r0"
    Isa.disassemble(st) shouldBe "tcgen05_st_x2 r0, r5, r6, r0"
    Isa.disassemble(waitLd) shouldBe "tcgen05_wait_ld r0, r0, r0, r0"
    Isa.disassemble(waitSt) shouldBe "tcgen05_wait_st r0, r0, r0, r0"
    Isa.disassemble(mma) shouldBe "tcgen05_mma_cta1_f16 r7, r8, r10, r12"
    Isa.disassemble(commit) shouldBe "tcgen05_commit_cta1 r0, r0, r0, r0"
  }

  test("assembles the supported tcgen05 PTX surface into fixed-width tensor opcodes") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry tcgen05_ops()
        |{
        |    .reg .u32 %r<9>;
        |
        |    tcgen05.ld.sync.aligned.32x32b.x2.b32 {%r0, %r1}, [%r2];
        |    tcgen05.wait::ld.sync.aligned;
        |    tcgen05.st.sync.aligned.32x32b.x2.b32 [%r2], {%r0, %r1};
        |    tcgen05.wait::st.sync.aligned;
        |    tcgen05.mma.cta_group::1.kind::f16 [%r2], {%r3, %r4}, {%r5, %r6}, {%r7, %r8};
        |    tcgen05.commit.cta_group::1.sync.aligned;
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeRrrr(Opcode.TCGEN05_LD_32X32B_X2, rd = 1, rs0 = 3, rs1 = 0, rs2 = 0),
      Isa.encodeRrrr(Opcode.TCGEN05_WAIT_LD, rd = 0, rs0 = 0, rs1 = 0, rs2 = 0),
      Isa.encodeRrrr(Opcode.TCGEN05_ST_32X32B_X2, rd = 0, rs0 = 3, rs1 = 1, rs2 = 0),
      Isa.encodeRrrr(Opcode.TCGEN05_WAIT_ST, rd = 0, rs0 = 0, rs1 = 0, rs2 = 0),
      Isa.encodeRrrr(Opcode.TCGEN05_MMA_CTA1_F16, rd = 3, rs0 = 4, rs1 = 6, rs2 = 8),
      Isa.encodeRrrr(Opcode.TCGEN05_COMMIT_CTA1, rd = 0, rs0 = 0, rs1 = 0, rs2 = 0),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("rejects malformed tcgen05 tuples and unsupported tcgen05 variants") {
    an[IllegalArgumentException] shouldBe thrownBy {
      PtxAssembler.assemble(
        """.version 8.0
          |.target spinalgpu
          |.address_size 32
          |
          |.visible .entry bad()
          |{
          |    .reg .u32 %r<4>;
          |
          |    tcgen05.ld.sync.aligned.32x32b.x2.b32 {%r0, %r2}, [%r3];
          |    ret;
          |}
          |""".stripMargin
      )
    }

    an[IllegalArgumentException] shouldBe thrownBy {
      PtxAssembler.assemble(
        """.version 8.0
          |.target spinalgpu
          |.address_size 32
          |
          |.visible .entry bad()
          |{
          |    .reg .u32 %r<9>;
          |
          |    tcgen05.mma.cta_group::2.kind::f16 [%r0], {%r1, %r2}, {%r3, %r4}, {%r5, %r6};
          |    ret;
          |}
          |""".stripMargin
      )
    }
  }

  test("builds the tcgen05-only kernel corpus") {
    val outputRoot = Files.createTempDirectory("tcgen05-corpus")
    val paths = BuildTcgen05KernelCorpus.buildAll(outputRoot)

    paths should not be empty
    paths.foreach(path => Files.exists(path) shouldBe true)
    KernelBinaryIO.readWords(paths.head) should not be empty
  }
}
