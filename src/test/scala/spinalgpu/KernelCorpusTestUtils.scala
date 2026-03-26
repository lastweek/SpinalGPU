package spinalgpu

import org.scalatest.Assertions._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.sim._
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

    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= kernel.launch.entryPc
      dut.io.control.launch.gridDimX #= kernel.launch.gridDimX
      dut.io.control.launch.blockDimX #= kernel.launch.blockDimX
      dut.io.control.launch.argBase #= kernel.launch.argBase
      dut.io.control.launch.sharedBytes #= kernel.launch.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      loadKernelCase(memory, kernel, config.byteCount)

      dut.io.control.start #= true
      dut.clockDomain.waitSampling()
      dut.io.control.start #= false

      var cycles = 0
      while (!dut.io.control.status.done.toBoolean && cycles < kernel.timeoutCycles) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      assert(
        dut.io.control.status.done.toBoolean,
        s"${kernel.name} (${kernel.relativeSourcePath}) did not complete after $cycles cycles; " +
          s"busy=${dut.io.control.status.busy.toBoolean} fault=${dut.io.control.status.fault.toBoolean} " +
          s"faultCode=${dut.io.control.status.faultCode.toBigInt} " +
          s"engineState=${dut.io.debug.engineState.toBigInt} " +
          s"selectedWarp=${dut.io.debug.selectedWarpId.toBigInt} " +
          s"selectedPc=0x${dut.io.debug.selectedPc.toBigInt.toString(16)}"
      )

      assertKernelExpectation(
        memory,
        kernel,
        config.byteCount,
        FaultObservation(
          fault = dut.io.control.status.fault.toBoolean,
          code = dut.io.control.status.faultCode.toBigInt,
          faultPc = dut.io.control.status.faultPc.toBigInt
        )
      )

      memory.stop()
    }
  }

  /** Runs one corpus case against the top-level GpuTop harness. */
  def runGpuTopKernelCase(kernel: KernelCase, config: SmConfig = SmConfig.default): Unit = {
    assert(kernel.harnessTargets.contains(HarnessTarget.GpuTop), s"${kernel.name} does not target the GpuTop harness")

    SimConfig.withVerilator.compile(new GpuTop(config)).doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)
      dut.coreClockDomain.assertReset()
      dut.coreClockDomain.waitSampling()
      dut.coreClockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      val control = AxiLite4Driver(dut.io.control, dut.coreClockDomain)
      memory.start()
      control.reset()

      loadKernelCase(memory, kernel, config.byteCount)
      ExecutionTestUtils.launchKernel(control, dut.coreClockDomain, kernel.launch)

      var cycles = 0
      while (!dut.io.debugStatus.done.toBoolean && cycles < kernel.timeoutCycles) {
        dut.coreClockDomain.waitSampling()
        cycles += 1
      }

      assert(
        dut.io.debugStatus.done.toBoolean,
        s"${kernel.name} (${kernel.relativeSourcePath}) did not complete after $cycles cycles; " +
          s"busy=${dut.io.debugStatus.busy.toBoolean} " +
          s"fault=${dut.io.debugStatus.fault.toBoolean} " +
          s"faultCode=${dut.io.debugStatus.faultCode.toBigInt} " +
          s"faultPc=0x${dut.io.debugStatus.faultPc.toBigInt.toString(16)}"
      )

      assertKernelExpectation(
        memory,
        kernel,
        config.byteCount,
        FaultObservation(
          fault = dut.io.debugStatus.fault.toBoolean,
          code = dut.io.debugStatus.faultCode.toBigInt,
          faultPc = dut.io.debugStatus.faultPc.toBigInt
        )
      )

      memory.stop()
    }
  }
}
