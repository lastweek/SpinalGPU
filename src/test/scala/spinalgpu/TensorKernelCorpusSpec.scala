package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.KernelCorpus

class StreamingMultiprocessorTensorSpec extends AnyFunSuite with Matchers {
  KernelCorpus.tensorCases.foreach { kernel =>
    test(s"${kernel.name} executes through StreamingMultiprocessor tensor-only coverage") {
      KernelCorpusTestUtils.runStreamingMultiprocessorKernelCase(kernel, GpuConfig.default)
    }
  }
}

class GpuTopTensorSpec extends AnyFunSuite with Matchers {
  KernelCorpus.tensorCases.foreach { kernel =>
    test(s"${kernel.name} executes through GpuTop tensor-only coverage") {
      KernelCorpusTestUtils.runGpuTopKernelCase(kernel, GpuConfig.default)
    }
  }
}
