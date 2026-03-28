package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class LocalWarpSlotTableSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 8,
    subSmCount = 1,
    residentWarpsPerSubSm = 2,
    subSmIssueWidth = 8,
    sharedMemoryBytes = 256
  )

  test("tracks bound warp ids, reports free slots, and clears fully on reset") {
    SimConfig.withVerilator.compile(new LocalWarpSlotTable(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.clearBindings #= false
      dut.io.bind.valid #= false
      dut.io.bind.payload.warpId #= 0
      dut.io.bind.payload.subSmId #= 0
      dut.io.bind.payload.localSlotId #= 0
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      dut.io.freeSlotFound.toBoolean shouldBe true
      dut.io.freeSlotId.toBigInt shouldBe BigInt(0)

      dut.io.bind.valid #= true
      dut.io.bind.payload.warpId #= 1
      dut.io.bind.payload.localSlotId #= 1
      dut.clockDomain.waitSampling()
      dut.io.bind.valid #= false
      dut.clockDomain.waitSampling()

      dut.io.slotOccupied.toBigInt shouldBe BigInt(0x2)
      dut.io.boundWarpIds(1).toBigInt shouldBe BigInt(1)
      dut.io.freeSlotFound.toBoolean shouldBe true
      dut.io.freeSlotId.toBigInt shouldBe BigInt(0)

      dut.io.bind.valid #= true
      dut.io.bind.payload.warpId #= 0
      dut.io.bind.payload.localSlotId #= 0
      dut.clockDomain.waitSampling()
      dut.io.bind.valid #= false
      dut.clockDomain.waitSampling()

      dut.io.slotOccupied.toBigInt shouldBe BigInt(0x3)
      dut.io.boundWarpIds(0).toBigInt shouldBe BigInt(0)
      dut.io.freeSlotFound.toBoolean shouldBe false

      dut.io.clearBindings #= true
      dut.clockDomain.waitSampling()
      dut.io.clearBindings #= false
      dut.clockDomain.waitSampling()

      dut.io.slotOccupied.toBigInt shouldBe BigInt(0)
      dut.io.boundWarpIds(0).toBigInt shouldBe BigInt(0)
      dut.io.boundWarpIds(1).toBigInt shouldBe BigInt(0)
      dut.io.freeSlotFound.toBoolean shouldBe true
      dut.io.freeSlotId.toBigInt shouldBe BigInt(0)
    }
  }
}
