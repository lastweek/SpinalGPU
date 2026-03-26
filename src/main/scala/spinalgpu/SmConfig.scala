package spinalgpu

import spinal.core._
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axilite.AxiLite4Config

case class SmConfig(
    warpSize: Int = 32,
    schedulerCount: Int = 1,
    cudaLaneCount: Int = 8,
    lsuCount: Int = 1,
    sfuCount: Int = 1,
    tensorCoreCount: Int = 1,
    sharedMemoryBankCount: Int = 32,
    sharedMemoryBytes: Int = 4 * 1024,
    residentWarpCount: Int = 4,
    addressWidth: Int = 32,
    dataWidth: Int = 32,
    axiIdWidth: Int = 1,
    registerCount: Int = 32,
    controlAddressWidth: Int = 8
) {
  require(warpSize > 0 && warpSize % 8 == 0, "warpSize must be a positive multiple of 8")
  require(schedulerCount > 0, "schedulerCount must be positive")
  require(cudaLaneCount > 0, "cudaLaneCount must be positive")
  require(lsuCount > 0, "lsuCount must be positive")
  require(sfuCount > 0, "sfuCount must be positive")
  require(tensorCoreCount > 0, "tensorCoreCount must be positive")
  require(sharedMemoryBankCount > 0, "sharedMemoryBankCount must be positive")
  require(sharedMemoryBytes > 0, "sharedMemoryBytes must be positive")
  require(residentWarpCount > 0, "residentWarpCount must be positive")
  require(dataWidth % 8 == 0, "dataWidth must be a multiple of 8")
  require(registerCount == 32, "v1 frontend assumes 32 general-purpose registers")

  val instructionWidth: Int = 32
  val pcWidth: Int = addressWidth
  val byteCount: Int = dataWidth / 8
  val byteMaskWidth: Int = byteCount
  val warpIdWidth: Int = log2Up(residentWarpCount max 2)
  val registerAddressWidth: Int = log2Up(registerCount max 2)
  val maxBlockThreads: Int = residentWarpCount * warpSize
  val threadCountWidth: Int = log2Up(maxBlockThreads + 1)
  val sharedBytesWidth: Int = log2Up(sharedMemoryBytes + 1)
  val sharedWordCount: Int = sharedMemoryBytes / byteCount
  val sharedAddressWidth: Int = log2Up(sharedWordCount max 2)
  val sharedBankIndexWidth: Int = log2Up(sharedMemoryBankCount max 2)
  val specialRegisterWidth: Int = 5
  val faultCodeWidth: Int = 8

  def axiConfig: Axi4Config =
    Axi4Config(
      addressWidth = addressWidth,
      dataWidth = dataWidth,
      idWidth = axiIdWidth,
      useId = false,
      withAxi3 = false,
      useRegion = false,
      useBurst = true,
      useLock = false,
      useCache = false,
      useSize = true,
      useQos = false,
      useLen = true,
      useLast = true,
      useResp = true,
      useProt = false,
      useStrb = true
    )

  def axiLiteConfig: AxiLite4Config =
    AxiLite4Config(
      addressWidth = controlAddressWidth,
      dataWidth = dataWidth
    )
}

object SmConfig {
  val default: SmConfig = SmConfig()
}
