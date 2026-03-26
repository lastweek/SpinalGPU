package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._

class StreamingMultiprocessorSimSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig.default

  private def pulseStart(dut: StreamingMultiprocessor): Unit = {
    dut.io.control.start #= true
    dut.clockDomain.waitSampling()
    dut.io.control.start #= false
  }

  test("launch controller initializes warp contexts and schedules multiple warps") {
    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= 0x100
      dut.io.control.launch.gridDimX #= 1
      dut.io.control.launch.blockDimX #= 40
      dut.io.control.launch.argBase #= 0
      dut.io.control.launch.sharedBytes #= 0
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      memory.memory.writeBigInt(0x100L, ExecutionTestUtils.u32(Isa.encodeBr(Opcode.EXIT, 0, 0)), config.byteCount)

      pulseStart(dut)

      dut.clockDomain.waitSamplingWhere(dut.io.debug.scheduledWarp.valid.toBoolean)
      dut.io.debug.scheduledWarp.payload.warpId.toBigInt shouldBe 0
      dut.io.debug.scheduledWarp.payload.context.pc.toBigInt shouldBe 0x100
      dut.io.debug.scheduledWarp.payload.context.threadCount.toBigInt shouldBe 32

      dut.clockDomain.waitSamplingWhere(dut.io.debug.fetchResponse.valid.toBoolean)
      dut.io.debug.fetchResponse.payload.instruction.toBigInt shouldBe ExecutionTestUtils.u32(Isa.encodeBr(Opcode.EXIT, 0, 0))

      dut.clockDomain.waitSamplingWhere(dut.io.debug.scheduledWarp.valid.toBoolean && dut.io.debug.scheduledWarp.payload.warpId.toBigInt == 1)
      dut.io.debug.scheduledWarp.payload.context.threadBase.toBigInt shouldBe 32
      dut.io.debug.scheduledWarp.payload.context.threadCount.toBigInt shouldBe 8

      dut.clockDomain.waitSamplingWhere(dut.io.control.status.done.toBoolean)
      dut.io.control.status.fault.toBoolean shouldBe false

      memory.stop()
    }
  }

  test("illegal opcode traps and latches fault status") {
    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= 0x80
      dut.io.control.launch.gridDimX #= 1
      dut.io.control.launch.blockDimX #= 1
      dut.io.control.launch.argBase #= 0
      dut.io.control.launch.sharedBytes #= 0
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      memory.memory.writeBigInt(0x80L, ExecutionTestUtils.u32(0x7F000000), config.byteCount)

      pulseStart(dut)

      dut.clockDomain.waitSamplingWhere(dut.io.debug.trap.valid.toBoolean)
      dut.io.debug.trap.payload.faultCode.toBigInt shouldBe FaultCode.IllegalOpcode
      dut.clockDomain.waitSamplingWhere(dut.io.control.status.done.toBoolean)
      dut.io.control.status.fault.toBoolean shouldBe true
      dut.io.control.status.faultCode.toBigInt shouldBe FaultCode.IllegalOpcode

      memory.stop()
    }
  }

  test("two-warp thread_id_store kernel completes and writes expected results") {
    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= 0x100
      dut.io.control.launch.gridDimX #= 1
      dut.io.control.launch.blockDimX #= 40
      dut.io.control.launch.argBase #= 0x200
      dut.io.control.launch.sharedBytes #= 0
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()

      ExecutionTestUtils.loadProgram(
        memory,
        0x100,
        """s2r r1, %argbase
          |ldg r2, [r1 + 0]
          |s2r r3, %tid.x
          |movi r4, 2
          |shl r5, r3, r4
          |add r6, r2, r5
          |stg [r6 + 0], r3
          |exit
          |""".stripMargin,
        config.byteCount
      )
      ExecutionTestUtils.writeArgBuffer(memory, 0x200, Seq(0x400), config.byteCount)

      pulseStart(dut)

      var cycles = 0
      while (!dut.io.control.status.done.toBoolean && cycles < 5000) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      assert(
        dut.io.control.status.done.toBoolean,
        s"kernel did not complete after $cycles cycles; " +
          s"busy=${dut.io.control.status.busy.toBoolean} fault=${dut.io.control.status.fault.toBoolean} " +
          s"faultCode=${dut.io.control.status.faultCode.toBigInt} " +
          s"engineState=${dut.io.debug.engineState.toBigInt} " +
          s"selectedWarp=${dut.io.debug.selectedWarpId.toBigInt} selectedPc=0x${dut.io.debug.selectedPc.toBigInt.toString(16)} " +
          s"scheduledValid=${dut.io.debug.scheduledWarp.valid.toBoolean} " +
          s"fetchValid=${dut.io.debug.fetchResponse.valid.toBoolean} " +
          s"fetchMemReq=${dut.io.debug.fetchMemoryReqValid.toBoolean}/${dut.io.debug.fetchMemoryReqReady.toBoolean} " +
          s"fetchMemRsp=${dut.io.debug.fetchMemoryRspValid.toBoolean}/${dut.io.debug.fetchMemoryRspReady.toBoolean} " +
          s"decodeValid=${dut.io.debug.decodedInstruction.valid.toBoolean} " +
          s"lsuIssue=${dut.io.debug.lsuIssueValid.toBoolean} lsuRsp=${dut.io.debug.lsuResponseValid.toBoolean} " +
          s"lsuExtReq=${dut.io.debug.lsuExternalReqValid.toBoolean}/${dut.io.debug.lsuExternalReqReady.toBoolean} " +
          s"lsuExtRsp=${dut.io.debug.lsuExternalRspValid.toBoolean}/${dut.io.debug.lsuExternalRspReady.toBoolean} " +
          s"trapValid=${dut.io.debug.trap.valid.toBoolean}"
      )
      dut.io.control.status.fault.toBoolean shouldBe false

      (0 until 40).foreach { index =>
        memory.memory.readBigInt(0x400L + index * 4L, config.byteCount) shouldBe BigInt(index)
      }

      memory.stop()
    }
  }

  test("misaligned fetch traps and latches fault status") {
    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= 0x102
      dut.io.control.launch.gridDimX #= 1
      dut.io.control.launch.blockDimX #= 1
      dut.io.control.launch.argBase #= 0
      dut.io.control.launch.sharedBytes #= 0
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()

      pulseStart(dut)

      dut.clockDomain.waitSamplingWhere(dut.io.debug.trap.valid.toBoolean)
      dut.io.debug.trap.payload.faultCode.toBigInt shouldBe FaultCode.MisalignedFetch
      dut.clockDomain.waitSamplingWhere(dut.io.control.status.done.toBoolean)
      dut.io.control.status.fault.toBoolean shouldBe true
      dut.io.control.status.faultCode.toBigInt shouldBe FaultCode.MisalignedFetch

      memory.stop()
    }
  }

  test("non-uniform branch traps and latches fault status") {
    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= 0x100
      dut.io.control.launch.gridDimX #= 1
      dut.io.control.launch.blockDimX #= 8
      dut.io.control.launch.argBase #= 0
      dut.io.control.launch.sharedBytes #= 0
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      ExecutionTestUtils.loadProgram(
        memory,
        0x100,
        """s2r r1, %tid.x
          |brnz r1, taken
          |exit
          |taken:
          |exit
          |""".stripMargin,
        config.byteCount
      )

      pulseStart(dut)

      dut.clockDomain.waitSamplingWhere(dut.io.debug.trap.valid.toBoolean)
      dut.io.debug.trap.payload.faultCode.toBigInt shouldBe FaultCode.NonUniformBranch
      dut.clockDomain.waitSamplingWhere(dut.io.control.status.done.toBoolean)
      dut.io.control.status.fault.toBoolean shouldBe true
      dut.io.control.status.faultCode.toBigInt shouldBe FaultCode.NonUniformBranch

      memory.stop()
    }
  }
}
