package spinalgpu.toolchain

import java.nio.file.Path
import spinalgpu.FaultCode

/** Single source of truth for the PTX teaching corpus.
  *
  * Each case ties together the checked-in PTX source, the generated binary path, the launch ABI, the preload image,
  * the expected outcome, and the harnesses that should execute the case. If a kernel's PTX changes, its corresponding
  * case here must be updated in the same change.
  */
object KernelCorpus {
  sealed trait KernelFeature {
    def id: String
  }

  object KernelFeature {
    case object Arithmetic extends KernelFeature {
      override val id: String = "arithmetic"
    }

    case object Control extends KernelFeature {
      override val id: String = "control"
    }

    case object GlobalMemory extends KernelFeature {
      override val id: String = "global_memory"
    }

    case object SharedMemory extends KernelFeature {
      override val id: String = "shared_memory"
    }

    case object SpecialRegisters extends KernelFeature {
      override val id: String = "special_registers"
    }
  }

  sealed trait KernelLevel {
    def id: String
  }

  object KernelLevel {
    case object Intro extends KernelLevel {
      override val id: String = "intro"
    }

    case object Core extends KernelLevel {
      override val id: String = "core"
    }

    case object Fault extends KernelLevel {
      override val id: String = "fault"
    }
  }

  sealed trait HarnessTarget {
    def id: String
  }

  object HarnessTarget {
    case object GpuTop extends HarnessTarget {
      override val id: String = "gpu_top"
    }

    case object StreamingMultiprocessor extends HarnessTarget {
      override val id: String = "streaming_multiprocessor"
    }
  }

  /** Command descriptor consumed by the host-side harness helpers. */
  final case class KernelCommand(
      entryPc: Long,
      blockDimX: Int,
      argBase: Long = 0L,
      gridDimX: Long = 1L,
      sharedBytes: Int = 0
  )

  /** Declarative prelaunch memory setup for a kernel case. */
  sealed trait PreloadOp

  object PreloadOp {
    final case class WriteArgBuffer(base: Long, values: Seq[Long]) extends PreloadOp
    final case class WriteDataWords(base: Long, values: Seq[Long]) extends PreloadOp
  }

  /** Declarative success checks against the simulated memory image after completion. */
  sealed trait SuccessCheck

  object SuccessCheck {
    final case class ExpectWords(base: Long, values: Seq[Long]) extends SuccessCheck
  }

  /** Declarative completion model for a kernel case. */
  sealed trait KernelExpectation {
    def expectedOutcomeId: String
  }

  object KernelExpectation {
    final case class Success(checks: Seq[SuccessCheck]) extends KernelExpectation {
      override val expectedOutcomeId: String = "success"
    }

    final case class Fault(code: Int, faultPc: Option[Long] = None) extends KernelExpectation {
      override val expectedOutcomeId: String = "fault"
    }
  }

  val sourceRoot: Path = Path.of("kernels")
  val outputRoot: Path = Path.of("generated", "kernels")

  final case class KernelCase(
      name: String,
      relativeSourcePath: String,
      purpose: String,
      primaryFeature: KernelFeature,
      secondaryFeatures: Seq[KernelFeature] = Seq.empty,
      teachingLevel: KernelLevel,
      command: KernelCommand,
      timeoutCycles: Int = 5000,
      preloadOps: Seq[PreloadOp] = Seq.empty,
      expectation: KernelExpectation,
      harnessTargets: Seq[HarnessTarget]
  ) {
    require(purpose.trim.nonEmpty, s"kernel '$name' must have a non-empty purpose")
    require(secondaryFeatures.distinct.size == secondaryFeatures.size, s"kernel '$name' has duplicate secondary features")
    require(!secondaryFeatures.contains(primaryFeature), s"kernel '$name' lists its primary feature as secondary")
    require(timeoutCycles > 0, s"kernel '$name' must have a positive timeout")
    require(harnessTargets.nonEmpty, s"kernel '$name' must target at least one harness")

    val entryPc: Long = command.entryPc
    val sourcePath: Path = sourceRoot.resolve(relativeSourcePath).normalize()
    val relativeBinaryPath: String = relativeSourcePath.stripSuffix(".ptx") + ".bin"
    val binaryPath: Path = outputRoot.resolve(relativeBinaryPath).normalize()
    val expectedOutcomeId: String = expectation.expectedOutcomeId
  }

  import HarnessTarget._
  import KernelExpectation._
  import KernelFeature._
  import KernelLevel._
  import PreloadOp._
  import SuccessCheck._

  val addStoreExit: KernelCase = KernelCase(
    name = "add_store_exit",
    relativeSourcePath = "arithmetic/add_store_exit.ptx",
    purpose = "Write a constant arithmetic result to global memory.",
    primaryFeature = Arithmetic,
    secondaryFeatures = Seq(GlobalMemory),
    teachingLevel = Intro,
    command = KernelCommand(entryPc = 0x100, blockDimX = 1, argBase = 0x200),
    preloadOps = Seq(WriteArgBuffer(base = 0x200, values = Seq(0x300L))),
    expectation = Success(checks = Seq(ExpectWords(base = 0x300, values = Seq(18L)))),
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val threadIdStore: KernelCase = KernelCase(
    name = "thread_id_store",
    relativeSourcePath = "special_registers/thread_id_store.ptx",
    purpose = "Store each thread's %tid.x to global memory.",
    primaryFeature = SpecialRegisters,
    secondaryFeatures = Seq(GlobalMemory),
    teachingLevel = Intro,
    command = KernelCommand(entryPc = 0x100, blockDimX = 40, argBase = 0x200),
    preloadOps = Seq(WriteArgBuffer(base = 0x200, values = Seq(0x400L))),
    expectation = Success(checks = Seq(ExpectWords(base = 0x400, values = (0 until 40).map(_.toLong)))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val basicSpecialRegisterStore: KernelCase = KernelCase(
    name = "basic_special_register_store",
    relativeSourcePath = "special_registers/basic_special_register_store.ptx",
    purpose = "Store the basic lane, warp, block, and SM special-register values to global memory.",
    primaryFeature = SpecialRegisters,
    secondaryFeatures = Seq(GlobalMemory),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 40, argBase = 0x240),
    timeoutCycles = 20000,
    preloadOps = Seq(WriteArgBuffer(base = 0x240, values = Seq(0x500L))),
    expectation = Success(
      checks = Seq(
        ExpectWords(base = 0x500, values = ((0 until 32) ++ (0 until 8)).map(_.toLong)),
        ExpectWords(base = 0x5A0, values = Seq.fill(32)(0L) ++ Seq.fill(8)(1L)),
        ExpectWords(base = 0x640, values = Seq.fill(40)(40L)),
        ExpectWords(base = 0x6E0, values = Seq.fill(40)(0L)),
        ExpectWords(base = 0x780, values = Seq.fill(40)(1L)),
        ExpectWords(base = 0x820, values = Seq.fill(40)(2L)),
        ExpectWords(base = 0x8C0, values = Seq.fill(40)(0L)),
        ExpectWords(base = 0x960, values = Seq.fill(40)(1L))
      )
    ),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val gridIdStore: KernelCase = KernelCase(
    name = "grid_id_store",
    relativeSourcePath = "special_registers/grid_id_store.ptx",
    purpose = "Store the current %gridid value to global memory.",
    primaryFeature = SpecialRegisters,
    secondaryFeatures = Seq(GlobalMemory),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 1, argBase = 0x280),
    preloadOps = Seq(WriteArgBuffer(base = 0x280, values = Seq(0xA00L))),
    expectation = Success(checks = Seq(ExpectWords(base = 0xA00, values = Seq(0L, 0L)))),
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val uniformLoop: KernelCase = KernelCase(
    name = "uniform_loop",
    relativeSourcePath = "control/uniform_loop.ptx",
    purpose = "Run a uniform countdown loop and store the terminal value.",
    primaryFeature = Control,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 1, argBase = 0x200),
    preloadOps = Seq(WriteArgBuffer(base = 0x200, values = Seq(0x300L))),
    expectation = Success(checks = Seq(ExpectWords(base = 0x300, values = Seq(0L)))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val sharedRoundtrip: KernelCase = KernelCase(
    name = "shared_roundtrip",
    relativeSourcePath = "shared_memory/shared_roundtrip.ptx",
    purpose = "Round-trip %tid.x through shared memory and write it to global memory.",
    primaryFeature = SharedMemory,
    secondaryFeatures = Seq(SpecialRegisters, GlobalMemory),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8, argBase = 0x200, sharedBytes = 256),
    preloadOps = Seq(WriteArgBuffer(base = 0x200, values = Seq(0x400L))),
    expectation = Success(checks = Seq(ExpectWords(base = 0x400, values = (0 until 8).map(_.toLong)))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val vectorAdd1Warp: KernelCase = KernelCase(
    name = "vector_add_1warp",
    relativeSourcePath = "global_memory/vector_add_1warp.ptx",
    purpose = "Compute C[i] = A[i] + B[i] for one warp.",
    primaryFeature = GlobalMemory,
    secondaryFeatures = Seq(Arithmetic, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8, argBase = 0x240),
    timeoutCycles = 10000,
    preloadOps = Seq(
      WriteDataWords(base = 0x500, values = (0 until 8).map(_.toLong)),
      WriteDataWords(base = 0x600, values = (0 until 8).map(index => (index * 10).toLong)),
      WriteArgBuffer(base = 0x240, values = Seq(0x500L, 0x600L, 0x700L))
    ),
    expectation = Success(
      checks = Seq(ExpectWords(base = 0x700, values = (0 until 8).map(index => (index + (index * 10)).toLong)))
    ),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val registerStress: KernelCase = KernelCase(
    name = "register_stress",
    relativeSourcePath = "arithmetic/register_stress.ptx",
    purpose = "Store one value from every allocatable PTX register to global memory.",
    primaryFeature = Arithmetic,
    secondaryFeatures = Seq(GlobalMemory),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 1, argBase = 0x2C0),
    timeoutCycles = 10000,
    preloadOps = Seq(WriteArgBuffer(base = 0x2C0, values = Seq(0x800L))),
    expectation = Success(checks = Seq(ExpectWords(base = 0x800, values = (100L to 128L) :+ 0x800L))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val nonUniformBranch: KernelCase = KernelCase(
    name = "non_uniform_branch",
    relativeSourcePath = "control/non_uniform_branch.ptx",
    purpose = "Branch on lane-varying state to provoke a non-uniform branch fault.",
    primaryFeature = Control,
    secondaryFeatures = Seq(SpecialRegisters),
    teachingLevel = KernelLevel.Fault,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8),
    expectation = KernelExpectation.Fault(FaultCode.NonUniformBranch),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val misalignedStore: KernelCase = KernelCase(
    name = "misaligned_store",
    relativeSourcePath = "global_memory/misaligned_store.ptx",
    purpose = "Store to a misaligned global address to provoke a load/store fault.",
    primaryFeature = GlobalMemory,
    teachingLevel = KernelLevel.Fault,
    command = KernelCommand(entryPc = 0x100, blockDimX = 1, argBase = 0x280),
    preloadOps = Seq(WriteArgBuffer(base = 0x280, values = Seq(0x300L))),
    expectation = KernelExpectation.Fault(FaultCode.MisalignedLoadStore),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val trap: KernelCase = KernelCase(
    name = "trap",
    relativeSourcePath = "control/trap.ptx",
    purpose = "Raise an explicit trap.",
    primaryFeature = Control,
    teachingLevel = KernelLevel.Fault,
    command = KernelCommand(entryPc = 0x100, blockDimX = 1),
    expectation = KernelExpectation.Fault(FaultCode.Trap),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val all: Seq[KernelCase] = Seq(
    addStoreExit,
    threadIdStore,
    basicSpecialRegisterStore,
    gridIdStore,
    uniformLoop,
    sharedRoundtrip,
    vectorAdd1Warp,
    registerStress,
    nonUniformBranch,
    misalignedStore,
    trap
  )

  val gpuTopCases: Seq[KernelCase] = all.filter(_.harnessTargets.contains(HarnessTarget.GpuTop))
  val streamingMultiprocessorCases: Seq[KernelCase] =
    all.filter(_.harnessTargets.contains(HarnessTarget.StreamingMultiprocessor))
}
