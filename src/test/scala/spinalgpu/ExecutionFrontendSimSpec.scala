package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.sim._
import spinalgpu.toolchain.KernelCorpus

// Low-level AXI-Lite and top-level boundary checks stay in dedicated specs.
// This suite reuses the shared corpus runner for ignored GpuTop execution smoke cases.
class ExecutionFrontendSimSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig.default

  KernelCorpus.gpuTopCases.foreach { kernel =>
    ignore(s"kernel corpus case '${kernel.name}' executes from ${kernel.relativeSourcePath}") {
      KernelCorpusTestUtils.runGpuTopKernelCase(kernel, config)
    }
  }

  test("grid_id_store increments across successive GpuTop launches") {
    val kernel = KernelCorpus.gridIdStore

    SimConfig.withVerilator.compile(new GpuTop(config)).doSim { dut =>
      def waitForDoneSignal(timeoutCycles: Int): Unit = {
        var cycles = 0
        while (!dut.io.debugStatus.done.toBoolean && cycles < timeoutCycles) {
          dut.coreClockDomain.waitSampling()
          cycles += 1
        }

        assert(
          dut.io.debugStatus.done.toBoolean,
          s"${kernel.name} did not complete after $timeoutCycles cycles; " +
            s"busy=${dut.io.debugStatus.busy.toBoolean} fault=${dut.io.debugStatus.fault.toBoolean} " +
            s"faultCode=${dut.io.debugStatus.faultCode.toBigInt} " +
            s"faultPc=0x${dut.io.debugStatus.faultPc.toBigInt.toString(16)}"
        )
      }

      dut.coreClockDomain.forkStimulus(period = 10)
      dut.coreClockDomain.assertReset()
      dut.coreClockDomain.waitSampling()
      dut.coreClockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      val control = AxiLite4Driver(dut.io.control, dut.coreClockDomain)
      memory.start()
      control.reset()

      KernelCorpusTestUtils.loadKernelCase(memory, kernel, config.byteCount)

      ExecutionTestUtils.launchKernel(control, dut.coreClockDomain, kernel.launch)
      waitForDoneSignal(kernel.timeoutCycles)
      dut.io.debugStatus.fault.toBoolean shouldBe false
      ExecutionTestUtils.readWord(memory, 0xA00L, config.byteCount) shouldBe BigInt(0)
      ExecutionTestUtils.readWord(memory, 0xA04L, config.byteCount) shouldBe BigInt(0)

      ExecutionTestUtils.clearDone(control, dut.coreClockDomain)
      dut.coreClockDomain.waitSampling(4)

      ExecutionTestUtils.launchKernel(control, dut.coreClockDomain, kernel.launch)
      waitForDoneSignal(kernel.timeoutCycles)
      dut.io.debugStatus.fault.toBoolean shouldBe false
      ExecutionTestUtils.readWord(memory, 0xA00L, config.byteCount) shouldBe BigInt(1)
      ExecutionTestUtils.readWord(memory, 0xA04L, config.byteCount) shouldBe BigInt(0)

      memory.stop()
    }
  }

  test("kernel corpus keeps at least one GpuTop-targeted case") {
    KernelCorpus.gpuTopCases should not be empty
    KernelCorpus.gpuTopCases.foreach { kernel =>
      kernel.harnessTargets should contain(KernelCorpus.HarnessTarget.GpuTop)
    }
  }
}
