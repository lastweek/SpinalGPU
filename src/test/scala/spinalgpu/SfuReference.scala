package spinalgpu

object SfuReference {
  def fp32Bits(value: Float): Int = SfuApproxModel.fp32Bits(value)

  def fp32FromBits(bits: Int): Float = SfuApproxModel.fp32FromBits(bits)

  def applyFp32(opcode: Int, operandBits: Int): Int = SfuApproxModel.applyFp32Opcode(opcode, operandBits)

  def applyRegister(opcode: Int, operandBits: Int): Int = SfuApproxModel.applyRegisterOpcode(opcode, operandBits)

  def apply(opcode: Int, operandBits: Int): Int = applyRegister(opcode, operandBits)

  def halfBits(value: Float): Int = LowPrecisionCodec.floatToHalfBits(value)

  def packHalf2(low: Float, high: Float): Int = LowPrecisionCodec.packHalf2(halfBits(low), halfBits(high))

  def canonicalNaNBits: Int = SfuApproxModel.canonicalNaNBits
}
