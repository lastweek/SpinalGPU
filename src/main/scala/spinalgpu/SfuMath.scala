package spinalgpu

import spinal.core._

object SfuMath {
  private val IndexIntWidth = 16

  private def lit32(value: Int): Bits = B(BigInt(value.toLong & 0xFFFFFFFFL), 32 bits)

  private def tableVec(values: IndexedSeq[Int]): Vec[Bits] = Vec(values.map(lit32))

  private def reciprocalSeedTable: Vec[Bits] = tableVec(SfuApproxModel.reciprocalSeedTable)
  private def rsqrtEvenSeedTable: Vec[Bits] = tableVec(SfuApproxModel.rsqrtEvenSeedTable)
  private def rsqrtOddSeedTable: Vec[Bits] = tableVec(SfuApproxModel.rsqrtOddSeedTable)
  private def log2MantissaTable: Vec[Bits] = tableVec(SfuApproxModel.log2MantissaTable)
  private def ex2FractionTable: Vec[Bits] = tableVec(SfuApproxModel.ex2FractionTable)
  private def sinQuarterTable: Vec[Bits] = tableVec(SfuApproxModel.sinQuarterTable)
  private def cosQuarterTable: Vec[Bits] = tableVec(SfuApproxModel.cosQuarterTable)
  private def tanhPositiveTable: Vec[Bits] = tableVec(SfuApproxModel.tanhPositiveTable)
  private def signedIntToFloatTable: Vec[Bits] = tableVec(SfuApproxModel.signedIntToFloatTable)
  private def pow2ScaleTable: Vec[Bits] = tableVec(SfuApproxModel.pow2ScaleTable)

  private def ZeroBits: Bits = lit32(SfuApproxModel.zeroBits)
  private def OneBits: Bits = lit32(SfuApproxModel.oneBits)
  private def NegativeOneBits: Bits = lit32(SfuApproxModel.negativeOneBits)
  private def HalfBits: Bits = lit32(SfuApproxModel.halfBits)
  private def OnePointFiveBits: Bits = lit32(SfuApproxModel.onePointFiveBits)
  private def TwoBits: Bits = lit32(SfuApproxModel.twoBits)
  private def CanonicalNaNBits: Bits = lit32(SfuApproxModel.canonicalNaNBits)
  private def PositiveInfinityBits: Bits = lit32(SfuApproxModel.positiveInfinityBits)
  private def NegativeInfinityBits: Bits = lit32(SfuApproxModel.negativeInfinityBits)
  private def InvHalfPiBits: Bits = lit32(SfuApproxModel.invHalfPiBits)
  private def Ex2MaxInputBits: Bits = lit32(SfuApproxModel.ex2MaxInputBits)
  private def Ex2MinInputBits: Bits = lit32(SfuApproxModel.ex2MinInputBits)
  private def TanhSaturationBits: Bits = lit32(SfuApproxModel.tanhSaturationBits)
  private def TanhPassThroughBits: Bits = lit32(SfuApproxModel.tanhPassThroughBits)
  private def Ex2IndexScaleBits: Bits = lit32(SfuApproxModel.ex2IndexScaleBits)
  private def TrigIndexScaleBits: Bits = lit32(SfuApproxModel.trigIndexScaleBits)
  private def TanhIndexScaleBits: Bits = lit32(SfuApproxModel.tanhIndexScaleBits)

  private def withSign(magnitude: Bits, sign: Bool): Bits = {
    (sign.asBits ## magnitude(30 downto 0)).asBits
  }

  private def negateIf(value: Bits, negate: Bool): Bits = Mux(negate, Fp32Math.neg(value), value)

  private def normalizePositiveFinite(value: Bits): (Bits, Bits, SInt, UInt) = {
    val exponent = value(30 downto 23).asUInt
    val fraction = value(22 downto 0).asUInt
    val normalizedFraction = UInt(23 bits)
    normalizedFraction := fraction
    val unbiasedExponent = SInt(IndexIntWidth bits)
    unbiasedExponent := exponent.resize(IndexIntWidth bits).asSInt - S(127, IndexIntWidth bits)

    when(exponent === 0) {
      val leadingShift = UInt(5 bits)
      leadingShift := 0
      var foundLeadingOne: Bool = False
      for (bit <- 22 downto 0) {
        when(!foundLeadingOne && fraction(bit)) {
          leadingShift := U(22 - bit, leadingShift.getWidth bits)
        }
        foundLeadingOne = foundLeadingOne || fraction(bit)
      }
      normalizedFraction := ((fraction.resize(24) |<< (leadingShift + U(1, leadingShift.getWidth bits))).resize(24))(22 downto 0)
      unbiasedExponent := S(-127, IndexIntWidth bits) - leadingShift.resize(IndexIntWidth bits).asSInt
    }

    val mantissaBits = Bits(32 bits)
    mantissaBits := lit32(0x3F800000)
    mantissaBits(22 downto 0) := normalizedFraction.asBits

    val doubledMantissaBits = Bits(32 bits)
    doubledMantissaBits := lit32(0x40000000)
    doubledMantissaBits(22 downto 0) := normalizedFraction.asBits

    val mantissaIndex = normalizedFraction(22 downto (23 - SfuApproxModel.MantissaTableBits))
    (mantissaBits, doubledMantissaBits, unbiasedExponent, mantissaIndex)
  }

  private def floorToSInt(value: Bits): SInt = {
    val sign = value(31)
    val exponent = value(30 downto 23).asUInt
    val fraction = value(22 downto 0).asUInt
    val result = SInt(IndexIntWidth bits)
    result := 0

    val unbiasedExponent = SInt(IndexIntWidth bits)
    unbiasedExponent := exponent.resize(IndexIntWidth bits).asSInt - S(127, IndexIntWidth bits)
    val significand = (B(1, 1 bits) ## fraction.asBits).asUInt
    val absInteger = UInt((IndexIntWidth - 1) bits)
    absInteger := 0
    val hasFraction = Bool()
    hasFraction := False

    val hasMagnitude = exponent =/= 0 || fraction =/= 0

    when(exponent === 0xFF) {
      result := Mux(sign, S(-(1 << (IndexIntWidth - 1)), IndexIntWidth bits), S((1 << (IndexIntWidth - 1)) - 1, IndexIntWidth bits))
    } elsewhen (exponent === 0 || unbiasedExponent < 0) {
      when(sign && hasMagnitude) {
        result := S(-1, IndexIntWidth bits)
      }
    } elsewhen (unbiasedExponent > S(IndexIntWidth - 2, IndexIntWidth bits)) {
      result := Mux(sign, S(-(1 << (IndexIntWidth - 1)), IndexIntWidth bits), S((1 << (IndexIntWidth - 1)) - 1, IndexIntWidth bits))
    } otherwise {
      val rightShift = (S(23, IndexIntWidth bits) - unbiasedExponent).asUInt.resize(5 bits)
      absInteger := (significand |>> rightShift).resize(absInteger.getWidth)

      for (amount <- 1 to 23) {
        when(rightShift === amount) {
          hasFraction := significand(amount - 1 downto 0).orR
        }
      }

      when(sign) {
        result := -absInteger.asSInt.resize(IndexIntWidth bits)
        when(hasFraction) {
          result := -absInteger.asSInt.resize(IndexIntWidth bits) - S(1, IndexIntWidth bits)
        }
      } otherwise {
        result := absInteger.asSInt.resize(IndexIntWidth bits)
      }
    }

    result
  }

  private def intToFloatBits(value: SInt): Bits = {
    val result = Bits(32 bits)
    result := ZeroBits
    when(value <= S(SfuApproxModel.SignedIntTableMin, IndexIntWidth bits)) {
      result := signedIntToFloatTable.head
    } elsewhen (value >= S(SfuApproxModel.SignedIntTableMax, IndexIntWidth bits)) {
      result := signedIntToFloatTable.last
    } otherwise {
      val index = (value + S(-SfuApproxModel.SignedIntTableMin, IndexIntWidth bits)).asUInt.resize(log2Up(signedIntToFloatTable.length) bits)
      result := signedIntToFloatTable(index)
    }
    result
  }

  private def scaleByPow2(value: Bits, exponentDelta: SInt, sign: Bool): Bits = {
    val exponentDeltaWide = SInt(IndexIntWidth bits)
    exponentDeltaWide := exponentDelta.resize(IndexIntWidth bits)
    val magnitudeResult = Bits(32 bits)
    magnitudeResult := ZeroBits

    when(exponentDeltaWide > S(SfuApproxModel.Pow2TableMax, IndexIntWidth bits)) {
      magnitudeResult := PositiveInfinityBits
    } elsewhen (exponentDeltaWide < S(SfuApproxModel.Pow2TableMin, IndexIntWidth bits)) {
      magnitudeResult := ZeroBits
    } otherwise {
      val index = (exponentDeltaWide + S(-SfuApproxModel.Pow2TableMin, IndexIntWidth bits)).asUInt.resize(log2Up(pow2ScaleTable.length) bits)
      magnitudeResult := Fp32Math.mul(value, pow2ScaleTable(index))
    }

    withSign(magnitudeResult, sign)
  }

  private def indexFromPositiveFloat(value: Bits, scaleBits: Bits, tableLength: Int): UInt = {
    val scaled = Fp32Math.mul(value, scaleBits)
    val floor = floorToSInt(scaled)
    val result = UInt(log2Up(tableLength) bits)
    result := 0
    when(floor >= S(tableLength, IndexIntWidth bits)) {
      result := U(tableLength - 1, result.getWidth bits)
    } elsewhen (floor > S(0, IndexIntWidth bits)) {
      result := floor.asUInt.resize(result.getWidth)
    }
    result
  }

  private def rcpApproxF32(value: Bits): Bits = {
    val absValue = Fp32Math.abs(value)
    val exponent = absValue(30 downto 23).asUInt
    val fraction = absValue(22 downto 0).asUInt
    val isZero = absValue(30 downto 0) === 0
    val isInf = exponent === 0xFF && fraction === 0
    val isNaN = exponent === 0xFF && fraction =/= 0
    val sign = value(31)

    val result = Bits(32 bits)
    result := ZeroBits

    when(isNaN) {
      result := CanonicalNaNBits
    } elsewhen (isInf) {
      result := withSign(ZeroBits, sign)
    } elsewhen (isZero) {
      result := withSign(PositiveInfinityBits, sign)
    } otherwise {
      val (mantissaBits, _, unbiasedExponent, mantissaIndex) = normalizePositiveFinite(absValue)
      val seed = reciprocalSeedTable(mantissaIndex)
      val product = Fp32Math.mul(mantissaBits, seed)
      val correction = Fp32Math.sub(TwoBits, product)
      val refined = Fp32Math.mul(seed, correction)
      result := scaleByPow2(refined, -unbiasedExponent, sign)
    }

    result
  }

  private def rsqrtApproxF32(value: Bits): Bits = {
    val absValue = Fp32Math.abs(value)
    val exponent = absValue(30 downto 23).asUInt
    val fraction = absValue(22 downto 0).asUInt
    val isZero = absValue(30 downto 0) === 0
    val isInf = exponent === 0xFF && fraction === 0
    val isNaN = exponent === 0xFF && fraction =/= 0
    val sign = value(31)

    val result = Bits(32 bits)
    result := ZeroBits

    when(isNaN) {
      result := CanonicalNaNBits
    } elsewhen (isInf) {
      result := ZeroBits
    } elsewhen (isZero) {
      result := Mux(sign, NegativeInfinityBits, PositiveInfinityBits)
    } elsewhen (sign) {
      result := CanonicalNaNBits
    } otherwise {
      val (mantissaBits, doubledMantissaBits, unbiasedExponent, mantissaIndex) = normalizePositiveFinite(absValue)
      val oddExponent = unbiasedExponent(0)
      val normalizedOperand = Bits(32 bits)
      normalizedOperand := mantissaBits
      when(oddExponent) {
        normalizedOperand := doubledMantissaBits
      }

      val seed = Bits(32 bits)
      seed := rsqrtEvenSeedTable(mantissaIndex)
      when(oddExponent) {
        seed := rsqrtOddSeedTable(mantissaIndex)
      }

      val seedSquared = Fp32Math.mul(seed, seed)
      val halfTimesOperand = Fp32Math.mul(HalfBits, normalizedOperand)
      val scaledTerm = Fp32Math.mul(halfTimesOperand, seedSquared)
      val correction = Fp32Math.sub(OnePointFiveBits, scaledTerm)
      val refined = Fp32Math.mul(seed, correction)

      val adjustedExponent = SInt(IndexIntWidth bits)
      adjustedExponent := unbiasedExponent
      when(oddExponent) {
        adjustedExponent := unbiasedExponent - S(1, IndexIntWidth bits)
      }

      result := scaleByPow2(refined, -(adjustedExponent >> 1), False)
    }

    result
  }

  private def sqrtApproxF32(value: Bits): Bits = {
    val absValue = Fp32Math.abs(value)
    val exponent = absValue(30 downto 23).asUInt
    val fraction = absValue(22 downto 0).asUInt
    val isZero = absValue(30 downto 0) === 0
    val isInf = exponent === 0xFF && fraction === 0
    val isNaN = exponent === 0xFF && fraction =/= 0
    val sign = value(31)

    val result = Bits(32 bits)
    result := ZeroBits

    when(isNaN) {
      result := CanonicalNaNBits
    } elsewhen (isInf) {
      result := Mux(sign, CanonicalNaNBits, PositiveInfinityBits)
    } elsewhen (isZero) {
      result := value
    } elsewhen (sign) {
      result := CanonicalNaNBits
    } otherwise {
      result := Fp32Math.mul(absValue, rsqrtApproxF32(absValue))
    }

    result
  }

  private def lg2ApproxF32(value: Bits): Bits = {
    val absValue = Fp32Math.abs(value)
    val exponent = absValue(30 downto 23).asUInt
    val fraction = absValue(22 downto 0).asUInt
    val isZero = absValue(30 downto 0) === 0
    val isInf = exponent === 0xFF && fraction === 0
    val isNaN = exponent === 0xFF && fraction =/= 0
    val sign = value(31)

    val result = Bits(32 bits)
    result := ZeroBits

    when(isNaN) {
      result := CanonicalNaNBits
    } elsewhen (isZero) {
      result := NegativeInfinityBits
    } elsewhen (isInf) {
      result := PositiveInfinityBits
    } elsewhen (sign) {
      result := CanonicalNaNBits
    } otherwise {
      val (_, _, unbiasedExponent, mantissaIndex) = normalizePositiveFinite(absValue)
      result := Fp32Math.add(intToFloatBits(unbiasedExponent), log2MantissaTable(mantissaIndex))
    }

    result
  }

  private def ex2ApproxF32(value: Bits): Bits = {
    val exponent = value(30 downto 23).asUInt
    val fraction = value(22 downto 0).asUInt
    val sign = value(31)
    val isZero = exponent === 0 && fraction === 0
    val isInf = exponent === 0xFF && fraction === 0
    val isNaN = exponent === 0xFF && fraction =/= 0
    val valueBelowMin = Fp32Math.lt(value, Ex2MinInputBits)
    val valueAtOrAboveMax = Fp32Math.lt(Ex2MaxInputBits, value) || Fp32Math.eq(value, Ex2MaxInputBits)

    val result = Bits(32 bits)
    result := ZeroBits

    when(isNaN) {
      result := CanonicalNaNBits
    } elsewhen (isInf) {
      result := Mux(sign, ZeroBits, PositiveInfinityBits)
    } elsewhen (isZero) {
      result := OneBits
    } elsewhen (valueAtOrAboveMax) {
      result := PositiveInfinityBits
    } elsewhen (valueBelowMin) {
      result := ZeroBits
    } otherwise {
      val integerPart = floorToSInt(value)
      val integerBits = intToFloatBits(integerPart)
      val fractional = Fp32Math.sub(value, integerBits)
      when(Fp32Math.eq(fractional, ZeroBits)) {
        result := scaleByPow2(OneBits, integerPart, False)
      } otherwise {
        val fractionIndex = indexFromPositiveFloat(Fp32Math.abs(fractional), Ex2IndexScaleBits, ex2FractionTable.length)
        result := scaleByPow2(ex2FractionTable(fractionIndex), integerPart, False)
      }
    }

    result
  }

  private def trigApproxF32(value: Bits, cosine: Boolean): Bits = {
    val absValue = Fp32Math.abs(value)
    val exponent = absValue(30 downto 23).asUInt
    val fraction = absValue(22 downto 0).asUInt
    val isZero = exponent === 0 && fraction === 0
    val isInf = exponent === 0xFF && fraction === 0
    val isNaN = exponent === 0xFF && fraction =/= 0
    val sign = value(31)

    val result = Bits(32 bits)
    result := ZeroBits

    when(isNaN || isInf) {
      result := CanonicalNaNBits
    } elsewhen (isZero) {
      result := (if (cosine) OneBits else value)
    } otherwise {
      val scaled = Fp32Math.mul(absValue, InvHalfPiBits)
      val quadrant = floorToSInt(scaled)
      val quadrantBits = intToFloatBits(quadrant)
      val fractional = Fp32Math.sub(scaled, quadrantBits)
      val fractionIndex = indexFromPositiveFloat(Fp32Math.abs(fractional), TrigIndexScaleBits, sinQuarterTable.length)
      val quarterSin = sinQuarterTable(fractionIndex)
      val quarterCos = cosQuarterTable(fractionIndex)
      val quadrantPhase = quadrant.asUInt(1 downto 0)

      val positiveResult = Bits(32 bits)
      positiveResult := quarterSin

      switch(quadrantPhase) {
        is(U(0, 2 bits)) {
          positiveResult := (if (cosine) quarterCos else quarterSin)
        }
        is(U(1, 2 bits)) {
          positiveResult := (if (cosine) Fp32Math.neg(quarterSin) else quarterCos)
        }
        is(U(2, 2 bits)) {
          positiveResult := (if (cosine) Fp32Math.neg(quarterCos) else Fp32Math.neg(quarterSin))
        }
        default {
          positiveResult := (if (cosine) quarterSin else Fp32Math.neg(quarterCos))
        }
      }

      result := positiveResult
      if (!cosine) {
        when(sign) {
          result := Fp32Math.neg(positiveResult)
        }
      }
    }

    result
  }

  private def tanhApproxF32(value: Bits): Bits = {
    val absValue = Fp32Math.abs(value)
    val exponent = absValue(30 downto 23).asUInt
    val fraction = absValue(22 downto 0).asUInt
    val isZero = exponent === 0 && fraction === 0
    val isInf = exponent === 0xFF && fraction === 0
    val isNaN = exponent === 0xFF && fraction =/= 0
    val sign = value(31)
    val passThrough = Fp32Math.lt(absValue, TanhPassThroughBits)
    val saturate = Fp32Math.lt(TanhSaturationBits, absValue) || Fp32Math.eq(absValue, TanhSaturationBits)

    val result = Bits(32 bits)
    result := ZeroBits

    when(isNaN) {
      result := CanonicalNaNBits
    } elsewhen (isInf) {
      result := Mux(sign, NegativeOneBits, OneBits)
    } elsewhen (isZero || passThrough) {
      result := value
    } elsewhen (saturate) {
      result := Mux(sign, NegativeOneBits, OneBits)
    } otherwise {
      val tanhIndex = indexFromPositiveFloat(absValue, TanhIndexScaleBits, tanhPositiveTable.length)
      result := withSign(tanhPositiveTable(tanhIndex), sign)
    }

    result
  }

  private def halfUnary(opcode: Int, operand: Bits): Bits = {
    val fp32Input = Fp16Math.toFp32(operand(15 downto 0))
    val fp32Result =
      opcode match {
        case Opcode.FEX2 => ex2ApproxF32(fp32Input)
        case Opcode.FTANH => tanhApproxF32(fp32Input)
      }
    (B(0, 16 bits) ## Fp16Math.fromFp32(fp32Result)).resized
  }

  private def half2Unary(opcode: Int, operand: Bits): Bits = {
    val low = halfUnary(opcode, operand(15 downto 0).resize(32))
    val high = halfUnary(opcode, operand(31 downto 16).resize(32))
    Fp16Math.pack2(low(15 downto 0), high(15 downto 0))
  }

  def applyOpcode(opcode: Bits, operand: Bits): Bits = {
    val result = Bits(32 bits)
    result := ZeroBits

    switch(opcode) {
      is(B(Opcode.FRCP, 8 bits)) {
        result := rcpApproxF32(operand)
      }
      is(B(Opcode.FSQRT, 8 bits)) {
        result := sqrtApproxF32(operand)
      }
      is(B(Opcode.FRSQRT, 8 bits)) {
        result := rsqrtApproxF32(operand)
      }
      is(B(Opcode.FSIN, 8 bits)) {
        result := trigApproxF32(operand, cosine = false)
      }
      is(B(Opcode.FCOS, 8 bits)) {
        result := trigApproxF32(operand, cosine = true)
      }
      is(B(Opcode.FLG2, 8 bits)) {
        result := lg2ApproxF32(operand)
      }
      is(B(Opcode.FEX2, 8 bits)) {
        result := ex2ApproxF32(operand)
      }
      is(B(Opcode.FTANH, 8 bits)) {
        result := tanhApproxF32(operand)
      }
      is(B(Opcode.HEX2, 8 bits)) {
        result := halfUnary(Opcode.FEX2, operand)
      }
      is(B(Opcode.HTANH, 8 bits)) {
        result := halfUnary(Opcode.FTANH, operand)
      }
      is(B(Opcode.HEX2X2, 8 bits)) {
        result := half2Unary(Opcode.FEX2, operand)
      }
      is(B(Opcode.HTANHX2, 8 bits)) {
        result := half2Unary(Opcode.FTANH, operand)
      }
    }

    result
  }
}
