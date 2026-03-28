package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class L1DataSharedMemorySpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 8,
    subSmCount = 4,
    residentWarpsPerSubSm = 1,
    subSmIssueWidth = 8,
    sharedMemoryBytes = 256
  )

  private def initDefaults(dut: L1DataSharedMemory): Unit = {
    dut.io.sharedMemReq.ready #= false
    dut.io.sharedMemRsp.valid #= false
    dut.io.sharedMemRsp.payload.warpId #= 0
    dut.io.sharedMemRsp.payload.completed #= true
    dut.io.sharedMemRsp.payload.error #= false
    dut.io.sharedMemRsp.payload.readData #= 0
    dut.io.sharedMemRsp.payload.bankIndex #= 0

    dut.io.memoryReq.ready #= false
    dut.io.memoryRsp.valid #= false
    dut.io.memoryRsp.payload.warpId #= 0
    dut.io.memoryRsp.payload.completed #= true
    dut.io.memoryRsp.payload.error #= false
    dut.io.memoryRsp.payload.beatCount #= 0
    for (beat <- 0 until config.cudaLaneCount) {
      dut.io.memoryRsp.payload.readData(beat) #= 0
    }

    for (subSm <- 0 until config.subSmCount) {
      dut.io.sharedReq(subSm).valid #= false
      dut.io.sharedReq(subSm).payload.warpId #= 0
      dut.io.sharedReq(subSm).payload.write #= false
      dut.io.sharedReq(subSm).payload.address #= 0
      dut.io.sharedReq(subSm).payload.writeData #= 0
      dut.io.sharedReq(subSm).payload.byteMask #= 0xF
      dut.io.sharedRsp(subSm).ready #= false

      dut.io.externalReq(subSm).valid #= false
      dut.io.externalReq(subSm).payload.warpId #= 0
      dut.io.externalReq(subSm).payload.write #= false
      dut.io.externalReq(subSm).payload.address #= 0
      dut.io.externalReq(subSm).payload.beatCount #= 0
      dut.io.externalReq(subSm).payload.byteMask #= 0xF
      for (beat <- 0 until config.cudaLaneCount) {
        dut.io.externalReq(subSm).payload.writeData(beat) #= 0
      }
      dut.io.externalRsp(subSm).ready #= false
    }
  }

  private def waitUntil(timeoutCycles: Int = 16)(condition: => Boolean)(implicit dut: L1DataSharedMemory): Unit = {
    var remaining = timeoutCycles
    while (!condition && remaining > 0) {
      dut.clockDomain.waitSampling()
      remaining -= 1
    }
    condition shouldBe true
  }

  test("routes shared-memory and external-memory traffic back to the requesting sub-SM") {
    SimConfig.withVerilator.compile(new L1DataSharedMemory(config)).doSim { dut =>
      implicit val implicitDut: L1DataSharedMemory = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      dut.io.sharedReq(3).valid #= true
      dut.io.sharedReq(3).payload.warpId #= 3
      dut.io.sharedReq(3).payload.address #= 5
      dut.io.sharedMemReq.ready #= true

      dut.clockDomain.waitSampling()
      dut.io.idle.toBoolean shouldBe false
      waitUntil() { dut.io.sharedMemReq.valid.toBoolean }
      dut.io.sharedMemReq.valid.toBoolean shouldBe true
      dut.io.sharedMemReq.payload.warpId.toBigInt shouldBe BigInt(3)
      dut.io.sharedMemReq.payload.address.toBigInt shouldBe BigInt(5)

      dut.clockDomain.waitSampling()
      dut.io.sharedReq(3).valid #= false
      dut.io.sharedMemReq.ready #= false

      dut.io.sharedRsp(3).ready #= true
      dut.io.sharedMemRsp.valid #= true
      dut.io.sharedMemRsp.payload.warpId #= 3
      dut.io.sharedMemRsp.payload.readData #= 0x55
      dut.clockDomain.waitSampling()
      waitUntil() { dut.io.sharedRsp(3).valid.toBoolean }
      dut.io.sharedRsp(3).valid.toBoolean shouldBe true
      dut.io.sharedRsp(3).payload.readData.toBigInt shouldBe BigInt(0x55)

      dut.clockDomain.waitSampling()
      dut.io.sharedMemRsp.valid #= false
      dut.io.sharedRsp(3).ready #= false
      dut.io.idle.toBoolean shouldBe true

      dut.io.externalReq(1).valid #= true
      dut.io.externalReq(1).payload.warpId #= 2
      dut.io.externalReq(1).payload.address #= 0x300
      dut.io.externalReq(1).payload.beatCount #= 2
      dut.io.externalReq(1).payload.writeData(0) #= 0x11
      dut.io.externalReq(1).payload.writeData(1) #= 0x22
      dut.io.memoryReq.ready #= true

      dut.clockDomain.waitSampling()
      dut.io.idle.toBoolean shouldBe false
      waitUntil() { dut.io.memoryReq.valid.toBoolean }
      dut.io.memoryReq.valid.toBoolean shouldBe true
      dut.io.memoryReq.payload.warpId.toBigInt shouldBe BigInt(2)
      dut.io.memoryReq.payload.address.toBigInt shouldBe BigInt(0x300)
      dut.io.memoryReq.payload.beatCount.toBigInt shouldBe BigInt(2)

      dut.clockDomain.waitSampling()
      dut.io.externalReq(1).valid #= false
      dut.io.memoryReq.ready #= false

      dut.io.externalRsp(1).ready #= true
      dut.io.memoryRsp.valid #= true
      dut.io.memoryRsp.payload.warpId #= 2
      dut.io.memoryRsp.payload.beatCount #= 2
      dut.io.memoryRsp.payload.readData(0) #= 0xAA
      dut.io.memoryRsp.payload.readData(1) #= 0xBB
      dut.clockDomain.waitSampling()
      waitUntil() { dut.io.externalRsp(1).valid.toBoolean }
      dut.io.externalRsp(1).valid.toBoolean shouldBe true
      dut.io.externalRsp(1).payload.readData(0).toBigInt shouldBe BigInt(0xAA)
      dut.io.externalRsp(1).payload.readData(1).toBigInt shouldBe BigInt(0xBB)
      dut.clockDomain.waitSampling()
      dut.io.memoryRsp.valid #= false
      dut.io.externalRsp(1).ready #= false
      dut.io.idle.toBoolean shouldBe true
    }
  }
}
