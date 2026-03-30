package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class SpecialFunctionUnitSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 8,
    subSmCount = 1,
    residentWarpsPerSubSm = 1,
    subSmIssueWidth = 2,
    sharedMemoryBytes = 256,
    sfuLatency = 3
  )
  private val subwarpSliceCount = config.warpSize / config.cudaLaneCount
  private lazy val compiled = SimConfig.withVerilator.compile(new SpecialFunctionUnit(config))

  private def u32(value: Int): BigInt = BigInt(value.toLong & 0xFFFFFFFFL)

  private def initDefaults(dut: SpecialFunctionUnit): Unit = {
    dut.io.issue.valid #= false
    dut.io.response.ready #= false
    dut.io.issue.payload.warpId #= 0
    dut.io.issue.payload.opcode #= Opcode.FRCP
    dut.io.issue.payload.activeMask #= 0
    for (lane <- 0 until config.warpSize) {
      dut.io.issue.payload.operand(lane) #= 0
    }
  }

  private def waitUntil(timeoutCycles: Int = 128)(condition: => Boolean)(implicit dut: SpecialFunctionUnit): Unit = {
    var remaining = timeoutCycles
    while (!condition && remaining > 0) {
      dut.clockDomain.waitSampling()
      remaining -= 1
    }
    condition shouldBe true
  }

  private def issue(dut: SpecialFunctionUnit, opcode: Int, operands: Seq[Int], activeMask: Int = 0xFF): Unit = {
    dut.io.issue.valid #= true
    dut.io.issue.payload.warpId #= 0
    dut.io.issue.payload.opcode #= opcode
    dut.io.issue.payload.activeMask #= activeMask
    for (lane <- 0 until config.warpSize) {
      val operand = if (lane < operands.length) operands(lane) else 0
      dut.io.issue.payload.operand(lane) #= u32(operand)
    }
    dut.clockDomain.waitSampling()
    dut.io.issue.valid #= false
  }

  private def collectResponse(dut: SpecialFunctionUnit)(implicit implicitDut: SpecialFunctionUnit): Seq[Int] = {
    waitUntil() { dut.io.response.valid.toBoolean }
    val values =
      (0 until config.warpSize).map { lane =>
        dut.io.response.payload.result(lane).toBigInt.toLong.toInt
      }
    dut.io.response.ready #= true
    dut.clockDomain.waitSampling()
    dut.io.response.ready #= false
    values
  }

  private def expected(opcode: Int, operands: Seq[Int], activeMask: Int): Seq[Int] =
    (0 until config.warpSize).map { lane =>
      if (((activeMask >> lane) & 0x1) != 0) SfuReference.apply(opcode, operands(lane))
      else 0
    }

  private val fp32CornerCases: Map[Int, Seq[Int]] = Map(
    Opcode.FRCP -> Seq(0x7FC00000, 0x7F800000, 0xFF800000, 0x00000000, 0x80000000, 0x3F800000, 0xC0000000, 0x00000001),
    Opcode.FSQRT -> Seq(0x7FC00000, 0x7F800000, 0xFF800000, 0x00000000, 0x80000000, 0xBF800000, 0x40800000, 0x00000001),
    Opcode.FRSQRT -> Seq(0x7FC00000, 0x7F800000, 0xFF800000, 0x00000000, 0x80000000, 0xBF800000, 0x40800000, 0x00000001),
    Opcode.FSIN -> Seq(0x7FC00000, 0x7F800000, 0xFF800000, 0x00000000, 0x80000000, SfuReference.fp32Bits((Math.PI / 6.0).toFloat), SfuReference.fp32Bits((-Math.PI / 3.0).toFloat), SfuReference.fp32Bits(100.0f)),
    Opcode.FCOS -> Seq(0x7FC00000, 0x7F800000, 0xFF800000, 0x00000000, 0x80000000, SfuReference.fp32Bits((Math.PI / 6.0).toFloat), SfuReference.fp32Bits((-Math.PI / 3.0).toFloat), SfuReference.fp32Bits(100.0f)),
    Opcode.FLG2 -> Seq(0x7FC00000, 0x7F800000, 0xFF800000, 0x00000000, 0x80000000, 0xBF800000, 0x3F800000, 0x40000000),
    Opcode.FEX2 -> Seq(0x7FC00000, 0x7F800000, 0xFF800000, 0x00000000, 0x80000000, 0x3F800000, 0xC0200000, 0x43000000),
    Opcode.FTANH -> Seq(0x7FC00000, 0x7F800000, 0xFF800000, 0x00000000, 0x80000000, SfuReference.fp32Bits(Math.scalb(1.0f, -11)), SfuReference.fp32Bits(4.0f), SfuReference.fp32Bits(-4.0f))
  )

  private val fp32FiniteVectors: Map[Int, Seq[Int]] = Map(
    Opcode.FRCP -> Seq(-8.0f, -2.0f, -0.5f, 0.25f, 0.75f, 1.5f, 2.5f, 5.0f).map(SfuReference.fp32Bits),
    Opcode.FSQRT -> Seq(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 4.0f, 16.0f).map(SfuReference.fp32Bits),
    Opcode.FRSQRT -> Seq(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 4.0f, 16.0f).map(SfuReference.fp32Bits),
    Opcode.FSIN -> Seq(-3.0f, -1.25f, -0.5f, 0.125f, 0.75f, 1.25f, 2.0f, 3.25f).map(SfuReference.fp32Bits),
    Opcode.FCOS -> Seq(-3.0f, -1.25f, -0.5f, 0.125f, 0.75f, 1.25f, 2.0f, 3.25f).map(SfuReference.fp32Bits),
    Opcode.FLG2 -> Seq(0.125f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.5f, 8.0f).map(SfuReference.fp32Bits),
    Opcode.FEX2 -> Seq(-3.5f, -1.5f, -0.75f, 0.25f, 0.75f, 1.5f, 2.5f, 5.0f).map(SfuReference.fp32Bits),
    Opcode.FTANH -> Seq(-3.5f, -1.5f, -0.75f, -0.25f, 0.25f, 0.75f, 1.5f, 3.5f).map(SfuReference.fp32Bits)
  )

  private val registerCornerCases: Map[Int, Seq[Int]] = Map(
    Opcode.HEX2 -> Seq(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity, 0.0f, -0.0f, 0.5f, -1.0f, 2.0f).map(SfuReference.halfBits),
    Opcode.HTANH -> Seq(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity, 0.0f, -0.0f, 0.5f, -1.0f, 2.0f).map(SfuReference.halfBits),
    Opcode.HEX2X2 -> Seq(
      SfuReference.packHalf2(Float.NaN, 0.0f),
      SfuReference.packHalf2(Float.PositiveInfinity, -0.0f),
      SfuReference.packHalf2(Float.NegativeInfinity, 0.5f),
      SfuReference.packHalf2(0.0f, -1.0f),
      SfuReference.packHalf2(-0.0f, 2.0f),
      SfuReference.packHalf2(0.5f, -2.0f),
      SfuReference.packHalf2(-1.0f, 1.0f),
      SfuReference.packHalf2(2.0f, -0.5f)
    ),
    Opcode.HTANHX2 -> Seq(
      SfuReference.packHalf2(Float.NaN, 0.0f),
      SfuReference.packHalf2(Float.PositiveInfinity, -0.0f),
      SfuReference.packHalf2(Float.NegativeInfinity, 0.5f),
      SfuReference.packHalf2(0.0f, -1.0f),
      SfuReference.packHalf2(-0.0f, 2.0f),
      SfuReference.packHalf2(0.5f, -2.0f),
      SfuReference.packHalf2(-1.0f, 1.0f),
      SfuReference.packHalf2(2.0f, -0.5f)
    )
  )

  private val registerFiniteVectors: Map[Int, Seq[Int]] = Map(
    Opcode.HEX2 -> Seq(-2.0f, -1.0f, -0.5f, 0.0f, 0.25f, 0.5f, 1.0f, 2.0f).map(SfuReference.halfBits),
    Opcode.HTANH -> Seq(-2.0f, -1.0f, -0.5f, 0.0f, 0.25f, 0.5f, 1.0f, 2.0f).map(SfuReference.halfBits),
    Opcode.HEX2X2 -> Seq(
      SfuReference.packHalf2(-1.0f, 0.25f),
      SfuReference.packHalf2(-0.5f, 0.5f),
      SfuReference.packHalf2(0.0f, 1.0f),
      SfuReference.packHalf2(0.25f, 2.0f),
      SfuReference.packHalf2(0.5f, -0.75f),
      SfuReference.packHalf2(1.0f, 0.125f),
      SfuReference.packHalf2(2.0f, 0.75f),
      SfuReference.packHalf2(3.0f, -0.25f)
    ),
    Opcode.HTANHX2 -> Seq(
      SfuReference.packHalf2(-1.0f, 0.25f),
      SfuReference.packHalf2(-0.5f, 0.5f),
      SfuReference.packHalf2(0.0f, 1.0f),
      SfuReference.packHalf2(0.25f, 2.0f),
      SfuReference.packHalf2(0.5f, -0.75f),
      SfuReference.packHalf2(1.0f, 0.125f),
      SfuReference.packHalf2(2.0f, 0.75f),
      SfuReference.packHalf2(3.0f, -0.25f)
    )
  )

  test("honors active masks and only responds after latency across all subwarp slices") {
    compiled.doSim { dut =>
      implicit val implicitDut: SpecialFunctionUnit = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      val operands = Seq(-8.0f, -2.0f, -0.5f, 0.25f, 0.75f, 1.5f, 2.5f, 5.0f).map(SfuReference.fp32Bits)
      val activeMask = 0x55

      issue(dut, Opcode.FEX2, operands, activeMask)

      dut.io.response.valid.toBoolean shouldBe false
      dut.clockDomain.waitSampling((config.sfuLatency * subwarpSliceCount) - 1)
      dut.io.response.valid.toBoolean shouldBe false

      val actual = collectResponse(dut)
      actual shouldBe expected(Opcode.FEX2, operands, activeMask)
    }
  }

  test("matches exact corner-case results for every unary SFU opcode") {
    compiled.doSim { dut =>
      implicit val implicitDut: SpecialFunctionUnit = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      (fp32CornerCases.toSeq ++ registerCornerCases.toSeq).foreach { case (opcode, operands) =>
        val actual = {
          issue(dut, opcode, operands)
          collectResponse(dut)
        }
        withClue(f"opcode=0x$opcode%02X ") {
          actual shouldBe expected(opcode, operands, activeMask = 0xFF)
        }
      }
    }
  }

  test("matches curated finite reference vectors for every unary SFU opcode") {
    compiled.doSim { dut =>
      implicit val implicitDut: SpecialFunctionUnit = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      (fp32FiniteVectors.toSeq ++ registerFiniteVectors.toSeq).foreach { case (opcode, operands) =>
        val actual = {
          issue(dut, opcode, operands)
          collectResponse(dut)
        }
        withClue(f"opcode=0x$opcode%02X ") {
          actual shouldBe expected(opcode, operands, activeMask = 0xFF)
        }
      }
    }
  }

  test("tanh.approx.f32 stays monotonic, odd, and saturating on curated vectors") {
    compiled.doSim { dut =>
      implicit val implicitDut: SpecialFunctionUnit = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      val operands = Seq(-8.0f, -4.0f, -1.0f, -0.25f, 0.25f, 1.0f, 4.0f, 8.0f).map(SfuReference.fp32Bits)
      issue(dut, Opcode.FTANH, operands)
      val actualBits = collectResponse(dut)
      val actual = actualBits.map(SfuReference.fp32FromBits)

      actualBits shouldBe expected(Opcode.FTANH, operands, activeMask = 0xFF)
      actual.sliding(2).foreach { pair =>
        pair(0) should be <= pair(1)
      }
      actualBits(0) shouldBe SfuReference.fp32Bits(-1.0f)
      actualBits(1) shouldBe SfuReference.fp32Bits(-1.0f)
      actualBits(6) shouldBe SfuReference.fp32Bits(1.0f)
      actualBits(7) shouldBe SfuReference.fp32Bits(1.0f)
      actualBits(2) shouldBe SfuReference.fp32Bits(-actual(5))
      actualBits(3) shouldBe SfuReference.fp32Bits(-actual(4))
    }
  }
}
