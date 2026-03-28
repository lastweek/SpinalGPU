package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core._
import spinal.core.sim._

private class Fp16MathHarness extends Component {
  val io = new Bundle {
    val halfA = in Bits(16 bits)
    val halfB = in Bits(16 bits)
    val halfC = in Bits(16 bits)
    val floatIn = in Bits(32 bits)
    val packedA = in Bits(32 bits)
    val packedB = in Bits(32 bits)
    val toF32 = out Bits(32 bits)
    val fromF32 = out Bits(16 bits)
    val add = out Bits(16 bits)
    val mul = out Bits(16 bits)
    val fma = out Bits(16 bits)
    val add2 = out Bits(32 bits)
  }

  io.toF32 := Fp16Math.toFp32(io.halfA)
  io.fromF32 := Fp16Math.fromFp32(io.floatIn)
  io.add := Fp16Math.add(io.halfA, io.halfB)
  io.mul := Fp16Math.mul(io.halfA, io.halfB)
  io.fma := Fp16Math.fma(io.halfA, io.halfB, io.halfC)
  io.add2 := Fp16Math.add2(io.packedA, io.packedB)
}

class Fp16MathSpec extends AnyFunSuite with Matchers {
  private def u32(value: Int): BigInt = BigInt(value.toLong & 0xFFFFFFFFL)

  test("converts between f16 and f32 using exact reference bits") {
    SimConfig.withVerilator.compile(new Fp16MathHarness).doSim { dut =>
      dut.io.halfA #= LowPrecisionCodec.floatToHalfBits(1.5f)
      dut.io.halfB #= 0
      dut.io.halfC #= 0
      dut.io.floatIn #= ExecutionTestUtils.f32Bits(2.25f)
      dut.io.packedA #= 0
      dut.io.packedB #= 0
      sleep(1)

      dut.io.toF32.toBigInt shouldBe BigInt(ExecutionTestUtils.f32Bits(1.5f))
      dut.io.fromF32.toBigInt shouldBe BigInt(LowPrecisionCodec.floatToHalfBits(2.25f))
    }
  }

  test("computes scalar and packed f16 arithmetic against the reference codec") {
    SimConfig.withVerilator.compile(new Fp16MathHarness).doSim { dut =>
      dut.io.halfA #= LowPrecisionCodec.floatToHalfBits(1.5f)
      dut.io.halfB #= LowPrecisionCodec.floatToHalfBits(0.5f)
      dut.io.halfC #= LowPrecisionCodec.floatToHalfBits(-0.25f)
      dut.io.floatIn #= 0
      dut.io.packedA #= u32(LowPrecisionCodec.packHalf2(
        LowPrecisionCodec.floatToHalfBits(1.0f),
        LowPrecisionCodec.floatToHalfBits(-0.5f)
      ))
      dut.io.packedB #= u32(LowPrecisionCodec.packHalf2(
        LowPrecisionCodec.floatToHalfBits(0.5f),
        LowPrecisionCodec.floatToHalfBits(1.5f)
      ))
      sleep(1)

      dut.io.add.toBigInt shouldBe BigInt(LowPrecisionCodec.floatToHalfBits(2.0f))
      dut.io.mul.toBigInt shouldBe BigInt(LowPrecisionCodec.floatToHalfBits(0.75f))
      dut.io.fma.toBigInt shouldBe BigInt(LowPrecisionCodec.floatToHalfBits(0.5f))
      dut.io.add2.toBigInt shouldBe BigInt(
        LowPrecisionCodec.packHalf2(
          LowPrecisionCodec.floatToHalfBits(1.5f),
          LowPrecisionCodec.floatToHalfBits(1.0f)
        )
      )
    }
  }
}
