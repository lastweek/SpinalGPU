package spinalgpu

import java.nio.file.Files
import java.nio.file.Path
import scala.math.BigDecimal.RoundingMode
import org.scalatest.Assertions._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinalgpu.toolchain.BenchmarkKernelCatalog

object GemmBenchmarkSupport {
  final case class GemmBenchmarkShape(m: Int, n: Int, k: Int) {
    require(m > 0 && n > 0 && k > 0, "GEMM benchmark shapes must be positive")
    require(m % 16 == 0, s"m must be a multiple of 16, got $m")
    require(n % 8 == 0, s"n must be a multiple of 8, got $n")
    require(k % 16 == 0, s"k must be a multiple of 16, got $k")

    val lda: Int = k
    val ldb: Int = n
    val ldc: Int = n
    val matrixElementCount: Int = m * n

    def label: String = s"${m}x${n}x${k}"
  }

  sealed trait BenchmarkKernel {
    def id: String
    def label: String
    def sharedBytes: Int
    def binaryPath: Path
    def command(shape: GemmBenchmarkShape): spinalgpu.toolchain.KernelCorpus.KernelCommand
  }

  case object CudaCoreFp16 extends BenchmarkKernel {
    override val id: String = "cuda_core"
    override val label: String = "CUDA core"
    override val sharedBytes: Int = 0
    override val binaryPath: Path = BenchmarkKernelCatalog.cudaCoreGemmF16.binaryPath

    override def command(shape: GemmBenchmarkShape): spinalgpu.toolchain.KernelCorpus.KernelCommand =
      spinalgpu.toolchain.KernelCorpus.KernelCommand(
        entryPc = EntryPc,
        blockDimX = 8,
        blockDimY = 16,
        gridDimX = 1,
        gridDimY = 1,
        argBase = ArgBase,
        sharedBytes = sharedBytes
      )
  }

  case object Tcgen05Fp16 extends BenchmarkKernel {
    override val id: String = "tcgen05"
    override val label: String = "tcgen05"
    override val sharedBytes: Int = 3 * 1024
    override val binaryPath: Path = BenchmarkKernelCatalog.tcgen05GemmF16.binaryPath

    override def command(shape: GemmBenchmarkShape): spinalgpu.toolchain.KernelCorpus.KernelCommand =
      spinalgpu.toolchain.KernelCorpus.KernelCommand(
        entryPc = EntryPc,
        blockDimX = 32,
        blockDimY = 4,
        gridDimX = 1,
        gridDimY = 1,
        argBase = ArgBase,
        sharedBytes = sharedBytes
      )
  }

  final case class BenchmarkRun(
      kernel: BenchmarkKernel,
      shape: GemmBenchmarkShape,
      observation: BenchmarkExecutionObservation,
      outputBits: Seq[Int],
      expectedBits: Seq[Int]
  )

  final case class BenchmarkExecutionObservation(
      cycles: Int,
      fault: Boolean,
      faultCode: BigInt,
      faultPc: BigInt
  )

  final case class ComparisonRow(
      shape: GemmBenchmarkShape,
      cudaCycles: Int,
      tcgen05Cycles: Int
  ) {
    val speedup: Double = cudaCycles.toDouble / tcgen05Cycles.toDouble
    val speedupText: String = BigDecimal(speedup).setScale(2, RoundingMode.HALF_UP).toString()
  }

  final case class ArtifactPaths(markdown: Path, csv: Path, svg: Path)

  private val EntryPc: Long = 0x100L
  private val ArgBase: Long = 0x1000L
  private val ABase: Long = 0x10000L
  private val BBase: Long = 0x20000L
  private val CBase: Long = 0x30000L

  val benchmarkConfig: GpuConfig =
    GpuConfig.default.copy(
      sm = GpuConfig.default.sm.copy(
        subSmCount = 1,
        residentWarpsPerSubSm = 4
      )
    )

  val defaultShapes: Seq[GemmBenchmarkShape] = Seq(
    GemmBenchmarkShape(16, 16, 16),
    GemmBenchmarkShape(32, 32, 32),
    GemmBenchmarkShape(48, 48, 48),
    GemmBenchmarkShape(64, 64, 64)
  )

  def ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

  private def halfBits(value: Float): Int = LowPrecisionCodec.floatToHalfBits(value)

  private def halfFloat(bits: Int): Float = LowPrecisionCodec.halfBitsToFloat(bits)

  private def halfFmaBits(lhs: Int, rhs: Int, acc: Int): Int =
    halfBits((halfFloat(lhs) * halfFloat(rhs)) + halfFloat(acc))

  def inputABits(shape: GemmBenchmarkShape): Seq[Int] =
    for {
      row <- 0 until shape.m
      col <- 0 until shape.k
    } yield halfBits((((row % 7) - (col % 5)) * 0.125f) + 0.375f)

  def inputBBits(shape: GemmBenchmarkShape): Seq[Int] =
    for {
      row <- 0 until shape.k
      col <- 0 until shape.n
    } yield halfBits((((row % 4) + (col % 3)) * 0.0625f) - 0.25f)

  def expectedOutputBits(shape: GemmBenchmarkShape): Seq[Int] = {
    val a = inputABits(shape)
    val b = inputBBits(shape)
    for {
      row <- 0 until shape.m
      col <- 0 until shape.n
    } yield {
      var acc = halfBits(0.0f)
      var kk = 0
      while (kk < shape.k) {
        acc = halfFmaBits(a(row * shape.k + kk), b(kk * shape.n + col), acc)
        kk += 1
      }
      acc
    }
  }

  def benchmarkLabel(shape: GemmBenchmarkShape, kernel: BenchmarkKernel): String =
    s"${kernel.id} ${shape.label}"

  def timeoutCycles(shape: GemmBenchmarkShape): Int =
    200000 + (shape.m * shape.n * shape.k * 24)

  def ensureBuilt(): Unit =
    ExecutionTestUtils.ensureBenchmarkKernelCorpusBuilt()

  private def loadOutputBits(memory: AxiMemorySim, shape: GemmBenchmarkShape): Seq[Int] =
    (0 until shape.matrixElementCount).map { index =>
      ExecutionTestUtils.readWord(memory, CBase + (index.toLong * 2L), byteCount = 2).toInt & 0xFFFF
    }

  def configSummary(config: GpuConfig): String =
    s"single-SM benchmark config, subSmCount=${config.sm.subSmCount}, residentWarpsPerSubSm=${config.sm.residentWarpsPerSubSm}"

  private def runKernelOnGpuTop(shape: GemmBenchmarkShape, kernel: BenchmarkKernel, config: GpuConfig): BenchmarkRun = {
    ensureBuilt()
    val aBits = inputABits(shape)
    val bBits = inputBBits(shape)
    val expected = expectedOutputBits(shape)
    var runResult: Option[BenchmarkRun] = None

    KernelCorpusTestUtils.compiledGpuTop(config).doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)
      ExecutionTestUtils.idleAxiLite(dut.io.hostControl)
      dut.coreClockDomain.assertReset()
      dut.coreClockDomain.waitSampling()
      dut.coreClockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      memory.start()
      ExecutionTestUtils.initializeAxiLiteMaster(dut.io.hostControl, dut.coreClockDomain)

      ExecutionTestUtils.loadBinaryFile(memory, EntryPc, kernel.binaryPath, config.byteCount)
      ExecutionTestUtils.writeDataF16(memory, ABase, aBits)
      ExecutionTestUtils.writeDataF16(memory, BBase, bBits)
      ExecutionTestUtils.writeDataF16(memory, CBase, Seq.fill(shape.matrixElementCount)(0))
      ExecutionTestUtils.writeArgBuffer(
        memory,
        ArgBase,
        Seq(ABase, BBase, CBase, shape.m.toLong, shape.n.toLong, shape.k.toLong, shape.lda.toLong, shape.ldb.toLong, shape.ldc.toLong),
        config.byteCount
      )

      ExecutionTestUtils.submitKernelCommand(dut.io.hostControl, dut.coreClockDomain, kernel.command(shape))
      val observation = KernelCorpusTestUtils.waitForGpuTopCompletion(
        dut,
        dut.io.hostControl,
        dut.coreClockDomain,
        timeoutCycles = timeoutCycles(shape),
        label = benchmarkLabel(shape, kernel)
      )
      dut.coreClockDomain.waitSampling(8)

      val outputBits = loadOutputBits(memory, shape)
      memory.stop()

      runResult = Some(
        BenchmarkRun(
          kernel = kernel,
          shape = shape,
          observation = BenchmarkExecutionObservation(
            cycles = observation.cycles,
            fault = observation.fault,
            faultCode = observation.faultCode,
            faultPc = observation.faultPc
          ),
          outputBits = outputBits,
          expectedBits = expected
        )
      )
    }

    runResult.get
  }

  private def runKernelOnStreamingMultiprocessor(shape: GemmBenchmarkShape, kernel: BenchmarkKernel, config: GpuConfig): BenchmarkRun = {
    ensureBuilt()
    val aBits = inputABits(shape)
    val bBits = inputBBits(shape)
    val expected = expectedOutputBits(shape)
    var runResult: Option[BenchmarkRun] = None

    KernelCorpusTestUtils.compiledStreamingMultiprocessor(config).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.command.start #= false
      dut.io.command.clearDone #= false
      dut.io.command.command.entryPc #= EntryPc
      dut.io.command.command.gridDimX #= kernel.command(shape).gridDimX
      dut.io.command.command.gridDimY #= kernel.command(shape).gridDimY
      dut.io.command.command.gridDimZ #= kernel.command(shape).gridDimZ
      dut.io.command.command.blockDimX #= kernel.command(shape).blockDimX
      dut.io.command.command.blockDimY #= kernel.command(shape).blockDimY
      dut.io.command.command.blockDimZ #= kernel.command(shape).blockDimZ
      dut.io.command.command.argBase #= ArgBase
      dut.io.command.command.sharedBytes #= kernel.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()

      ExecutionTestUtils.loadBinaryFile(memory, EntryPc, kernel.binaryPath, config.byteCount)
      ExecutionTestUtils.writeDataF16(memory, ABase, aBits)
      ExecutionTestUtils.writeDataF16(memory, BBase, bBits)
      ExecutionTestUtils.writeDataF16(memory, CBase, Seq.fill(shape.matrixElementCount)(0))
      ExecutionTestUtils.writeArgBuffer(
        memory,
        ArgBase,
        Seq(ABase, BBase, CBase, shape.m.toLong, shape.n.toLong, shape.k.toLong, shape.lda.toLong, shape.ldb.toLong, shape.ldc.toLong),
        config.byteCount
      )

      dut.io.command.start #= true
      dut.clockDomain.waitSampling()
      dut.io.command.start #= false

      var cycles = 0
      while (!dut.io.command.executionStatus.done.toBoolean && cycles < timeoutCycles(shape)) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      assert(
        dut.io.command.executionStatus.done.toBoolean,
        s"${benchmarkLabel(shape, kernel)} did not complete after $cycles cycles; " +
          s"busy=${dut.io.command.executionStatus.busy.toBoolean} " +
          s"fault=${dut.io.command.executionStatus.fault.toBoolean} " +
          s"faultCode=${dut.io.command.executionStatus.faultCode.toBigInt} " +
          s"engineState=${dut.io.debug.engineState.toBigInt} " +
          s"selectedWarp=${dut.io.debug.selectedWarpId.toBigInt} " +
          s"selectedPc=0x${dut.io.debug.selectedPc.toBigInt.toString(16)}"
      )

      val outputBits = loadOutputBits(memory, shape)
      memory.stop()

      runResult = Some(
        BenchmarkRun(
          kernel = kernel,
          shape = shape,
          observation = BenchmarkExecutionObservation(
            cycles = cycles,
            fault = dut.io.command.executionStatus.fault.toBoolean,
            faultCode = dut.io.command.executionStatus.faultCode.toBigInt,
            faultPc = dut.io.command.executionStatus.faultPc.toBigInt
          ),
          outputBits = outputBits,
          expectedBits = expected
        )
      )
    }

    runResult.get
  }

  def runKernel(shape: GemmBenchmarkShape, kernel: BenchmarkKernel, config: GpuConfig = benchmarkConfig): BenchmarkRun =
    runKernelOnStreamingMultiprocessor(shape, kernel, config)

  def runGpuTopKernel(shape: GemmBenchmarkShape, kernel: BenchmarkKernel, config: GpuConfig = benchmarkConfig): BenchmarkRun =
    runKernelOnGpuTop(shape, kernel, config)

  def assertSuccessful(run: BenchmarkRun): Unit = {
    assert(!run.observation.fault, s"${benchmarkLabel(run.shape, run.kernel)} faulted with code ${run.observation.faultCode}")
    assert(run.observation.cycles > 0, s"${benchmarkLabel(run.shape, run.kernel)} reported a non-positive cycle count")
    assert(run.outputBits == run.expectedBits, s"${benchmarkLabel(run.shape, run.kernel)} output mismatch")
  }

  def runComparison(shape: GemmBenchmarkShape, config: GpuConfig = benchmarkConfig): ComparisonRow = {
    val cudaRun = runKernel(shape, CudaCoreFp16, config)
    val tcgen05Run = runKernel(shape, Tcgen05Fp16, config)
    assertSuccessful(cudaRun)
    assertSuccessful(tcgen05Run)
    ComparisonRow(shape, cudaRun.observation.cycles, tcgen05Run.observation.cycles)
  }

  def markdownTable(rows: Seq[ComparisonRow], config: GpuConfig = benchmarkConfig): String = {
    val header =
      """# GEMM Performance Comparison
        |
        |Harness: `StreamingMultiprocessor`
        |Config: """.stripMargin +
        configSummary(config) +
        """
        |
        |Kernels: `benchmark/cuda_core_gemm_f16.ptx` vs `benchmark/tcgen05_gemm_f16.ptx`
        |
        || M | N | K | CUDA cycles | tcgen05 cycles | CUDA/tcgen05 speedup |
        || ---: | ---: | ---: | ---: | ---: | ---: |
        |""".stripMargin
    val body = rows.map { row =>
      s"| ${row.shape.m} | ${row.shape.n} | ${row.shape.k} | ${row.cudaCycles} | ${row.tcgen05Cycles} | ${row.speedupText}x |"
    }
    (header + body.mkString("\n") + "\n")
  }

  def csvTable(rows: Seq[ComparisonRow]): String = {
    val lines = rows.map { row =>
      s"${row.shape.m},${row.shape.n},${row.shape.k},${row.cudaCycles},${row.tcgen05Cycles},${row.speedupText}"
    }
    ("m,n,k,cuda_cycles,tcgen05_cycles,cuda_over_tcgen05_speedup\n" + lines.mkString("\n") + "\n")
  }

  def svgChart(rows: Seq[ComparisonRow]): String = {
    require(rows.nonEmpty, "benchmark chart requires at least one row")
    val width = 720.0
    val height = 420.0
    val marginLeft = 70.0
    val marginRight = 30.0
    val marginTop = 30.0
    val marginBottom = 60.0
    val plotWidth = width - marginLeft - marginRight
    val plotHeight = height - marginTop - marginBottom
    val sizes = rows.map(_.shape.m.toDouble)
    val yMax = rows.flatMap(row => Seq(row.cudaCycles.toDouble, row.tcgen05Cycles.toDouble)).max
    val xMin = sizes.min
    val xMax = sizes.max

    def xCoord(size: Double): Double =
      if (xMax == xMin) marginLeft + (plotWidth / 2.0)
      else marginLeft + ((size - xMin) / (xMax - xMin)) * plotWidth

    def yCoord(cycles: Double): Double =
      marginTop + plotHeight - ((cycles / yMax) * plotHeight)

    def seriesPath(values: Seq[(Double, Double)]): String =
      values.zipWithIndex.map { case ((x, y), index) =>
        val prefix = if (index == 0) "M" else "L"
        f"$prefix$x%.2f,$y%.2f"
      }.mkString(" ")

    val cudaPoints = rows.map(row => xCoord(row.shape.m.toDouble) -> yCoord(row.cudaCycles.toDouble))
    val tcgen05Points = rows.map(row => xCoord(row.shape.m.toDouble) -> yCoord(row.tcgen05Cycles.toDouble))
    val xTicks = rows.map(_.shape.m).distinct
    val yTicks = 0 to 4 map (step => yMax * step / 4.0)

    val xTickSvg = xTicks.map { size =>
      val x = xCoord(size.toDouble)
      f"""<line x1="$x%.2f" y1="${marginTop + plotHeight}%.2f" x2="$x%.2f" y2="${marginTop + plotHeight + 6.0}%.2f" stroke="#333"/>
         |<text x="$x%.2f" y="${height - 20.0}%.2f" text-anchor="middle" font-size="12">$size</text>""".stripMargin
    }.mkString("\n")

    val yTickSvg = yTicks.map { value =>
      val y = yCoord(value)
      val label = value.toInt
      f"""<line x1="${marginLeft - 6.0}%.2f" y1="$y%.2f" x2="$marginLeft%.2f" y2="$y%.2f" stroke="#333"/>
         |<text x="${marginLeft - 12.0}%.2f" y="${y + 4.0}%.2f" text-anchor="end" font-size="12">$label</text>""".stripMargin
    }.mkString("\n")

    val pointSvg =
      rows.zip(cudaPoints.zip(tcgen05Points)).map { case (row, ((cudaX, cudaY), (tcgenX, tcgenY))) =>
        f"""<circle cx="$cudaX%.2f" cy="$cudaY%.2f" r="4" fill="#1f77b4"/>
           |<circle cx="$tcgenX%.2f" cy="$tcgenY%.2f" r="4" fill="#d62728">
           |  <title>${row.shape.label}</title>
           |</circle>""".stripMargin
      }.mkString("\n")

    f"""<svg xmlns="http://www.w3.org/2000/svg" width="$width%.0f" height="$height%.0f" viewBox="0 0 $width%.0f $height%.0f">
       |  <rect width="100%%" height="100%%" fill="#ffffff"/>
       |  <text x="${width / 2.0}%.2f" y="18" text-anchor="middle" font-size="18" font-family="sans-serif">GpuTop GEMM cycles</text>
       |  <line x1="$marginLeft%.2f" y1="${marginTop + plotHeight}%.2f" x2="${marginLeft + plotWidth}%.2f" y2="${marginTop + plotHeight}%.2f" stroke="#333"/>
       |  <line x1="$marginLeft%.2f" y1="$marginTop%.2f" x2="$marginLeft%.2f" y2="${marginTop + plotHeight}%.2f" stroke="#333"/>
       |  $xTickSvg
       |  $yTickSvg
       |  <path d="${seriesPath(cudaPoints)}" fill="none" stroke="#1f77b4" stroke-width="3"/>
       |  <path d="${seriesPath(tcgen05Points)}" fill="none" stroke="#d62728" stroke-width="3"/>
       |  $pointSvg
       |  <text x="${marginLeft + plotWidth / 2.0}%.2f" y="${height - 8.0}%.2f" text-anchor="middle" font-size="13" font-family="sans-serif">Matrix size (M=N=K)</text>
       |  <text x="18" y="${marginTop + plotHeight / 2.0}%.2f" text-anchor="middle" font-size="13" font-family="sans-serif" transform="rotate(-90 18 ${marginTop + plotHeight / 2.0}%.2f)">GpuTop cycles</text>
       |  <rect x="${width - 190.0}%.2f" y="36" width="148" height="48" fill="#f8f8f8" stroke="#cccccc"/>
       |  <line x1="${width - 176.0}%.2f" y1="54" x2="${width - 146.0}%.2f" y2="54" stroke="#1f77b4" stroke-width="3"/>
       |  <text x="${width - 138.0}%.2f" y="58" font-size="12" font-family="sans-serif">CUDA core</text>
       |  <line x1="${width - 176.0}%.2f" y1="72" x2="${width - 146.0}%.2f" y2="72" stroke="#d62728" stroke-width="3"/>
       |  <text x="${width - 138.0}%.2f" y="76" font-size="12" font-family="sans-serif">tcgen05</text>
       |</svg>
       |""".stripMargin
  }

  def writeArtifacts(rows: Seq[ComparisonRow], outputDir: Path, config: GpuConfig = benchmarkConfig): ArtifactPaths = {
    Files.createDirectories(outputDir)
    val markdownPath = outputDir.resolve("gemm_perf_comparison.md")
    val csvPath = outputDir.resolve("gemm_perf_comparison.csv")
    val svgPath = outputDir.resolve("gemm_perf_comparison.svg")
    Files.writeString(markdownPath, markdownTable(rows, config))
    Files.writeString(csvPath, csvTable(rows))
    Files.writeString(svgPath, svgChart(rows))
    ArtifactPaths(markdownPath, csvPath, svgPath)
  }
}

object GpuTopGemmPerfComparison {
  def main(args: Array[String]): Unit = {
    val shapes =
      if (args.isEmpty) GemmBenchmarkSupport.defaultShapes
      else args.toSeq.map { sizeText =>
        val size = sizeText.toInt
        GemmBenchmarkSupport.GemmBenchmarkShape(size, size, size)
      }
    val rows = shapes.map { shape =>
      println(s"[benchmark] running ${shape.label}")
      GemmBenchmarkSupport.runComparison(shape, GemmBenchmarkSupport.benchmarkConfig)
    }
    val artifacts = GemmBenchmarkSupport.writeArtifacts(rows, Path.of("generated", "benchmarks"), GemmBenchmarkSupport.benchmarkConfig)
    val markdown = GemmBenchmarkSupport.markdownTable(rows, GemmBenchmarkSupport.benchmarkConfig)
    println(markdown)
    println(s"wrote ${artifacts.markdown}")
    println(s"wrote ${artifacts.csv}")
    println(s"wrote ${artifacts.svg}")
  }
}
