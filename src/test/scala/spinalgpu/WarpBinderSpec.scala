package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class WarpBinderSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 8,
    subSmCount = 4,
    residentWarpsPerSubSm = 2,
    subSmIssueWidth = 8,
    sharedMemoryBytes = 256
  )

  private def initDefaults(dut: WarpBinder): Unit = {
    dut.io.subSmRequest #= 0
    for (subSm <- 0 until config.subSmCount) {
      dut.io.freeLocalSlotId(subSm) #= 0
    }
    for (warpId <- 0 until config.residentWarpCount) {
      dut.io.bindings(warpId).bound #= false
      dut.io.bindings(warpId).subSmId #= 0
      dut.io.bindings(warpId).localSlotId #= 0
      dut.io.warpContexts(warpId).valid #= false
      dut.io.warpContexts(warpId).runnable #= false
      dut.io.warpContexts(warpId).pc #= 0
      dut.io.warpContexts(warpId).activeMask #= 0
      dut.io.warpContexts(warpId).threadBase #= 0
      dut.io.warpContexts(warpId).threadBaseX #= 0
      dut.io.warpContexts(warpId).threadBaseY #= 0
      dut.io.warpContexts(warpId).threadBaseZ #= 0
      dut.io.warpContexts(warpId).threadCount #= 0
      dut.io.warpContexts(warpId).outstanding #= false
      dut.io.warpContexts(warpId).exited #= false
      dut.io.warpContexts(warpId).faulted #= false
    }
  }

  private def markReadyWarp(dut: WarpBinder, warpId: Int, pc: BigInt = 0x100): Unit = {
    dut.io.warpContexts(warpId).valid #= true
    dut.io.warpContexts(warpId).runnable #= true
    dut.io.warpContexts(warpId).pc #= pc + (warpId * 4)
    dut.io.warpContexts(warpId).activeMask #= ((BigInt(1) << config.warpSize) - 1)
    dut.io.warpContexts(warpId).threadCount #= config.warpSize
    dut.io.warpContexts(warpId).outstanding #= false
    dut.io.warpContexts(warpId).exited #= false
    dut.io.warpContexts(warpId).faulted #= false
  }

  private def waitUntil(timeoutCycles: Int = 16)(condition: => Boolean)(implicit dut: WarpBinder): Unit = {
    var remaining = timeoutCycles
    while (!condition && remaining > 0) {
      dut.clockDomain.waitSampling()
      remaining -= 1
    }
    condition shouldBe true
  }

  test("binds the first four ready warps one per sub-SM before reusing a partition") {
    SimConfig.withVerilator.compile(new WarpBinder(config)).doSim { dut =>
      implicit val implicitDut: WarpBinder = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      dut.io.subSmRequest #= 0xF
      for (subSm <- 0 until config.subSmCount) {
        dut.io.freeLocalSlotId(subSm) #= 0
      }
      for (warpId <- 0 until config.residentWarpCount) {
        markReadyWarp(dut, warpId)
      }

      val expected = Seq((0, 0), (1, 1), (2, 2), (3, 3))
      expected.foreach { case (expectedWarpId, expectedSubSmId) =>
        waitUntil() { dut.io.bind.valid.toBoolean }
        dut.io.bind.valid.toBoolean shouldBe true
        dut.io.bind.payload.warpId.toBigInt shouldBe BigInt(expectedWarpId)
        dut.io.bind.payload.subSmId.toBigInt shouldBe BigInt(expectedSubSmId)
        dut.io.bind.payload.localSlotId.toBigInt shouldBe BigInt(0)
        dut.clockDomain.waitSampling()
      }
    }
  }

  test("skips bound or non-runnable warps and uses the requesting partition slot id") {
    SimConfig.withVerilator.compile(new WarpBinder(config)).doSim { dut =>
      implicit val implicitDut: WarpBinder = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      dut.io.subSmRequest #= 0x4
      dut.io.freeLocalSlotId(2) #= 1

      markReadyWarp(dut, warpId = 0)
      dut.io.bindings(0).bound #= true
      markReadyWarp(dut, warpId = 1)
      dut.io.warpContexts(1).runnable #= false
      markReadyWarp(dut, warpId = 2)

      waitUntil() { dut.io.bind.valid.toBoolean }
      dut.io.bind.valid.toBoolean shouldBe true
      dut.io.bind.payload.warpId.toBigInt shouldBe BigInt(2)
      dut.io.bind.payload.subSmId.toBigInt shouldBe BigInt(2)
      dut.io.bind.payload.localSlotId.toBigInt shouldBe BigInt(1)
    }
  }
}
