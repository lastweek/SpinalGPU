package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._

class GpuTopSimSpec extends AnyFunSuite with Matchers {
  test("GpuTop exposes idle AXI memory and AXI-Lite control boundaries") {
    println("[progress][gputop-integration] idle-boundary-smoke start")
    KernelCorpusTestUtils.compiledGpuTop(GpuConfig.default).doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)
      ExecutionTestUtils.idleAxiLite(dut.io.hostControl)
      dut.coreClockDomain.assertReset()
      dut.coreClockDomain.waitSampling()
      dut.coreClockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig())
      memory.start()
      ExecutionTestUtils.initializeAxiLiteMaster(dut.io.hostControl, dut.coreClockDomain)

      ExecutionTestUtils.readExecutionStatus(dut.io.hostControl, dut.coreClockDomain) shouldBe BigInt(0)
      dut.coreClockDomain.waitSampling(5)

      memory.stop()
    }
    println("[progress][gputop-integration] idle-boundary-smoke done")
  }
}
