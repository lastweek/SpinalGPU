package spinalgpu.toolchain

import java.nio.file.Path
import spinalgpu.FaultCode
import spinalgpu.LowPrecisionCodec

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
    final case class WriteDataF16(base: Long, values: Seq[Int]) extends PreloadOp
    final case class WriteDataPacked16(base: Long, values: Seq[Int]) extends PreloadOp
  }

  /** Declarative success checks against the simulated memory image after completion. */
  sealed trait SuccessCheck

  object SuccessCheck {
    final case class ExpectWords(base: Long, values: Seq[Long]) extends SuccessCheck
    final case class ExpectF32(base: Long, values: Seq[Float]) extends SuccessCheck
    final case class ExpectF16(base: Long, values: Seq[Int]) extends SuccessCheck
    final case class ExpectPacked16(base: Long, values: Seq[Int]) extends SuccessCheck
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

  private def halfBits(value: Float): Int = LowPrecisionCodec.floatToHalfBits(value)

  private def halfFloat(bits: Int): Float = LowPrecisionCodec.halfBitsToFloat(bits)

  private def halfAddBits(lhs: Int, rhs: Int): Int = halfBits(halfFloat(lhs) + halfFloat(rhs))

  private def half2(bitsLow: Int, bitsHigh: Int): Int = LowPrecisionCodec.packHalf2(bitsLow, bitsHigh)

  private def word32(value: Int): Long = value.toLong & 0xFFFFFFFFL

  private def packHalfWords(values: Seq[Int]): Seq[Int] = {
    require(values.length % 2 == 0, "packed halfword sequences must have an even element count")
    values.grouped(2).map { pair => half2(pair(0), pair(1)) }.toSeq
  }

  private def packFp8Words(values: Seq[Float], encode: Float => Int): Seq[Int] = {
    require(values.length % 2 == 0, "packed fp8 sequences must have an even element count")
    values.grouped(2).map { pair =>
      LowPrecisionCodec.packFp8x2(encode(pair(0)), encode(pair(1)))
    }.toSeq
  }

  private def packedFp8ToPackedHalf(words: Seq[Int], decode: Int => Float): Seq[Int] =
    words.map { word =>
      half2(
        halfBits(decode(LowPrecisionCodec.unpackFp8x2Low(word))),
        halfBits(decode(LowPrecisionCodec.unpackFp8x2High(word)))
      )
    }

  private def packedFp8AddExpected(wordsA: Seq[Int], wordsB: Seq[Int], decode: Int => Float, encode: Float => Int): Seq[Int] =
    wordsA.zip(wordsB).map { case (lhs, rhs) =>
      val lhsLow = halfBits(decode(LowPrecisionCodec.unpackFp8x2Low(lhs)))
      val lhsHigh = halfBits(decode(LowPrecisionCodec.unpackFp8x2High(lhs)))
      val rhsLow = halfBits(decode(LowPrecisionCodec.unpackFp8x2Low(rhs)))
      val rhsHigh = halfBits(decode(LowPrecisionCodec.unpackFp8x2High(rhs)))
      LowPrecisionCodec.packFp8x2(
        encode(halfFloat(halfAddBits(lhsLow, rhsLow))),
        encode(halfFloat(halfAddBits(lhsHigh, rhsHigh)))
      )
    }

  private def packMatrixRowsAsFp8x2(values: Seq[Float], rows: Int, cols: Int, encode: Float => Int): Seq[Int] = {
    require(cols % 2 == 0, "matrix fp8 row packing requires an even column count")
    for {
      row <- 0 until rows
      pair <- 0 until cols / 2
    } yield {
      val lowIndex = row * cols + (pair * 2)
      val highIndex = lowIndex + 1
      LowPrecisionCodec.packFp8x2(encode(values(lowIndex)), encode(values(highIndex)))
    }
  }

  private def packMatrixColsAsFp8x2(values: Seq[Float], rows: Int, cols: Int, encode: Float => Int): Seq[Int] = {
    require(rows % 2 == 0, "matrix fp8 column packing requires an even row count")
    for {
      pair <- 0 until rows / 2
      col <- 0 until cols
    } yield {
      val lowIndex = (pair * 2) * cols + col
      val highIndex = ((pair * 2) + 1) * cols + col
      LowPrecisionCodec.packFp8x2(encode(values(lowIndex)), encode(values(highIndex)))
    }
  }

  private def quantizeE4m3ToHalfFloat(value: Float): Float = {
    val encoded = LowPrecisionCodec.floatToE4m3BitsSatFinite(value)
    halfFloat(halfBits(LowPrecisionCodec.e4m3BitsToFloat(encoded)))
  }

  private def quantizeE5m2ToHalfFloat(value: Float): Float = {
    val encoded = LowPrecisionCodec.floatToE5m2BitsSatFinite(value)
    halfFloat(halfBits(LowPrecisionCodec.e5m2BitsToFloat(encoded)))
  }

  private val scalarAddF16InputA: Seq[Int] =
    Seq(-1.0f, 0.5f, 1.5f, -2.25f, 3.0f, 4.5f, -5.5f, 6.0f).map(halfBits)

  private val scalarAddF16InputB: Seq[Int] =
    Seq(0.25f, -0.5f, 2.0f, 1.25f, -3.0f, 0.5f, 1.5f, -2.0f).map(halfBits)

  private val scalarAddF16Expected: Seq[Int] =
    scalarAddF16InputA.zip(scalarAddF16InputB).map { case (lhs, rhs) => halfAddBits(lhs, rhs) }

  private val vectorAddF16x2InputAElements: Seq[Int] =
    Seq(1.0f, -0.5f, 2.0f, 3.0f, -4.0f, 0.25f, 5.5f, -6.5f).map(halfBits)

  private val vectorAddF16x2InputBElements: Seq[Int] =
    Seq(0.5f, 1.5f, -1.0f, 2.5f, 0.75f, -0.25f, -2.5f, 4.0f).map(halfBits)

  private val vectorAddF16x2InputA: Seq[Int] = packHalfWords(vectorAddF16x2InputAElements)
  private val vectorAddF16x2InputB: Seq[Int] = packHalfWords(vectorAddF16x2InputBElements)

  private val vectorAddF16x2Expected: Seq[Int] =
    vectorAddF16x2InputAElements
      .zip(vectorAddF16x2InputBElements)
      .map { case (lhs, rhs) => halfAddBits(lhs, rhs) }
      .grouped(2)
      .map(pair => half2(pair(0), pair(1)))
      .toSeq

  private def matrixAddF16InputA(rows: Int, cols: Int): Seq[Int] = matrixAddInputA(rows, cols).map(halfBits)

  private def matrixAddF16InputB(rows: Int, cols: Int): Seq[Int] = matrixAddInputB(rows, cols).map(halfBits)

  private def matrixAddF16Expected(rows: Int, cols: Int): Seq[Int] =
    matrixAddF16InputA(rows, cols).zip(matrixAddF16InputB(rows, cols)).map { case (lhs, rhs) => halfAddBits(lhs, rhs) }

  private def matrixMulF16InputA(m: Int, k: Int): Seq[Int] = matrixMulInputA(m, k).map(halfBits)

  private def matrixMulF16InputB(k: Int, n: Int): Seq[Int] = matrixMulInputB(k, n).map(halfBits)

  private def matrixMulF16Expected(m: Int, n: Int, k: Int): Seq[Float] = {
    val a = matrixMulF16InputA(m, k).map(halfFloat)
    val b = matrixMulF16InputB(k, n).map(halfFloat)
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

  private val scalarConvertE4m3Input: Seq[Int] =
    packFp8Words(Seq(1.0f, -0.5f, 2.5f, 0.125f, -3.0f, 4.0f, 0.0f, 6.0f), LowPrecisionCodec.floatToE4m3BitsSatFinite)

  private val scalarConvertE5m2Input: Seq[Int] =
    packFp8Words(Seq(1.0f, -0.5f, 8.0f, 0.15625f, -12.0f, 3.5f, 0.0f, 24.0f), LowPrecisionCodec.floatToE5m2BitsSatFinite)

  private val scalarConvertE4m3Expected: Seq[Int] =
    packedFp8ToPackedHalf(scalarConvertE4m3Input, LowPrecisionCodec.e4m3BitsToFloat)

  private val scalarConvertE5m2Expected: Seq[Int] =
    packedFp8ToPackedHalf(scalarConvertE5m2Input, LowPrecisionCodec.e5m2BitsToFloat)

  private val vectorAddE4m3InputA: Seq[Int] =
    packFp8Words(Seq(1.0f, -0.5f, 2.0f, 3.0f, -4.0f, 0.25f, 5.5f, -6.5f), LowPrecisionCodec.floatToE4m3BitsSatFinite)

  private val vectorAddE4m3InputB: Seq[Int] =
    packFp8Words(Seq(0.5f, 1.5f, -1.0f, 2.5f, 0.75f, -0.25f, -2.5f, 4.0f), LowPrecisionCodec.floatToE4m3BitsSatFinite)

  private val vectorAddE4m3Expected: Seq[Int] =
    packedFp8AddExpected(
      vectorAddE4m3InputA,
      vectorAddE4m3InputB,
      LowPrecisionCodec.e4m3BitsToFloat,
      LowPrecisionCodec.floatToE4m3BitsSatFinite
    )

  private val vectorAddE5m2InputA: Seq[Int] =
    packFp8Words(Seq(1.0f, -0.5f, 8.0f, 3.0f, -10.0f, 0.25f, 12.0f, -16.0f), LowPrecisionCodec.floatToE5m2BitsSatFinite)

  private val vectorAddE5m2InputB: Seq[Int] =
    packFp8Words(Seq(0.5f, 1.5f, -4.0f, 2.5f, 1.0f, -0.25f, -3.0f, 8.0f), LowPrecisionCodec.floatToE5m2BitsSatFinite)

  private val vectorAddE5m2Expected: Seq[Int] =
    packedFp8AddExpected(
      vectorAddE5m2InputA,
      vectorAddE5m2InputB,
      LowPrecisionCodec.e5m2BitsToFloat,
      LowPrecisionCodec.floatToE5m2BitsSatFinite
    )

  private val matrixMulFp8LogicalA: Seq[Float] = matrixMulInputA(m = 2, k = 4)
  private val matrixMulFp8LogicalB: Seq[Float] = matrixMulInputB(k = 4, n = 2)

  private val matrixMulE4m3PackedA: Seq[Int] =
    packMatrixRowsAsFp8x2(matrixMulFp8LogicalA, rows = 2, cols = 4, LowPrecisionCodec.floatToE4m3BitsSatFinite)

  private val matrixMulE4m3PackedB: Seq[Int] =
    packMatrixColsAsFp8x2(matrixMulFp8LogicalB, rows = 4, cols = 2, LowPrecisionCodec.floatToE4m3BitsSatFinite)

  private val matrixMulE5m2PackedA: Seq[Int] =
    packMatrixRowsAsFp8x2(matrixMulFp8LogicalA, rows = 2, cols = 4, LowPrecisionCodec.floatToE5m2BitsSatFinite)

  private val matrixMulE5m2PackedB: Seq[Int] =
    packMatrixColsAsFp8x2(matrixMulFp8LogicalB, rows = 4, cols = 2, LowPrecisionCodec.floatToE5m2BitsSatFinite)

  private def matrixMulPackedFp8Expected(
      aLogical: Seq[Float],
      bLogical: Seq[Float],
      m: Int,
      n: Int,
      k: Int,
      quantize: Float => Float
  ): Seq[Float] = {
    val a = aLogical.map(quantize)
    val b = bLogical.map(quantize)
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

  private val matrixMulE4m3Expected: Seq[Float] =
    matrixMulPackedFp8Expected(matrixMulFp8LogicalA, matrixMulFp8LogicalB, m = 2, n = 2, k = 4, quantizeE4m3ToHalfFloat)

  private val matrixMulE5m2Expected: Seq[Float] =
    matrixMulPackedFp8Expected(matrixMulFp8LogicalA, matrixMulFp8LogicalB, m = 2, n = 2, k = 4, quantizeE5m2ToHalfFloat)

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

  val scalarAddF16: KernelCase = KernelCase(
    name = "scalar_add_f16",
    relativeSourcePath = "arithmetic/scalar_add_f16.ptx",
    purpose = "Add one FP16 scalar from each input array per thread and store the FP16 result.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 8, argBase = 0x6A0),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataF16(base = 0x8000, values = scalarAddF16InputA),
      WriteDataF16(base = 0x8200, values = scalarAddF16InputB),
      WriteArgBuffer(base = 0x6A0, values = Seq(0x8000L, 0x8200L, 0x8400L))
    ),
    expectation = Success(checks = Seq(ExpectF16(base = 0x8400, values = scalarAddF16Expected))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val vectorAddF16x2: KernelCase = KernelCase(
    name = "vector_add_f16x2",
    relativeSourcePath = "arithmetic/vector_add_f16x2.ptx",
    purpose = "Add one packed FP16x2 value from each input array per thread and store the packed result.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, argBase = 0x6C0),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataWords(base = 0x8600, values = vectorAddF16x2InputA.map(word32)),
      WriteDataWords(base = 0x8700, values = vectorAddF16x2InputB.map(word32)),
      WriteArgBuffer(base = 0x6C0, values = Seq(0x8600L, 0x8700L, 0x8800L))
    ),
    expectation = Success(checks = Seq(ExpectWords(base = 0x8800, values = vectorAddF16x2Expected.map(word32)))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val matrixAddF16: KernelCase = KernelCase(
    name = "matrix_add_f16",
    relativeSourcePath = "arithmetic/matrix_add_f16.ptx",
    purpose = "Add two FP16 row-major matrices using 2D thread coordinates and store an FP16 matrix result.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, blockDimY = 4, argBase = 0x6E0),
    timeoutCycles = 40000,
    preloadOps = Seq(
      WriteDataF16(base = 0x8A00, values = matrixAddF16InputA(rows = 4, cols = 4)),
      WriteDataF16(base = 0x8C00, values = matrixAddF16InputB(rows = 4, cols = 4)),
      WriteArgBuffer(base = 0x6E0, values = Seq(0x8A00L, 0x8C00L, 0x8E00L, 4L, 4L, 4L, 4L, 4L))
    ),
    expectation = Success(checks = Seq(ExpectF16(base = 0x8E00, values = matrixAddF16Expected(rows = 4, cols = 4)))),
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val matrixMulF16AccumF32: KernelCase = KernelCase(
    name = "matrix_mul_f16_accum_f32",
    relativeSourcePath = "arithmetic/matrix_mul_f16_accum_f32.ptx",
    purpose = "Multiply two FP16 input matrices with FP32 accumulation and store an FP32 output matrix.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 2, blockDimY = 2, argBase = 0x700),
    timeoutCycles = 80000,
    preloadOps = Seq(
      WriteDataF16(base = 0x9000, values = matrixMulF16InputA(m = 2, k = 4)),
      WriteDataF16(base = 0x9200, values = matrixMulF16InputB(k = 4, n = 2)),
      WriteArgBuffer(base = 0x700, values = Seq(0x9000L, 0x9200L, 0x9400L, 2L, 2L, 4L, 4L, 2L, 2L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0x9400, values = matrixMulF16Expected(m = 2, n = 2, k = 4)))),
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val scalarConvertE4m3x2F16x2: KernelCase = KernelCase(
    name = "scalar_convert_e4m3x2_f16x2",
    relativeSourcePath = "arithmetic/scalar_convert_e4m3x2_f16x2.ptx",
    purpose = "Convert one packed E4M3x2 value per thread into one packed F16x2 value.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, argBase = 0x720),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataPacked16(base = 0x9800, values = scalarConvertE4m3Input),
      WriteArgBuffer(base = 0x720, values = Seq(0x9800L, 0x9900L))
    ),
    expectation = Success(checks = Seq(ExpectWords(base = 0x9900, values = scalarConvertE4m3Expected.map(word32)))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val scalarConvertE5m2x2F16x2: KernelCase = KernelCase(
    name = "scalar_convert_e5m2x2_f16x2",
    relativeSourcePath = "arithmetic/scalar_convert_e5m2x2_f16x2.ptx",
    purpose = "Convert one packed E5M2x2 value per thread into one packed F16x2 value.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, argBase = 0x740),
    timeoutCycles = 30000,
    preloadOps = Seq(
      WriteDataPacked16(base = 0x9A00, values = scalarConvertE5m2Input),
      WriteArgBuffer(base = 0x740, values = Seq(0x9A00L, 0x9B00L))
    ),
    expectation = Success(checks = Seq(ExpectWords(base = 0x9B00, values = scalarConvertE5m2Expected.map(word32)))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val vectorAddE4m3x2: KernelCase = KernelCase(
    name = "vector_add_e4m3x2",
    relativeSourcePath = "arithmetic/vector_add_e4m3x2.ptx",
    purpose = "Add one packed E4M3x2 vector from each input array per thread and store one packed E4M3x2 result.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, argBase = 0x760),
    timeoutCycles = 40000,
    preloadOps = Seq(
      WriteDataPacked16(base = 0x9C00, values = vectorAddE4m3InputA),
      WriteDataPacked16(base = 0x9D00, values = vectorAddE4m3InputB),
      WriteArgBuffer(base = 0x760, values = Seq(0x9C00L, 0x9D00L, 0x9E00L))
    ),
    expectation = Success(checks = Seq(ExpectPacked16(base = 0x9E00, values = vectorAddE4m3Expected))),
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val vectorAddE5m2x2: KernelCase = KernelCase(
    name = "vector_add_e5m2x2",
    relativeSourcePath = "arithmetic/vector_add_e5m2x2.ptx",
    purpose = "Add one packed E5M2x2 vector from each input array per thread and store one packed E5M2x2 result.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 4, argBase = 0x780),
    timeoutCycles = 40000,
    preloadOps = Seq(
      WriteDataPacked16(base = 0xA000, values = vectorAddE5m2InputA),
      WriteDataPacked16(base = 0xA100, values = vectorAddE5m2InputB),
      WriteArgBuffer(base = 0x780, values = Seq(0xA000L, 0xA100L, 0xA200L))
    ),
    expectation = Success(checks = Seq(ExpectPacked16(base = 0xA200, values = vectorAddE5m2Expected))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val matrixMulE4m3x2AccumF32: KernelCase = KernelCase(
    name = "matrix_mul_e4m3x2_accum_f32",
    relativeSourcePath = "arithmetic/matrix_mul_e4m3x2_accum_f32.ptx",
    purpose = "Multiply two packed E4M3x2 input matrices with FP32 accumulation and store an FP32 output matrix.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 2, blockDimY = 2, argBase = 0x7A0),
    timeoutCycles = 80000,
    preloadOps = Seq(
      WriteDataPacked16(base = 0xA400, values = matrixMulE4m3PackedA),
      WriteDataPacked16(base = 0xA600, values = matrixMulE4m3PackedB),
      WriteArgBuffer(base = 0x7A0, values = Seq(0xA400L, 0xA600L, 0xA800L, 2L, 2L, 2L, 2L, 2L, 2L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0xA800, values = matrixMulE4m3Expected))),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val matrixMulE5m2x2AccumF32: KernelCase = KernelCase(
    name = "matrix_mul_e5m2x2_accum_f32",
    relativeSourcePath = "arithmetic/matrix_mul_e5m2x2_accum_f32.ptx",
    purpose = "Multiply two packed E5M2x2 input matrices with FP32 accumulation and store an FP32 output matrix.",
    primaryFeature = FloatingPoint,
    secondaryFeatures = Seq(Arithmetic, GlobalMemory, SpecialRegisters),
    teachingLevel = Core,
    command = KernelCommand(entryPc = 0x100, blockDimX = 2, blockDimY = 2, argBase = 0x7C0),
    timeoutCycles = 80000,
    preloadOps = Seq(
      WriteDataPacked16(base = 0xAA00, values = matrixMulE5m2PackedA),
      WriteDataPacked16(base = 0xAC00, values = matrixMulE5m2PackedB),
      WriteArgBuffer(base = 0x7C0, values = Seq(0xAA00L, 0xAC00L, 0xAE00L, 2L, 2L, 2L, 2L, 2L, 2L))
    ),
    expectation = Success(checks = Seq(ExpectF32(base = 0xAE00, values = matrixMulE5m2Expected))),
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
    scalarAddF16,
    vectorAddF16x2,
    matrixAddF16,
    matrixMulF16AccumF32,
    scalarConvertE4m3x2F16x2,
    scalarConvertE5m2x2F16x2,
    vectorAddE4m3x2,
    vectorAddE5m2x2,
    matrixMulE4m3x2AccumF32,
    matrixMulE5m2x2AccumF32,
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
