package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.KernelCorpus

abstract class StreamingMultiprocessorKernelCaseSpec(kernel: KernelCorpus.KernelCase) extends AnyFunSuite with Matchers {
  private val config = SmConfig.default

  test(s"kernel corpus case '${kernel.name}' executes from ${kernel.relativeSourcePath}") {
    KernelCorpusTestUtils.runStreamingMultiprocessorKernelCase(kernel, config)
  }
}

class AddStoreExitStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.addStoreExit)

class ThreadIdStoreStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.threadIdStore)

class BasicSpecialRegisterStoreStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.basicSpecialRegisterStore)

class GridIdStoreStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.gridIdStore)

class UniformLoopStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.uniformLoop)

class SharedRoundtripStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.sharedRoundtrip)

class VectorAdd1WarpStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.vectorAdd1Warp)

class MatrixAddF32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.matrixAddF32)

class MatrixMulF32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.matrixMulF32)

class RegisterStressStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.registerStress)

class NonUniformBranchStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.nonUniformBranch)

class MisalignedStoreStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.misalignedStore)

class TrapStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.trap)
