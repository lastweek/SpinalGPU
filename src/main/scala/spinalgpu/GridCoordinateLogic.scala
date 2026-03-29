package spinalgpu

import spinal.core._

object GridCoordinateLogic {
  def increment(
      config: SmConfig,
      x: UInt,
      y: UInt,
      z: UInt,
      dimX: UInt,
      dimY: UInt,
      dimZ: UInt
  ): (UInt, UInt, UInt) = {
    val nextX = UInt(config.dataWidth bits)
    val nextY = UInt(config.dataWidth bits)
    val nextZ = UInt(config.dataWidth bits)
    val xPlusOne = (x + 1).resized
    val yPlusOne = (y + 1).resized
    val zPlusOne = (z + 1).resized
    val wrapX = xPlusOne === dimX
    val wrapY = yPlusOne === dimY
    val wrapZ = zPlusOne === dimZ

    nextX := xPlusOne
    nextY := y
    nextZ := z

    when(wrapX) {
      nextX := 0
      nextY := yPlusOne
      when(wrapY) {
        nextY := 0
        nextZ := zPlusOne
        when(wrapZ) {
          nextZ := 0
        }
      }
    }

    (nextX, nextY, nextZ)
  }
}
