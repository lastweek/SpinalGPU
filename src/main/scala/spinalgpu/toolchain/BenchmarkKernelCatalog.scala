package spinalgpu.toolchain

import java.nio.file.Path

object BenchmarkKernelCatalog {
  final case class BenchmarkKernel(
      name: String,
      relativeSourcePath: String
  ) {
    val sourcePath: Path = Path.of("kernels").resolve(relativeSourcePath).normalize()
    val relativeBinaryPath: String = relativeSourcePath.stripSuffix(".ptx") + ".bin"
    val binaryPath: Path = KernelCorpus.outputRoot.resolve(relativeBinaryPath).normalize()
  }

  val cudaCoreGemmF16: BenchmarkKernel =
    BenchmarkKernel("cuda_core_gemm_f16", "benchmark/cuda_core_gemm_f16.ptx")

  val tcgen05GemmF16: BenchmarkKernel =
    BenchmarkKernel("tcgen05_gemm_f16", "benchmark/tcgen05_gemm_f16.ptx")

  val all: Seq[BenchmarkKernel] = Seq(
    cudaCoreGemmF16,
    tcgen05GemmF16
  )
}

