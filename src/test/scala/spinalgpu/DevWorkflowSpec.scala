package spinalgpu

import org.scalatest.{DoNotDiscover, Suites}

@DoNotDiscover
class DevRegressionSpec
    extends Suites(
      new ArchitectureSkeletonSpec,
      new KernelCorpusSpec,
      new IsaSpec,
      new PtxAssemblerSpec,
      new CudaCoreArraySpec,
      new Fp16MathSpec,
      new Fp8FormatSpec,
      new WarpRegisterFileSpec,
      new LoadStoreUnitSpec,
      new ExternalMemoryAxiAdapterSpec,
      new L1InstructionCacheSpec,
      new L1DataSharedMemorySpec,
      new LocalWarpSchedulerSpec,
      new LocalWarpSlotTableSpec,
      new WarpBinderSpec,
      new GridDispatchControllerSpec
    )

@DoNotDiscover
class ExecutionSmokeSpec
    extends Suites(
      new StreamingMultiprocessorWrapperSpec,
      new StreamingMultiprocessorCorpusSmokeSpec,
      new GpuTopSmokeSpec
    )
