package spinalgpu.toolchain

import java.nio.file.Path

object BuildKernelCorpus {
  def buildAll(outputRoot: Path = KernelCatalog.outputRoot): Seq[Path] = {
    KernelCatalog.all.map { artifact =>
      val program = PtxAssembler.assembleFile(artifact.sourcePath)
      val outputPath = outputRoot.resolve(artifact.relativeBinaryPath).normalize()
      KernelBinaryIO.writeWords(outputPath, program.words)
      outputPath
    }
  }

  def main(args: Array[String]): Unit = {
    require(args.isEmpty, "usage: spinalgpu.toolchain.BuildKernelCorpus")
    buildAll().foreach(path => println(s"built ${path.toString}"))
  }
}
