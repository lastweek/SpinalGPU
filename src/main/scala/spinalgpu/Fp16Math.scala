package spinalgpu

import spinal.core._

object Fp16Math {
  private val HalfExponentWidth = 5
  private val HalfFractionWidth = 10
  private val HalfSignificandWidth = HalfFractionWidth + 1
  private val HalfExponentBias = 15

  private val Fp32ExponentWidth = 8
  private val Fp32FractionWidth = 23
  private val Fp32SignificandWidth = Fp32FractionWidth + 1
  private val Fp32ExponentBias = 127

  private def canonicalNaN: Bits = B(0x7E00, 16 bits)

  private def roundShiftRightConstant(value: UInt, shift: Int, keptWidth: Int): UInt = {
    val truncated = (value |>> shift).resize(keptWidth + 1)
    val lowerBitsNonZero =
      if (shift > 1) value(shift - 2 downto 0).orR else False
    val guard = value(shift - 1)
    val increment = guard && (lowerBitsNonZero || truncated(0))
    val result = UInt((keptWidth + 1) bits)
    result := truncated + U(increment, keptWidth + 1 bits)
    result
  }

  private def roundShiftRightDynamic(value: UInt, shift: UInt, keptWidth: Int, maxShift: Int): UInt = {
    val result = UInt((keptWidth + 1) bits)
    result := 0
    val maxSupportedShift = Math.min(maxShift, value.getWidth - 1)

    when(shift === 0) {
      result := value(keptWidth - 1 downto 0).resize(keptWidth + 1)
    }

    for (amount <- 1 to maxSupportedShift) {
      when(shift === amount) {
        val truncated = (value |>> amount).resize(keptWidth + 1)
        val lowerBitsNonZero =
          if (amount > 1) value(amount - 2 downto 0).orR else False
        val guard = value(amount - 1)
        val increment = guard && (lowerBitsNonZero || truncated(0))
        result := truncated + U(increment, keptWidth + 1 bits)
      }
    }

    when(shift > maxSupportedShift) {
      result := 0
    }

    result
  }

  def unpackLow(value: Bits): Bits = value(15 downto 0)

  def unpackHigh(value: Bits): Bits = value(31 downto 16)

  def pack2(low: Bits, high: Bits): Bits = high ## low

  def toFp32(value: Bits): Bits = {
    val sign = value(15)
    val exponent = value(14 downto 10).asUInt
    val fraction = value(9 downto 0).asUInt

    val result = Bits(32 bits)
    result := 0
    result(31) := sign

    when(exponent === 0) {
      when(fraction === 0) {
        result(30 downto 0) := 0
      } otherwise {
        val leadingShift = UInt(4 bits)
        leadingShift := 0
        var foundLeadingOne: Bool = False
        for (bit <- HalfFractionWidth - 1 downto 0) {
          when(!foundLeadingOne && fraction(bit)) {
            leadingShift := U((HalfFractionWidth - 1) - bit, leadingShift.getWidth bits)
          }
          foundLeadingOne = foundLeadingOne || fraction(bit)
        }

        val normalizedFraction = UInt(24 bits)
        normalizedFraction := (fraction.resize(24) |<< (leadingShift + U(14, leadingShift.getWidth bits))).resize(24)
        result(30 downto 23) := (U(112, 8 bits) - leadingShift.resize(8)).asBits
        result(22 downto 0) := normalizedFraction(22 downto 0).asBits
      }
    } elsewhen (exponent === 0x1F) {
      result(30 downto 23) := B(0xFF, 8 bits)
      result(22 downto 0) := (fraction.resize(23) |<< 13).asBits
      when(fraction =/= 0) {
        result(22) := True
      }
    } otherwise {
      result(30 downto 23) := (exponent.resize(8) + U(112, 8 bits)).asBits
      result(22 downto 0) := (fraction.resize(23) |<< 13).asBits
    }

    result
  }

  def fromFp32(value: Bits): Bits = {
    val sign = value(31)
    val exponent = value(30 downto 23).asUInt
    val fraction = value(22 downto 0).asUInt

    val isZero = exponent === 0 && fraction === 0
    val isInf = exponent === 0xFF && fraction === 0
    val isNaN = exponent === 0xFF && fraction =/= 0

    val result = Bits(16 bits)
    result := 0
    result(15) := sign

    when(isNaN) {
      result := canonicalNaN
      result(15) := sign
    } elsewhen (isInf) {
      result(14 downto 10) := B(0x1F, 5 bits)
      result(9 downto 0) := 0
    } elsewhen (isZero) {
      result(14 downto 0) := 0
    } otherwise {
      val unbiasedExponent = SInt(10 bits)
      unbiasedExponent := exponent.resize(10).asSInt - S(Fp32ExponentBias, 10 bits)
      when(exponent === 0) {
        unbiasedExponent := S(-126, 10 bits)
      }

      val significand = UInt(Fp32SignificandWidth bits)
      significand := fraction.resize(Fp32SignificandWidth)
      when(exponent =/= 0) {
        significand := (B(1, 1 bits) ## fraction.asBits).asUInt
      }

      when(unbiasedExponent > S(15, 10 bits)) {
        result(14 downto 10) := B(0x1F, 5 bits)
        result(9 downto 0) := 0
      } elsewhen (unbiasedExponent >= S(-14, 10 bits)) {
        val rounded = roundShiftRightConstant(significand, shift = 13, keptWidth = HalfSignificandWidth)
        val exponentFieldBase = (unbiasedExponent + S(HalfExponentBias, 10 bits)).asUInt.resized
        val exponentFieldAdjusted = UInt((HalfExponentWidth + 1) bits)
        val roundedMantissaAdjusted = UInt(HalfSignificandWidth bits)
        exponentFieldAdjusted := exponentFieldBase
        roundedMantissaAdjusted := rounded(HalfSignificandWidth - 1 downto 0)

        when(rounded(HalfSignificandWidth)) {
          exponentFieldAdjusted := exponentFieldBase + U(1, exponentFieldAdjusted.getWidth bits)
          roundedMantissaAdjusted := rounded(HalfSignificandWidth downto 1)
        }

        when(exponentFieldAdjusted >= U(0x1F, exponentFieldAdjusted.getWidth bits)) {
          result(14 downto 10) := B(0x1F, 5 bits)
          result(9 downto 0) := 0
        } otherwise {
          result(14 downto 10) := exponentFieldAdjusted(4 downto 0).asBits
          result(9 downto 0) := roundedMantissaAdjusted(9 downto 0).asBits
        }
      } elsewhen (unbiasedExponent < S(-24, 10 bits)) {
        result(14 downto 0) := 0
      } otherwise {
        val subnormalShift = UInt(6 bits)
        subnormalShift := (S(0, 10 bits) - unbiasedExponent - 1).asUInt.resized
        val roundedSubnormal = roundShiftRightDynamic(significand, subnormalShift, keptWidth = HalfFractionWidth, maxShift = 31)

        when(roundedSubnormal(HalfFractionWidth)) {
          result(14 downto 10) := B(1, 5 bits)
          result(9 downto 0) := 0
        } otherwise {
          result(14 downto 10) := 0
          result(9 downto 0) := roundedSubnormal(HalfFractionWidth - 1 downto 0).asBits
        }
      }
    }

    result
  }

  def add(a: Bits, b: Bits): Bits = fromFp32(Fp32Math.add(toFp32(a), toFp32(b)))

  def mul(a: Bits, b: Bits): Bits = fromFp32(Fp32Math.mul(toFp32(a), toFp32(b)))

  def fma(a: Bits, b: Bits, c: Bits): Bits = fromFp32(Fp32Math.fma(toFp32(a), toFp32(b), toFp32(c)))

  def add2(a: Bits, b: Bits): Bits =
    pack2(
      add(unpackLow(a), unpackLow(b)),
      add(unpackHigh(a), unpackHigh(b))
    )

  def mul2(a: Bits, b: Bits): Bits =
    pack2(
      mul(unpackLow(a), unpackLow(b)),
      mul(unpackHigh(a), unpackHigh(b))
    )
}
