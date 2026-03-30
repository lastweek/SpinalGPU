package spinalgpu.toolchain

import java.nio.file.Path

object BuildBenchmarkKernelCorpus {
  def buildAll(outputRoot: Path = KernelCorpus.outputRoot): Seq[Path] =
    BuildKernelCorpus.buildCases(
      BenchmarkKernelCatalog.all.map { kernel =>
        KernelCorpus.KernelCase(
          name = kernel.name,
          relativeSourcePath = kernel.relativeSourcePath,
          purpose = s"Benchmark kernel ${kernel.name}",
          primaryFeature = KernelCorpus.KernelFeature.Arithmetic,
          teachingLevel = KernelCorpus.KernelLevel.Core,
          command = KernelCorpus.KernelCommand(entryPc = 0x100, blockDimX = 1, argBase = 0),
          expectation = KernelCorpus.KernelExpectation.Success(checks = Seq.empty),
          harnessTargets = Seq(KernelCorpus.HarnessTarget.GpuTop)
        )
      },
      outputRoot
    )

  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "usage: spinalgpu.toolchain.BuildBenchmarkKernelCorpus")
    buildAll().foreach(path => println(s"built ${path.toString}"))
  }
}

