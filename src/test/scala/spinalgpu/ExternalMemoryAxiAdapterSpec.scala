package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._

class ExternalMemoryAxiAdapterSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(warpSize = 8, cudaLaneCount = 4, residentWarpCount = 1, sharedMemoryBytes = 256)

  private def initDefaults(dut: ExternalMemoryAxiAdapter): Unit = {
    dut.io.request.valid #= false
    dut.io.response.ready #= true
    dut.io.request.payload.warpId #= 0
    dut.io.request.payload.write #= false
    dut.io.request.payload.address #= 0
    dut.io.request.payload.beatCount #= 0
    dut.io.request.payload.byteMask #= 0xF
    for (beat <- 0 until config.cudaLaneCount) {
      dut.io.request.payload.writeData(beat) #= 0
    }
  }

  private def waitUntil(timeoutCycles: Int = 100)(condition: => Boolean)(implicit clockDomain: ClockDomain): Unit = {
    var cycles = 0
    while (!condition && cycles < timeoutCycles) {
      clockDomain.waitSampling()
      cycles += 1
    }
    assert(condition, s"condition not met after $cycles cycles")
  }

  test("drives AXI burst reads and returns multiple beats") {
    SimConfig.withVerilator.compile(new ExternalMemoryAxiAdapter(config)).doSim { dut =>
      implicit val clockDomain: ClockDomain = dut.clockDomain
      dut.clockDomain.forkStimulus(period = 10)
      val memory = AxiMemorySim(dut.io.axi, dut.clockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      memory.start()
      initDefaults(dut)

      Seq(10, 20, 30, 40).zipWithIndex.foreach { case (value, index) =>
        memory.memory.writeBigInt(0x200L + (index * config.byteCount), BigInt(value), config.byteCount)
      }

      dut.io.request.valid #= true
      dut.io.request.payload.address #= 0x200
      dut.io.request.payload.beatCount #= 4
      waitUntil() { dut.io.axi.ar.valid.toBoolean }
      dut.io.axi.ar.len.toBigInt shouldBe BigInt(3)
      dut.clockDomain.waitSampling()
      dut.io.request.valid #= false

      waitUntil(timeoutCycles = 200) { dut.io.response.valid.toBoolean }
      dut.io.response.payload.error.toBoolean shouldBe false
      dut.io.response.payload.readData(0).toBigInt shouldBe BigInt(10)
      dut.io.response.payload.readData(1).toBigInt shouldBe BigInt(20)
      dut.io.response.payload.readData(2).toBigInt shouldBe BigInt(30)
      dut.io.response.payload.readData(3).toBigInt shouldBe BigInt(40)

      memory.stop()
    }
  }

  test("drives AXI burst writes across multiple beats") {
    SimConfig.withVerilator.compile(new ExternalMemoryAxiAdapter(config)).doSim { dut =>
      implicit val clockDomain: ClockDomain = dut.clockDomain
      dut.clockDomain.forkStimulus(period = 10)
      val memory = AxiMemorySim(dut.io.axi, dut.clockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      memory.start()
      initDefaults(dut)

      dut.io.request.valid #= true
      dut.io.request.payload.write #= true
      dut.io.request.payload.address #= 0x240
      dut.io.request.payload.beatCount #= 3
      dut.io.request.payload.writeData(0) #= 0x1111
      dut.io.request.payload.writeData(1) #= 0x2222
      dut.io.request.payload.writeData(2) #= 0x3333
      waitUntil() { dut.io.axi.aw.valid.toBoolean }
      dut.io.axi.aw.len.toBigInt shouldBe BigInt(2)
      dut.clockDomain.waitSampling()
      dut.io.request.valid #= false

      waitUntil(timeoutCycles = 200) { dut.io.response.valid.toBoolean }
      dut.io.response.payload.error.toBoolean shouldBe false
      memory.memory.readBigInt(0x240L, config.byteCount) shouldBe BigInt(0x1111)
      memory.memory.readBigInt(0x244L, config.byteCount) shouldBe BigInt(0x2222)
      memory.memory.readBigInt(0x248L, config.byteCount) shouldBe BigInt(0x3333)

      memory.stop()
    }
  }
}
