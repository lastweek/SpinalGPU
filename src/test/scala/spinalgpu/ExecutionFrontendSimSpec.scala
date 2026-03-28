package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.AxiLite4
import spinalgpu.toolchain.KernelCorpus

abstract class ExecutionFrontendGpuTopSpec extends AnyFunSuite with Matchers {
  protected val config: SmConfig = SmConfig.default

  protected def waitForGpuTopDoneClear(dut: GpuTop, timeoutCycles: Int, label: String): Unit = {
    var cycles = 0
    while (dut.io.debugExecutionStatus.done.toBoolean && cycles < timeoutCycles) {
      dut.coreClockDomain.waitSampling()
      cycles += 1
    }

    assert(
      !dut.io.debugExecutionStatus.done.toBoolean,
      s"$label did not clear the done flag after $cycles cycles; " +
        s"busy=${dut.io.debugExecutionStatus.busy.toBoolean} " +
        s"fault=${dut.io.debugExecutionStatus.fault.toBoolean} " +
        s"faultCode=${dut.io.debugExecutionStatus.faultCode.toBigInt} " +
        f"faultPc=0x${dut.io.debugExecutionStatus.faultPc.toBigInt.toString(16)}"
    )
  }

  protected def clearDoneAndWaitForGpuTopReady(
      hostControlBus: AxiLite4,
      dut: GpuTop,
      timeoutCycles: Int
  ): Unit = {
    ExecutionTestUtils.clearDone(hostControlBus, dut.coreClockDomain)
    waitForGpuTopDoneClear(dut, timeoutCycles, "GpuTop clearDone")
    dut.coreClockDomain.waitSampling(4)
  }

  protected def waitForGpuTopCompletionSignal(
      hostControlBus: AxiLite4,
      dut: GpuTop,
      kernel: KernelCorpus.KernelCase
  ): (Boolean, BigInt, BigInt) = {
    // Wait on the live completion signal, then sample the host-visible registers once.
    // This avoids the high-memory AXI-Lite polling pattern that can accumulate simulator fibers.
    var cycles = 0
    while (!dut.io.debugExecutionStatus.done.toBoolean && cycles < kernel.timeoutCycles) {
      dut.coreClockDomain.waitSampling()
      cycles += 1
    }

    assert(
      dut.io.debugExecutionStatus.done.toBoolean,
      s"${kernel.name} did not complete after $cycles cycles; " +
        s"busy=${dut.io.debugExecutionStatus.busy.toBoolean} " +
        s"fault=${dut.io.debugExecutionStatus.fault.toBoolean} " +
        s"faultCode=${dut.io.debugExecutionStatus.faultCode.toBigInt} " +
        f"faultPc=0x${dut.io.debugExecutionStatus.faultPc.toBigInt.toString(16)}"
    )

    val status = ExecutionTestUtils.readExecutionStatus(hostControlBus, dut.coreClockDomain)
    val fault = ((status >> 2) & 1) == 1
    val faultCode = ExecutionTestUtils.readFaultCode(hostControlBus, dut.coreClockDomain)
    val faultPc = ExecutionTestUtils.readFaultPc(hostControlBus, dut.coreClockDomain)
    (fault, faultCode, faultPc)
  }

  protected def runGpuTopKernelCaseWithoutHarnessGate(kernel: KernelCorpus.KernelCase): Unit = {
    SimConfig.withVerilator.compile(new GpuTop(config)).doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)
      ExecutionTestUtils.idleAxiLite(dut.io.hostControl)
      dut.coreClockDomain.assertReset()
      dut.coreClockDomain.waitSampling()
      dut.coreClockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      memory.start()
      ExecutionTestUtils.initializeAxiLiteMaster(dut.io.hostControl, dut.coreClockDomain)

      KernelCorpusTestUtils.loadKernelCase(memory, kernel, config.byteCount)
      ExecutionTestUtils.submitKernelCommand(dut.io.hostControl, dut.coreClockDomain, kernel.command)
      val (fault, faultCode, faultPc) = waitForGpuTopCompletionSignal(dut.io.hostControl, dut, kernel)
      dut.coreClockDomain.waitSampling(8)

      withClue(f"faultCode=0x${faultCode.toString(16)} faultPc=0x${faultPc.toString(16)} ") {
        fault shouldBe false
      }
      kernel.expectation match {
        case KernelCorpus.KernelExpectation.Success(checks) =>
          checks.foreach(KernelCorpusTestUtils.assertSuccessCheck(memory, _, config.byteCount))
        case other =>
          fail(s"expected success expectation for explicit GpuTop run, got $other")
      }

      memory.stop()
    }
  }
}

class MatrixAddF32GpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("matrix_add_f32 executes through GpuTop") {
    KernelCorpusTestUtils.runGpuTopKernelCase(KernelCorpus.matrixAddF32, config)
  }
}

class MatrixCopyF32GpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("matrix_copy_f32 executes through GpuTop") {
    KernelCorpusTestUtils.runGpuTopKernelCase(KernelCorpus.matrixCopyF32, config)
  }
}

class VectorAddF32x4GpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("vector_add_f32x4 executes through GpuTop") {
    KernelCorpusTestUtils.runGpuTopKernelCase(KernelCorpus.vectorAddF32x4, config)
  }
}

class MatrixMulF32GpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("matrix_mul_f32 executes through GpuTop") {
    KernelCorpusTestUtils.runGpuTopKernelCase(KernelCorpus.matrixMulF32, config)
  }
}

class MatrixAddF16GpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("matrix_add_f16 executes through GpuTop") {
    KernelCorpusTestUtils.runGpuTopKernelCase(KernelCorpus.matrixAddF16, config)
  }
}

class MatrixMulF16AccumF32GpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("matrix_mul_f16_accum_f32 executes through GpuTop") {
    KernelCorpusTestUtils.runGpuTopKernelCase(KernelCorpus.matrixMulF16AccumF32, config)
  }
}

class VectorAddE4m3x2GpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("vector_add_e4m3x2 executes through GpuTop") {
    KernelCorpusTestUtils.runGpuTopKernelCase(KernelCorpus.vectorAddE4m3x2, config)
  }
}

class MatrixMulE5m2x2AccumF32GpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("matrix_mul_e5m2x2_accum_f32 executes through GpuTop") {
    KernelCorpusTestUtils.runGpuTopKernelCase(KernelCorpus.matrixMulE5m2x2AccumF32, config)
  }
}

class LinearBiasReluF32GpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("linear_bias_relu_f32 executes through GpuTop") {
    runGpuTopKernelCaseWithoutHarnessGate(KernelCorpus.linearBiasReluF32)
  }
}

class GridIdStoreGpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("grid_id_store increments across successive GpuTop command submissions") {
    val kernel = KernelCorpus.gridIdStore

    SimConfig.withVerilator.compile(new GpuTop(config)).doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)
      ExecutionTestUtils.idleAxiLite(dut.io.hostControl)
      dut.coreClockDomain.assertReset()
      dut.coreClockDomain.waitSampling()
      dut.coreClockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      memory.start()
      ExecutionTestUtils.initializeAxiLiteMaster(dut.io.hostControl, dut.coreClockDomain)

      KernelCorpusTestUtils.loadKernelCase(memory, kernel, config.byteCount)

      ExecutionTestUtils.submitKernelCommand(dut.io.hostControl, dut.coreClockDomain, kernel.command)
      val (firstFault, firstFaultCode, firstFaultPc) = waitForGpuTopCompletionSignal(dut.io.hostControl, dut, kernel)
      dut.coreClockDomain.waitSampling(8)
      withClue(f"faultCode=0x${firstFaultCode.toString(16)} faultPc=0x${firstFaultPc.toString(16)} ") {
        firstFault shouldBe false
      }
      ExecutionTestUtils.readWord(memory, 0xA00L, config.byteCount) shouldBe BigInt(0)
      ExecutionTestUtils.readWord(memory, 0xA04L, config.byteCount) shouldBe BigInt(0)

      clearDoneAndWaitForGpuTopReady(dut.io.hostControl, dut, timeoutCycles = 128)

      ExecutionTestUtils.submitKernelCommand(dut.io.hostControl, dut.coreClockDomain, kernel.command)
      val (secondFault, secondFaultCode, secondFaultPc) = waitForGpuTopCompletionSignal(dut.io.hostControl, dut, kernel)
      dut.coreClockDomain.waitSampling(8)
      withClue(f"faultCode=0x${secondFaultCode.toString(16)} faultPc=0x${secondFaultPc.toString(16)} ") {
        secondFault shouldBe false
      }
      ExecutionTestUtils.readWord(memory, 0xA00L, config.byteCount) shouldBe BigInt(1)
      ExecutionTestUtils.readWord(memory, 0xA04L, config.byteCount) shouldBe BigInt(0)

      memory.stop()
    }
  }
}

class ExecutionFrontendSuiteContractSpec extends AnyFunSuite with Matchers {
  KernelCorpus.gpuTopCases.foreach { kernel =>
    ignore(s"kernel corpus case '${kernel.name}' executes from ${kernel.relativeSourcePath}") {
      KernelCorpusTestUtils.runGpuTopKernelCase(kernel, SmConfig.default)
    }
  }

  test("kernel corpus keeps at least one GpuTop-targeted case") {
    KernelCorpus.gpuTopCases should not be empty
    KernelCorpus.gpuTopCases.foreach { kernel =>
      kernel.harnessTargets should contain(KernelCorpus.HarnessTarget.GpuTop)
    }
  }
}
