package spinalgpu

import spinal.core._

object GenerateGpuTop extends App {
  SpinalConfig(
    targetDirectory = "generated/verilog",
    oneFilePerComponent = true
  ).generateVerilog(new GpuTop)
}
