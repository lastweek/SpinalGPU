package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinalgpu.toolchain.KernelCorpus

// Low-level launch, fetch, and trap behavior stays explicit here.
// Corpus-backed PTX execution cases are generated below through the shared declarative runner.
class StreamingMultiprocessorSimSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig.default

  private def pulseStart(dut: StreamingMultiprocessor): Unit = {
    dut.io.command.start #= true
    dut.clockDomain.waitSampling()
    dut.io.command.start #= false
  }

  test("SM admission controller initializes warp contexts and schedules multiple warps") {
    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.command.start #= false
      dut.io.command.clearDone #= false
      dut.io.command.command.entryPc #= 0x100
      dut.io.command.command.gridDimX #= 1
      dut.io.command.command.blockDimX #= 40
      dut.io.command.command.argBase #= 0
      dut.io.command.command.sharedBytes #= 0
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

      dut.clockDomain.waitSamplingWhere(dut.io.command.executionStatus.done.toBoolean)
      dut.io.command.executionStatus.fault.toBoolean shouldBe false

      memory.stop()
    }
  }

  test("illegal opcode traps and latches fault status") {
    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.command.start #= false
      dut.io.command.clearDone #= false
      dut.io.command.command.entryPc #= 0x80
      dut.io.command.command.gridDimX #= 1
      dut.io.command.command.blockDimX #= 1
      dut.io.command.command.argBase #= 0
      dut.io.command.command.sharedBytes #= 0
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      memory.memory.writeBigInt(0x80L, ExecutionTestUtils.u32(0x7F000000), config.byteCount)

      pulseStart(dut)

      dut.clockDomain.waitSamplingWhere(dut.io.debug.trap.valid.toBoolean)
      dut.io.debug.trap.payload.faultCode.toBigInt shouldBe FaultCode.IllegalOpcode
      dut.clockDomain.waitSamplingWhere(dut.io.command.executionStatus.done.toBoolean)
      dut.io.command.executionStatus.fault.toBoolean shouldBe true
      dut.io.command.executionStatus.faultCode.toBigInt shouldBe FaultCode.IllegalOpcode

      memory.stop()
    }
  }

  test("misaligned fetch traps and latches fault status") {
    SimConfig.withVerilator.compile(new StreamingMultiprocessor(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.command.start #= false
      dut.io.command.clearDone #= false
      dut.io.command.command.entryPc #= 0x102
      dut.io.command.command.gridDimX #= 1
      dut.io.command.command.blockDimX #= 1
      dut.io.command.command.argBase #= 0
      dut.io.command.command.sharedBytes #= 0
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()

      pulseStart(dut)

      dut.clockDomain.waitSamplingWhere(dut.io.debug.trap.valid.toBoolean)
      dut.io.debug.trap.payload.faultCode.toBigInt shouldBe FaultCode.MisalignedFetch
      dut.clockDomain.waitSamplingWhere(dut.io.command.executionStatus.done.toBoolean)
      dut.io.command.executionStatus.fault.toBoolean shouldBe true
      dut.io.command.executionStatus.faultCode.toBigInt shouldBe FaultCode.MisalignedFetch

      memory.stop()
    }
  }

  KernelCorpus.streamingMultiprocessorCases.foreach { kernel =>
    test(s"kernel corpus case '${kernel.name}' executes from ${kernel.relativeSourcePath}") {
      KernelCorpusTestUtils.runStreamingMultiprocessorKernelCase(kernel, config)
    }
  }
}
