package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.KernelCorpus

class StreamingMultiprocessorTcgen05Spec extends AnyFunSuite with Matchers {
  private val corpusCases = Seq(
    KernelCorpus.tcgen05LdStRoundtripF16,
    KernelCorpus.tcgen05MmaF16,
    KernelCorpus.tcgen05LdHazard
  )

  corpusCases.foreach { kernel =>
    test(s"${kernel.name} executes through StreamingMultiprocessor tcgen05 coverage") {
      KernelCorpusTestUtils.runStreamingMultiprocessorKernelCase(kernel, GpuConfig.default)
    }
  }
}

class GpuTopTcgen05Spec extends AnyFunSuite with Matchers {
  private val corpusCases = Seq(
    KernelCorpus.tcgen05LdStRoundtripF16,
    KernelCorpus.tcgen05MmaF16,
    KernelCorpus.tcgen05LdHazard
  )

  corpusCases.foreach { kernel =>
    test(s"${kernel.name} executes through GpuTop tcgen05 coverage") {
      KernelCorpusTestUtils.runGpuTopKernelCase(kernel, GpuConfig.default)
    }
  }
}
