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

class VectorLoadStoreF32x2StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.vectorLoadStoreF32x2)

class VectorLoadStoreF32x4StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.vectorLoadStoreF32x4)

class VectorAddF32x4StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.vectorAddF32x4)

class MatrixAddF32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.matrixAddF32)

class MatrixMulF32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.matrixMulF32)

class ReluClampF32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.reluClampF32)

class LinearBiasReluF32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.linearBiasReluF32)

class HingeStepF32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.hingeStepF32)

class BitopsPackU32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.bitopsPackU32)

class ScalarUnaryF32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.scalarUnaryF32)

class ScalarMinS32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.scalarMinS32)

class ScalarMadU32StreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.scalarMadU32)

class RegisterStressStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.registerStress)

class NonUniformBranchStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.nonUniformBranch)

class MisalignedStoreStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.misalignedStore)

class TrapStreamingMultiprocessorSpec
    extends StreamingMultiprocessorKernelCaseSpec(KernelCorpus.trap)
