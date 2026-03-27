package spinalgpu

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.BuildKernelCorpus
import spinalgpu.toolchain.KernelBinaryIO
import spinalgpu.toolchain.PtxAssembler

class PtxAssemblerSpec extends AnyFunSuite with Matchers {
  test("lowers entry headers and ld.param to machine words") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry load_arg(
        |    .param .u32 out_ptr
        |)
        |{
        |    .reg .u32 %r<1>;
        |
        |    ld.param.u32 %r0, [out_ptr];
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeSys(Opcode.S2R, rd = 2, specialRegister = SpecialRegisterKind.ArgBase),
      Isa.encodeMem(Opcode.LDG, reg = 1, base = 2, offset = 0),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("resolves labels and lowers predicated branches from predicate registers") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry branch_test()
        |{
        |    .reg .u32 %r<1>;
        |    .pred %p<1>;
        |
        |    mov.u32 %r0, %tid.x;
        |    setp.ne.u32 %p0, %r0, 0;
        |    @%p0 bra taken;
        |    ret;
        |taken:
        |    ret;
        |}
        |""".stripMargin
    )

    program.labels("taken") shouldBe 24
    program.words shouldBe Seq(
      Isa.encodeSys(Opcode.S2R, rd = 1, specialRegister = SpecialRegisterKind.TidX),
      Isa.encodeRrr(Opcode.SETEQ, rd = 2, rs0 = 1, rs1 = 0),
      Isa.encodeRri(Opcode.MOVI, rd = 3, rs0 = 0, immediate = 1),
      Isa.encodeRrr(Opcode.XOR, rd = 2, rs0 = 2, rs1 = 3),
      Isa.encodeBr(Opcode.BRNZ, rs0 = 2, offset = 4),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("lowers the extended 32-bit special-register reads") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry special_regs()
        |{
        |    .reg .u32 %r<3>;
        |
        |    mov.u32 %r0, %nwarpid;
        |    mov.u32 %r1, %smid;
        |    mov.u32 %r2, %nsmid;
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeSys(Opcode.S2R, rd = 1, specialRegister = SpecialRegisterKind.NwarpId),
      Isa.encodeSys(Opcode.S2R, rd = 2, specialRegister = SpecialRegisterKind.SmId),
      Isa.encodeSys(Opcode.S2R, rd = 3, specialRegister = SpecialRegisterKind.NsmId),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("lowers mov.u64 %gridid and st.global.u64 through paired 32-bit machine operations") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry grid_id_store(
        |    .param .u32 out_ptr
        |)
        |{
        |    .reg .u32 %r<1>;
        |    .reg .u64 %rd<1>;
        |
        |    ld.param.u32 %r0, [out_ptr];
        |    mov.u64 %rd0, %gridid;
        |    st.global.u64 [%r0], %rd0;
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeSys(Opcode.S2R, rd = 4, specialRegister = SpecialRegisterKind.ArgBase),
      Isa.encodeMem(Opcode.LDG, reg = 1, base = 4, offset = 0),
      Isa.encodeSys(Opcode.S2R, rd = 2, specialRegister = SpecialRegisterKind.GridIdLo),
      Isa.encodeSys(Opcode.S2R, rd = 3, specialRegister = SpecialRegisterKind.GridIdHi),
      Isa.encodeMem(Opcode.STG, reg = 2, base = 1, offset = 0),
      Isa.encodeMem(Opcode.STG, reg = 3, base = 1, offset = 4),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("allocates integer and float PTX registers from one physical pool and lowers FP32 CUDA-core instructions") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry float_ops(
        |    .param .u32 ptr_param
        |)
        |{
        |    .reg .u32 %r<3>;
        |    .reg .f32 %f<3>;
        |
        |    ld.param.u32 %r0, [ptr_param];
        |    mov.u32 %r1, %tid.y;
        |    mov.u32 %r2, %tid.z;
        |    mov.f32 %f0, 0f00000000;
        |    ld.global.f32 %f1, [%r0];
        |    add.f32 %f2, %f0, %f1;
        |    mul.f32 %f1, %f2, %f1;
        |    fma.rn.f32 %f2, %f1, %f0, %f2;
        |    st.global.f32 [%r0], %f2;
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeSys(Opcode.S2R, rd = 7, specialRegister = SpecialRegisterKind.ArgBase),
      Isa.encodeMem(Opcode.LDG, reg = 1, base = 7, offset = 0),
      Isa.encodeSys(Opcode.S2R, rd = 2, specialRegister = SpecialRegisterKind.TidY),
      Isa.encodeSys(Opcode.S2R, rd = 3, specialRegister = SpecialRegisterKind.TidZ),
      Isa.encodeRri(Opcode.MOVI, rd = 4, rs0 = 0, immediate = 0),
      Isa.encodeMem(Opcode.LDG, reg = 5, base = 1, offset = 0),
      Isa.encodeRrr(Opcode.FADD, rd = 6, rs0 = 4, rs1 = 5),
      Isa.encodeRrr(Opcode.FMUL, rd = 5, rs0 = 6, rs1 = 5),
      Isa.encodeRrrr(Opcode.FFMA, rd = 6, rs0 = 5, rs1 = 4, rs2 = 6),
      Isa.encodeMem(Opcode.STG, reg = 6, base = 1, offset = 0),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("lowers 2D thread-coordinate guards with setp.lt.u32") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry coord_guard(
        |    .param .u32 rows_param
        |)
        |{
        |    .reg .u32 %r<2>;
        |    .pred %p<1>;
        |
        |    ld.param.u32 %r0, [rows_param];
        |    mov.u32 %r1, %tid.y;
        |    setp.lt.u32 %p0, %r1, %r0;
        |    @!%p0 bra done;
        |done:
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeSys(Opcode.S2R, rd = 4, specialRegister = SpecialRegisterKind.ArgBase),
      Isa.encodeMem(Opcode.LDG, reg = 1, base = 4, offset = 0),
      Isa.encodeSys(Opcode.S2R, rd = 2, specialRegister = SpecialRegisterKind.TidY),
      Isa.encodeRrr(Opcode.SETLT, rd = 3, rs0 = 2, rs1 = 1),
      Isa.encodeBr(Opcode.BRZ, rs0 = 3, offset = 0),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("lowers shared symbols to byte offsets in shared-memory instructions") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry shared_test()
        |{
        |    .reg .u32 %r<2>;
        |    .shared .align 4 .b8 pad[4];
        |    .shared .align 4 .b8 shared_data[16];
        |
        |    ld.shared.u32 %r0, [shared_data + %r1];
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeMem(Opcode.LDS, reg = 1, base = 2, offset = 4),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("rejects unsupported PTX constructs") {
    an[IllegalArgumentException] shouldBe thrownBy {
      PtxAssembler.assemble(
        """.version 8.0
          |.target spinalgpu
          |.address_size 32
          |
          |.visible .entry bad()
          |{
          |    .const .align 4 .b8 const_data[16];
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
          |    .reg .u32 %r<1>
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
          |    call foo;
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
          |    .reg .u64 %rd<1>;
          |
          |    mov.u64 %rd0, 1;
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
          |    .reg .u64 %rd<1>;
          |
          |    add.u64 %rd0, %rd0, %rd0;
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
          |    .reg .f32 %f<1>;
          |
          |    mov.f32 %f0, 0f3f800000;
          |    ret;
          |}
          |""".stripMargin
      )
    }
  }

  test("kernel corpus builder emits raw little-endian binaries from PTX sources") {
    val outputRoot = Files.createTempDirectory("kernel-corpus")

    val outputPaths =
      try {
        BuildKernelCorpus.buildAll(outputRoot)
      } finally {
        ()
      }

    outputPaths should not be empty
    outputPaths.foreach(path => Files.exists(path) shouldBe true)

    val firstWords = KernelBinaryIO.readWords(outputPaths.head)
    firstWords should not be empty
  }
}
