package spinalgpu.toolchain

import java.nio.file.Path

object BuildKernelCorpus {
  def buildAll(outputRoot: Path = KernelCorpus.outputRoot): Seq[Path] = {
    KernelCorpus.all.map { kernel =>
      val program = PtxAssembler.assembleFile(kernel.sourcePath)
      val outputPath = outputRoot.resolve(kernel.relativeBinaryPath).normalize()
      KernelBinaryIO.writeWords(outputPath, program.words)
      outputPath
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "usage: spinalgpu.toolchain.BuildKernelCorpus")
    buildAll().foreach(path => println(s"built ${path.toString}"))
  }
}
