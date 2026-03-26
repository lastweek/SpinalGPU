package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.sim._

class GpuTopSimSpec extends AnyFunSuite with Matchers {
  test("GpuTop exposes idle AXI memory and AXI-Lite control boundaries") {
    SimConfig.withVerilator.compile(new GpuTop).doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)

      dut.coreReset #= true
      dut.coreClockDomain.waitSampling()
      dut.coreReset #= false

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig())
      val control = AxiLite4Driver(dut.io.control, dut.coreClockDomain)
      memory.start()
      control.reset()

      ExecutionTestUtils.readStatus(control) shouldBe BigInt(0)
      dut.coreClockDomain.waitSampling(5)

      memory.stop()
    }
  }
}
