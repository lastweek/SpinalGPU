package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class LoadStoreUnitSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 8,
    subSmCount = 1,
    residentWarpsPerSubSm = 1,
    subSmIssueWidth = 4,
    sharedMemoryBytes = 256
  )

  private def initDefaults(dut: LoadStoreUnit): Unit = {
    dut.io.issue.valid #= false
    dut.io.response.ready #= true
    dut.io.sharedMemReq.ready #= false
    dut.io.sharedMemRsp.valid #= false
    dut.io.sharedMemRsp.payload.warpId #= 0
    dut.io.sharedMemRsp.payload.completed #= true
    dut.io.sharedMemRsp.payload.error #= false
    dut.io.sharedMemRsp.payload.readData #= 0
    dut.io.sharedMemRsp.payload.bankIndex #= 0
    dut.io.externalMemReq.ready #= false
    dut.io.externalMemRsp.valid #= false
    dut.io.externalMemRsp.payload.warpId #= 0
    dut.io.externalMemRsp.payload.completed #= true
    dut.io.externalMemRsp.payload.error #= false
    dut.io.externalMemRsp.payload.beatCount #= 0
    for (beat <- 0 until config.cudaLaneCount) {
      dut.io.externalMemRsp.payload.readData(beat) #= 0
    }

    dut.io.issue.payload.warpId #= 0
    dut.io.issue.payload.addressSpace #= AddressSpaceKind.GLOBAL
    dut.io.issue.payload.write #= false
    dut.io.issue.payload.accessWidth #= MemoryAccessWidthKind.WORD
    dut.io.issue.payload.activeMask #= 0
    dut.io.issue.payload.byteMask #= 0xF
    for (lane <- 0 until config.warpSize) {
      dut.io.issue.payload.addresses(lane) #= 0
      dut.io.issue.payload.writeData(lane) #= 0
    }
  }

  private def waitUntil(timeoutCycles: Int = 100)(step: => Boolean)(implicit dut: LoadStoreUnit): Unit = {
    var cycles = 0
    while (!step && cycles < timeoutCycles) {
      dut.clockDomain.waitSampling()
      cycles += 1
    }
    assert(step, s"condition not met after $cycles cycles")
  }

  test("coalesces contiguous global loads into one burst and scatters the response") {
    SimConfig.withVerilator.compile(new LoadStoreUnit(config)).doSim { dut =>
      implicit val implicitDut: LoadStoreUnit = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      dut.io.issue.valid #= true
      dut.io.issue.payload.activeMask #= 0x0F
      for (lane <- 0 until 4) {
        dut.io.issue.payload.addresses(lane) #= 0x100 + (lane * 4)
      }

      waitUntil() { dut.io.externalMemReq.valid.toBoolean }
      dut.io.externalMemReq.payload.address.toBigInt shouldBe BigInt(0x100)
      dut.io.externalMemReq.payload.beatCount.toBigInt shouldBe BigInt(4)

      dut.io.externalMemReq.ready #= true
      dut.clockDomain.waitSampling()
      dut.io.issue.valid #= false
      dut.io.externalMemReq.ready #= false

      dut.io.externalMemRsp.valid #= true
      dut.io.externalMemRsp.payload.warpId #= 0
      dut.io.externalMemRsp.payload.beatCount #= 4
      dut.io.externalMemRsp.payload.readData(0) #= 11
      dut.io.externalMemRsp.payload.readData(1) #= 22
      dut.io.externalMemRsp.payload.readData(2) #= 33
      dut.io.externalMemRsp.payload.readData(3) #= 44
      waitUntil() { dut.io.response.valid.toBoolean }
      dut.io.response.payload.error.toBoolean shouldBe false
      dut.io.response.payload.readData(0).toBigInt shouldBe BigInt(11)
      dut.io.response.payload.readData(1).toBigInt shouldBe BigInt(22)
      dut.io.response.payload.readData(2).toBigInt shouldBe BigInt(33)
      dut.io.response.payload.readData(3).toBigInt shouldBe BigInt(44)
      dut.io.externalMemRsp.valid #= false
    }
  }

  test("splits sparse global accesses into multiple bursts") {
    SimConfig.withVerilator.compile(new LoadStoreUnit(config)).doSim { dut =>
      implicit val implicitDut: LoadStoreUnit = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      dut.io.issue.valid #= true
      dut.io.issue.payload.activeMask #= 0x05
      dut.io.issue.payload.addresses(0) #= 0x100
      dut.io.issue.payload.addresses(2) #= 0x108

      waitUntil() { dut.io.externalMemReq.valid.toBoolean }
      dut.io.externalMemReq.payload.address.toBigInt shouldBe BigInt(0x100)
      dut.io.externalMemReq.payload.beatCount.toBigInt shouldBe BigInt(1)
      dut.io.externalMemReq.ready #= true
      dut.clockDomain.waitSampling()
      dut.io.issue.valid #= false
      dut.io.externalMemReq.ready #= false

      dut.io.externalMemRsp.valid #= true
      dut.io.externalMemRsp.payload.beatCount #= 1
      dut.io.externalMemRsp.payload.readData(0) #= 0xAA
      dut.clockDomain.waitSampling()
      dut.io.externalMemRsp.valid #= false

      waitUntil() { dut.io.externalMemReq.valid.toBoolean }
      dut.io.externalMemReq.payload.address.toBigInt shouldBe BigInt(0x108)
      dut.io.externalMemReq.payload.beatCount.toBigInt shouldBe BigInt(1)
    }
  }

  test("extracts halfword global loads from narrow bursts") {
    SimConfig.withVerilator.compile(new LoadStoreUnit(config)).doSim { dut =>
      implicit val implicitDut: LoadStoreUnit = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      dut.io.issue.valid #= true
      dut.io.issue.payload.activeMask #= 0x03
      dut.io.issue.payload.accessWidth #= MemoryAccessWidthKind.HALFWORD
      dut.io.issue.payload.byteMask #= 0x3
      dut.io.issue.payload.addresses(0) #= 0x100
      dut.io.issue.payload.addresses(1) #= 0x102

      waitUntil() { dut.io.externalMemReq.valid.toBoolean }
      dut.io.externalMemReq.payload.address.toBigInt shouldBe BigInt(0x100)
      dut.io.externalMemReq.payload.beatCount.toBigInt shouldBe BigInt(2)
      dut.io.externalMemReq.payload.accessWidth.toBigInt shouldBe BigInt(0)

      dut.io.externalMemReq.ready #= true
      dut.clockDomain.waitSampling()
      dut.io.issue.valid #= false
      dut.io.externalMemReq.ready #= false

      dut.io.externalMemRsp.valid #= true
      dut.io.externalMemRsp.payload.warpId #= 0
      dut.io.externalMemRsp.payload.beatCount #= 2
      dut.io.externalMemRsp.payload.readData(0) #= BigInt("0000A1B2", 16)
      dut.io.externalMemRsp.payload.readData(1) #= BigInt("C3D40000", 16)
      waitUntil() { dut.io.response.valid.toBoolean }
      dut.io.response.payload.error.toBoolean shouldBe false
      dut.io.response.payload.readData(0).toBigInt shouldBe BigInt("A1B2", 16)
      dut.io.response.payload.readData(1).toBigInt shouldBe BigInt("C3D4", 16)
      dut.io.externalMemRsp.valid #= false
    }
  }
}
