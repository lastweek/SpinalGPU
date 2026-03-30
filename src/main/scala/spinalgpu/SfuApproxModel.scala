package spinalgpu

import scala.math.Pi

object SfuApproxModel {
  private val ExponentWidth = 8
  private val FractionWidth = 23
  private val SignificandWidth = FractionWidth + 1
  private val ExtraBits = 3
  private val ExtendedWidth = SignificandWidth + ExtraBits
  private val ExponentBias = 127
  val MantissaTableBits = 8
  val MantissaTableSize = 1 << MantissaTableBits
  val FractionTableBits = 8
  val FractionTableSize = 1 << FractionTableBits
  val TrigTableBits = 10
  val TrigTableSize = 1 << TrigTableBits
  val TanhTableBits = 9
  val TanhTableSize = 1 << TanhTableBits
  val SignedIntTableMin = -512
  val SignedIntTableMax = 512
  val Pow2TableMin = -149
  val Pow2TableMax = 127

  val canonicalNaNBits: Int = 0x7FC00000
  val positiveInfinityBits: Int = 0x7F800000
  val negativeInfinityBits: Int = 0xFF800000
  val oneBits: Int = fp32Bits(1.0f)
  val negativeOneBits: Int = fp32Bits(-1.0f)
  val zeroBits: Int = fp32Bits(0.0f)
  val halfBits: Int = fp32Bits(0.5f)
  val onePointFiveBits: Int = fp32Bits(1.5f)
  val twoBits: Int = fp32Bits(2.0f)
  val invHalfPiBits: Int = fp32Bits((2.0 / Pi).toFloat)
  val ex2MaxInputBits: Int = fp32Bits(128.0f)
  val ex2MinInputBits: Int = fp32Bits(-149.0f)
  val tanhSaturationBits: Int = fp32Bits(4.0f)
  val tanhPassThroughBits: Int = fp32Bits(Math.scalb(1.0f, -10))
  val ex2IndexScaleBits: Int = fp32Bits(FractionTableSize.toFloat)
  val trigIndexScaleBits: Int = fp32Bits(TrigTableSize.toFloat)
  val tanhIndexScaleBits: Int = fp32Bits((TanhTableSize.toFloat / 4.0f).toFloat)

  private def mantissaCenter(index: Int): Float =
    (1.0 + ((index + 0.5) / MantissaTableSize.toDouble)).toFloat

  private def rsqrtOddCenter(index: Int): Float =
    (2.0 + (((index + 0.5) * 2.0) / MantissaTableSize.toDouble)).toFloat

  private def fractionCenter(index: Int): Float =
    (((index + 0.5) / FractionTableSize.toDouble)).toFloat

  private def trigCenter(index: Int): Float =
    (((index + 0.5) / TrigTableSize.toDouble) * (Pi / 2.0)).toFloat

  private def tanhCenter(index: Int): Float =
    (((index + 0.5) / TanhTableSize.toDouble) * 4.0).toFloat

  val reciprocalSeedTable: IndexedSeq[Int] =
    IndexedSeq.tabulate(MantissaTableSize)(index => fp32Bits((1.0f / mantissaCenter(index)).toFloat))

  val rsqrtEvenSeedTable: IndexedSeq[Int] =
    IndexedSeq.tabulate(MantissaTableSize)(index => fp32Bits((1.0 / math.sqrt(mantissaCenter(index).toDouble)).toFloat))

  val rsqrtOddSeedTable: IndexedSeq[Int] =
    IndexedSeq.tabulate(MantissaTableSize)(index => fp32Bits((1.0 / math.sqrt(rsqrtOddCenter(index).toDouble)).toFloat))

  val log2MantissaTable: IndexedSeq[Int] =
    IndexedSeq.tabulate(MantissaTableSize)(index => fp32Bits((math.log(mantissaCenter(index).toDouble) / math.log(2.0)).toFloat))

  val ex2FractionTable: IndexedSeq[Int] =
    IndexedSeq.tabulate(FractionTableSize)(index => fp32Bits(math.pow(2.0, fractionCenter(index).toDouble).toFloat))

  val sinQuarterTable: IndexedSeq[Int] =
    IndexedSeq.tabulate(TrigTableSize)(index => fp32Bits(math.sin(trigCenter(index).toDouble).toFloat))

  val cosQuarterTable: IndexedSeq[Int] =
    IndexedSeq.tabulate(TrigTableSize)(index => fp32Bits(math.cos(trigCenter(index).toDouble).toFloat))

  val tanhPositiveTable: IndexedSeq[Int] =
    IndexedSeq.tabulate(TanhTableSize)(index => fp32Bits(math.tanh(tanhCenter(index).toDouble).toFloat))

  val signedIntToFloatTable: IndexedSeq[Int] =
    IndexedSeq.tabulate(SignedIntTableMax - SignedIntTableMin + 1) { index =>
      fp32Bits((SignedIntTableMin + index).toFloat)
    }

  val pow2ScaleTable: IndexedSeq[Int] =
    IndexedSeq.tabulate(Pow2TableMax - Pow2TableMin + 1) { index =>
      fp32Bits(Math.scalb(1.0f, Pow2TableMin + index))
    }

  def fp32Bits(value: Float): Int = java.lang.Float.floatToRawIntBits(value)

  def fp32FromBits(bits: Int): Float = java.lang.Float.intBitsToFloat(bits)

  private def withSign(magnitudeBits: Int, signBit: Int): Int =
    (magnitudeBits & 0x7FFFFFFF) | ((signBit & 0x1) << 31)

  private def shiftRightJam(value: Int, shift: Int, width: Int): Int = {
    if (shift <= 0) {
      value
    } else if (shift >= width) {
      if (value != 0) 1 else 0
    } else {
      val shifted = value >>> shift
      val lostMask = (1 << shift) - 1
      if ((value & lostMask) != 0) shifted | 0x1 else shifted
    }
  }

  private def roundSignificand(significand: Int): (Int, Boolean) = {
    val mantissa = significand >>> ExtraBits
    val increment =
      (((significand >>> 2) & 0x1) != 0) &&
        ((((significand >>> 1) & 0x1) != 0) || ((significand & 0x1) != 0) || (((significand >>> 3) & 0x1) != 0))
    val roundedWide = mantissa + (if (increment) 1 else 0)
    val carry = ((roundedWide >>> SignificandWidth) & 0x1) != 0
    val rounded =
      if (carry) (roundedWide >>> 1) & ((1 << SignificandWidth) - 1)
      else roundedWide & ((1 << SignificandWidth) - 1)
    (rounded, carry)
  }

  private def packFromNormalized(signBit: Int, exponent: Int, significand: Int): Int = {
    val (roundedSig, carry) = roundSignificand(significand)
    val adjustedExponent = if (carry) exponent + 1 else exponent

    if (adjustedExponent > ExponentBias) {
      withSign(positiveInfinityBits, signBit)
    } else if (adjustedExponent >= -126) {
      val biasedExponent = adjustedExponent + ExponentBias
      withSign((biasedExponent << FractionWidth) | (roundedSig & 0x7FFFFF), signBit)
    } else {
      val underflowAmount = -126 - adjustedExponent
      val subnormalSig = shiftRightJam(significand, underflowAmount, ExtendedWidth)
      val (roundedSubnormal, subnormalCarry) = roundSignificand(subnormalSig)
      if (subnormalCarry || (((roundedSubnormal >>> (SignificandWidth - 1)) & 0x1) != 0)) {
        withSign((1 << FractionWidth) | (roundedSubnormal & 0x7FFFFF), signBit)
      } else {
        withSign(roundedSubnormal & 0x7FFFFF, signBit)
      }
    }
  }

  private def mulBits(aBits: Int, bBits: Int): Int = {
    val signA = (aBits >>> 31) & 0x1
    val exponentA = (aBits >>> 23) & 0xFF
    val fractionA = aBits & 0x7FFFFF
    val signB = (bBits >>> 31) & 0x1
    val exponentB = (bBits >>> 23) & 0xFF
    val fractionB = bBits & 0x7FFFFF

    val isZeroA = exponentA == 0 && fractionA == 0
    val isZeroB = exponentB == 0 && fractionB == 0
    val isInfA = exponentA == 0xFF && fractionA == 0
    val isInfB = exponentB == 0xFF && fractionB == 0
    val isNaNA = exponentA == 0xFF && fractionA != 0
    val isNaNB = exponentB == 0xFF && fractionB != 0
    val resultSign = signA ^ signB

    if (isNaNA || isNaNB) {
      canonicalNaNBits
    } else if ((isZeroA && isInfB) || (isZeroB && isInfA)) {
      canonicalNaNBits
    } else if (isInfA || isInfB) {
      withSign(positiveInfinityBits, resultSign)
    } else if (isZeroA || isZeroB) {
      withSign(zeroBits, resultSign)
    } else {
      val effectiveExponentA = if (exponentA == 0) -126 else exponentA - ExponentBias
      val effectiveExponentB = if (exponentB == 0) -126 else exponentB - ExponentBias
      val significandA = if (exponentA == 0) fractionA else (1 << FractionWidth) | fractionA
      val significandB = if (exponentB == 0) fractionB else (1 << FractionWidth) | fractionB
      val product = significandA.toLong * significandB.toLong

      val (normalized, normalizedExponent) =
        if (((product >>> 47) & 0x1L) != 0L) {
          val normalized = ((product >>> 21) & ((1L << ExtendedWidth) - 1L)).toInt
          val jammed = if ((product & ((1L << 21) - 1L)) != 0L) normalized | 0x1 else normalized
          (jammed, effectiveExponentA + effectiveExponentB + 1)
        } else {
          val normalized = ((product >>> 20) & ((1L << ExtendedWidth) - 1L)).toInt
          val jammed = if ((product & ((1L << 20) - 1L)) != 0L) normalized | 0x1 else normalized
          (jammed, effectiveExponentA + effectiveExponentB)
        }

      packFromNormalized(resultSign, normalizedExponent, normalized)
    }
  }

  private final case class NormalizedInput(mantissaBits: Int, doubledMantissaBits: Int, exponent: Int, mantissaIndex: Int)

  private def normalizePositiveFinite(bits: Int): NormalizedInput = {
    val absBits = bits & 0x7FFFFFFF
    val exponent = (absBits >>> 23) & 0xFF
    val fraction = absBits & 0x7FFFFF
    require(absBits != 0, "normalizePositiveFinite requires a non-zero finite magnitude")
    require(exponent != 0xFF, "normalizePositiveFinite requires a finite magnitude")

    if (exponent == 0) {
      val highestBit = 31 - Integer.numberOfLeadingZeros(fraction)
      val leadingShift = 22 - highestBit
      val normalizedFraction = (fraction << (leadingShift + 1)) & 0x7FFFFF
      val mantissaBits = 0x3F800000 | normalizedFraction
      val doubledMantissaBits = 0x40000000 | normalizedFraction
      NormalizedInput(
        mantissaBits = mantissaBits,
        doubledMantissaBits = doubledMantissaBits,
        exponent = -127 - leadingShift,
        mantissaIndex = normalizedFraction >>> (23 - MantissaTableBits)
      )
    } else {
      val normalizedFraction = fraction & 0x7FFFFF
      NormalizedInput(
        mantissaBits = 0x3F800000 | normalizedFraction,
        doubledMantissaBits = 0x40000000 | normalizedFraction,
        exponent = exponent - 127,
        mantissaIndex = normalizedFraction >>> (23 - MantissaTableBits)
      )
    }
  }

  private def tableIndex(value: Float, size: Int): Int = {
    val scaled = math.floor(value.toDouble * size.toDouble).toInt
    scaled.max(0).min(size - 1)
  }

  private def rcpApproxF32(bits: Int): Int = {
    val signBit = (bits >>> 31) & 0x1
    val absBits = bits & 0x7FFFFFFF
    val exponent = (absBits >>> 23) & 0xFF
    val fraction = absBits & 0x7FFFFF

    if (exponent == 0xFF) {
      if (fraction != 0) canonicalNaNBits else withSign(zeroBits, signBit)
    } else if (absBits == 0) {
      withSign(positiveInfinityBits, signBit)
    } else {
      val normalized = normalizePositiveFinite(absBits)
      val x = fp32FromBits(normalized.mantissaBits)
      val y0 = fp32FromBits(reciprocalSeedTable(normalized.mantissaIndex))
      val refined = y0 * (2.0f - (x * y0))
      withSign(fp32Bits(Math.scalb(refined, -normalized.exponent)), signBit)
    }
  }

  private def rsqrtApproxF32(bits: Int): Int = {
    val signBit = (bits >>> 31) & 0x1
    val absBits = bits & 0x7FFFFFFF
    val exponent = (absBits >>> 23) & 0xFF
    val fraction = absBits & 0x7FFFFF

    if (exponent == 0xFF) {
      if (fraction != 0) canonicalNaNBits else zeroBits
    } else if (absBits == 0) {
      if (signBit == 0) positiveInfinityBits else negativeInfinityBits
    } else if (signBit == 1) {
      canonicalNaNBits
    } else {
      val normalized = normalizePositiveFinite(absBits)
      val exponentIsOdd = (normalized.exponent & 0x1) != 0
      val normalizedBits = if (exponentIsOdd) normalized.doubledMantissaBits else normalized.mantissaBits
      val x = fp32FromBits(normalizedBits)
      val y0 = fp32FromBits(
        if (exponentIsOdd) rsqrtOddSeedTable(normalized.mantissaIndex)
        else rsqrtEvenSeedTable(normalized.mantissaIndex)
      )
      val refined = y0 * (1.5f - (0.5f * x * y0 * y0))
      val scaledExponent = -((normalized.exponent - (if (exponentIsOdd) 1 else 0)) / 2)
      fp32Bits(Math.scalb(refined, scaledExponent))
    }
  }

  private def sqrtApproxF32(bits: Int): Int = {
    val signBit = (bits >>> 31) & 0x1
    val absBits = bits & 0x7FFFFFFF
    val exponent = (absBits >>> 23) & 0xFF
    val fraction = absBits & 0x7FFFFF

    if (exponent == 0xFF) {
      if (fraction != 0) canonicalNaNBits else if (signBit == 0) positiveInfinityBits else canonicalNaNBits
    } else if (absBits == 0) {
      bits
    } else if (signBit == 1) {
      canonicalNaNBits
    } else {
      mulBits(absBits, rsqrtApproxF32(absBits))
    }
  }

  private def lg2ApproxF32(bits: Int): Int = {
    val signBit = (bits >>> 31) & 0x1
    val absBits = bits & 0x7FFFFFFF
    val exponent = (absBits >>> 23) & 0xFF
    val fraction = absBits & 0x7FFFFF

    if (exponent == 0xFF) {
      if (fraction != 0) canonicalNaNBits else positiveInfinityBits
    } else if (absBits == 0) {
      negativeInfinityBits
    } else if (signBit == 1) {
      canonicalNaNBits
    } else {
      val normalized = normalizePositiveFinite(absBits)
      val mantissaTerm = fp32FromBits(log2MantissaTable(normalized.mantissaIndex))
      fp32Bits(normalized.exponent.toFloat + mantissaTerm)
    }
  }

  private def ex2ApproxF32(bits: Int): Int = {
    val signBit = (bits >>> 31) & 0x1
    val absBits = bits & 0x7FFFFFFF
    val exponent = (absBits >>> 23) & 0xFF
    val fraction = absBits & 0x7FFFFF
    val value = fp32FromBits(bits)

    if (exponent == 0xFF) {
      if (fraction != 0) canonicalNaNBits else if (signBit == 1) zeroBits else positiveInfinityBits
    } else if (value == 0.0f) {
      oneBits
    } else if (value >= 128.0f) {
      positiveInfinityBits
    } else if (value < -149.0f) {
      zeroBits
    } else {
      val integerPart = math.floor(value.toDouble).toInt
      val fractional = value - integerPart.toFloat
      if (fractional == 0.0f) {
        fp32Bits(Math.scalb(1.0f, integerPart))
      } else {
        val index = tableIndex(fractional.max(0.0f).min(0.99999994f), FractionTableSize)
        val base = fp32FromBits(ex2FractionTable(index))
        fp32Bits(Math.scalb(base, integerPart))
      }
    }
  }

  private def tanhApproxF32(bits: Int): Int = {
    val signBit = (bits >>> 31) & 0x1
    val absBits = bits & 0x7FFFFFFF
    val exponent = (absBits >>> 23) & 0xFF
    val fraction = absBits & 0x7FFFFF
    val value = fp32FromBits(bits)
    val magnitude = math.abs(value)

    if (exponent == 0xFF) {
      if (fraction != 0) canonicalNaNBits else if (signBit == 1) negativeOneBits else oneBits
    } else if (magnitude == 0.0f) {
      bits
    } else if (magnitude < Math.scalb(1.0f, -10)) {
      bits
    } else if (magnitude >= 4.0f) {
      if (signBit == 1) negativeOneBits else oneBits
    } else {
      val index = tableIndex((magnitude / 4.0f).toFloat, TanhTableSize)
      withSign(tanhPositiveTable(index), signBit)
    }
  }

  private def trigApproxF32(bits: Int, cosine: Boolean): Int = {
    val signBit = (bits >>> 31) & 0x1
    val absBits = bits & 0x7FFFFFFF
    val exponent = (absBits >>> 23) & 0xFF
    val fraction = absBits & 0x7FFFFF
    val value = fp32FromBits(bits)
    val magnitude = math.abs(value)

    if (exponent == 0xFF || java.lang.Float.isNaN(value)) {
      canonicalNaNBits
    } else if (magnitude == 0.0f) {
      if (cosine) oneBits else bits
    } else {
      val scaled = magnitude.toDouble * (2.0 / Pi)
      val quadrant = math.floor(scaled).toInt
      val fractional = (scaled - quadrant.toDouble).toFloat
      val index = tableIndex(fractional.max(0.0f).min(0.99999994f), TrigTableSize)
      val quarterSin = sinQuarterTable(index)
      val quarterCos = cosQuarterTable(index)
      val quadrantPhase = quadrant & 0x3

      val positiveResult =
        if (!cosine) {
          quadrantPhase match {
            case 0 => quarterSin
            case 1 => quarterCos
            case 2 => withSign(quarterSin, 1)
            case _ => withSign(quarterCos, 1)
          }
        } else {
          quadrantPhase match {
            case 0 => quarterCos
            case 1 => withSign(quarterSin, 1)
            case 2 => withSign(quarterCos, 1)
            case _ => quarterSin
          }
        }

      if (!cosine && signBit == 1) withSign(positiveResult, ((positiveResult >>> 31) ^ 0x1) & 0x1)
      else positiveResult
    }
  }

  def applyFp32Opcode(opcode: Int, operandBits: Int): Int =
    opcode match {
      case Opcode.FRCP => rcpApproxF32(operandBits)
      case Opcode.FSQRT => sqrtApproxF32(operandBits)
      case Opcode.FRSQRT => rsqrtApproxF32(operandBits)
      case Opcode.FSIN => trigApproxF32(operandBits, cosine = false)
      case Opcode.FCOS => trigApproxF32(operandBits, cosine = true)
      case Opcode.FLG2 => lg2ApproxF32(operandBits)
      case Opcode.FEX2 => ex2ApproxF32(operandBits)
      case Opcode.FTANH => tanhApproxF32(operandBits)
      case other => throw new IllegalArgumentException(f"unsupported FP32 SFU opcode 0x$other%02X")
    }

  def applyRegisterOpcode(opcode: Int, operandBits: Int): Int =
    opcode match {
      case Opcode.FRCP | Opcode.FSQRT | Opcode.FRSQRT | Opcode.FSIN | Opcode.FCOS | Opcode.FLG2 | Opcode.FEX2 | Opcode.FTANH =>
        applyFp32Opcode(opcode, operandBits)
      case Opcode.HEX2 =>
        fromHalfUnary(operandBits, opcode = Opcode.FEX2)
      case Opcode.HTANH =>
        fromHalfUnary(operandBits, opcode = Opcode.FTANH)
      case Opcode.HEX2X2 =>
        fromHalf2Unary(operandBits, opcode = Opcode.FEX2)
      case Opcode.HTANHX2 =>
        fromHalf2Unary(operandBits, opcode = Opcode.FTANH)
      case other =>
        throw new IllegalArgumentException(f"unsupported SFU opcode 0x$other%02X")
    }

  private def fromHalfUnary(operandBits: Int, opcode: Int): Int = {
    val input = operandBits & 0xFFFF
    val widened = fp32Bits(LowPrecisionCodec.halfBitsToFloat(input))
    val result = applyFp32Opcode(opcode, widened)
    LowPrecisionCodec.floatToHalfBits(fp32FromBits(result))
  }

  private def fromHalf2Unary(operandBits: Int, opcode: Int): Int = {
    val low = fromHalfUnary(LowPrecisionCodec.unpackHalf2Low(operandBits), opcode)
    val high = fromHalfUnary(LowPrecisionCodec.unpackHalf2High(operandBits), opcode)
    LowPrecisionCodec.packHalf2(low, high)
  }
}
