package spinalgpu

import spinal.core._

case class SmConfig(
    warpSize: Int = 32,
    subSmCount: Int = 4,
    residentWarpsPerSubSm: Int = 2,
    subSmIssueWidth: Int = 32,
    cudaIntegerLatency: Int = 1,
    fpAddLatency: Int = 4,
    fpMulLatency: Int = 4,
    fpFmaLatency: Int = 5,
    fp16ScalarLatency: Int = 4,
    fp16x2Latency: Int = 4,
    fp8ConvertLatency: Int = 4,
    lsuCount: Int = 1,
    sfuCount: Int = 1,
    tensorCoreCount: Int = 1,
    sharedMemoryBankCount: Int = 32,
    sharedMemoryBytes: Int = 4 * 1024,
    addressWidth: Int = 32,
    dataWidth: Int = 32,
    axiIdWidth: Int = 1,
    registerCount: Int = 32
) {
  require(warpSize > 0 && warpSize % 8 == 0, "warpSize must be a positive multiple of 8")
  require(subSmCount > 0, "subSmCount must be positive")
  require(residentWarpsPerSubSm > 0, "residentWarpsPerSubSm must be positive")
  require(subSmIssueWidth > 0, "subSmIssueWidth must be positive")
  require(warpSize % subSmIssueWidth == 0, "warpSize must be an integer multiple of subSmIssueWidth")
  require(cudaIntegerLatency > 0, "cudaIntegerLatency must be positive")
  require(fpAddLatency > 0, "fpAddLatency must be positive")
  require(fpMulLatency > 0, "fpMulLatency must be positive")
  require(fpFmaLatency > 0, "fpFmaLatency must be positive")
  require(fp16ScalarLatency > 0, "fp16ScalarLatency must be positive")
  require(fp16x2Latency > 0, "fp16x2Latency must be positive")
  require(fp8ConvertLatency > 0, "fp8ConvertLatency must be positive")
  require(lsuCount > 0, "lsuCount must be positive")
  require(sfuCount > 0, "sfuCount must be positive")
  require(tensorCoreCount > 0, "tensorCoreCount must be positive")
  require(sharedMemoryBankCount > 0, "sharedMemoryBankCount must be positive")
  require(sharedMemoryBytes > 0, "sharedMemoryBytes must be positive")
  require(dataWidth % 8 == 0, "dataWidth must be a multiple of 8")
  require(registerCount == 32, "v1 frontend assumes 32 general-purpose registers")

  val schedulerCount: Int = subSmCount
  val cudaLaneCount: Int = subSmIssueWidth
  val residentWarpCount: Int = subSmCount * residentWarpsPerSubSm
  val instructionWidth: Int = 32
  val pcWidth: Int = addressWidth
  val byteCount: Int = dataWidth / 8
  val byteMaskWidth: Int = byteCount
  val warpIdWidth: Int = log2Up(residentWarpCount max 2)
  val subSmIdWidth: Int = log2Up(subSmCount max 2)
  val localSlotIdWidth: Int = log2Up(residentWarpsPerSubSm max 2)
  val registerAddressWidth: Int = log2Up(registerCount max 2)
  val maxBlockThreads: Int = residentWarpCount * warpSize
  val threadCountWidth: Int = log2Up(maxBlockThreads + 1)
  val sharedBytesWidth: Int = log2Up(sharedMemoryBytes + 1)
  val sharedWordCount: Int = sharedMemoryBytes / byteCount
  val sharedAddressWidth: Int = log2Up(sharedWordCount max 2)
  val sharedBankIndexWidth: Int = log2Up(sharedMemoryBankCount max 2)
  val specialRegisterWidth: Int = 5
  val faultCodeWidth: Int = 8
  val globalBurstBeatCountWidth: Int = log2Up(cudaLaneCount + 1)
}

object SmConfig {
  val default: SmConfig = SmConfig()
}
