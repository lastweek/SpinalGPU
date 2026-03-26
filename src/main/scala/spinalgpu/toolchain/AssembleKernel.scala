package spinalgpu.toolchain

import java.nio.file.Path

object AssembleKernel {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "usage: spinalgpu.toolchain.AssembleKernel <input.gpuasm> <output.bin>")

    val inputPath = Path.of(args(0))
    val outputPath = Path.of(args(1))
    val program = Assembler.assembleFile(inputPath)
    KernelBinaryIO.writeWords(outputPath, program.words)
    println(s"assembled ${inputPath.toString} -> ${outputPath.toString}")
  }
}
