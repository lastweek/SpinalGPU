package spinalgpu

import scala.math.abs

object LowPrecisionCodec {
  private def signBit(value: Float): Int = (java.lang.Float.floatToRawIntBits(value) >>> 31) & 0x1

  def halfBitsToFloat(bits: Int): Float = {
    val sign = (bits >>> 15) & 0x1
    val exponent = (bits >>> 10) & 0x1F
    val fraction = bits & 0x3FF

    if (exponent == 0) {
      if (fraction == 0) {
        java.lang.Float.intBitsToFloat(sign << 31)
      } else {
        val magnitude = Math.scalb(fraction.toDouble, -24).toFloat
        if (sign == 0) magnitude else -magnitude
      }
    } else if (exponent == 0x1F) {
      if (fraction == 0) {
        if (sign == 0) Float.PositiveInfinity else Float.NegativeInfinity
      } else {
        Float.NaN
      }
    } else {
      val significand = 1.0 + (fraction.toDouble / 1024.0)
      val magnitude = Math.scalb(significand, exponent - 15).toFloat
      if (sign == 0) magnitude else -magnitude
    }
  }

  private lazy val halfFiniteEncodings: IndexedSeq[(Int, Float)] =
    (0 until 0x10000).iterator
      .filter { bits =>
        val exponent = (bits >>> 10) & 0x1F
        !(exponent == 0x1F && (bits & 0x3FF) != 0)
      }
      .map(bits => bits -> halfBitsToFloat(bits))
      .toIndexedSeq

  def floatToHalfBits(value: Float): Int = {
    if (value.isNaN) {
      0x7E00
    } else if (value.isPosInfinity) {
      0x7C00
    } else if (value.isNegInfinity) {
      0xFC00
    } else if (value == 0.0f) {
      if (signBit(value) == 0) 0x0000 else 0x8000
    } else {
      val target = value.toDouble
      halfFiniteEncodings.minBy { case (bits, decoded) =>
        val error = abs(decoded.toDouble - target)
        (error, bits & 0x1, bits)
      }._1
    }
  }

  def packHalf2(low: Int, high: Int): Int = (low & 0xFFFF) | ((high & 0xFFFF) << 16)

  def unpackHalf2Low(bits: Int): Int = bits & 0xFFFF

  def unpackHalf2High(bits: Int): Int = (bits >>> 16) & 0xFFFF

  def e4m3BitsToFloat(bits: Int): Float = {
    val sign = (bits >>> 7) & 0x1
    val exponent = (bits >>> 3) & 0xF
    val fraction = bits & 0x7

    if (exponent == 0) {
      if (fraction == 0) {
        java.lang.Float.intBitsToFloat(sign << 31)
      } else {
        val magnitude = Math.scalb(fraction.toDouble, -9).toFloat
        if (sign == 0) magnitude else -magnitude
      }
    } else if (exponent == 0xF && fraction == 0x7) {
      Float.NaN
    } else {
      val significand = 1.0 + (fraction.toDouble / 8.0)
      val magnitude = Math.scalb(significand, exponent - 7).toFloat
      if (sign == 0) magnitude else -magnitude
    }
  }

  private lazy val e4m3FiniteEncodings: IndexedSeq[(Int, Float)] =
    (0 until 0x100).iterator
      .filter(bits => bits != 0x7F && bits != 0xFF)
      .map(bits => bits -> e4m3BitsToFloat(bits))
      .toIndexedSeq

  def floatToE4m3BitsSatFinite(value: Float): Int = {
    if (value.isNaN) {
      0x7F
    } else if (value.isInfinite) {
      if (value > 0.0f) 0x7E else 0xFE
    } else if (value == 0.0f) {
      if (signBit(value) == 0) 0x00 else 0x80
    } else {
      val target = value.toDouble
      e4m3FiniteEncodings.minBy { case (bits, decoded) =>
        val error = abs(decoded.toDouble - target)
        (error, bits & 0x1, bits)
      }._1
    }
  }

  def e5m2BitsToFloat(bits: Int): Float = {
    val sign = (bits >>> 7) & 0x1
    val exponent = (bits >>> 2) & 0x1F
    val fraction = bits & 0x3

    if (exponent == 0) {
      if (fraction == 0) {
        java.lang.Float.intBitsToFloat(sign << 31)
      } else {
        val magnitude = Math.scalb(fraction.toDouble, -16).toFloat
        if (sign == 0) magnitude else -magnitude
      }
    } else if (exponent == 0x1F) {
      if (fraction == 0) {
        if (sign == 0) Float.PositiveInfinity else Float.NegativeInfinity
      } else {
        Float.NaN
      }
    } else {
      val significand = 1.0 + (fraction.toDouble / 4.0)
      val magnitude = Math.scalb(significand, exponent - 15).toFloat
      if (sign == 0) magnitude else -magnitude
    }
  }

  private lazy val e5m2FiniteEncodings: IndexedSeq[(Int, Float)] =
    (0 until 0x100).iterator
      .filter(bits => ((bits >>> 2) & 0x1F) != 0x1F)
      .map(bits => bits -> e5m2BitsToFloat(bits))
      .toIndexedSeq

  def floatToE5m2BitsSatFinite(value: Float): Int = {
    if (value.isNaN) {
      0x7F
    } else if (value.isInfinite) {
      if (value > 0.0f) 0x7B else 0xFB
    } else if (value == 0.0f) {
      if (signBit(value) == 0) 0x00 else 0x80
    } else {
      val target = value.toDouble
      e5m2FiniteEncodings.minBy { case (bits, decoded) =>
        val error = abs(decoded.toDouble - target)
        (error, bits & 0x1, bits)
      }._1
    }
  }

  def packFp8x2(low: Int, high: Int): Int = (low & 0xFF) | ((high & 0xFF) << 8)

  def unpackFp8x2Low(bits: Int): Int = bits & 0xFF

  def unpackFp8x2High(bits: Int): Int = (bits >>> 8) & 0xFF
}
