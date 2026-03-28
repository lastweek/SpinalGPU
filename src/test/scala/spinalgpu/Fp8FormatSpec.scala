package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core._
import spinal.core.sim._

private class Fp8FormatHarness extends Component {
  val io = new Bundle {
    val fp8Value = in Bits(8 bits)
    val carrier = in Bits(16 bits)
    val halfValue = in Bits(16 bits)
    val half2Value = in Bits(32 bits)
    val e4ToF16 = out Bits(16 bits)
    val e5ToF16 = out Bits(16 bits)
    val e4x2ToF16x2 = out Bits(32 bits)
    val e5x2ToF16x2 = out Bits(32 bits)
    val f16ToE4 = out Bits(8 bits)
    val f16ToE5 = out Bits(8 bits)
    val f16x2ToE4x2 = out Bits(16 bits)
    val f16x2ToE5x2 = out Bits(16 bits)
  }

  io.e4ToF16 := Fp8Format.e4m3ToF16(io.fp8Value)
  io.e5ToF16 := Fp8Format.e5m2ToF16(io.fp8Value)
  io.e4x2ToF16x2 := Fp8Format.e4m3x2ToF16x2(io.carrier)
  io.e5x2ToF16x2 := Fp8Format.e5m2x2ToF16x2(io.carrier)
  io.f16ToE4 := Fp8Format.f16ToE4m3SatFinite(io.halfValue)
  io.f16ToE5 := Fp8Format.f16ToE5m2SatFinite(io.halfValue)
  io.f16x2ToE4x2 := Fp8Format.f16x2ToE4m3x2SatFinite(io.half2Value)
  io.f16x2ToE5x2 := Fp8Format.f16x2ToE5m2x2SatFinite(io.half2Value)
}

class Fp8FormatSpec extends AnyFunSuite with Matchers {
  private def u32(value: Int): BigInt = BigInt(value.toLong & 0xFFFFFFFFL)
  private def u16(value: Int): BigInt = BigInt(value & 0xFFFF)

  test("converts scalar fp8 formats to f16 reference bits") {
    SimConfig.withVerilator.compile(new Fp8FormatHarness).doSim { dut =>
      dut.io.fp8Value #= LowPrecisionCodec.floatToE4m3BitsSatFinite(1.5f)
      dut.io.carrier #= 0
      dut.io.halfValue #= LowPrecisionCodec.floatToHalfBits(1.5f)
      dut.io.half2Value #= 0
      sleep(1)

      dut.io.e4ToF16.toBigInt shouldBe BigInt(LowPrecisionCodec.floatToHalfBits(1.5f))
      dut.io.f16ToE4.toBigInt shouldBe BigInt(LowPrecisionCodec.floatToE4m3BitsSatFinite(1.5f))
    }
  }

  test("converts packed fp8 carriers and satfinite narrowing against the reference codec") {
    SimConfig.withVerilator.compile(new Fp8FormatHarness).doSim { dut =>
      val carrier = LowPrecisionCodec.packFp8x2(
        LowPrecisionCodec.floatToE5m2BitsSatFinite(1.0f),
        LowPrecisionCodec.floatToE5m2BitsSatFinite(-0.5f)
      )
      val half2 = LowPrecisionCodec.packHalf2(
        LowPrecisionCodec.floatToHalfBits(1.0f),
        LowPrecisionCodec.floatToHalfBits(-0.5f)
      )

      dut.io.fp8Value #= LowPrecisionCodec.floatToE5m2BitsSatFinite(-0.5f)
      dut.io.carrier #= carrier
      dut.io.halfValue #= LowPrecisionCodec.floatToHalfBits(-0.5f)
      dut.io.half2Value #= u32(half2)
      sleep(1)

      dut.io.e5ToF16.toBigInt shouldBe BigInt(LowPrecisionCodec.floatToHalfBits(-0.5f))
      dut.io.e5x2ToF16x2.toBigInt shouldBe u32(half2)
      dut.io.f16ToE5.toBigInt shouldBe BigInt(LowPrecisionCodec.floatToE5m2BitsSatFinite(-0.5f))
      dut.io.f16x2ToE5x2.toBigInt shouldBe u16(carrier)
      dut.io.f16x2ToE4x2.toBigInt shouldBe u16(
        LowPrecisionCodec.packFp8x2(
          LowPrecisionCodec.floatToE4m3BitsSatFinite(1.0f),
          LowPrecisionCodec.floatToE4m3BitsSatFinite(-0.5f)
        )
      )
    }
  }
}
