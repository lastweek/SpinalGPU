package spinalgpu

import org.scalatest.Assertions._
import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.AxiLite4
import spinalgpu.toolchain.KernelCorpus
import spinalgpu.toolchain.KernelCorpus._

/** Shared runner for declarative kernel corpus cases.
  *
  * Low-level protocol and architecture tests stay hand-written in the specs. Corpus-backed execution tests should flow
  * through these helpers so PTX source, launch ABI, preload image, expected outcome, and harness coverage are
  * interpreted the same way everywhere.
  */
object KernelCorpusTestUtils {
  private final case class FaultObservation(fault: Boolean, code: BigInt, faultPc: BigInt)
  private object CompiledHarnessCache {
    lazy val defaultStreamingMultiprocessor: SimCompiled[StreamingMultiprocessor] = {
      println("[progress][compile][sm] compiling default StreamingMultiprocessor once for kernel-corpus execution tests")
      SimConfig.withVerilator.compile(new StreamingMultiprocessor(SmConfig.default))
    }

    lazy val defaultGpuTop: SimCompiled[GpuTop] = {
      println("[progress][compile][gputop] compiling default GpuTop once for top-level corpus execution tests")
      SimConfig.withVerilator.compile(new GpuTop(SmConfig.default))
    }
  }

  def compiledStreamingMultiprocessor(config: SmConfig = SmConfig.default): SimCompiled[StreamingMultiprocessor] =
    if (config == SmConfig.default) {
      CompiledHarnessCache.defaultStreamingMultiprocessor
    } else {
      println("[progress][compile][sm] compiling ad-hoc StreamingMultiprocessor for non-default config")
      SimConfig.withVerilator.compile(new StreamingMultiprocessor(config))
    }

  def compiledGpuTop(config: SmConfig = SmConfig.default): SimCompiled[GpuTop] =
    if (config == SmConfig.default) {
      CompiledHarnessCache.defaultGpuTop
    } else {
      println("[progress][compile][gputop] compiling ad-hoc GpuTop for non-default config")
      SimConfig.withVerilator.compile(new GpuTop(config))
    }

  private def progressLabel(harness: String, cases: Seq[KernelCase], kernel: KernelCase): String = {
    val index = cases.indexWhere(_.name == kernel.name)
    val ordinal = if (index >= 0) s"${index + 1}/${cases.size}" else s"?/${cases.size}"
    s"[progress][$harness $ordinal] ${kernel.name}"
  }

  private def waitForGpuTopCompletion(
      dut: GpuTop,
      hostControlBus: AxiLite4,
      clockDomain: ClockDomain,
      kernel: KernelCase
  ): FaultObservation = {
    // Wait on the live completion signal, then read the latched AXI-Lite status once.
    // Repeated AXI-Lite polling here made long-running simulations consume excessive memory.
    var cycles = 0
    while (!dut.io.debugExecutionStatus.done.toBoolean && cycles < kernel.timeoutCycles) {
      clockDomain.waitSampling()
      cycles += 1
    }

    assert(
      dut.io.debugExecutionStatus.done.toBoolean,
      s"${kernel.name} (${kernel.relativeSourcePath}) did not complete after $cycles cycles; " +
        s"busy=${dut.io.debugExecutionStatus.busy.toBoolean} " +
        s"fault=${dut.io.debugExecutionStatus.fault.toBoolean} " +
        s"faultCode=${dut.io.debugExecutionStatus.faultCode.toBigInt} " +
        f"faultPc=0x${dut.io.debugExecutionStatus.faultPc.toBigInt.toString(16)}"
    )

    val status = ExecutionTestUtils.readExecutionStatus(hostControlBus, clockDomain)
    val fault = ((status >> 2) & 1) == 1
    FaultObservation(
      fault = fault,
      code = ExecutionTestUtils.readFaultCode(hostControlBus, clockDomain),
      faultPc = ExecutionTestUtils.readFaultPc(hostControlBus, clockDomain)
    )
  }

  /** Loads the generated machine code and applies the declarative preload image before launch. */
  def loadKernelCase(memory: AxiMemorySim, kernel: KernelCase, byteCount: Int): Unit = {
    ExecutionTestUtils.loadBinaryFile(memory, kernel.entryPc, kernel.binaryPath, byteCount)
    kernel.preloadOps.foreach(applyPreloadOp(memory, _, byteCount))
  }

  /** Applies one preload operation from the declarative corpus definition. */
  def applyPreloadOp(memory: AxiMemorySim, preloadOp: PreloadOp, byteCount: Int): Unit = preloadOp match {
    case PreloadOp.WriteArgBuffer(base, values) =>
      ExecutionTestUtils.writeArgBuffer(memory, base, values, byteCount)
    case PreloadOp.WriteDataWords(base, values) =>
      ExecutionTestUtils.writeDataWords(memory, base, values, byteCount)
    case PreloadOp.WriteDataF32(base, values) =>
      ExecutionTestUtils.writeDataF32(memory, base, values, byteCount)
    case PreloadOp.WriteDataF16(base, values) =>
      ExecutionTestUtils.writeDataF16(memory, base, values)
    case PreloadOp.WriteDataPacked16(base, values) =>
      ExecutionTestUtils.writeDataPacked16(memory, base, values)
  }

  /** Checks one success condition from the declarative corpus definition. */
  def assertSuccessCheck(memory: AxiMemorySim, successCheck: SuccessCheck, byteCount: Int): Unit = successCheck match {
    case SuccessCheck.ExpectWords(base, values) =>
      values.zipWithIndex.foreach { case (expected, index) =>
        val address = base + (index.toLong * byteCount)
        val actual = ExecutionTestUtils.readWord(memory, address, byteCount)
        val expectedWord = ExecutionTestUtils.u32(expected)
        assert(
          actual == expectedWord,
          f"memory[0x$address%X] expected 0x${expectedWord.toString(16)} but saw 0x${actual.toString(16)}"
        )
      }
    case SuccessCheck.ExpectF32(base, values) =>
      values.zipWithIndex.foreach { case (expected, index) =>
        val address = base + (index.toLong * byteCount)
        val actual = ExecutionTestUtils.readWord(memory, address, byteCount)
        val expectedWord = ExecutionTestUtils.u32(ExecutionTestUtils.f32Bits(expected))
        assert(
          actual == expectedWord,
          f"memory[0x$address%X] expected f32 bits 0x${expectedWord.toString(16)} but saw 0x${actual.toString(16)}"
        )
      }
    case SuccessCheck.ExpectF16(base, values) =>
      values.zipWithIndex.foreach { case (expected, index) =>
        val address = base + (index.toLong * 2L)
        val actual = ExecutionTestUtils.readWord(memory, address, 2)
        val expectedWord = BigInt(expected & 0xFFFF)
        assert(
          actual == expectedWord,
          f"memory[0x$address%X] expected f16 bits 0x${expectedWord.toString(16)} but saw 0x${actual.toString(16)}"
        )
      }
    case SuccessCheck.ExpectPacked16(base, values) =>
      values.zipWithIndex.foreach { case (expected, index) =>
        val address = base + (index.toLong * 2L)
        val actual = ExecutionTestUtils.readWord(memory, address, 2)
        val expectedWord = BigInt(expected & 0xFFFF)
        assert(
          actual == expectedWord,
          f"memory[0x$address%X] expected packed16 bits 0x${expectedWord.toString(16)} but saw 0x${actual.toString(16)}"
        )
      }
  }

  /** Asserts the declarative postcondition after a harness reports completion. */
  private def assertKernelExpectation(memory: AxiMemorySim, kernel: KernelCase, byteCount: Int, observation: FaultObservation): Unit =
    kernel.expectation match {
      case KernelExpectation.Success(checks) =>
        assert(!observation.fault, s"${kernel.name} completed with unexpected fault code ${observation.code}")
        checks.foreach(assertSuccessCheck(memory, _, byteCount))
      case KernelExpectation.Fault(code, faultPc) =>
        assert(observation.fault, s"${kernel.name} should have faulted with code $code")
        assert(observation.code == BigInt(code), s"${kernel.name} fault code mismatch")
        faultPc.foreach(expectedPc => assert(observation.faultPc == BigInt(expectedPc), s"${kernel.name} fault PC mismatch"))
    }

  /** Runs one corpus case against the SM harness. */
  def runStreamingMultiprocessorKernelCase(kernel: KernelCase, config: SmConfig = SmConfig.default): Unit = {
    assert(
      kernel.harnessTargets.contains(HarnessTarget.StreamingMultiprocessor),
      s"${kernel.name} does not target the StreamingMultiprocessor harness"
    )

    val label = progressLabel("sm", KernelCorpus.streamingMultiprocessorCases, kernel)
    println(s"$label start")
    compiledStreamingMultiprocessor(config).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.command.start #= false
      dut.io.command.clearDone #= false
      dut.io.command.command.entryPc #= kernel.command.entryPc
      dut.io.command.command.gridDimX #= kernel.command.gridDimX
      dut.io.command.command.gridDimY #= kernel.command.gridDimY
      dut.io.command.command.gridDimZ #= kernel.command.gridDimZ
      dut.io.command.command.blockDimX #= kernel.command.blockDimX
      dut.io.command.command.blockDimY #= kernel.command.blockDimY
      dut.io.command.command.blockDimZ #= kernel.command.blockDimZ
      dut.io.command.command.argBase #= kernel.command.argBase
      dut.io.command.command.sharedBytes #= kernel.command.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      loadKernelCase(memory, kernel, config.byteCount)

      dut.io.command.start #= true
      dut.clockDomain.waitSampling()
      dut.io.command.start #= false

      var cycles = 0
      while (!dut.io.command.executionStatus.done.toBoolean && cycles < kernel.timeoutCycles) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      assert(
        dut.io.command.executionStatus.done.toBoolean,
        s"${kernel.name} (${kernel.relativeSourcePath}) did not complete after $cycles cycles; " +
          s"busy=${dut.io.command.executionStatus.busy.toBoolean} fault=${dut.io.command.executionStatus.fault.toBoolean} " +
          s"faultCode=${dut.io.command.executionStatus.faultCode.toBigInt} " +
          s"engineState=${dut.io.debug.engineState.toBigInt} " +
          s"selectedWarp=${dut.io.debug.selectedWarpId.toBigInt} " +
          s"selectedPc=0x${dut.io.debug.selectedPc.toBigInt.toString(16)}"
      )

      assertKernelExpectation(
        memory,
        kernel,
        config.byteCount,
        FaultObservation(
          fault = dut.io.command.executionStatus.fault.toBoolean,
          code = dut.io.command.executionStatus.faultCode.toBigInt,
          faultPc = dut.io.command.executionStatus.faultPc.toBigInt
        )
      )

      memory.stop()
    }
    println(s"$label done")
  }

  /** Runs one corpus case against the top-level GpuTop harness. */
  def runGpuTopKernelCase(kernel: KernelCase, config: SmConfig = SmConfig.default): Unit = {
    assert(kernel.harnessTargets.contains(HarnessTarget.GpuTop), s"${kernel.name} does not target the GpuTop harness")

    val label = progressLabel("gputop", KernelCorpus.gpuTopCases, kernel)
    println(s"$label start")
    compiledGpuTop(config).doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)
      ExecutionTestUtils.idleAxiLite(dut.io.hostControl)
      dut.coreClockDomain.assertReset()
      dut.coreClockDomain.waitSampling()
      dut.coreClockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      memory.start()
      ExecutionTestUtils.initializeAxiLiteMaster(dut.io.hostControl, dut.coreClockDomain)

      loadKernelCase(memory, kernel, config.byteCount)
      ExecutionTestUtils.submitKernelCommand(dut.io.hostControl, dut.coreClockDomain, kernel.command)
      val observation = waitForGpuTopCompletion(dut, dut.io.hostControl, dut.coreClockDomain, kernel)
      dut.coreClockDomain.waitSampling(8)

      assertKernelExpectation(
        memory,
        kernel,
        config.byteCount,
        observation
      )

      memory.stop()
    }
    println(s"$label done")
  }
}
