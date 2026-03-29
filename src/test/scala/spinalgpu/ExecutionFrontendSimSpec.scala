package spinalgpu

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.AxiLite4
import spinalgpu.toolchain.KernelCorpus

abstract class ExecutionFrontendGpuTopSpec extends AnyFunSuite with Matchers {
  protected val config: GpuConfig = GpuConfig.default

  protected def withGpuTopSimulation(label: String)(body: GpuTop => Unit): Unit = {
    println(s"[progress][gputop-integration] $label start")
    KernelCorpusTestUtils.compiledGpuTop(config).doSim { dut =>
      body(dut)
    }
    println(s"[progress][gputop-integration] $label done")
  }

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

  protected def runGpuTopKernelCase(kernel: KernelCorpus.KernelCase): Unit =
    KernelCorpusTestUtils.runGpuTopKernelCase(kernel, config)

  protected def runGpuTopKernelCaseWithoutHarnessGate(kernel: KernelCorpus.KernelCase): Unit = {
    val gpuTopKernels = KernelCorpus.gpuTopCases :+ KernelCorpus.linearBiasReluF32
    val index = gpuTopKernels.indexWhere(_.name == kernel.name)
    val ordinal = if (index >= 0) s"${index + 1}/${gpuTopKernels.size}" else s"?/${gpuTopKernels.size}"
    withGpuTopSimulation(s"${kernel.name} [$ordinal]") { dut =>
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

  protected def runGridIdStoreSuccessiveSubmissionCase(): Unit = {
    val kernel = KernelCorpus.gridIdStore

    withGpuTopSimulation("grid_id_store successive submissions") { dut =>
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

abstract class MultiSmExecutionFrontendGpuTopSpec extends ExecutionFrontendGpuTopSpec {
  override protected val config: GpuConfig = KernelCorpus.multiSmRegressionConfig
}

class GpuTopFullSpec extends ExecutionFrontendGpuTopSpec {
  KernelCorpus.gpuTopCases.foreach { kernel =>
    test(s"${kernel.name} executes through GpuTop") {
      runGpuTopKernelCase(kernel)
    }
  }

  test("linear_bias_relu_f32 executes through GpuTop") {
    runGpuTopKernelCaseWithoutHarnessGate(KernelCorpus.linearBiasReluF32)
  }

  test("grid_id_store increments across successive GpuTop command submissions") {
    runGridIdStoreSuccessiveSubmissionCase()
  }
}

@DoNotDiscover
class GpuTopSmokeSpec extends ExecutionFrontendGpuTopSpec {
  test("matrix_add_f32 executes through GpuTop") {
    runGpuTopKernelCase(KernelCorpus.matrixAddF32)
  }

  test("vector_add_f32x4 executes through GpuTop") {
    runGpuTopKernelCase(KernelCorpus.vectorAddF32x4)
  }

  test("linear_bias_relu_f32 executes through GpuTop") {
    runGpuTopKernelCaseWithoutHarnessGate(KernelCorpus.linearBiasReluF32)
  }

  test("grid_id_store increments across successive GpuTop command submissions") {
    runGridIdStoreSuccessiveSubmissionCase()
  }
}

class MultiSmGpuTopSpec extends MultiSmExecutionFrontendGpuTopSpec {
  KernelCorpus.multiSmGpuTopCases.foreach { kernel =>
    test(s"${kernel.name} executes through GpuTop with curated multi-SM coverage") {
      runGpuTopKernelCase(kernel)
    }
  }
}

class ExecutionFrontendSuiteContractSpec extends AnyFunSuite with Matchers {
  KernelCorpus.gpuTopCases.foreach { kernel =>
    ignore(s"kernel corpus case '${kernel.name}' executes from ${kernel.relativeSourcePath}") {
      KernelCorpusTestUtils.runGpuTopKernelCase(kernel, GpuConfig.default)
    }
  }

  test("kernel corpus keeps at least one GpuTop-targeted case") {
    KernelCorpus.gpuTopCases should not be empty
    KernelCorpus.gpuTopCases.foreach { kernel =>
      kernel.harnessTargets should contain(KernelCorpus.HarnessTarget.GpuTop)
    }
  }

  test("kernel corpus keeps curated multi-SM GpuTop cases") {
    KernelCorpus.multiSmGpuTopCases should not be empty
    KernelCorpus.multiSmGpuTopCases.foreach { kernel =>
      kernel.harnessTargets should contain(KernelCorpus.HarnessTarget.GpuTop)
    }
  }
}
