package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.sim._

class ExecutionFrontendSimSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig.default

  private def withGpu(testBody: (GpuTop, AxiMemorySim, AxiLite4Driver) => Unit): Unit = {
    SimConfig.withVerilator.compile(new GpuTop).doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)
      dut.coreClockDomain.assertReset()
      dut.coreClockDomain.waitSampling()
      dut.coreClockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      val control = AxiLite4Driver(dut.io.control, dut.coreClockDomain)
      memory.start()
      control.reset()

      testBody(dut, memory, control)

      memory.stop()
    }
  }

  private def waitForDoneSignal(dut: GpuTop, timeoutCycles: Int = 20000): Unit = {
    var cycles = 0
    while (!dut.io.debugStatus.done.toBoolean && cycles < timeoutCycles) {
      dut.coreClockDomain.waitSampling()
      cycles += 1
    }

    assert(
      dut.io.debugStatus.done.toBoolean,
      s"kernel did not complete within $timeoutCycles cycles; " +
        s"busy=${dut.io.debugStatus.busy.toBoolean} " +
        s"fault=${dut.io.debugStatus.fault.toBoolean} " +
        s"faultCode=${dut.io.debugStatus.faultCode.toBigInt} " +
        s"faultPc=0x${dut.io.debugStatus.faultPc.toBigInt.toString(16)}"
    )
  }

  private def assertKernelOutcome(
      dut: GpuTop,
      memory: AxiMemorySim,
      control: AxiLite4Driver,
      kernel: KernelManifest.KernelCase
  ): Unit = {
    ExecutionTestUtils.loadBinaryFile(memory, kernel.entryPc, kernel.binaryPath, config.byteCount)
    kernel.preload(memory, config.byteCount)
    ExecutionTestUtils.launchKernel(control, dut.coreClockDomain, kernel.launch)
    waitForDoneSignal(dut)

    kernel.expectation match {
      case success: KernelManifest.CompletionExpectation.Success =>
        dut.io.debugStatus.fault.toBoolean shouldBe false
        success.assertResults(memory, config.byteCount)
      case fault: KernelManifest.CompletionExpectation.Fault =>
        dut.io.debugStatus.fault.toBoolean shouldBe true
        dut.io.debugStatus.faultCode.toBigInt shouldBe BigInt(fault.code)
        fault.faultPc.foreach(expectedPc => dut.io.debugStatus.faultPc.toBigInt shouldBe BigInt(expectedPc))
    }
  }

  KernelManifest.gpuTopCases.foreach { kernel =>
    ignore(s"kernel corpus case '${kernel.name}' executes from ${kernel.relativeSourcePath}") {
      withGpu { (dut, memory, control) =>
        assertKernelOutcome(dut, memory, control, kernel)
      }
    }
  }
}
