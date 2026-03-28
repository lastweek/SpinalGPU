package spinalgpu

import spinal.core._

object Fp32Math {
  private val ExponentWidth = 8
  private val FractionWidth = 23
  private val SignificandWidth = FractionWidth + 1
  private val ExtraBits = 3
  private val ExtendedWidth = SignificandWidth + ExtraBits
  private val ExponentBias = 127
  private val ExponentMathWidth = 12

  private def canonicalNaN: Bits = B(0x7FC00000L, 32 bits)

  def abs(value: Bits): Bits = {
    val result = Bits(32 bits)
    result := value
    result(31) := False
    result
  }

  def neg(value: Bits): Bits = {
    val result = Bits(32 bits)
    result := value
    result(31) := !value(31)
    result
  }

  def eq(a: Bits, b: Bits): Bool = {
    val exponentA = a(30 downto 23).asUInt
    val fractionA = a(22 downto 0).asUInt
    val exponentB = b(30 downto 23).asUInt
    val fractionB = b(22 downto 0).asUInt

    val isNaNA = exponentA === 255 && fractionA =/= 0
    val isNaNB = exponentB === 255 && fractionB =/= 0
    val isZeroA = exponentA === 0 && fractionA === 0
    val isZeroB = exponentB === 0 && fractionB === 0

    val result = Bool()
    result := False
    when(!(isNaNA || isNaNB)) {
      result := (a === b) || (isZeroA && isZeroB)
    }
    result
  }

  def lt(a: Bits, b: Bits): Bool = {
    val exponentA = a(30 downto 23).asUInt
    val fractionA = a(22 downto 0).asUInt
    val exponentB = b(30 downto 23).asUInt
    val fractionB = b(22 downto 0).asUInt

    val isNaNA = exponentA === 255 && fractionA =/= 0
    val isNaNB = exponentB === 255 && fractionB =/= 0
    val isZeroA = exponentA === 0 && fractionA === 0
    val isZeroB = exponentB === 0 && fractionB === 0
    val equal = (a === b) || (isZeroA && isZeroB)
    val signA = a(31)
    val signB = b(31)
    val magnitudeA = a(30 downto 0).asUInt
    val magnitudeB = b(30 downto 0).asUInt

    val result = Bool()
    result := False
    when(!(isNaNA || isNaNB) && !equal) {
      when(signA =/= signB) {
        result := signA && !signB
      } elsewhen (!signA) {
        result := magnitudeA < magnitudeB
      } otherwise {
        result := magnitudeA > magnitudeB
      }
    }
    result
  }

  private def shiftRightJam(value: UInt, shift: UInt): UInt = {
    val width = value.getWidth
    val result = UInt(width bits)
    result := 0

    when(shift === 0) {
      result := value
    }

    for (amount <- 1 until width) {
      when(shift === amount) {
        val shifted = (value |>> amount).resized
        result := shifted
        when(value(amount - 1 downto 0).orR) {
          result(0) := True
        }
      }
    }

    when(shift >= width) {
      result := 0
      when(value.orR) {
        result(0) := True
      }
    }

    result
  }

  private def normalizeAfterSub(significand: UInt): (UInt, UInt) = {
    val shiftAmount = UInt(log2Up(ExtendedWidth) bits)
    shiftAmount := 0

    var foundLeadingOne: Bool = False
    for (bit <- ExtendedWidth - 1 downto 0) {
      when(!foundLeadingOne && significand(bit)) {
        shiftAmount := U((ExtendedWidth - 1) - bit, shiftAmount.getWidth bits)
      }
      foundLeadingOne = foundLeadingOne || significand(bit)
    }

    (((significand |<< shiftAmount).resized), shiftAmount)
  }

  private def roundSignificand(significand: UInt): (UInt, Bool) = {
    val mantissa = significand(ExtendedWidth - 1 downto ExtraBits).resize(SignificandWidth + 1)
    val increment = significand(2) && (significand(1) || significand(0) || significand(3))
    val roundedWide = UInt((SignificandWidth + 1) bits)
    roundedWide := mantissa + U(increment, SignificandWidth + 1 bits)

    val rounded = UInt(SignificandWidth bits)
    rounded := roundedWide(SignificandWidth - 1 downto 0)
    val carry = roundedWide(SignificandWidth)
    when(carry) {
      rounded := roundedWide(SignificandWidth downto 1)
    }

    (rounded, carry)
  }

  private def packFromNormalized(sign: Bool, exponent: SInt, significand: UInt): Bits = {
    val result = Bits(32 bits)
    result := 0

    val (roundedSig, carry) = roundSignificand(significand)
    val adjustedExponent = SInt(ExponentMathWidth bits)
    adjustedExponent := exponent
    when(carry) {
      adjustedExponent := exponent + 1
    }

    when(adjustedExponent > S(ExponentBias, ExponentMathWidth bits)) {
      result := B(0x7F800000L, 32 bits)
      result(31) := sign
    } elsewhen (adjustedExponent >= S(-126, ExponentMathWidth bits)) {
      val biasedExponent = UInt(ExponentWidth bits)
      biasedExponent := (adjustedExponent + ExponentBias).asUInt.resized
      result(31) := sign
      result(30 downto 23) := biasedExponent.asBits
      result(22 downto 0) := roundedSig(FractionWidth - 1 downto 0).asBits
    } otherwise {
      val underflowAmount = (S(-126, ExponentMathWidth bits) - adjustedExponent).asUInt.resize(log2Up(ExtendedWidth + 1) bits)
      val subnormalSig = shiftRightJam(significand, underflowAmount)
      val (roundedSubnormal, subnormalCarry) = roundSignificand(subnormalSig)
      result(31) := sign
      when(subnormalCarry || roundedSubnormal(SignificandWidth - 1)) {
        result(30 downto 23) := B(1, ExponentWidth bits)
        result(22 downto 0) := roundedSubnormal(FractionWidth - 1 downto 0).asBits
      } otherwise {
        result(30 downto 23) := B(0, ExponentWidth bits)
        result(22 downto 0) := roundedSubnormal(FractionWidth - 1 downto 0).asBits
      }
    }

    result
  }

  def add(a: Bits, b: Bits): Bits = {
    val signA = a(31)
    val exponentA = a(30 downto 23).asUInt
    val fractionA = a(22 downto 0).asUInt
    val signB = b(31)
    val exponentB = b(30 downto 23).asUInt
    val fractionB = b(22 downto 0).asUInt

    val isZeroA = exponentA === 0 && fractionA === 0
    val isZeroB = exponentB === 0 && fractionB === 0
    val isInfA = exponentA === 255 && fractionA === 0
    val isInfB = exponentB === 255 && fractionB === 0
    val isNaNA = exponentA === 255 && fractionA =/= 0
    val isNaNB = exponentB === 255 && fractionB =/= 0

    val result = Bits(32 bits)
    result := 0

    when(isNaNA || isNaNB) {
      result := canonicalNaN
    } elsewhen (isInfA && isInfB && signA =/= signB) {
      result := canonicalNaN
    } elsewhen (isInfA) {
      result := a
    } elsewhen (isInfB) {
      result := b
    } elsewhen (isZeroA && isZeroB) {
      result := 0
    } elsewhen (isZeroA) {
      result := b
    } elsewhen (isZeroB) {
      result := a
    } otherwise {
      val effectiveExponentA = SInt(ExponentMathWidth bits)
      val effectiveExponentB = SInt(ExponentMathWidth bits)
      effectiveExponentA := exponentA.resize(ExponentMathWidth bits).asSInt - ExponentBias
      effectiveExponentB := exponentB.resize(ExponentMathWidth bits).asSInt - ExponentBias
      when(exponentA === 0) {
        effectiveExponentA := S(-126, ExponentMathWidth bits)
      }
      when(exponentB === 0) {
        effectiveExponentB := S(-126, ExponentMathWidth bits)
      }

      val significandA = UInt(SignificandWidth bits)
      val significandB = UInt(SignificandWidth bits)
      significandA := (B(0, 1 bits) ## fractionA.asBits).asUInt
      significandB := (B(0, 1 bits) ## fractionB.asBits).asUInt
      when(exponentA =/= 0) {
        significandA := (B(1, 1 bits) ## fractionA.asBits).asUInt
      }
      when(exponentB =/= 0) {
        significandB := (B(1, 1 bits) ## fractionB.asBits).asUInt
      }

      val aHasLargerMagnitude = Bool()
      aHasLargerMagnitude := effectiveExponentA > effectiveExponentB || (effectiveExponentA === effectiveExponentB && significandA >= significandB)

      val largeSign = Bool()
      val smallSign = Bool()
      val largeExponent = SInt(ExponentMathWidth bits)
      val smallExponent = SInt(ExponentMathWidth bits)
      val largeSignificand = UInt(SignificandWidth bits)
      val smallSignificand = UInt(SignificandWidth bits)
      largeSign := signA
      smallSign := signB
      largeExponent := effectiveExponentA
      smallExponent := effectiveExponentB
      largeSignificand := significandA
      smallSignificand := significandB
      when(!aHasLargerMagnitude) {
        largeSign := signB
        smallSign := signA
        largeExponent := effectiveExponentB
        smallExponent := effectiveExponentA
        largeSignificand := significandB
        smallSignificand := significandA
      }

      val exponentDifference = UInt(log2Up(ExtendedWidth + 1) bits)
      exponentDifference := (largeExponent - smallExponent).asUInt.resized
      val largeExtended = (largeSignificand ## B(0, ExtraBits bits)).asUInt
      val smallExtended = (smallSignificand ## B(0, ExtraBits bits)).asUInt
      val alignedSmall = shiftRightJam(smallExtended, exponentDifference)

      val normalized = UInt(ExtendedWidth bits)
      normalized := 0
      val normalizedExponent = SInt(ExponentMathWidth bits)
      normalizedExponent := largeExponent
      val resultSign = Bool()
      resultSign := largeSign

      when(largeSign === smallSign) {
        val sum = largeExtended.resize(ExtendedWidth + 1) + alignedSmall.resize(ExtendedWidth + 1)
        when(sum(ExtendedWidth)) {
          normalized := sum(ExtendedWidth downto 1)
          when(sum(0)) {
            normalized(0) := True
          }
          normalizedExponent := largeExponent + 1
        } otherwise {
          normalized := sum(ExtendedWidth - 1 downto 0)
        }
      } otherwise {
        val difference = largeExtended - alignedSmall
        when(difference === 0) {
          normalized := 0
          normalizedExponent := S(-126, ExponentMathWidth bits)
          resultSign := False
        } otherwise {
          val (shifted, leftShift) = normalizeAfterSub(difference)
          normalized := shifted
          normalizedExponent := largeExponent - leftShift.resize(ExponentMathWidth bits).asSInt
        }
      }

      when(normalized === 0) {
        result := 0
      } otherwise {
        result := packFromNormalized(resultSign, normalizedExponent, normalized)
      }
    }

    result
  }

  def sub(a: Bits, b: Bits): Bits = add(a, neg(b))

  def mul(a: Bits, b: Bits): Bits = {
    val signA = a(31)
    val exponentA = a(30 downto 23).asUInt
    val fractionA = a(22 downto 0).asUInt
    val signB = b(31)
    val exponentB = b(30 downto 23).asUInt
    val fractionB = b(22 downto 0).asUInt

    val isZeroA = exponentA === 0 && fractionA === 0
    val isZeroB = exponentB === 0 && fractionB === 0
    val isInfA = exponentA === 255 && fractionA === 0
    val isInfB = exponentB === 255 && fractionB === 0
    val isNaNA = exponentA === 255 && fractionA =/= 0
    val isNaNB = exponentB === 255 && fractionB =/= 0
    val resultSign = signA ^ signB

    val result = Bits(32 bits)
    result := 0

    when(isNaNA || isNaNB) {
      result := canonicalNaN
    } elsewhen ((isZeroA && isInfB) || (isZeroB && isInfA)) {
      result := canonicalNaN
    } elsewhen (isInfA || isInfB) {
      result := B(0x7F800000L, 32 bits)
      result(31) := resultSign
    } elsewhen (isZeroA || isZeroB) {
      result := 0
      result(31) := resultSign
    } otherwise {
      val effectiveExponentA = SInt(ExponentMathWidth bits)
      val effectiveExponentB = SInt(ExponentMathWidth bits)
      effectiveExponentA := exponentA.resize(ExponentMathWidth bits).asSInt - ExponentBias
      effectiveExponentB := exponentB.resize(ExponentMathWidth bits).asSInt - ExponentBias
      when(exponentA === 0) {
        effectiveExponentA := S(-126, ExponentMathWidth bits)
      }
      when(exponentB === 0) {
        effectiveExponentB := S(-126, ExponentMathWidth bits)
      }

      val significandA = UInt(SignificandWidth bits)
      val significandB = UInt(SignificandWidth bits)
      significandA := (B(0, 1 bits) ## fractionA.asBits).asUInt
      significandB := (B(0, 1 bits) ## fractionB.asBits).asUInt
      when(exponentA =/= 0) {
        significandA := (B(1, 1 bits) ## fractionA.asBits).asUInt
      }
      when(exponentB =/= 0) {
        significandB := (B(1, 1 bits) ## fractionB.asBits).asUInt
      }

      val product = significandA * significandB
      val normalized = UInt(ExtendedWidth bits)
      normalized := 0
      val normalizedExponent = SInt(ExponentMathWidth bits)
      normalizedExponent := effectiveExponentA + effectiveExponentB

      when(product(47)) {
        normalized := product(47 downto 21)
        when(product(20 downto 0).orR) {
          normalized(0) := True
        }
        normalizedExponent := effectiveExponentA + effectiveExponentB + 1
      } otherwise {
        normalized := product(46 downto 20)
        when(product(19 downto 0).orR) {
          normalized(0) := True
        }
      }

      result := packFromNormalized(resultSign, normalizedExponent, normalized)
    }

    result
  }

  def fma(a: Bits, b: Bits, c: Bits): Bits = add(mul(a, b), c)
}
