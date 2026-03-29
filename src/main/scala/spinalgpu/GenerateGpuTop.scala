package spinalgpu

import java.nio.file.Files
import java.nio.file.Path
import spinal.core._

object GenerateGpuTop extends App {
  private val targetDir = Path.of("generated/verilog")
  private val config =
    args.toList match {
      case Nil => SmConfig.default
      case List(smCount, subSmCount, residentWarpsPerSubSm, subSmIssueWidth, sharedMemoryBytes) =>
        SmConfig(
          smCount = smCount.toInt,
          subSmCount = subSmCount.toInt,
          residentWarpsPerSubSm = residentWarpsPerSubSm.toInt,
          subSmIssueWidth = subSmIssueWidth.toInt,
          sharedMemoryBytes = sharedMemoryBytes.toInt
        )
      case List(subSmCount, residentWarpsPerSubSm, subSmIssueWidth, sharedMemoryBytes) =>
        SmConfig(
          subSmCount = subSmCount.toInt,
          residentWarpsPerSubSm = residentWarpsPerSubSm.toInt,
          subSmIssueWidth = subSmIssueWidth.toInt,
          sharedMemoryBytes = sharedMemoryBytes.toInt
        )
      case _ =>
        sys.error(
          "usage: GenerateGpuTop [smCount subSmCount residentWarpsPerSubSm subSmIssueWidth sharedMemoryBytes] " +
            "or GenerateGpuTop [subSmCount residentWarpsPerSubSm subSmIssueWidth sharedMemoryBytes]"
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
