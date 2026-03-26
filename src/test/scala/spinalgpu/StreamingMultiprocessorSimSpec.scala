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

  test("two-warp thread_id_store kernel from the corpus completes and writes expected results") {
    val kernel = KernelManifest.threadIdStore

    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= kernel.entryPc
      dut.io.control.launch.gridDimX #= kernel.launch.gridDimX
      dut.io.control.launch.blockDimX #= kernel.launch.blockDimX
      dut.io.control.launch.argBase #= kernel.launch.argBase
      dut.io.control.launch.sharedBytes #= kernel.launch.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()

      ExecutionTestUtils.loadBinaryFile(memory, kernel.entryPc, kernel.binaryPath, config.byteCount)
      kernel.preload(memory, config.byteCount)

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

      kernel.expectation match {
        case success: KernelManifest.CompletionExpectation.Success =>
          success.assertResults(memory, config.byteCount)
        case _ =>
          fail("thread_id_store should be a success case")
      }

      memory.stop()
    }
  }

  test("add_store_exit kernel from the corpus completes and writes the arithmetic result") {
    val kernel = KernelManifest.addStoreExit

    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= kernel.entryPc
      dut.io.control.launch.gridDimX #= kernel.launch.gridDimX
      dut.io.control.launch.blockDimX #= kernel.launch.blockDimX
      dut.io.control.launch.argBase #= kernel.launch.argBase
      dut.io.control.launch.sharedBytes #= kernel.launch.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      ExecutionTestUtils.loadBinaryFile(memory, kernel.entryPc, kernel.binaryPath, config.byteCount)
      kernel.preload(memory, config.byteCount)

      pulseStart(dut)

      var cycles = 0
      while (!dut.io.control.status.done.toBoolean && cycles < 5000) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      assert(
        dut.io.control.status.done.toBoolean,
        s"add_store_exit did not complete after $cycles cycles; " +
          s"busy=${dut.io.control.status.busy.toBoolean} fault=${dut.io.control.status.fault.toBoolean} " +
          s"faultCode=${dut.io.control.status.faultCode.toBigInt} " +
          s"engineState=${dut.io.debug.engineState.toBigInt} " +
          s"selectedWarp=${dut.io.debug.selectedWarpId.toBigInt} selectedPc=0x${dut.io.debug.selectedPc.toBigInt.toString(16)}"
      )
      dut.io.control.status.fault.toBoolean shouldBe false

      kernel.expectation match {
        case success: KernelManifest.CompletionExpectation.Success =>
          success.assertResults(memory, config.byteCount)
        case _ =>
          fail("add_store_exit should be a success case")
      }

      memory.stop()
    }
  }

  test("uniform_loop kernel from the corpus completes and writes the terminal value") {
    val kernel = KernelManifest.uniformLoop

    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= kernel.entryPc
      dut.io.control.launch.gridDimX #= kernel.launch.gridDimX
      dut.io.control.launch.blockDimX #= kernel.launch.blockDimX
      dut.io.control.launch.argBase #= kernel.launch.argBase
      dut.io.control.launch.sharedBytes #= kernel.launch.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      ExecutionTestUtils.loadBinaryFile(memory, kernel.entryPc, kernel.binaryPath, config.byteCount)
      kernel.preload(memory, config.byteCount)

      pulseStart(dut)

      var cycles = 0
      while (!dut.io.control.status.done.toBoolean && cycles < 5000) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      assert(
        dut.io.control.status.done.toBoolean,
        s"uniform_loop did not complete after $cycles cycles; " +
          s"busy=${dut.io.control.status.busy.toBoolean} fault=${dut.io.control.status.fault.toBoolean} " +
          s"faultCode=${dut.io.control.status.faultCode.toBigInt} " +
          s"engineState=${dut.io.debug.engineState.toBigInt} " +
          s"selectedWarp=${dut.io.debug.selectedWarpId.toBigInt} selectedPc=0x${dut.io.debug.selectedPc.toBigInt.toString(16)}"
      )
      dut.io.control.status.fault.toBoolean shouldBe false

      kernel.expectation match {
        case success: KernelManifest.CompletionExpectation.Success =>
          success.assertResults(memory, config.byteCount)
        case _ =>
          fail("uniform_loop should be a success case")
      }

      memory.stop()
    }
  }

  test("shared_roundtrip kernel from the corpus completes and writes shared-memory results") {
    val kernel = KernelManifest.sharedRoundtrip

    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= kernel.entryPc
      dut.io.control.launch.gridDimX #= kernel.launch.gridDimX
      dut.io.control.launch.blockDimX #= kernel.launch.blockDimX
      dut.io.control.launch.argBase #= kernel.launch.argBase
      dut.io.control.launch.sharedBytes #= kernel.launch.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      ExecutionTestUtils.loadBinaryFile(memory, kernel.entryPc, kernel.binaryPath, config.byteCount)
      kernel.preload(memory, config.byteCount)

      pulseStart(dut)

      var cycles = 0
      while (!dut.io.control.status.done.toBoolean && cycles < 5000) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      assert(dut.io.control.status.done.toBoolean, s"shared_roundtrip did not complete after $cycles cycles")
      dut.io.control.status.fault.toBoolean shouldBe false

      kernel.expectation match {
        case success: KernelManifest.CompletionExpectation.Success =>
          success.assertResults(memory, config.byteCount)
        case _ =>
          fail("shared_roundtrip should be a success case")
      }

      memory.stop()
    }
  }

  test("vector_add_1warp kernel from the corpus completes and writes vector sums") {
    val kernel = KernelManifest.vectorAdd1Warp

    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= kernel.entryPc
      dut.io.control.launch.gridDimX #= kernel.launch.gridDimX
      dut.io.control.launch.blockDimX #= kernel.launch.blockDimX
      dut.io.control.launch.argBase #= kernel.launch.argBase
      dut.io.control.launch.sharedBytes #= kernel.launch.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      ExecutionTestUtils.loadBinaryFile(memory, kernel.entryPc, kernel.binaryPath, config.byteCount)
      kernel.preload(memory, config.byteCount)

      pulseStart(dut)

      var cycles = 0
      while (!dut.io.control.status.done.toBoolean && cycles < 10000) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      assert(dut.io.control.status.done.toBoolean, s"vector_add_1warp did not complete after $cycles cycles")
      dut.io.control.status.fault.toBoolean shouldBe false

      kernel.expectation match {
        case success: KernelManifest.CompletionExpectation.Success =>
          success.assertResults(memory, config.byteCount)
        case _ =>
          fail("vector_add_1warp should be a success case")
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

  test("misaligned_store kernel from the corpus traps and latches fault status") {
    val kernel = KernelManifest.misalignedStore

    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= kernel.entryPc
      dut.io.control.launch.gridDimX #= kernel.launch.gridDimX
      dut.io.control.launch.blockDimX #= kernel.launch.blockDimX
      dut.io.control.launch.argBase #= kernel.launch.argBase
      dut.io.control.launch.sharedBytes #= kernel.launch.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      ExecutionTestUtils.loadBinaryFile(memory, kernel.entryPc, kernel.binaryPath, config.byteCount)
      kernel.preload(memory, config.byteCount)

      pulseStart(dut)

      dut.clockDomain.waitSamplingWhere(dut.io.control.status.done.toBoolean)
      dut.io.control.status.fault.toBoolean shouldBe true
      dut.io.control.status.faultCode.toBigInt shouldBe FaultCode.MisalignedLoadStore

      memory.stop()
    }
  }

  test("non-uniform branch kernel from the corpus traps and latches fault status") {
    val kernel = KernelManifest.nonUniformBranch

    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= kernel.entryPc
      dut.io.control.launch.gridDimX #= kernel.launch.gridDimX
      dut.io.control.launch.blockDimX #= kernel.launch.blockDimX
      dut.io.control.launch.argBase #= kernel.launch.argBase
      dut.io.control.launch.sharedBytes #= kernel.launch.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      ExecutionTestUtils.loadBinaryFile(memory, kernel.entryPc, kernel.binaryPath, config.byteCount)
      kernel.preload(memory, config.byteCount)

      pulseStart(dut)

      dut.clockDomain.waitSamplingWhere(dut.io.debug.trap.valid.toBoolean)
      dut.io.debug.trap.payload.faultCode.toBigInt shouldBe FaultCode.NonUniformBranch
      dut.clockDomain.waitSamplingWhere(dut.io.control.status.done.toBoolean)
      dut.io.control.status.fault.toBoolean shouldBe true

      kernel.expectation match {
        case fault: KernelManifest.CompletionExpectation.Fault =>
          dut.io.control.status.faultCode.toBigInt shouldBe fault.code
        case _ =>
          fail("non_uniform_branch should be a fault case")
      }

      memory.stop()
    }
  }

  test("trap kernel from the corpus traps and latches fault status") {
    val kernel = KernelManifest.trap

    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.control.start #= false
      dut.io.control.clearDone #= false
      dut.io.control.launch.entryPc #= kernel.entryPc
      dut.io.control.launch.gridDimX #= kernel.launch.gridDimX
      dut.io.control.launch.blockDimX #= kernel.launch.blockDimX
      dut.io.control.launch.argBase #= kernel.launch.argBase
      dut.io.control.launch.sharedBytes #= kernel.launch.sharedBytes
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      ExecutionTestUtils.loadBinaryFile(memory, kernel.entryPc, kernel.binaryPath, config.byteCount)
      kernel.preload(memory, config.byteCount)

      pulseStart(dut)

      dut.clockDomain.waitSamplingWhere(dut.io.control.status.done.toBoolean)
      dut.io.control.status.fault.toBoolean shouldBe true
      dut.io.control.status.faultCode.toBigInt shouldBe FaultCode.Trap

      memory.stop()
    }
  }
}
