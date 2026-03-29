package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinalgpu.toolchain.KernelCorpus

abstract class StreamingMultiprocessorSingleSimSpec extends AnyFunSuite with Matchers {
  protected val config: GpuConfig = GpuConfig.default

  protected def withStreamingMultiprocessorSimulation(label: String)(body: StreamingMultiprocessor => Unit): Unit = {
    println(s"[progress][sm-integration] $label start")
    KernelCorpusTestUtils.compiledStreamingMultiprocessor(config).doSim { dut =>
      body(dut)
    }
    println(s"[progress][sm-integration] $label done")
  }

  protected def pulseStart(dut: StreamingMultiprocessor): Unit = {
    dut.io.command.start #= true
    dut.clockDomain.waitSampling()
    dut.io.command.start #= false
  }

  protected def waitUntil(dut: StreamingMultiprocessor, timeoutCycles: Int, label: String)(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < timeoutCycles) {
      dut.clockDomain.waitSampling()
      cycles += 1
    }

    assert(
      condition,
      s"$label did not occur after $cycles cycles; busy=${dut.io.command.executionStatus.busy.toBoolean} " +
        s"done=${dut.io.command.executionStatus.done.toBoolean} fault=${dut.io.command.executionStatus.fault.toBoolean} " +
        s"faultCode=${dut.io.command.executionStatus.faultCode.toBigInt} engineState=${dut.io.debug.engineState.toBigInt} " +
        s"selectedWarp=${dut.io.debug.selectedWarpId.toBigInt} selectedPc=0x${dut.io.debug.selectedPc.toBigInt.toString(16)} " +
        s"grid=(${dut.io.command.command.gridDimX.toBigInt},${dut.io.command.command.gridDimY.toBigInt},${dut.io.command.command.gridDimZ.toBigInt}) " +
        s"block=(${dut.io.command.command.blockDimX.toBigInt},${dut.io.command.command.blockDimY.toBigInt},${dut.io.command.command.blockDimZ.toBigInt}) " +
        s"requestedBlockThreads=${dut.io.debug.launchRequestedBlockThreads.toBigInt} " +
        s"invalidGrid=${dut.io.debug.launchInvalidGridDim.toBoolean} invalidZero=${dut.io.debug.launchInvalidBlockDimZero.toBoolean} " +
        s"invalidThreadCount=${dut.io.debug.launchInvalidBlockThreadCount.toBoolean} invalidShared=${dut.io.debug.launchInvalidSharedBytes.toBoolean}"
    )
  }
}

class StreamingMultiprocessorWrapperSpec extends StreamingMultiprocessorKernelIntegrationSpec {
  test("SM admission controller initializes warp contexts and schedules multiple warps") {
    withStreamingMultiprocessorSimulation("admission") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.command.start #= false
      dut.io.command.clearDone #= false
      dut.io.command.command.entryPc #= 0x100
      dut.io.command.command.gridDimX #= 1
      dut.io.command.command.gridDimY #= 1
      dut.io.command.command.gridDimZ #= 1
      dut.io.command.command.blockDimX #= 40
      dut.io.command.command.blockDimY #= 1
      dut.io.command.command.blockDimZ #= 1
      dut.io.command.command.argBase #= 0
      dut.io.command.command.sharedBytes #= 0
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      memory.memory.writeBigInt(0x100L, ExecutionTestUtils.u32(Isa.encodeBr(Opcode.EXIT, 0, 0)), config.byteCount)

      pulseStart(dut)

      waitUntil(dut, timeoutCycles = 5000, "first warp schedule") {
        dut.io.debug.scheduledWarp.valid.toBoolean
      }
      dut.io.debug.scheduledWarp.payload.warpId.toBigInt shouldBe 0
      dut.io.debug.scheduledWarp.payload.context.pc.toBigInt shouldBe 0x100
      dut.io.debug.scheduledWarp.payload.context.threadCount.toBigInt shouldBe 32

      waitUntil(dut, timeoutCycles = 5000, "fetch response") {
        dut.io.debug.fetchResponse.valid.toBoolean
      }
      dut.io.debug.fetchResponse.payload.instruction.toBigInt shouldBe ExecutionTestUtils.u32(Isa.encodeBr(Opcode.EXIT, 0, 0))

      waitUntil(dut, timeoutCycles = 5000, "second warp schedule") {
        dut.io.debug.scheduledWarp.valid.toBoolean && dut.io.debug.scheduledWarp.payload.warpId.toBigInt == 1
      }
      dut.io.debug.scheduledWarp.payload.context.threadBase.toBigInt shouldBe 32
      dut.io.debug.scheduledWarp.payload.context.threadCount.toBigInt shouldBe 8

      waitUntil(dut, timeoutCycles = 5000, "kernel completion") {
        dut.io.command.executionStatus.done.toBoolean
      }
      dut.io.command.executionStatus.fault.toBoolean shouldBe false

      memory.stop()
    }
  }

  test("illegal opcode traps and latches fault status") {
    withStreamingMultiprocessorSimulation("illegal-opcode") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.command.start #= false
      dut.io.command.clearDone #= false
      dut.io.command.command.entryPc #= 0x80
      dut.io.command.command.gridDimX #= 1
      dut.io.command.command.gridDimY #= 1
      dut.io.command.command.gridDimZ #= 1
      dut.io.command.command.blockDimX #= 1
      dut.io.command.command.blockDimY #= 1
      dut.io.command.command.blockDimZ #= 1
      dut.io.command.command.argBase #= 0
      dut.io.command.command.sharedBytes #= 0
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()
      memory.memory.writeBigInt(0x80L, ExecutionTestUtils.u32(0x7F000000), config.byteCount)

      pulseStart(dut)

      waitUntil(dut, timeoutCycles = 2000, "illegal opcode trap") {
        dut.io.debug.trap.valid.toBoolean
      }
      dut.io.debug.trap.payload.faultCode.toBigInt shouldBe FaultCode.IllegalOpcode
      waitUntil(dut, timeoutCycles = 2000, "illegal opcode completion") {
        dut.io.command.executionStatus.done.toBoolean
      }
      dut.io.command.executionStatus.fault.toBoolean shouldBe true
      dut.io.command.executionStatus.faultCode.toBigInt shouldBe FaultCode.IllegalOpcode

      memory.stop()
    }
  }

  test("misaligned fetch traps and latches fault status") {
    withStreamingMultiprocessorSimulation("misaligned-fetch") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      dut.io.command.start #= false
      dut.io.command.clearDone #= false
      dut.io.command.command.entryPc #= 0x102
      dut.io.command.command.gridDimX #= 1
      dut.io.command.command.gridDimY #= 1
      dut.io.command.command.gridDimZ #= 1
      dut.io.command.command.blockDimX #= 1
      dut.io.command.command.blockDimY #= 1
      dut.io.command.command.blockDimZ #= 1
      dut.io.command.command.argBase #= 0
      dut.io.command.command.sharedBytes #= 0
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, AxiMemorySimConfig())
      memory.start()

      pulseStart(dut)

      waitUntil(dut, timeoutCycles = 2000, "misaligned fetch trap") {
        dut.io.debug.trap.valid.toBoolean
      }
      dut.io.debug.trap.payload.faultCode.toBigInt shouldBe FaultCode.MisalignedFetch
      waitUntil(dut, timeoutCycles = 2000, "misaligned fetch completion") {
        dut.io.command.executionStatus.done.toBoolean
      }
      dut.io.command.executionStatus.fault.toBoolean shouldBe true
      dut.io.command.executionStatus.faultCode.toBigInt shouldBe FaultCode.MisalignedFetch

      memory.stop()
    }
  }

  test("add_store_exit waits for the delayed external store response before asserting done") {
    val kernel = KernelCorpus.addStoreExit

    withLaunchedKernelCase(
      kernel,
      memoryConfig = AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 32)
    ) { (dut, memory) =>
      var lsuExternalRequestCount = 0
      var cycles = 0

      while (lsuExternalRequestCount < 2 && cycles < kernel.timeoutCycles) {
        if (dut.io.debug.lsuExternalReqValid.toBoolean && dut.io.debug.lsuExternalReqReady.toBoolean) {
          lsuExternalRequestCount += 1
        }
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      assert(
        lsuExternalRequestCount >= 2,
        s"${kernel.name} never issued the delayed external store after $cycles cycles"
      )

      for (_ <- 0 until 31) {
        dut.io.command.executionStatus.done.toBoolean shouldBe false
        dut.clockDomain.waitSampling()
      }

      waitUntil(dut, timeoutCycles = kernel.timeoutCycles, label = s"${kernel.name} completion after delayed drain") {
        dut.io.command.executionStatus.done.toBoolean
      }
      dut.io.command.executionStatus.fault.toBoolean shouldBe false
      assertKernelSuccess(memory, kernel)
    }
  }
}
