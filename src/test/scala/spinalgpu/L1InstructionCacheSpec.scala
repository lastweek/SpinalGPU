package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class L1InstructionCacheSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 8,
    subSmCount = 4,
    residentWarpsPerSubSm = 1,
    subSmIssueWidth = 8,
    sharedMemoryBytes = 256
  )

  private def initDefaults(dut: L1InstructionCache): Unit = {
    dut.io.memoryReq.ready #= false
    dut.io.memoryRsp.valid #= false
    dut.io.memoryRsp.payload.warpId #= 0
    dut.io.memoryRsp.payload.error #= false
    dut.io.memoryRsp.payload.readData #= 0
    for (subSm <- 0 until config.subSmCount) {
      dut.io.subSmReq(subSm).valid #= false
      dut.io.subSmReq(subSm).payload.warpId #= 0
      dut.io.subSmReq(subSm).payload.address #= 0
      dut.io.subSmRsp(subSm).ready #= false
    }
  }

  private def waitUntil(timeoutCycles: Int = 16)(condition: => Boolean)(implicit dut: L1InstructionCache): Unit = {
    var remaining = timeoutCycles
    while (!condition && remaining > 0) {
      dut.clockDomain.waitSampling()
      remaining -= 1
    }
    condition shouldBe true
  }

  test("round-robins fetch requests and routes responses back to the requesting partition") {
    SimConfig.withVerilator.compile(new L1InstructionCache(config)).doSim { dut =>
      implicit val implicitDut: L1InstructionCache = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      dut.io.subSmReq(0).valid #= true
      dut.io.subSmReq(0).payload.warpId #= 1
      dut.io.subSmReq(0).payload.address #= 0x100
      dut.io.subSmReq(1).valid #= true
      dut.io.subSmReq(1).payload.warpId #= 2
      dut.io.subSmReq(1).payload.address #= 0x200
      dut.io.memoryReq.ready #= true

      dut.clockDomain.waitSampling()
      waitUntil() { dut.io.memoryReq.valid.toBoolean }
      dut.io.memoryReq.valid.toBoolean shouldBe true
      dut.io.memoryReq.payload.warpId.toBigInt shouldBe BigInt(1)
      dut.io.memoryReq.payload.address.toBigInt shouldBe BigInt(0x100)

      dut.clockDomain.waitSampling()
      dut.io.subSmReq(0).valid #= false
      dut.io.memoryReq.ready #= false

      dut.io.subSmRsp(0).ready #= true
      dut.io.memoryRsp.valid #= true
      dut.io.memoryRsp.payload.warpId #= 1
      dut.io.memoryRsp.payload.readData #= 0x12345678L
      dut.clockDomain.waitSampling()
      waitUntil() { dut.io.subSmRsp(0).valid.toBoolean }
      dut.io.subSmRsp(0).valid.toBoolean shouldBe true
      dut.io.subSmRsp(1).valid.toBoolean shouldBe false
      dut.io.subSmRsp(0).payload.readData.toBigInt shouldBe BigInt(0x12345678L)

      dut.clockDomain.waitSampling()
      dut.io.memoryRsp.valid #= false
      dut.io.subSmRsp(0).ready #= false

      dut.io.memoryReq.ready #= true
      dut.clockDomain.waitSampling()
      waitUntil() { dut.io.memoryReq.valid.toBoolean }
      dut.io.memoryReq.valid.toBoolean shouldBe true
      dut.io.memoryReq.payload.warpId.toBigInt shouldBe BigInt(2)
      dut.io.memoryReq.payload.address.toBigInt shouldBe BigInt(0x200)
    }
  }
}
