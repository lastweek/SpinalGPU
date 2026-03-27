package spinalgpu

import spinal.core._

object ThreadCoordinateLogic {
  def increment(
      config: SmConfig,
      x: UInt,
      y: UInt,
      z: UInt,
      dimX: UInt,
      dimY: UInt,
      dimZ: UInt
  ): (UInt, UInt, UInt) = {
    val nextX = UInt(config.threadCountWidth bits)
    val nextY = UInt(config.threadCountWidth bits)
    val nextZ = UInt(config.threadCountWidth bits)
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

  def linearToCoords(
      config: SmConfig,
      linearIndex: UInt,
      dimX: UInt,
      dimY: UInt,
      dimZ: UInt
  ): (UInt, UInt, UInt) = {
    val coordX = UInt(config.threadCountWidth bits)
    val coordY = UInt(config.threadCountWidth bits)
    val coordZ = UInt(config.threadCountWidth bits)
    coordX := 0
    coordY := 0
    coordZ := 0

    var walkX: UInt = U(0, config.threadCountWidth bits)
    var walkY: UInt = U(0, config.threadCountWidth bits)
    var walkZ: UInt = U(0, config.threadCountWidth bits)

    for (step <- 0 until config.maxBlockThreads) {
      when(linearIndex === step) {
        coordX := walkX
        coordY := walkY
        coordZ := walkZ
      }

      if (step != config.maxBlockThreads - 1) {
        val (nextX, nextY, nextZ) = increment(config, walkX, walkY, walkZ, dimX, dimY, dimZ)
        walkX = nextX
        walkY = nextY
        walkZ = nextZ
      }
    }

    (coordX, coordY, coordZ)
  }
}
