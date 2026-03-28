package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class LocalWarpSchedulerSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 8,
    subSmCount = 1,
    residentWarpsPerSubSm = 4,
    subSmIssueWidth = 8,
    sharedMemoryBytes = 256
  )

  test("round-robins across ready local slots and resets base on clear") {
    SimConfig.withVerilator.compile(new LocalWarpScheduler(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.clear #= false
      dut.io.advance #= false
      dut.io.readySlots #= 0
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      dut.io.readySlots #= 0x3
      dut.clockDomain.waitSampling()
      dut.io.selectedValid.toBoolean shouldBe true
      dut.io.selectedSlotId.toBigInt shouldBe BigInt(0)

      dut.io.advance #= true
      dut.clockDomain.waitSampling()
      dut.io.advance #= false
      dut.clockDomain.waitSampling()
      dut.io.selectedValid.toBoolean shouldBe true
      dut.io.selectedSlotId.toBigInt shouldBe BigInt(1)

      dut.io.readySlots #= 0x9
      dut.clockDomain.waitSampling()
      dut.io.selectedValid.toBoolean shouldBe true
      dut.io.selectedSlotId.toBigInt shouldBe BigInt(3)

      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false
      dut.io.readySlots #= 0x3
      dut.clockDomain.waitSampling()
      dut.io.selectedValid.toBoolean shouldBe true
      dut.io.selectedSlotId.toBigInt shouldBe BigInt(0)
    }
  }
}
