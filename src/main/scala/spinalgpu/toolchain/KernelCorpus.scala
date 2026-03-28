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

    case object FloatingPoint extends KernelFeature {
      override val id: String = "floating_point"
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
      blockDimY: Int = 1,
      blockDimZ: Int = 1,
      argBase: Long = 0L,
      gridDimX: Long = 1L,
      gridDimY: Long = 1L,
      gridDimZ: Long = 1L,
      sharedBytes: Int = 0
  )

  /** Declarative prelaunch memory setup for a kernel case. */
  sealed trait PreloadOp

  object PreloadOp {
    final case class WriteArgBuffer(base: Long, values: Seq[Long]) extends PreloadOp
    final case class WriteDataWords(base: Long, values: Seq[Long]) extends PreloadOp
    final case class WriteDataF32(base: Long, values: Seq[Float]) extends PreloadOp
  }

  /** Declarative success checks against the simulated memory image after completion. */
  sealed trait SuccessCheck

  object SuccessCheck {
    final case class ExpectWords(base: Long, values: Seq[Long]) extends SuccessCheck
    final case class ExpectF32(base: Long, values: Seq[Float]) extends SuccessCheck
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

  private def matrixAddInputA(rows: Int, cols: Int): Seq[Float] =
    (0 until rows * cols).map(index => (index.toFloat * 0.5f) - 7.0f)

  private def matrixAddInputB(rows: Int, cols: Int): Seq[Float] =
    (0 until rows * cols).map(index => (index.toFloat * 1.25f) + 3.0f)

  private def matrixAddExpected(rows: Int, cols: Int): Seq[Float] =
    matrixAddInputA(rows, cols).zip(matrixAddInputB(rows, cols)).map { case (a, b) => a + b }

  private def matrixCopyExpected(rows: Int, cols: Int): Seq[Float] =
    matrixAddInputA(rows, cols)

  private def matrixTransposeExpected(rows: Int, cols: Int): Seq[Float] = {
    val input = matrixAddInputA(rows, cols)
    for {
      row <- 0 until cols
      col <- 0 until rows
    } yield input(col * cols + row)
  }

  private def matrixMulInputA(m: Int, k: Int): Seq[Float] =
    (0 until m * k).map(index => ((index % k).toFloat * 0.25f) + (index / k).toFloat)

  private def matrixMulInputB(k: Int, n: Int): Seq[Float] =
    (0 until k * n).map(index => ((index / n).toFloat * 0.75f) - ((index % n).toFloat * 0.5f))

  private def matrixMulExpected(m: Int, n: Int, k: Int): Seq[Float] = {
    val a = matrixMulInputA(m, k)
    val b = matrixMulInputB(k, n)
    for {
      row <- 0 until m
      col <- 0 until n
    } yield {
      var sum = 0.0f
      var kk = 0
      while (kk < k) {
        sum = sum + (a(row * k + kk) * b(kk * n + col))
        kk += 1
      }
      sum
    }
  }

  private val reluClampInput: Seq[Float] = Seq(-3.0f, -1.0f, 0.25f, 1.5f, 2.5f, 4.0f, 7.0f, 8.5f)
  private val reluClampLimit: Seq[Float] = Seq.fill(8)(3.5f)
  private val reluClampExpected: Seq[Float] =
    reluClampInput.zip(reluClampLimit).map { case (value, limit) => value.min(limit).max(0.0f) }

  private def linearBiasReluInput(featureCount: Int): Seq[Float] =
    (0 until featureCount).map(index => (index.toFloat * 0.5f) - 1.0f)

  private def linearBiasReluWeights(outputCount: Int, featureCount: Int): Seq[Float] =
    for {
      row <- 0 until outputCount
      col <- 0 until featureCount
    } yield ((row + 1).toFloat * 0.2f) - ((col + 1).toFloat * 0.1f)

  private def linearBiasReluBias(outputCount: Int): Seq[Float] =
    (0 until outputCount).map(index => if ((index & 1) == 0) -0.75f else 0.25f)

  private def linearBiasReluExpected(outputCount: Int, featureCount: Int): Seq[Float] = {
    val input = linearBiasReluInput(featureCount)
    val weights = linearBiasReluWeights(outputCount, featureCount)
    val bias = linearBiasReluBias(outputCount)
    (0 until outputCount).map { row =>
      var sum = 0.0f
      var col = 0
      while (col < featureCount) {
        sum = sum + (weights(row * featureCount + col) * input(col))
        col += 1
      }
      sum = sum + bias(row)
      sum.max(0.0f)
    }
  }

  private val hingeLabels: Seq[Float] = Seq(1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f)
  private val hingeScores: Seq[Float] = Seq(2.0f, 0.3f, -0.5f, -2.0f, 1.0f, -1.5f, 0.2f, 3.0f)
  private val hingeMargins: Seq[Float] = Seq.fill(8)(1.0f)
  private val hingeExpected: Seq[Float] =
    hingeLabels.zip(hingeScores).zip(hingeMargins).map { case ((label, score), margin) => (margin - (label * score)).max(0.0f) }

  private val bitopsInputA: Seq[Long] = Seq(0x12345678L, 0xCAFEBABEL, 0x0F0F0F0FL, 0x13579BDFL, 0x89ABCDEFL, 0x01020304L, 0xFFFFFFFFL, 0x2468ACE0L)
  private val bitopsInputB: Seq[Long] = Seq(0x89ABCDEFL, 0x10203040L, 0xF0F0F0F0L, 0xAAAAAAAAL, 0x55555555L, 0xFEDCBA98L, 0x00000000L, 0x13579BDFL)
  private val bitopsExpected: Seq[Long] =
    bitopsInputA.zip(bitopsInputB).map { case (a, b) =>
      ((((a & 0x000000FFL) ^ (b | 0x00000F0FL)) >>> 4) & 0xFFFFFFFFL)
    }

  private val scalarUnaryInputA: Seq[Float] = Seq(1.0f, -2.5f, 3.75f, -4.0f, 0.5f, -0.75f, 2.25f, -3.5f)
  private val scalarUnaryInputB: Seq[Float] = Seq(0.5f, -1.0f, 1.25f, -6.0f, 0.25f, 0.75f, -2.25f, 3.5f)
  private val scalarUnaryExpected: Seq[Float] =
    scalarUnaryInputA.zip(scalarUnaryInputB).map { case (a, b) => -Math.abs((a - b).toDouble).toFloat }

  private val scalarMinSignedInputA: Seq[Int] = Seq(-10, 5, -3, 7, -100, 42, 0, Int.MaxValue)
  private val scalarMinSignedInputB: Seq[Int] = Seq(3, -5, -8, 7, -50, 100, -1, Int.MinValue + 1)
  private val scalarMinSignedExpected: Seq[Int] =
    scalarMinSignedInputA.zip(scalarMinSignedInputB).map { case (a, b) => Math.min(a, b) }

  private val scalarMadInputA: Seq[Long] = Seq(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L)
  private val scalarMadInputB: Seq[Long] = Seq(8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L)
  private val scalarMadInputC: Seq[Long] = Seq(9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L)
  private val scalarMadExpected: Seq[Long] =
    scalarMadInputA.zip(scalarMadInputB).zip(scalarMadInputC).map { case ((a, b), c) =>
      ((a * b) + c) & 0xFFFFFFFFL
    }

  private val vectorLoadStoreF32x2Input: Seq[Float] =
    Seq(-1.0f, 0.5f, 2.0f, -3.25f, 4.5f, 5.75f, -6.0f, 7.125f)

  private val vectorLoadStoreF32x4Input: Seq[Float] =
    Seq(1.0f, -2.0f, 3.5f, -4.25f, 5.0f, -6.5f, 7.75f, 8.125f)

  private val vectorAddF32x4InputA: Seq[Float] =
    Seq(1.0f, 2.0f, 3.0f, 4.0f, -1.5f, -2.5f, 6.0f, 8.0f)

  private val vectorAddF32x4InputB: Seq[Float] =
    Seq(0.25f, -0.5f, 1.5f, 2.0f, 3.5f, 4.5f, -6.0f, 0.125f)

  private val vectorAddF32x4Expected: Seq[Float] =
    vectorAddF32x4InputA.zip(vectorAddF32x4InputB).map { case (a, b) => a + b }

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

  val threadIdStore256: KernelCase = KernelCase(
    name = "thread_id_store_256",
    relativeSourcePath = "special_registers/thread_id_store.ptx",
    purpose = "Store each thread's %tid.x to global memory.",
    primaryFeature = SpecialRegisters,
    secondaryFeatures = Seq(GlobalMemory),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 256, argBase = 0x620),
    timeoutCycles = 120000,
    preloadOps = Seq(WriteArgBuffer(base = 0x620, values = Seq(0x7000L))),
    expectation = Success(checks = Seq(ExpectWords(base = 0x7000, values = (0 until 256).map(_.toLong)))),
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

  val vectorLoadStoreF32x2: KernelCase = KernelCase(
    name = "vector_load_store_f32x2",
    relativeSourcePath = "arithmetic/vector_load_store_f32x2.ptx",
    purpose = "Round-trip one FP32 float2 tuple per thread through global memory.",
    primaryFeature = GlobalMemory,
    secondaryFeatures = Seq(FloatingPoint, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, argBase = 0x560),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataF32(base = 0x6000, values = vectorLoadStoreF32x2Input),
      WriteArgBuffer(base = 0x560, values = Seq(0x6000L, 0x6100L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x6100, values = vectorLoadStoreF32x2Input))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val vectorLoadStoreF32x4: KernelCase = KernelCase(
    name = "vector_load_store_f32x4",
    relativeSourcePath = "arithmetic/vector_load_store_f32x4.ptx",
    purpose = "Round-trip one FP32 float4 tuple per thread through global memory.",
    primaryFeature = GlobalMemory,
    secondaryFeatures = Seq(FloatingPoint, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 2, argBase = 0x5A0),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataF32(base = 0x6200, values = vectorLoadStoreF32x4Input),
      WriteArgBuffer(base = 0x5A0, values = Seq(0x6200L, 0x6300L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x6300, values = vectorLoadStoreF32x4Input))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val vectorAddF32x4: KernelCase = KernelCase(
    name = "vector_add_f32x4",
    relativeSourcePath = "arithmetic/vector_add_f32x4.ptx",
    purpose = "Add one FP32 float4 tuple per thread using vector loads/stores and scalar CUDA-core adds.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 2, argBase = 0x5E0),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataF32(base = 0x6400, values = vectorAddF32x4InputA),
      WriteDataF32(base = 0x6500, values = vectorAddF32x4InputB),
      WriteArgBuffer(base = 0x5E0, values = Seq(0x6400L, 0x6500L, 0x6600L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x6600, values = vectorAddF32x4Expected))),
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val matrixCopyF32: KernelCase = KernelCase(
    name = "matrix_copy_f32",
    relativeSourcePath = "arithmetic/matrix_copy_f32.ptx",
    purpose = "Copy one FP32 row-major matrix element per thread using 2D thread coordinates.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, blockDimY = 4, argBase = 0x2C0),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataF32(base = 0x0C00, values = matrixAddInputA(rows = 4, cols = 4)),
      WriteArgBuffer(base = 0x2C0, values = Seq(0x0C00L, 0x0D00L, 4L, 4L, 4L, 4L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x0D00, values = matrixCopyExpected(rows = 4, cols = 4)))),
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val matrixTransposeF32: KernelCase = KernelCase(
    name = "matrix_transpose_f32",
    relativeSourcePath = "arithmetic/matrix_transpose_f32.ptx",
    purpose = "Transpose one FP32 row-major matrix using 2D thread coordinates.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, blockDimY = 4, argBase = 0x2E0),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataF32(base = 0x0E00, values = matrixAddInputA(rows = 4, cols = 4)),
      WriteArgBuffer(base = 0x2E0, values = Seq(0x0E00L, 0x0F00L, 4L, 4L, 4L, 4L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x0F00, values = matrixTransposeExpected(rows = 4, cols = 4)))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val matrixAddF32: KernelCase = KernelCase(
    name = "matrix_add_f32",
    relativeSourcePath = "arithmetic/matrix_add_f32.ptx",
    purpose = "Add two FP32 row-major matrices using CUDA cores and 2D thread coordinates.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, blockDimY = 4, argBase = 0x300),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataF32(base = 0x1000, values = matrixAddInputA(rows = 4, cols = 4)),
      WriteDataF32(base = 0x1200, values = matrixAddInputB(rows = 4, cols = 4)),
      WriteArgBuffer(base = 0x300, values = Seq(0x1000L, 0x1200L, 0x1400L, 4L, 4L, 4L, 4L, 4L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x1400, values = matrixAddExpected(rows = 4, cols = 4)))),
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val matrixMulF32: KernelCase = KernelCase(
    name = "matrix_mul_f32",
    relativeSourcePath = "arithmetic/matrix_mul_f32.ptx",
    purpose = "Multiply two FP32 row-major matrices using CUDA cores and fused multiply-add.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, blockDimY = 4, argBase = 0x340),
    timeoutCycles = 60000,
    preloadOps = Seq(
      WriteDataF32(base = 0x1800, values = matrixMulInputA(m = 4, k = 4)),
      WriteDataF32(base = 0x1A00, values = matrixMulInputB(k = 4, n = 4)),
      WriteArgBuffer(base = 0x340, values = Seq(0x1800L, 0x1A00L, 0x1C00L, 4L, 4L, 4L, 4L, 4L, 4L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x1C00, values = matrixMulExpected(m = 4, n = 4, k = 4)))),
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val reluClampF32: KernelCase = KernelCase(
    name = "relu_clamp_f32",
    relativeSourcePath = "arithmetic/relu_clamp_f32.ptx",
    purpose = "Clamp an FP32 vector to [0, clamp_hi] with branchless compare and select.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8, argBase = 0x3A0),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataF32(base = 0x2000, values = reluClampInput),
      WriteDataF32(base = 0x2100, values = reluClampLimit),
      WriteArgBuffer(base = 0x3A0, values = Seq(0x2000L, 0x2100L, 0x2200L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x2200, values = reluClampExpected))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val linearBiasReluF32: KernelCase = KernelCase(
    name = "linear_bias_relu_f32",
    relativeSourcePath = "arithmetic/linear_bias_relu_f32.ptx",
    purpose = "Compute a one-CTA dense layer followed by bias add and ReLU.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8, argBase = 0x3E0),
    timeoutCycles = 120000,
    preloadOps = Seq(
      WriteDataF32(base = 0x3000, values = linearBiasReluWeights(outputCount = 8, featureCount = 8)),
      WriteDataF32(base = 0x3200, values = linearBiasReluInput(featureCount = 8)),
      WriteDataF32(base = 0x3300, values = linearBiasReluBias(outputCount = 8)),
      WriteArgBuffer(base = 0x3E0, values = Seq(0x3000L, 0x3200L, 0x3300L, 0x3400L, 8L, 8L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x3400, values = linearBiasReluExpected(outputCount = 8, featureCount = 8)))),
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val hingeStepF32: KernelCase = KernelCase(
    name = "hinge_step_f32",
    relativeSourcePath = "arithmetic/hinge_step_f32.ptx",
    purpose = "Compute one SVM-style hinge-loss contribution per thread.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8, argBase = 0x420),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataF32(base = 0x4000, values = hingeLabels),
      WriteDataF32(base = 0x4100, values = hingeScores),
      WriteDataF32(base = 0x4200, values = hingeMargins),
      WriteArgBuffer(base = 0x420, values = Seq(0x4000L, 0x4100L, 0x4200L, 0x4300L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x4300, values = hingeExpected))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val bitopsPackU32: KernelCase = KernelCase(
    name = "bitops_pack_u32",
    relativeSourcePath = "arithmetic/bitops_pack_u32.ptx",
    purpose = "Apply integer bit masks, XOR, and logical right shift to packed 32-bit data.",
    primaryFeature = Arithmetic,
    secondaryFeatures = Seq(GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8, argBase = 0x460),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataWords(base = 0x5000, values = bitopsInputA),
      WriteDataWords(base = 0x5100, values = bitopsInputB),
      WriteArgBuffer(base = 0x460, values = Seq(0x5000L, 0x5100L, 0x5200L))
    ),
    expectation = Success(checks = Seq(ExpectWords(base = 0x5200, values = bitopsExpected))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val scalarUnaryF32: KernelCase = KernelCase(
    name = "scalar_unary_f32",
    relativeSourcePath = "arithmetic/scalar_unary_f32.ptx",
    purpose = "Apply scalar FP32 subtract, absolute value, and negate to one value per thread.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8, argBase = 0x4A0),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataF32(base = 0x5400, values = scalarUnaryInputA),
      WriteDataF32(base = 0x5500, values = scalarUnaryInputB),
      WriteArgBuffer(base = 0x4A0, values = Seq(0x5400L, 0x5500L, 0x5600L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x5600, values = scalarUnaryExpected))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val scalarMinS32: KernelCase = KernelCase(
    name = "scalar_min_s32",
    relativeSourcePath = "arithmetic/scalar_min_s32.ptx",
    purpose = "Compute the signed minimum of two 32-bit scalar inputs per thread.",
    primaryFeature = Arithmetic,
    secondaryFeatures = Seq(GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8, argBase = 0x4E0),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataWords(base = 0x5800, values = scalarMinSignedInputA.map(_.toLong & 0xFFFFFFFFL)),
      WriteDataWords(base = 0x5900, values = scalarMinSignedInputB.map(_.toLong & 0xFFFFFFFFL)),
      WriteArgBuffer(base = 0x4E0, values = Seq(0x5800L, 0x5900L, 0x5A00L))
    ),
    expectation = Success(checks = Seq(ExpectWords(base = 0x5A00, values = scalarMinSignedExpected.map(_.toLong & 0xFFFFFFFFL)))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val scalarMadU32: KernelCase = KernelCase(
    name = "scalar_mad_u32",
    relativeSourcePath = "arithmetic/scalar_mad_u32.ptx",
    purpose = "Compute one scalar mad.lo.u32 result per thread from three input vectors.",
    primaryFeature = Arithmetic,
    secondaryFeatures = Seq(GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8, argBase = 0x520),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataWords(base = 0x5C00, values = scalarMadInputA),
      WriteDataWords(base = 0x5D00, values = scalarMadInputB),
      WriteDataWords(base = 0x5E00, values = scalarMadInputC),
      WriteArgBuffer(base = 0x520, values = Seq(0x5C00L, 0x5D00L, 0x5E00L, 0x5F00L))
    ),
    expectation = Success(checks = Seq(ExpectWords(base = 0x5F00, values = scalarMadExpected))),
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

  val warpidStallIsolation: KernelCase = KernelCase(
    name = "warpid_stall_isolation",
    relativeSourcePath = "control/warpid_stall_isolation.ptx",
    purpose = "Let one warp take a long uniform delay while the other warps still retire.",
    primaryFeature = Control,
    secondaryFeatures = Seq(GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 128, argBase = 0x660),
    timeoutCycles = 60000,
    preloadOps = Seq(WriteArgBuffer(base = 0x660, values = Seq(0x7400L))),
    expectation = Success(
      checks = Seq(ExpectWords(base = 0x7400, values = Seq(0x100L, 0x101L, 0x102L, 0x103L, 0x200L, 0x201L, 0x202L, 0x203L)))
    ),
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
    threadIdStore256,
    basicSpecialRegisterStore,
    gridIdStore,
    uniformLoop,
    sharedRoundtrip,
    vectorAdd1Warp,
    vectorLoadStoreF32x2,
    vectorLoadStoreF32x4,
    vectorAddF32x4,
    matrixCopyF32,
    matrixTransposeF32,
    matrixAddF32,
    matrixMulF32,
    reluClampF32,
    linearBiasReluF32,
    hingeStepF32,
    bitopsPackU32,
    scalarUnaryF32,
    scalarMinS32,
    scalarMadU32,
    registerStress,
    warpidStallIsolation,
    nonUniformBranch,
    misalignedStore,
    trap
  )

  val gpuTopCases: Seq[KernelCase] = all.filter(_.harnessTargets.contains(HarnessTarget.GpuTop))
  val streamingMultiprocessorCases: Seq[KernelCase] =
    all.filter(_.harnessTargets.contains(HarnessTarget.StreamingMultiprocessor))
}
