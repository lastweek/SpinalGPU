package spinalgpu.autoresearch

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable
import scala.util.control.NonFatal
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinalgpu._
import spinalgpu.toolchain.BenchmarkKernelCatalog
import spinalgpu.toolchain.KernelCorpus

final case class Tcgen05StateCycles(
    collectCycles: Int,
    sharedCycles: Int,
    tensorCycles: Int,
    computeCycles: Int,
    packCycles: Int,
    respondCycles: Int
) {
  def totalCycles: Int =
    collectCycles + sharedCycles + tensorCycles + computeCycles + packCycles + respondCycles

  def suggestedNextFocus: String = {
    val buckets = Seq(
      "tcgen05_compute" -> computeCycles,
      "tcgen05_tensor" -> tensorCycles,
      "tcgen05_shared" -> sharedCycles,
      "tcgen05_collect" -> collectCycles,
      "tcgen05_pack" -> packCycles,
      "tcgen05_respond" -> respondCycles
    )
    buckets.maxBy(_._2)._1
  }
}

final case class KernelBenchmarkObservation(
    cycles: Int,
    fault: Boolean,
    faultCode: BigInt,
    faultPc: BigInt,
    correctnessPassed: Boolean
)

final case class MatmulAutoResearchRoundResult(
    shape: String,
    cudaCoreCycles: Int,
    tcgen05Cycles: Int,
    tcgen05StateCycles: Tcgen05StateCycles,
    correctnessPassed: Boolean,
    fault: Boolean,
    suggestedNextFocus: String
) {
  def toJson: String = {
    val tcgen05Json =
      s""""tcgen05_state_cycles":{"collect_cycles":${tcgen05StateCycles.collectCycles},"shared_cycles":${tcgen05StateCycles.sharedCycles},"tensor_cycles":${tcgen05StateCycles.tensorCycles},"compute_cycles":${tcgen05StateCycles.computeCycles},"pack_cycles":${tcgen05StateCycles.packCycles},"respond_cycles":${tcgen05StateCycles.respondCycles},"total_cycles":${tcgen05StateCycles.totalCycles}}"""
    s"""{"shape":"${AutoResearchJson.escape(shape)}","cuda_core_cycles":$cudaCoreCycles,"tcgen05_cycles":$tcgen05Cycles,$tcgen05Json,"correctness_passed":$correctnessPassed,"fault":$fault,"suggested_next_focus":"${AutoResearchJson.escape(suggestedNextFocus)}"}"""
  }
}

object AutoResearchJson {
  def escape(value: String): String =
    value.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c.isControl => f"\\u${c.toInt}%04x"
      case c => c.toString
    }
}

object MatmulAutoResearchRound {
  private final case class GemmBenchmarkShape(m: Int, n: Int, k: Int) {
    require(m > 0 && n > 0 && k > 0, "GEMM benchmark shapes must be positive")
    require(m % 16 == 0, s"m must be a multiple of 16, got $m")
    require(n % 8 == 0, s"n must be a multiple of 8, got $n")
    require(k % 16 == 0, s"k must be a multiple of 16, got $k")

    val lda: Int = k
    val ldb: Int = n
    val ldc: Int = n
    val matrixElementCount: Int = m * n
    val label: String = s"${m}x${n}x${k}"
  }

  private sealed trait BenchmarkKernel {
    def id: String
    def sharedBytes: Int
    def binaryPath: Path
    def command(shape: GemmBenchmarkShape): KernelCorpus.KernelCommand
  }

  private case object CudaCoreFp16 extends BenchmarkKernel {
    override val id: String = "cuda_core"
    override val sharedBytes: Int = 0
    override val binaryPath: Path = BenchmarkKernelCatalog.cudaCoreGemmF16.binaryPath

    override def command(shape: GemmBenchmarkShape): KernelCorpus.KernelCommand =
      KernelCorpus.KernelCommand(
        entryPc = EntryPc,
        blockDimX = 8,
        blockDimY = 16,
        gridDimX = 1,
        gridDimY = 1,
        argBase = ArgBase,
        sharedBytes = sharedBytes
      )
  }

  private case object Tcgen05Fp16 extends BenchmarkKernel {
    override val id: String = "tcgen05"
    override val sharedBytes: Int = 0
    override val binaryPath: Path = BenchmarkKernelCatalog.tcgen05GemmF16.binaryPath

    override def command(shape: GemmBenchmarkShape): KernelCorpus.KernelCommand =
      KernelCorpus.KernelCommand(
        entryPc = EntryPc,
        blockDimX = 32,
        blockDimY = 1,
        gridDimX = 1,
        gridDimY = 1,
        argBase = ArgBase,
        sharedBytes = sharedBytes
      )
  }

  private object HarnessCache {
    private val byConfig = mutable.HashMap.empty[GpuConfig, SimCompiled[StreamingMultiprocessor]]

    def compiled(config: GpuConfig): SimCompiled[StreamingMultiprocessor] = synchronized {
      byConfig.getOrElseUpdate(config, SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)))
    }
  }

  private val EntryPc: Long = 0x100L
  private val ArgBase: Long = 0x1000L
  private val ABase: Long = 0x10000L
  private val BBase: Long = 0x20000L
  private val CBase: Long = 0x30000L

  private val benchmarkConfig: GpuConfig =
    GpuConfig.default.copy(
      sm = GpuConfig.default.sm.copy(
        subSmCount = 1,
        residentWarpsPerSubSm = 4
      )
    )

  private def halfBits(value: Float): Int = LowPrecisionCodec.floatToHalfBits(value)

  private def halfFloat(bits: Int): Float = LowPrecisionCodec.halfBitsToFloat(bits)

  private def halfFmaBits(lhs: Int, rhs: Int, acc: Int): Int =
    halfBits((halfFloat(lhs) * halfFloat(rhs)) + halfFloat(acc))

  private def timeoutCycles(shape: GemmBenchmarkShape): Int =
    200000 + (shape.m * shape.n * shape.k * 24)

  private def inputABits(shape: GemmBenchmarkShape): Seq[Int] =
    for {
      row <- 0 until shape.m
      col <- 0 until shape.k
    } yield halfBits((((row % 7) - (col % 5)) * 0.125f) + 0.375f)

  private def inputBBits(shape: GemmBenchmarkShape): Seq[Int] =
    for {
      row <- 0 until shape.k
      col <- 0 until shape.n
    } yield halfBits((((row % 4) + (col % 3)) * 0.0625f) - 0.25f)

  private def expectedOutputBits(shape: GemmBenchmarkShape): Seq[Int] = {
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

  private def loadOutputBits(memory: AxiMemorySim, shape: GemmBenchmarkShape): Seq[Int] =
    (0 until shape.matrixElementCount).map { index =>
      ExecutionTestUtils.readWord(memory, CBase + (index.toLong * 2L), byteCount = 2).toInt & 0xFFFF
    }

  private def countTcgen05Phases(
      dut: StreamingMultiprocessor,
      counts: mutable.Map[String, Int]
  ): Unit = {
    if (!dut.io.debug.subSmTcgen05Busy(0).toBoolean) {
      ()
    } else {
      dut.io.debug.subSmTcgen05States(0).toBigInt.toInt match {
        case 1 | 2 | 3 => counts("collect") = counts("collect") + 1
        case 4 | 5 => counts("shared") = counts("shared") + 1
        case 6 | 7 => counts("tensor") = counts("tensor") + 1
        case 8 | 9 => counts("compute") = counts("compute") + 1
        case 10 => counts("pack") = counts("pack") + 1
        case 11 => counts("respond") = counts("respond") + 1
        case _ =>
      }
    }
  }

  private def runKernel(
      shape: GemmBenchmarkShape,
      kernel: BenchmarkKernel
  ): (KernelBenchmarkObservation, Tcgen05StateCycles) = {
    ExecutionTestUtils.ensureBenchmarkKernelCorpusBuilt()
    val aBits = inputABits(shape)
    val bBits = inputBBits(shape)
    val expected = expectedOutputBits(shape)
    var result: Option[(KernelBenchmarkObservation, Tcgen05StateCycles)] = None

    HarnessCache.compiled(benchmarkConfig).doSim { dut =>
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

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      memory.start()

      ExecutionTestUtils.loadBinaryFile(memory, EntryPc, kernel.binaryPath, benchmarkConfig.byteCount)
      ExecutionTestUtils.writeDataF16(memory, ABase, aBits)
      ExecutionTestUtils.writeDataF16(memory, BBase, bBits)
      ExecutionTestUtils.writeDataF16(memory, CBase, Seq.fill(shape.matrixElementCount)(0))
      ExecutionTestUtils.writeArgBuffer(
        memory,
        ArgBase,
        Seq(ABase, BBase, CBase, shape.m.toLong, shape.n.toLong, shape.k.toLong, shape.lda.toLong, shape.ldb.toLong, shape.ldc.toLong),
        benchmarkConfig.byteCount
      )

      dut.io.command.start #= true
      dut.clockDomain.waitSampling()
      dut.io.command.start #= false

      var cycles = 0
      val phaseCounts = mutable.Map[String, Int](
        "collect" -> 0,
        "shared" -> 0,
        "tensor" -> 0,
        "compute" -> 0,
        "pack" -> 0,
        "respond" -> 0
      )

      while (!dut.io.command.executionStatus.done.toBoolean && cycles < timeoutCycles(shape)) {
        if (kernel == Tcgen05Fp16) {
          countTcgen05Phases(dut, phaseCounts)
        }
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      if (!dut.io.command.executionStatus.done.toBoolean) {
        memory.stop()
        throw new RuntimeException(
          s"${kernel.id} ${shape.label} did not complete after $cycles cycles; " +
            s"busy=${dut.io.command.executionStatus.busy.toBoolean} " +
            s"fault=${dut.io.command.executionStatus.fault.toBoolean} " +
            s"faultCode=${dut.io.command.executionStatus.faultCode.toBigInt}"
        )
      }

      val outputBits = loadOutputBits(memory, shape)
      val observation = KernelBenchmarkObservation(
        cycles = cycles,
        fault = dut.io.command.executionStatus.fault.toBoolean,
        faultCode = dut.io.command.executionStatus.faultCode.toBigInt,
        faultPc = dut.io.command.executionStatus.faultPc.toBigInt,
        correctnessPassed = !dut.io.command.executionStatus.fault.toBoolean && outputBits == expected
      )
      val tcgen05StateCycles = Tcgen05StateCycles(
        collectCycles = phaseCounts("collect"),
        sharedCycles = phaseCounts("shared"),
        tensorCycles = phaseCounts("tensor"),
        computeCycles = phaseCounts("compute"),
        packCycles = phaseCounts("pack"),
        respondCycles = phaseCounts("respond")
      )
      memory.stop()
      result = Some(observation -> tcgen05StateCycles)
    }

    result.get
  }

  def measure(shape: GemmBenchmarkShape): MatmulAutoResearchRoundResult = {
    val (cudaObs, _) = runKernel(shape, CudaCoreFp16)
    val (tcgenObs, tcgenCycles) = runKernel(shape, Tcgen05Fp16)
    MatmulAutoResearchRoundResult(
      shape = shape.label,
      cudaCoreCycles = cudaObs.cycles,
      tcgen05Cycles = tcgenObs.cycles,
      tcgen05StateCycles = tcgenCycles,
      correctnessPassed = cudaObs.correctnessPassed && tcgenObs.correctnessPassed,
      fault = cudaObs.fault || tcgenObs.fault,
      suggestedNextFocus = tcgenCycles.suggestedNextFocus
    )
  }

  private def parseArgs(args: Array[String]): (GemmBenchmarkShape, Path) = {
    var size = 32
    var output: Option[Path] = None
    var index = 0
    while (index < args.length) {
      args(index) match {
        case "--shape" =>
          index += 1
          size = args(index).toInt
        case "--output" =>
          index += 1
          output = Some(Path.of(args(index)))
        case other =>
          throw new IllegalArgumentException(s"unknown argument: $other")
      }
      index += 1
    }
    (GemmBenchmarkShape(size, size, size), output.getOrElse(throw new IllegalArgumentException("--output is required")))
  }

  def main(args: Array[String]): Unit = {
    try {
      val (shape, outputPath) = parseArgs(args)
      val result = measure(shape)
      Option(outputPath.getParent).foreach(Files.createDirectories)
      Files.writeString(outputPath, result.toJson + "\n")
      println(result.toJson)
    } catch {
      case NonFatal(error) =>
        Console.err.println(s"[autoresearch] benchmark round failed: ${error.getMessage}")
        throw error
    }
  }
}
