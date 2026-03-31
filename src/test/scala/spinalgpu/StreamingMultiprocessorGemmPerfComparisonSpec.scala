package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class StreamingMultiprocessorGemmPerfComparisonSpec extends AnyFunSuite with Matchers {
  private val smokeShape = GemmBenchmarkSupport.GemmBenchmarkShape(16, 16, 16)

  test(s"cuda-core benchmark GEMM is correct on StreamingMultiprocessor for ${smokeShape.label}") {
    val run = GemmBenchmarkSupport.runKernel(smokeShape, GemmBenchmarkSupport.CudaCoreFp16, GemmBenchmarkSupport.benchmarkConfig)
    GemmBenchmarkSupport.assertSuccessful(run)
    run.observation.cycles should be > 0
  }

  test(s"tcgen05 benchmark GEMM is correct on StreamingMultiprocessor for ${smokeShape.label}") {
    val run = GemmBenchmarkSupport.runKernel(smokeShape, GemmBenchmarkSupport.Tcgen05Fp16, GemmBenchmarkSupport.benchmarkConfig)
    GemmBenchmarkSupport.assertSuccessful(run)
    run.observation.cycles should be > 0
  }
}
