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

  test("lowers scalar integer bitwise ops, signed compare, and selp.u32") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry int_scalar()
        |{
        |    .reg .u32 %r<5>;
        |    .pred %p<1>;
        |
        |    mov.u32 %r0, 255;
        |    mov.u32 %r1, 3855;
        |    and.b32 %r2, %r0, %r1;
        |    or.b32 %r3, %r2, 7;
        |    xor.b32 %r4, %r3, %r0;
        |    shr.b32 %r2, %r4, 4;
        |    setp.lt.s32 %p0, %r2, %r3;
        |    selp.u32 %r4, %r2, %r3, %p0;
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeRri(Opcode.MOVI, rd = 1, rs0 = 0, immediate = 255),
      Isa.encodeRri(Opcode.MOVI, rd = 2, rs0 = 0, immediate = 3855),
      Isa.encodeRrr(Opcode.AND, rd = 3, rs0 = 1, rs1 = 2),
      Isa.encodeRri(Opcode.MOVI, rd = 7, rs0 = 0, immediate = 7),
      Isa.encodeRrr(Opcode.OR, rd = 4, rs0 = 3, rs1 = 7),
      Isa.encodeRrr(Opcode.XOR, rd = 5, rs0 = 4, rs1 = 1),
      Isa.encodeRri(Opcode.MOVI, rd = 7, rs0 = 0, immediate = 4),
      Isa.encodeRrr(Opcode.SHR, rd = 3, rs0 = 5, rs1 = 7),
      Isa.encodeRrr(Opcode.SETLTS, rd = 6, rs0 = 3, rs1 = 4),
      Isa.encodeRrrr(Opcode.SEL, rd = 5, rs0 = 3, rs1 = 4, rs2 = 6),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("lowers scalar FP compare/select and unary FP ops") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry fp_scalar()
        |{
        |    .reg .f32 %f<5>;
        |    .pred %p<1>;
        |
        |    mov.f32 %f0, 0f00000000;
        |    mov.f32 %f1, %f0;
        |    neg.f32 %f2, %f1;
        |    abs.f32 %f3, %f2;
        |    sub.f32 %f4, %f3, %f0;
        |    setp.ge.f32 %p0, %f4, %f1;
        |    selp.f32 %f4, %f3, %f0, %p0;
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeRri(Opcode.MOVI, rd = 1, rs0 = 0, immediate = 0),
      Isa.encodeRrr(Opcode.MOV, rd = 2, rs0 = 1, rs1 = 0),
      Isa.encodeRrr(Opcode.FNEG, rd = 3, rs0 = 2, rs1 = 0),
      Isa.encodeRrr(Opcode.FABS, rd = 4, rs0 = 3, rs1 = 0),
      Isa.encodeRrr(Opcode.FSUB, rd = 5, rs0 = 4, rs1 = 1),
      Isa.encodeRrr(Opcode.FSETLT, rd = 6, rs0 = 2, rs1 = 5),
      Isa.encodeRrr(Opcode.FSETEQ, rd = 7, rs0 = 5, rs1 = 2),
      Isa.encodeRrr(Opcode.OR, rd = 6, rs0 = 6, rs1 = 7),
      Isa.encodeRrrr(Opcode.SEL, rd = 5, rs0 = 4, rs1 = 1, rs2 = 6),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("lowers min/max convenience ops and mad.lo.u32 through existing scalar datapaths") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry lowered_helpers()
        |{
        |    .reg .u32 %r<4>;
        |    .reg .f32 %f<3>;
        |
        |    mov.u32 %r0, 3;
        |    mov.u32 %r1, 5;
        |    mad.lo.u32 %r2, %r0, %r1, %r1;
        |    min.s32 %r3, %r2, %r1;
        |    mov.f32 %f0, 0f00000000;
        |    mov.f32 %f1, %f0;
        |    max.f32 %f2, %f0, %f1;
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeRri(Opcode.MOVI, rd = 1, rs0 = 0, immediate = 3),
      Isa.encodeRri(Opcode.MOVI, rd = 2, rs0 = 0, immediate = 5),
      Isa.encodeRrr(Opcode.MULLO, rd = 8, rs0 = 1, rs1 = 2),
      Isa.encodeRrr(Opcode.ADD, rd = 3, rs0 = 8, rs1 = 2),
      Isa.encodeRrr(Opcode.SETLTS, rd = 8, rs0 = 3, rs1 = 2),
      Isa.encodeRrrr(Opcode.SEL, rd = 4, rs0 = 3, rs1 = 2, rs2 = 8),
      Isa.encodeRri(Opcode.MOVI, rd = 5, rs0 = 0, immediate = 0),
      Isa.encodeRrr(Opcode.MOV, rd = 6, rs0 = 5, rs1 = 0),
      Isa.encodeRrr(Opcode.FSETLT, rd = 8, rs0 = 5, rs1 = 6),
      Isa.encodeRrrr(Opcode.SEL, rd = 7, rs0 = 6, rs1 = 5, rs2 = 8),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("lowers v2.f32 tuple move and global-memory operations into ordered scalar instructions") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry vector_v2(
        |    .param .u32 ptr_param
        |)
        |{
        |    .reg .u32 %r<1>;
        |    .reg .f32 %f<4>;
        |
        |    ld.param.u32 %r0, [ptr_param];
        |    ld.global.v2.f32 {%f0, %f1}, [%r0];
        |    mov.v2.f32 {%f2, %f3}, {%f0, %f1};
        |    st.global.v2.f32 [%r0], {%f2, %f3};
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeSys(Opcode.S2R, rd = 6, specialRegister = SpecialRegisterKind.ArgBase),
      Isa.encodeMem(Opcode.LDG, reg = 1, base = 6, offset = 0),
      Isa.encodeMem(Opcode.LDG, reg = 2, base = 1, offset = 0),
      Isa.encodeMem(Opcode.LDG, reg = 3, base = 1, offset = 4),
      Isa.encodeRrr(Opcode.MOV, rd = 4, rs0 = 2, rs1 = 0),
      Isa.encodeRrr(Opcode.MOV, rd = 5, rs0 = 3, rs1 = 0),
      Isa.encodeMem(Opcode.STG, reg = 4, base = 1, offset = 0),
      Isa.encodeMem(Opcode.STG, reg = 5, base = 1, offset = 4),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("lowers v4.f32 tuple move and global-memory operations into ordered scalar instructions") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry vector_v4(
        |    .param .u32 ptr_param
        |)
        |{
        |    .reg .u32 %r<1>;
        |    .reg .f32 %f<8>;
        |
        |    ld.param.u32 %r0, [ptr_param];
        |    ld.global.v4.f32 {%f0, %f1, %f2, %f3}, [%r0 + 16];
        |    mov.v4.f32 {%f4, %f5, %f6, %f7}, {%f0, %f1, %f2, %f3};
        |    st.global.v4.f32 [%r0 + 32], {%f4, %f5, %f6, %f7};
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeSys(Opcode.S2R, rd = 10, specialRegister = SpecialRegisterKind.ArgBase),
      Isa.encodeMem(Opcode.LDG, reg = 1, base = 10, offset = 0),
      Isa.encodeMem(Opcode.LDG, reg = 2, base = 1, offset = 16),
      Isa.encodeMem(Opcode.LDG, reg = 3, base = 1, offset = 20),
      Isa.encodeMem(Opcode.LDG, reg = 4, base = 1, offset = 24),
      Isa.encodeMem(Opcode.LDG, reg = 5, base = 1, offset = 28),
      Isa.encodeRrr(Opcode.MOV, rd = 6, rs0 = 2, rs1 = 0),
      Isa.encodeRrr(Opcode.MOV, rd = 7, rs0 = 3, rs1 = 0),
      Isa.encodeRrr(Opcode.MOV, rd = 8, rs0 = 4, rs1 = 0),
      Isa.encodeRrr(Opcode.MOV, rd = 9, rs0 = 5, rs1 = 0),
      Isa.encodeMem(Opcode.STG, reg = 6, base = 1, offset = 32),
      Isa.encodeMem(Opcode.STG, reg = 7, base = 1, offset = 36),
      Isa.encodeMem(Opcode.STG, reg = 8, base = 1, offset = 40),
      Isa.encodeMem(Opcode.STG, reg = 9, base = 1, offset = 44),
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

  test("allocates low-precision register classes from the shared pool and lowers FP16 and FP8 instructions") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry low_precision(
        |    .param .u32 ptr_param
        |)
        |{
        |    .reg .u32 %r<1>;
        |    .reg .f32 %f<1>;
        |    .reg .f16 %h<2>;
        |    .reg .f16x2 %x<2>;
        |    .reg .b16 %b<1>;
        |
        |    ld.param.u32 %r0, [ptr_param];
        |    ld.global.f16 %h0, [%r0];
        |    ld.global.f16x2 %x0, [%r0 + 4];
        |    ld.global.b16 %b0, [%r0 + 8];
        |    cvt.f32.f16 %f0, %h0;
        |    cvt.rn.f16.f32 %h1, %f0;
        |    add.rn.f16x2 %x1, %x0, %x0;
        |    cvt.rn.f16x2.e4m3x2 %x0, %b0;
        |    cvt.satfinite.e4m3x2.f16x2 %b0, %x1;
        |    st.global.f16 [%r0], %h1;
        |    st.global.f16x2 [%r0 + 4], %x1;
        |    st.global.b16 [%r0 + 8], %b0;
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeSys(Opcode.S2R, rd = 8, specialRegister = SpecialRegisterKind.ArgBase),
      Isa.encodeMem(Opcode.LDG, reg = 1, base = 8, offset = 0),
      Isa.encodeMem(Opcode.LDG16, reg = 3, base = 1, offset = 0),
      Isa.encodeMem(Opcode.LDG, reg = 5, base = 1, offset = 4),
      Isa.encodeMem(Opcode.LDG16, reg = 7, base = 1, offset = 8),
      Isa.encodeRrr(Opcode.CVTF32F16, rd = 2, rs0 = 3, rs1 = 0),
      Isa.encodeRrr(Opcode.CVTF16F32, rd = 4, rs0 = 2, rs1 = 0),
      Isa.encodeRrr(Opcode.HADD2, rd = 6, rs0 = 5, rs1 = 5),
      Isa.encodeRrr(Opcode.CVTF16X2E4M3X2, rd = 5, rs0 = 7, rs1 = 0),
      Isa.encodeRrr(Opcode.CVTE4M3X2F16X2, rd = 7, rs0 = 6, rs1 = 0),
      Isa.encodeMem(Opcode.STG16, reg = 4, base = 1, offset = 0),
      Isa.encodeMem(Opcode.STG, reg = 6, base = 1, offset = 4),
      Isa.encodeMem(Opcode.STG16, reg = 7, base = 1, offset = 8),
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

  test("lowers the minimal tensor-core PTX surface into single tensor opcodes") {
    val program = PtxAssembler.assemble(
      """.version 8.0
        |.target spinalgpu
        |.address_size 32
        |
        |.visible .entry tensor_ops()
        |{
        |    .reg .u32 %r<3>;
        |    .reg .f16x2 %x<8>;
        |
        |    ldmatrix.sync.aligned.m8n8.x4.shared::cta.b16 {%x0, %x1, %x2, %x3}, [%r0];
        |    ldmatrix.sync.aligned.m8n8.x2.trans.shared::cta.b16 {%x4, %x5}, [%r1];
        |    mma.sync.aligned.m16n8k16.row.col.f16.f16.f16.f16 {%x6, %x7}, {%x0, %x1, %x2, %x3}, {%x4, %x5}, {%x6, %x7};
        |    stmatrix.sync.aligned.m8n8.x2.shared::cta.b16 [%r2], {%x6, %x7};
        |    ret;
        |}
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeRrrr(Opcode.LDMATRIX_X4, rd = 4, rs0 = 1, rs1 = 0, rs2 = 0),
      Isa.encodeRrrr(Opcode.LDMATRIX_X2_TRANS, rd = 8, rs0 = 2, rs1 = 0, rs2 = 0),
      Isa.encodeRrrr(Opcode.MMA_SYNC_F16_F16_F16_F16, rd = 10, rs0 = 4, rs1 = 8, rs2 = 10),
      Isa.encodeRrrr(Opcode.STMATRIX_X2, rd = 0, rs0 = 3, rs1 = 10, rs2 = 0),
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
          |    .reg .f32 %f<4>;
          |
          |    mov.v2.f32 {%f0}, {%f1, %f2};
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
          |    .reg .u32 %r<1>;
          |    .reg .f16x2 %x<5>;
          |
          |    ldmatrix.sync.aligned.m8n8.x4.shared::cta.b16 {%x0, %x2, %x3, %x4}, [%r0];
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
          |    .reg .u32 %r<2>;
          |    .reg .f16 %h<2>;
          |    .reg .f16x2 %x<4>;
          |
          |    mma.sync.aligned.m16n8k16.row.col.f16.f16.f16.f16 {%x0, %x1}, {%x0, %x1, %x2, %x3}, {%h0, %h1}, {%x0, %x1};
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
          |    .reg .u32 %r<1>;
          |    .reg .f16x2 %x<2>;
          |
          |    stmatrix.sync.aligned.m8n8.x2.shared::cta.b16 [%r0 + 16], {%x0, %x1};
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
          |    .reg .u32 %r<1>;
          |    .reg .f16 %h<1>;
          |
          |    ld.shared.f16 %h0, [%r0];
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
          |    .reg .u32 %r<2>;
          |    .reg .f32 %f<3>;
          |
          |    ld.global.v4.f32 {%f0, %f1, %f2, %r0}, [%r1];
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
          |    .reg .u32 %r<2>;
          |
          |    ld.global.v2.u32 {%r0, %r1}, [%r0];
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
          |    .reg .f32 %f<2>;
          |
          |    mov.v2.f32 %f0, {%f0, %f1};
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
          |    .reg .f32 %f<2>;
          |    .pred %p<1>;
          |
          |    setp.ltu.f32 %p0, %f0, %f1;
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
