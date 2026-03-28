package spinalgpu

import spinal.core._

object Fp8Format {
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

  private def decodeAltToF16(value: Bits, exponentBits: Int, fractionBits: Int, exponentBias: Int, finiteOnly: Boolean): Bits = {
    val sign = value(7)
    val exponent = value(7 - 1 downto fractionBits).asUInt
    val fraction = value(fractionBits - 1 downto 0).asUInt
    val allOnesExponent = (1 << exponentBits) - 1
    val allOnesFraction = (1 << fractionBits) - 1
    val isSpecialNaN = Bool()
    isSpecialNaN := exponent === allOnesExponent && fraction === allOnesFraction
    val isSpecialInf = Bool()
    isSpecialInf := exponent === allOnesExponent && fraction === 0

    val result = Bits(16 bits)
    result := 0
    result(15) := sign

    when(exponent === 0) {
      when(fraction === 0) {
        result(14 downto 0) := 0
      } otherwise {
        val leadingShift = UInt(log2Up(fractionBits + 1) bits)
        leadingShift := 0
        var foundLeadingOne: Bool = False
        for (bit <- fractionBits - 1 downto 0) {
          when(!foundLeadingOne && fraction(bit)) {
            leadingShift := U((fractionBits - 1) - bit, leadingShift.getWidth bits)
          }
          foundLeadingOne = foundLeadingOne || fraction(bit)
        }

        val halfExponent = UInt(5 bits)
        halfExponent := U(15 - exponentBias, 5 bits) - leadingShift.resize(5)

        val normalizedFraction = UInt(11 bits)
        normalizedFraction := (fraction.resize(11) |<< (leadingShift.resize(5) + U(11 - fractionBits, 5 bits))).resized

        result(14 downto 10) := halfExponent.asBits
        result(9 downto 0) := normalizedFraction(9 downto 0).asBits
      }
    } elsewhen (Mux(if (finiteOnly) True else False, isSpecialNaN, exponent === allOnesExponent)) {
      when((if (finiteOnly) False else True) && isSpecialInf) {
        result(14 downto 10) := B(0x1F, 5 bits)
        result(9 downto 0) := 0
      } otherwise {
        result := B(0x7E00, 16 bits)
        result(15) := sign
      }
    } otherwise {
      result(14 downto 10) := (exponent.resize(5) + U(15 - exponentBias, 5 bits)).asBits
      result(9 downto 0) := (fraction.resize(10) |<< (10 - fractionBits)).asBits
    }

    result
  }

  private def encodeF16ToAltSatFinite(
      value: Bits,
      exponentBits: Int,
      fractionBits: Int,
      exponentBias: Int,
      finiteOnly: Boolean,
      maxFiniteEncoding: Int
  ): Bits = {
    val sign = value(15)
    val exponent = value(14 downto 10).asUInt
    val fraction = value(9 downto 0).asUInt
    val allOnesFraction = (1 << fractionBits) - 1
    val maxFiniteExponentField = if (finiteOnly) (1 << exponentBits) - 1 else (1 << exponentBits) - 2
    val maxFiniteUnbiasedExponent = maxFiniteExponentField - exponentBias
    val minNormalUnbiasedExponent = 1 - exponentBias
    val minSubnormalUnbiasedExponent = 1 - exponentBias - fractionBits
    val clampAtReservedNaN = Bool()
    clampAtReservedNaN := False

    val result = Bits(8 bits)
    result := 0
    result(7) := sign

    when(exponent === 0 && fraction === 0) {
      result(6 downto 0) := 0
    } elsewhen (exponent === 0x1F && fraction =/= 0) {
      result := B((0x7F | 0x80), 8 bits)
      result(7) := sign
    } elsewhen (exponent === 0x1F) {
      result := B(maxFiniteEncoding | 0x80, 8 bits)
      result(7) := sign
    } otherwise {
      val unbiasedExponent = SInt(10 bits)
      unbiasedExponent := exponent.resize(10).asSInt - S(15, 10 bits)
      val significand = UInt(11 bits)
      significand := (B(1, 1 bits) ## fraction.asBits).asUInt

      when(exponent === 0) {
        val leadingShift = UInt(4 bits)
        leadingShift := 0
        var foundLeadingOne: Bool = False
        for (bit <- 9 downto 0) {
          when(!foundLeadingOne && fraction(bit)) {
            leadingShift := U(9 - bit, leadingShift.getWidth bits)
          }
          foundLeadingOne = foundLeadingOne || fraction(bit)
        }
        unbiasedExponent := S(-15, 10 bits) - leadingShift.resize(10).asSInt
        significand := (fraction.resize(11) |<< (leadingShift + U(1, leadingShift.getWidth bits))).resized
      }

      when(unbiasedExponent > S(maxFiniteUnbiasedExponent, 10 bits)) {
        result := B(maxFiniteEncoding, 8 bits)
        result(7) := sign
      } elsewhen (unbiasedExponent >= S(minNormalUnbiasedExponent, 10 bits)) {
        val rounded = roundShiftRightConstant(significand, shift = 10 - fractionBits, keptWidth = fractionBits + 1)
        val exponentFieldBase = (unbiasedExponent + S(exponentBias, 10 bits)).asUInt.resized
        val exponentFieldAdjusted = UInt((exponentBits + 1) bits)
        val roundedMantissaAdjusted = UInt((fractionBits + 1) bits)
        exponentFieldAdjusted := exponentFieldBase
        roundedMantissaAdjusted := rounded(fractionBits downto 0)

        when(rounded(fractionBits + 1)) {
          exponentFieldAdjusted := exponentFieldBase + U(1, exponentFieldAdjusted.getWidth bits)
          roundedMantissaAdjusted := rounded(fractionBits + 1 downto 1)
        }

        clampAtReservedNaN := (if (finiteOnly) True else False) &&
          exponentFieldAdjusted === U(maxFiniteExponentField, exponentFieldAdjusted.getWidth bits) &&
          roundedMantissaAdjusted(fractionBits - 1 downto 0) === U(allOnesFraction, fractionBits bits)

        when(exponentFieldAdjusted > U(maxFiniteExponentField, exponentFieldAdjusted.getWidth bits) || clampAtReservedNaN) {
          result := B(maxFiniteEncoding, 8 bits)
          result(7) := sign
        } otherwise {
          result(7) := sign
          result(6 downto fractionBits) := exponentFieldAdjusted(exponentBits - 1 downto 0).asBits
          result(fractionBits - 1 downto 0) := roundedMantissaAdjusted(fractionBits - 1 downto 0).asBits
        }
      } elsewhen (unbiasedExponent < S(minSubnormalUnbiasedExponent, 10 bits)) {
        result(6 downto 0) := 0
      } otherwise {
        val subnormalShift = UInt(6 bits)
        subnormalShift := (S(minNormalUnbiasedExponent + 10 - fractionBits, 10 bits) - unbiasedExponent).asUInt.resized
        val roundedSubnormal = roundShiftRightDynamic(significand, subnormalShift, keptWidth = fractionBits, maxShift = 31)

        when(roundedSubnormal(fractionBits)) {
          result(7) := sign
          result(6 downto fractionBits) := B(1, exponentBits bits)
          result(fractionBits - 1 downto 0) := 0
        } otherwise {
          result(7) := sign
          result(6 downto fractionBits) := 0
          result(fractionBits - 1 downto 0) := roundedSubnormal(fractionBits - 1 downto 0).asBits
        }
      }
    }

    result
  }

  def e4m3ToF16(value: Bits): Bits = decodeAltToF16(value, exponentBits = 4, fractionBits = 3, exponentBias = 7, finiteOnly = true)

  def e5m2ToF16(value: Bits): Bits = decodeAltToF16(value, exponentBits = 5, fractionBits = 2, exponentBias = 15, finiteOnly = false)

  def e4m3x2ToF16x2(value: Bits): Bits =
    Fp16Math.pack2(
      e4m3ToF16(value(7 downto 0)),
      e4m3ToF16(value(15 downto 8))
    )

  def e5m2x2ToF16x2(value: Bits): Bits =
    Fp16Math.pack2(
      e5m2ToF16(value(7 downto 0)),
      e5m2ToF16(value(15 downto 8))
    )

  def f16ToE4m3SatFinite(value: Bits): Bits =
    encodeF16ToAltSatFinite(value, exponentBits = 4, fractionBits = 3, exponentBias = 7, finiteOnly = true, maxFiniteEncoding = 0x7E)

  def f16ToE5m2SatFinite(value: Bits): Bits =
    encodeF16ToAltSatFinite(value, exponentBits = 5, fractionBits = 2, exponentBias = 15, finiteOnly = false, maxFiniteEncoding = 0x7B)

  def f16x2ToE4m3x2SatFinite(value: Bits): Bits =
    f16ToE4m3SatFinite(Fp16Math.unpackHigh(value)) ## f16ToE4m3SatFinite(Fp16Math.unpackLow(value))

  def f16x2ToE5m2x2SatFinite(value: Bits): Bits =
    f16ToE5m2SatFinite(Fp16Math.unpackHigh(value)) ## f16ToE5m2SatFinite(Fp16Math.unpackLow(value))
}
