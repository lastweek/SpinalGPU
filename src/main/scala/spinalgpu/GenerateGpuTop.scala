package spinalgpu

import java.nio.file.Files
import java.nio.file.Path
import spinal.core._

object GenerateGpuTop extends App {
  private val targetDir = Path.of("generated/verilog")
  private val config =
    args.toList match {
      case Nil => GpuConfig.default
      case List(smCount, subSmCount, residentWarpsPerSubSm, subSmIssueWidth, sharedMemoryBytes) =>
        GpuConfig(
          cluster = GpuClusterConfig(smCount = smCount.toInt),
          sm = SmConfig(
            subSmCount = subSmCount.toInt,
            residentWarpsPerSubSm = residentWarpsPerSubSm.toInt,
            subSmIssueWidth = subSmIssueWidth.toInt,
            sharedMemoryBytes = sharedMemoryBytes.toInt
          )
        )
      case List(subSmCount, residentWarpsPerSubSm, subSmIssueWidth, sharedMemoryBytes) =>
        GpuConfig(
          sm = SmConfig(
            subSmCount = subSmCount.toInt,
            residentWarpsPerSubSm = residentWarpsPerSubSm.toInt,
            subSmIssueWidth = subSmIssueWidth.toInt,
            sharedMemoryBytes = sharedMemoryBytes.toInt
          )
        )
      case _ =>
        sys.error(
          "usage: GenerateGpuTop [smCount subSmCount residentWarpsPerSubSm subSmIssueWidth sharedMemoryBytes] " +
            "or GenerateGpuTop [subSmCount residentWarpsPerSubSm subSmIssueWidth sharedMemoryBytes]; " +
            "the first form overrides GpuConfig.cluster.smCount and the second keeps the default single-SM GpuConfig"
        )
    }

  if (Files.exists(targetDir)) {
    Files
      .walk(targetDir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(Files.delete)
  }

  SpinalConfig(
    targetDirectory = targetDir.toString,
    oneFilePerComponent = true
  ).generateVerilog(new GpuTop(config))
}
