package spinalgpu

import spinal.core._
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axilite.AxiLite4Config

case class GpuClusterConfig(smCount: Int = 1) {
  require(smCount > 0, "smCount must be positive")

  val smIdWidth: Int = log2Up(smCount max 2)
}

case class HostControlConfig(controlAddressWidth: Int = 8) {
  require(controlAddressWidth > 0, "controlAddressWidth must be positive")
}

case class GpuConfig(
    cluster: GpuClusterConfig = GpuClusterConfig(),
    sm: SmConfig = SmConfig.default,
    host: HostControlConfig = HostControlConfig()
) {
  val smCount: Int = cluster.smCount
  val smIdWidth: Int = cluster.smIdWidth
  val addressWidth: Int = sm.addressWidth
  val dataWidth: Int = sm.dataWidth
  val axiIdWidth: Int = sm.axiIdWidth
  val byteCount: Int = sm.byteCount
  val byteMaskWidth: Int = sm.byteMaskWidth
  val threadCountWidth: Int = sm.threadCountWidth
  val sharedBytesWidth: Int = sm.sharedBytesWidth
  val faultCodeWidth: Int = sm.faultCodeWidth
  val controlAddressWidth: Int = host.controlAddressWidth

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

object GpuConfig {
  val default: GpuConfig = GpuConfig()
}
