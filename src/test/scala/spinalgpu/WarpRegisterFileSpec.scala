package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class WarpRegisterFileSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 8,
    subSmCount = 1,
    residentWarpsPerSubSm = 2,
    subSmIssueWidth = 8,
    registerCount = 32
  )
  private lazy val compiled = SimConfig.withVerilator.compile(new WarpRegisterFile(config))

  private def pattern(warpId: Int, reg: Int, lane: Int): BigInt =
    BigInt(((warpId + 1) << 16) | (reg << 8) | lane)

  private def laneMask(enabledLanes: Seq[Int]): BigInt =
    enabledLanes.foldLeft(BigInt(0)) { case (mask, lane) =>
      require(lane >= 0 && lane < config.warpSize, s"lane index out of range: $lane")
      mask | (BigInt(1) << lane)
    }

  private def withDut(body: WarpRegisterFile => Unit): Unit = {
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initializeInputs(dut)
      dut.clockDomain.assertReset()
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()
      body(dut)
    }
  }

  private def initializeInputs(dut: WarpRegisterFile): Unit = {
    dut.io.readSlotId #= 0
    dut.io.readAddrA #= 0
    dut.io.readAddrB #= 0
    dut.io.readAddrC #= 0
    dut.io.write.valid #= false
    dut.io.write.payload.slotId #= 0
    dut.io.write.payload.rd #= 0
    dut.io.write.payload.writeMask #= 0
    for (lane <- 0 until config.warpSize) {
      dut.io.write.payload.data(lane) #= 0
    }
    dut.io.clearSlot.valid #= false
    dut.io.clearSlot.payload #= 0
  }

  private def writeRegister(
      dut: WarpRegisterFile,
      slotId: Int,
      reg: Int,
      enabledLanes: Seq[Int],
      dataForLane: Int => BigInt
  ): Unit = {
    dut.io.clearSlot.valid #= false
    dut.io.write.valid #= true
    dut.io.write.payload.slotId #= slotId
    dut.io.write.payload.rd #= reg
    dut.io.write.payload.writeMask #= laneMask(enabledLanes)
    for (lane <- 0 until config.warpSize) {
      dut.io.write.payload.data(lane) #= dataForLane(lane)
    }
    dut.clockDomain.waitSampling()
    dut.io.write.valid #= false
    dut.io.write.payload.writeMask #= 0
  }

  private def clearWarp(dut: WarpRegisterFile, slotId: Int): Unit = {
    dut.io.write.valid #= false
    dut.io.clearSlot.valid #= true
    dut.io.clearSlot.payload #= slotId
    dut.clockDomain.waitSampling()
    dut.io.clearSlot.valid #= false
  }

  private def expectRead(
      dut: WarpRegisterFile,
      slotId: Int,
      regA: Int,
      regB: Int,
      expectedA: Int => BigInt,
      expectedB: Int => BigInt
  ): Unit = {
    dut.io.readSlotId #= slotId
    dut.io.readAddrA #= regA
    dut.io.readAddrB #= regB
    dut.clockDomain.waitSampling()
    for (lane <- 0 until config.warpSize) {
      dut.io.readDataA(lane).toBigInt shouldBe expectedA(lane)
      dut.io.readDataB(lane).toBigInt shouldBe expectedB(lane)
    }
  }

  test("writes to r1..r31 can be read back on both ports across the full address sweep") {
    withDut { dut =>
      val allLanes = 0 until config.warpSize
      for (reg <- 1 until config.registerCount) {
        writeRegister(dut, slotId = 0, reg = reg, enabledLanes = allLanes, dataForLane = lane => pattern(0, reg, lane))
      }

      for (reg <- 0 until config.registerCount) {
        val expected =
          if (reg == 0) (_: Int) => BigInt(0)
          else (lane: Int) => pattern(0, reg, lane)
        expectRead(dut, slotId = 0, regA = reg, regB = reg, expectedA = expected, expectedB = expected)
      }
    }
  }

  test("repeated reads return stable values without destructive side effects") {
    withDut { dut =>
      val targetReg = 7
      val allLanes = 0 until config.warpSize
      writeRegister(dut, slotId = 0, reg = targetReg, enabledLanes = allLanes, dataForLane = lane => pattern(0, targetReg, lane))

      for (_ <- 0 until 4) {
        expectRead(
          dut,
          slotId = 0,
          regA = targetReg,
          regB = targetReg,
          expectedA = lane => pattern(0, targetReg, lane),
          expectedB = lane => pattern(0, targetReg, lane)
        )
      }
    }
  }

  test("dual read ports can access different registers from the same warp independently") {
    withDut { dut =>
      val regA = 5
      val regB = 9
      val allLanes = 0 until config.warpSize
      writeRegister(dut, slotId = 0, reg = regA, enabledLanes = allLanes, dataForLane = lane => pattern(0, regA, lane))
      writeRegister(dut, slotId = 0, reg = regB, enabledLanes = allLanes, dataForLane = lane => pattern(0, regB, lane))

      expectRead(
        dut,
        slotId = 0,
        regA = regA,
        regB = regB,
        expectedA = lane => pattern(0, regA, lane),
        expectedB = lane => pattern(0, regB, lane)
      )
    }
  }

  test("write masks update only the selected lanes") {
    withDut { dut =>
      val reg = 8
      val allLanes = 0 until config.warpSize
      val maskedLanes = Seq(0, 2, 4, 6)
      writeRegister(dut, slotId = 0, reg = reg, enabledLanes = allLanes, dataForLane = lane => BigInt(0x1000 + lane))
      writeRegister(dut, slotId = 0, reg = reg, enabledLanes = maskedLanes, dataForLane = lane => BigInt(0x2000 + lane))

      expectRead(
        dut,
        slotId = 0,
        regA = reg,
        regB = reg,
        expectedA = lane => if (maskedLanes.contains(lane)) BigInt(0x2000 + lane) else BigInt(0x1000 + lane),
        expectedB = lane => if (maskedLanes.contains(lane)) BigInt(0x2000 + lane) else BigInt(0x1000 + lane)
      )
    }
  }

  test("warp-local storage is isolated across warp ids") {
    withDut { dut =>
      val reg = 6
      val allLanes = 0 until config.warpSize
      writeRegister(dut, slotId = 0, reg = reg, enabledLanes = allLanes, dataForLane = lane => pattern(0, reg, lane))
      writeRegister(dut, slotId = 1, reg = reg, enabledLanes = allLanes, dataForLane = lane => pattern(1, reg, lane))

      expectRead(
        dut,
        slotId = 0,
        regA = reg,
        regB = reg,
        expectedA = lane => pattern(0, reg, lane),
        expectedB = lane => pattern(0, reg, lane)
      )
      expectRead(
        dut,
        slotId = 1,
        regA = reg,
        regB = reg,
        expectedA = lane => pattern(1, reg, lane),
        expectedB = lane => pattern(1, reg, lane)
      )
    }
  }

  test("clearWarp resets only the selected warp") {
    withDut { dut =>
      val allLanes = 0 until config.warpSize
      writeRegister(dut, slotId = 0, reg = 3, enabledLanes = allLanes, dataForLane = lane => pattern(0, 3, lane))
      writeRegister(dut, slotId = 0, reg = 12, enabledLanes = allLanes, dataForLane = lane => pattern(0, 12, lane))
      writeRegister(dut, slotId = 1, reg = 3, enabledLanes = allLanes, dataForLane = lane => pattern(1, 3, lane))

      clearWarp(dut, slotId = 0)

      expectRead(
        dut,
        slotId = 0,
        regA = 3,
        regB = 12,
        expectedA = _ => BigInt(0),
        expectedB = _ => BigInt(0)
      )
      expectRead(
        dut,
        slotId = 1,
        regA = 3,
        regB = 3,
        expectedA = lane => pattern(1, 3, lane),
        expectedB = lane => pattern(1, 3, lane)
      )
    }
  }

  test("r0 ignores writes and always reads as zero") {
    withDut { dut =>
      val allLanes = 0 until config.warpSize
      writeRegister(dut, slotId = 0, reg = 0, enabledLanes = allLanes, dataForLane = lane => BigInt(0xDEAD + lane))
      writeRegister(dut, slotId = 0, reg = 5, enabledLanes = allLanes, dataForLane = lane => pattern(0, 5, lane))

      expectRead(
        dut,
        slotId = 0,
        regA = 0,
        regB = 0,
        expectedA = _ => BigInt(0),
        expectedB = _ => BigInt(0)
      )
    }
  }
}
