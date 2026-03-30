package spinalgpu.toolchain

import java.nio.file.Path

object BuildKernelCorpus {
  def buildCases(kernels: Seq[KernelCorpus.KernelCase], outputRoot: Path = KernelCorpus.outputRoot): Seq[Path] = {
    kernels.map { kernel =>
      val program = PtxAssembler.assembleFile(kernel.sourcePath)
      val outputPath = outputRoot.resolve(kernel.relativeBinaryPath).normalize()
      KernelBinaryIO.writeWords(outputPath, program.words)
      outputPath
    }
  }

  def buildAll(outputRoot: Path = KernelCorpus.outputRoot): Seq[Path] =
    buildCases(KernelCorpus.all, outputRoot)

  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "usage: spinalgpu.toolchain.BuildKernelCorpus")
    buildAll().foreach(path => println(s"built ${path.toString}"))
  }
}

object BuildTensorKernelCorpus {
  def buildAll(outputRoot: Path = KernelCorpus.outputRoot): Seq[Path] =
    BuildKernelCorpus.buildCases(KernelCorpus.tensorCases, outputRoot)

  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "usage: spinalgpu.toolchain.BuildTensorKernelCorpus")
    buildAll().foreach(path => println(s"built ${path.toString}"))
  }
}
