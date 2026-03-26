package spinalgpu

import java.nio.file.Files
import java.nio.file.Path
import spinal.core._

object GenerateGpuTop extends App {
  private val targetDir = Path.of("generated/verilog")

  if (Files.exists(targetDir)) {
    Files
      .walk(targetDir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(Files.delete)
  }

  SpinalConfig(
    targetDirectory = targetDir.toString,
    oneFilePerComponent = true
  ).generateVerilog(new GpuTop)
}
