package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class CudaCoreArraySpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 8,
    subSmCount = 1,
    residentWarpsPerSubSm = 1,
    subSmIssueWidth = 2,
    sharedMemoryBytes = 256
  )
  private val subwarpSliceCount = config.warpSize / config.cudaLaneCount

  private def initDefaults(dut: CudaCoreArray): Unit = {
    dut.io.issue.valid #= false
    dut.io.response.ready #= false
    dut.io.issue.payload.warpId #= 0
    dut.io.issue.payload.opcode #= Opcode.ADD
    dut.io.issue.payload.activeMask #= 0
    for (lane <- 0 until config.warpSize) {
      dut.io.issue.payload.operandA(lane) #= 0
      dut.io.issue.payload.operandB(lane) #= 0
      dut.io.issue.payload.operandC(lane) #= 0
    }
  }

  private def waitUntil(timeoutCycles: Int = 64)(condition: => Boolean)(implicit dut: CudaCoreArray): Unit = {
    var remaining = timeoutCycles
    while (!condition && remaining > 0) {
      dut.clockDomain.waitSampling()
      remaining -= 1
    }
    condition shouldBe true
  }

  test("integer CUDA ops complete after subwarp slices and preserve per-lane results") {
    SimConfig.withVerilator.compile(new CudaCoreArray(config)).doSim { dut =>
      implicit val implicitDut: CudaCoreArray = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      dut.io.issue.valid #= true
      dut.io.issue.payload.opcode #= Opcode.ADD
      dut.io.issue.payload.activeMask #= 0xFF
      for (lane <- 0 until config.warpSize) {
        dut.io.issue.payload.operandA(lane) #= lane
        dut.io.issue.payload.operandB(lane) #= lane * 10
      }

      dut.clockDomain.waitSampling()
      dut.io.issue.valid #= false

      dut.io.response.valid.toBoolean shouldBe false
      dut.clockDomain.waitSampling(subwarpSliceCount - 1)
      dut.io.response.valid.toBoolean shouldBe false
      waitUntil(timeoutCycles = 4) { dut.io.response.valid.toBoolean }
      dut.io.response.valid.toBoolean shouldBe true
      dut.io.response.payload.completed.toBoolean shouldBe true
      for (lane <- 0 until config.warpSize) {
        dut.io.response.payload.result(lane).toBigInt shouldBe BigInt(lane + (lane * 10))
      }
      dut.io.response.ready #= true
      dut.clockDomain.waitSampling()
    }
  }

  test("FP32 add and ffma return exact IEEE bits") {
    SimConfig.withVerilator.compile(new CudaCoreArray(config)).doSim { dut =>
      implicit val implicitDut: CudaCoreArray = dut
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)

      dut.io.issue.valid #= true
      dut.io.issue.payload.opcode #= Opcode.FADD
      dut.io.issue.payload.activeMask #= 0x0F
      for (lane <- 0 until config.warpSize) {
        dut.io.issue.payload.operandA(lane) #= ExecutionTestUtils.f32Bits(1.5f + lane.toFloat)
        dut.io.issue.payload.operandB(lane) #= ExecutionTestUtils.f32Bits(0.25f)
      }

      dut.clockDomain.waitSampling()
      dut.io.issue.valid #= false
      dut.clockDomain.waitSampling((config.fpAddLatency * subwarpSliceCount) - 1)
      dut.io.response.valid.toBoolean shouldBe false
      waitUntil(timeoutCycles = 4) { dut.io.response.valid.toBoolean }
      dut.io.response.valid.toBoolean shouldBe true
      for (lane <- 0 until 4) {
        dut.io.response.payload.result(lane).toBigInt shouldBe BigInt(ExecutionTestUtils.f32Bits(1.75f + lane.toFloat))
      }
      dut.io.response.ready #= true
      dut.clockDomain.waitSampling()

      initDefaults(dut)
      dut.io.issue.valid #= true
      dut.io.issue.payload.opcode #= Opcode.FFMA
      dut.io.issue.payload.activeMask #= 0x03
      dut.io.issue.payload.operandA(0) #= ExecutionTestUtils.f32Bits(2.0f)
      dut.io.issue.payload.operandB(0) #= ExecutionTestUtils.f32Bits(3.0f)
      dut.io.issue.payload.operandC(0) #= ExecutionTestUtils.f32Bits(0.5f)
      dut.io.issue.payload.operandA(1) #= ExecutionTestUtils.f32Bits(-1.5f)
      dut.io.issue.payload.operandB(1) #= ExecutionTestUtils.f32Bits(4.0f)
      dut.io.issue.payload.operandC(1) #= ExecutionTestUtils.f32Bits(1.0f)

      dut.clockDomain.waitSampling()
      dut.io.issue.valid #= false
      dut.clockDomain.waitSampling((config.fpFmaLatency * subwarpSliceCount) - 1)
      dut.io.response.valid.toBoolean shouldBe false
      waitUntil(timeoutCycles = 4) { dut.io.response.valid.toBoolean }
      dut.io.response.valid.toBoolean shouldBe true
      dut.io.response.payload.result(0).toBigInt shouldBe BigInt(ExecutionTestUtils.f32Bits(6.5f))
      dut.io.response.payload.result(1).toBigInt shouldBe BigInt(ExecutionTestUtils.f32Bits(-5.0f))
      dut.io.response.ready #= true
    }
  }
}
