package spinalgpu

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.KernelCorpus

abstract class StreamingMultiprocessorKernelSuite(kernels: Seq[KernelCorpus.KernelCase]) extends AnyFunSuite with Matchers {
  private val config = GpuConfig.default

  kernels.foreach { kernel =>
    test(s"kernel corpus case '${kernel.name}' executes from ${kernel.relativeSourcePath}") {
      KernelCorpusTestUtils.runStreamingMultiprocessorKernelCase(kernel, config)
    }
  }
}

class StreamingMultiprocessorCorpusFullSpec
    extends StreamingMultiprocessorKernelSuite(KernelCorpus.streamingMultiprocessorCases)

@DoNotDiscover
class StreamingMultiprocessorCorpusSmokeSpec
    extends StreamingMultiprocessorKernelSuite(
      Seq(
        KernelCorpus.addStoreExit,
        KernelCorpus.threadIdStore,
        KernelCorpus.sharedRoundtrip,
        KernelCorpus.vectorAddF32x4,
        KernelCorpus.matrixAddF32,
        KernelCorpus.scalarAddF16,
        KernelCorpus.vectorAddE4m3x2,
        KernelCorpus.trap
      )
    )
