package spinalgpu

import spinal.core._

class SpecialRegisterReadUnit(config: SmConfig) extends Component {
  private val blockThreadCountWidth = config.threadCountWidth * 3

  val io = new Bundle {
    val decoded = in(DecodedInstruction(config))
    val selectedWarpId = in(UInt(config.warpIdWidth bits))
    val selectedContext = in(WarpContext(config))
    val currentCommand = in(CtaCommandDesc(config))
    val values = out(Vec(UInt(config.dataWidth bits), config.warpSize))
  }

  private val currentBlockThreadCount =
    (io.currentCommand.blockDimX.resize(blockThreadCountWidth bits) *
      io.currentCommand.blockDimY.resize(blockThreadCountWidth bits) *
      io.currentCommand.blockDimZ.resize(blockThreadCountWidth bits)).resized
  private val blockWarpCount =
    ((currentBlockThreadCount.resized.resize(config.dataWidth bits) + U(config.warpSize - 1, config.dataWidth bits)) /
      U(config.warpSize, config.dataWidth bits)).resized
  private val gridIdLow = io.currentCommand.gridId(31 downto 0).resize(config.dataWidth)
  private val gridIdHigh = io.currentCommand.gridId(63 downto 32).resize(config.dataWidth)

  private val laneTidX = Vec(UInt(config.threadCountWidth bits), config.warpSize)
  private val laneTidY = Vec(UInt(config.threadCountWidth bits), config.warpSize)
  private val laneTidZ = Vec(UInt(config.threadCountWidth bits), config.warpSize)
  laneTidX(0) := io.selectedContext.threadBaseX
  laneTidY(0) := io.selectedContext.threadBaseY
  laneTidZ(0) := io.selectedContext.threadBaseZ
  for (lane <- 1 until config.warpSize) {
    val (nextX, nextY, nextZ) = ThreadCoordinateLogic.increment(
      config,
      laneTidX(lane - 1),
      laneTidY(lane - 1),
      laneTidZ(lane - 1),
      io.currentCommand.blockDimX,
      io.currentCommand.blockDimY,
      io.currentCommand.blockDimZ
    )
    laneTidX(lane) := nextX
    laneTidY(lane) := nextY
    laneTidZ(lane) := nextZ
  }

  for (lane <- 0 until config.warpSize) {
    io.values(lane) := U(0, config.dataWidth bits)
    switch(io.decoded.specialRegister) {
      is(U(SpecialRegisterKind.TidX, config.specialRegisterWidth bits)) {
        io.values(lane) := laneTidX(lane).resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.TidY, config.specialRegisterWidth bits)) {
        io.values(lane) := laneTidY(lane).resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.TidZ, config.specialRegisterWidth bits)) {
        io.values(lane) := laneTidZ(lane).resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.LaneId, config.specialRegisterWidth bits)) {
        io.values(lane) := U(lane, config.dataWidth bits)
      }
      is(U(SpecialRegisterKind.WarpId, config.specialRegisterWidth bits)) {
        io.values(lane) := io.selectedWarpId.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.NtidX, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.blockDimX.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.NtidY, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.blockDimY.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.NtidZ, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.blockDimZ.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.CtaidX, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.ctaidX
      }
      is(U(SpecialRegisterKind.CtaidY, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.ctaidY
      }
      is(U(SpecialRegisterKind.CtaidZ, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.ctaidZ
      }
      is(U(SpecialRegisterKind.NctaidX, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.gridDimX
      }
      is(U(SpecialRegisterKind.NctaidY, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.gridDimY
      }
      is(U(SpecialRegisterKind.NctaidZ, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.gridDimZ
      }
      is(U(SpecialRegisterKind.NwarpId, config.specialRegisterWidth bits)) {
        io.values(lane) := blockWarpCount
      }
      is(U(SpecialRegisterKind.SmId, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.smId.resize(config.dataWidth)
      }
      is(U(SpecialRegisterKind.NsmId, config.specialRegisterWidth bits)) {
        io.values(lane) := U(config.smCount, config.dataWidth bits)
      }
      is(U(SpecialRegisterKind.GridIdLo, config.specialRegisterWidth bits)) {
        io.values(lane) := gridIdLow
      }
      is(U(SpecialRegisterKind.GridIdHi, config.specialRegisterWidth bits)) {
        io.values(lane) := gridIdHigh
      }
      is(U(SpecialRegisterKind.ArgBase, config.specialRegisterWidth bits)) {
        io.values(lane) := io.currentCommand.argBase.resize(config.dataWidth)
      }
    }
  }
}
