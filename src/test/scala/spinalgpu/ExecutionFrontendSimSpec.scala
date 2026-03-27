package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.sim._
import spinalgpu.toolchain.KernelCorpus

abstract class ExecutionFrontendGpuTopSpec extends AnyFunSuite with Matchers {
  protected val config: SmConfig = SmConfig.default

  protected def waitForGpuTopCompletionSignal(dut: GpuTop, kernel: KernelCorpus.KernelCase): Unit = {
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
  }

  protected def runGpuTopKernelCaseWithoutHarnessGate(kernel: KernelCorpus.KernelCase): Unit = {
    SimConfig.withVerilator.compile(new GpuTop(config)).doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)
      dut.coreClockDomain.assertReset()
      dut.coreClockDomain.waitSampling()
      dut.coreClockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      val hostControl = AxiLite4Driver(dut.io.hostControl, dut.coreClockDomain)
      memory.start()
      hostControl.reset()

      KernelCorpusTestUtils.loadKernelCase(memory, kernel, config.byteCount)
      ExecutionTestUtils.submitKernelCommand(hostControl, dut.coreClockDomain, kernel.command)
      waitForGpuTopCompletionSignal(dut, kernel)
      dut.coreClockDomain.waitSampling(8)

      dut.io.debugExecutionStatus.fault.toBoolean shouldBe false
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

class MatrixMulF32GpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("matrix_mul_f32 executes through GpuTop") {
    runGpuTopKernelCaseWithoutHarnessGate(KernelCorpus.matrixMulF32)
  }
}

class GridIdStoreGpuTopSpec extends ExecutionFrontendGpuTopSpec {
  test("grid_id_store increments across successive GpuTop command submissions") {
    val kernel = KernelCorpus.gridIdStore

    SimConfig.withVerilator.compile(new GpuTop(config)).doSim { dut =>
      def waitForExecutionCompleteSignal(timeoutCycles: Int): Unit = {
        var cycles = 0
        while (!dut.io.debugExecutionStatus.done.toBoolean && cycles < timeoutCycles) {
          dut.coreClockDomain.waitSampling()
          cycles += 1
        }

        assert(
          dut.io.debugExecutionStatus.done.toBoolean,
          s"${kernel.name} did not complete after $timeoutCycles cycles; " +
            s"busy=${dut.io.debugExecutionStatus.busy.toBoolean} fault=${dut.io.debugExecutionStatus.fault.toBoolean} " +
            s"faultCode=${dut.io.debugExecutionStatus.faultCode.toBigInt} " +
            s"faultPc=0x${dut.io.debugExecutionStatus.faultPc.toBigInt.toString(16)}"
        )
      }

      dut.coreClockDomain.forkStimulus(period = 10)
      dut.coreClockDomain.assertReset()
      dut.coreClockDomain.waitSampling()
      dut.coreClockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      val hostControl = AxiLite4Driver(dut.io.hostControl, dut.coreClockDomain)
      memory.start()
      hostControl.reset()

      KernelCorpusTestUtils.loadKernelCase(memory, kernel, config.byteCount)

      ExecutionTestUtils.submitKernelCommand(hostControl, dut.coreClockDomain, kernel.command)
      waitForExecutionCompleteSignal(kernel.timeoutCycles)
      dut.coreClockDomain.waitSampling(8)
      dut.io.debugExecutionStatus.fault.toBoolean shouldBe false
      ExecutionTestUtils.readWord(memory, 0xA00L, config.byteCount) shouldBe BigInt(0)
      ExecutionTestUtils.readWord(memory, 0xA04L, config.byteCount) shouldBe BigInt(0)

      ExecutionTestUtils.clearDone(hostControl, dut.coreClockDomain)
      dut.coreClockDomain.waitSampling(4)

      ExecutionTestUtils.submitKernelCommand(hostControl, dut.coreClockDomain, kernel.command)
      waitForExecutionCompleteSignal(kernel.timeoutCycles)
      dut.coreClockDomain.waitSampling(8)
      dut.io.debugExecutionStatus.fault.toBoolean shouldBe false
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
