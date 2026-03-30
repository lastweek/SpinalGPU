package spinalgpu

object Tcgen05Layouts {
  val TransferShapeWords = 64
  val TransferRegisterCount = 2
  val DescriptorRegisterCount = 2
  val ControlRegisterCount = 2

  val M = 16
  val N = 8
  val K = 16
  val AWordsPerRow = K / 2
  val BWordsPerRow = N / 2
  val DWordsPerRow = N / 2
  val AWordCount = M * AWordsPerRow
  val BWordCount = K * BWordsPerRow
  val DWordCount = M * DWordsPerRow
  val MaxWordCount = Seq(AWordCount, BWordCount, DWordCount, TransferShapeWords).max
  val TransferBytes = TransferShapeWords * 4

  val FullWarpMask32: BigInt = (BigInt(1) << 32) - 1
  val F16InstructionDescriptor: Int = 0x10
  val ControlEnableInputDMask: Int = 0x1

  def isTcgen05Opcode(opcode: Int): Boolean =
    opcode match {
      case Opcode.TCGEN05_LD_32X32B_X2 | Opcode.TCGEN05_ST_32X32B_X2 | Opcode.TCGEN05_WAIT_LD |
          Opcode.TCGEN05_WAIT_ST | Opcode.TCGEN05_MMA_CTA1_F16 | Opcode.TCGEN05_COMMIT_CTA1 =>
        true
      case _ => false
    }

  def launchedOpClass(opcode: Int): Tcgen05OpClass.C =
    opcode match {
      case Opcode.TCGEN05_LD_32X32B_X2 => Tcgen05OpClass.LD
      case Opcode.TCGEN05_ST_32X32B_X2 => Tcgen05OpClass.ST
      case Opcode.TCGEN05_MMA_CTA1_F16 => Tcgen05OpClass.MMA
      case _ => Tcgen05OpClass.NONE
    }

  def wordLane(wordIndex: Int): Int = wordIndex & 0x1F

  def wordRegister(wordIndex: Int): Int = wordIndex >> 5

  def aWordIndex(row: Int, col: Int): Int = (row * AWordsPerRow) + (col >> 1)

  def bWordIndex(row: Int, col: Int): Int = (row * BWordsPerRow) + (col >> 1)

  def dWordIndex(row: Int, col: Int): Int = (row * DWordsPerRow) + (col >> 1)

  def matrixHalfIndex(col: Int): Int = col & 0x1
}
