package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class GpuTopSimSpec extends AnyFunSuite {
  test("GpuTop elaborates and runs a short smoke simulation") {
    SimConfig.withVerilator.withWave.compile(new GpuTop).doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)
      dut.coreClockDomain.waitSampling(5)
    }
  }
}
