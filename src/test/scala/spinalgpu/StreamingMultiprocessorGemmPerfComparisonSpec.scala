package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class StreamingMultiprocessorGemmPerfComparisonSpec extends AnyFunSuite with Matchers {
  private val validatedShapes = Seq(
    GemmBenchmarkSupport.GemmBenchmarkShape(16, 16, 16),
    GemmBenchmarkSupport.GemmBenchmarkShape(32, 32, 32)
  )

  validatedShapes.foreach { shape =>
    test(s"cuda-core benchmark GEMM is correct on StreamingMultiprocessor for ${shape.label}") {
      val run = GemmBenchmarkSupport.runKernel(shape, GemmBenchmarkSupport.CudaCoreFp16, GemmBenchmarkSupport.benchmarkConfig)
      GemmBenchmarkSupport.assertSuccessful(run)
      run.observation.cycles should be > 0
    }

    test(s"tcgen05 benchmark GEMM is correct on StreamingMultiprocessor for ${shape.label}") {
      val run = GemmBenchmarkSupport.runKernel(shape, GemmBenchmarkSupport.Tcgen05Fp16, GemmBenchmarkSupport.benchmarkConfig)
      GemmBenchmarkSupport.assertSuccessful(run)
      run.observation.cycles should be > 0
    }
  }
}
