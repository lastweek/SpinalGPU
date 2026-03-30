package spinalgpu

object TensorCoreLayouts {
  val M = 16
  val N = 8
  val K = 16
  val TileRows = 8
  val TileCols = 8

  val LdmatrixX4WriteCount = 4
  val LdmatrixX2WriteCount = 2
  val MmaWriteCount = 2
  val StmatrixWriteCount = 0

  val FullWarpMask32: BigInt = (BigInt(1) << 32) - 1

  def isTensorOpcode(opcode: Int): Boolean =
    opcode >= Opcode.tensorBase && opcode <= Opcode.tensorLast

  def tensorWritesRd(opcode: Int): Boolean =
    opcode match {
      case Opcode.LDMATRIX_X4 | Opcode.LDMATRIX_X2 | Opcode.LDMATRIX_X2_TRANS | Opcode.MMA_SYNC_F16_F16_F16_F16 => true
      case Opcode.STMATRIX_X2 => false
      case _ => false
    }

  def tensorUsesRs1(opcode: Int): Boolean =
    opcode match {
      case Opcode.MMA_SYNC_F16_F16_F16_F16 | Opcode.STMATRIX_X2 => true
      case _ => false
    }

  def tensorUsesRs2(opcode: Int): Boolean =
    opcode == Opcode.MMA_SYNC_F16_F16_F16_F16

  def tensorWriteCount(opcode: Int): Int =
    opcode match {
      case Opcode.LDMATRIX_X4 => LdmatrixX4WriteCount
      case Opcode.LDMATRIX_X2 | Opcode.LDMATRIX_X2_TRANS => LdmatrixX2WriteCount
      case Opcode.MMA_SYNC_F16_F16_F16_F16 => MmaWriteCount
      case Opcode.STMATRIX_X2 => StmatrixWriteCount
      case _ => 0
    }

  def rowMajorLane(row: Int, col: Int): Int = (row * 4) + (col / 2)

  def rowMajorHalf(col: Int): Int = col & 0x1

  def aRegister(row: Int, col: Int): Int =
    ((if (col >= 8) 2 else 0) + (if (row >= 8) 1 else 0))

  def aLane(row: Int, col: Int): Int = rowMajorLane(row & 0x7, col & 0x7)

  def aHalf(col: Int): Int = rowMajorHalf(col)

  def bRegister(row: Int): Int = if (row >= 8) 1 else 0

  def bLane(row: Int, col: Int): Int = (col * 4) + (row & 0x3)

  def bHalf(row: Int): Int = (row >> 2) & 0x1

  def cdRegister(row: Int): Int = if (row >= 8) 1 else 0

  def cdLane(row: Int, col: Int): Int = rowMajorLane(row & 0x7, col)

  def cdHalf(col: Int): Int = rowMajorHalf(col)

  def ldmatrixRow(lane: Int): Int = lane >> 2

  def ldmatrixColPair(lane: Int): Int = (lane & 0x3) * 2

  def ldmatrixColumn(lane: Int): Int = lane >> 2

  def ldmatrixTransRowLow(lane: Int): Int = lane & 0x3

  def ldmatrixTransRowHigh(lane: Int): Int = (lane & 0x3) + 4
}
