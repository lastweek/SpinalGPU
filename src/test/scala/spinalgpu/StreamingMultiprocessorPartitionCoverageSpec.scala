package spinalgpu

import scala.collection.mutable
import org.scalatest.Assertions.fail
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinalgpu.toolchain.KernelCorpus
import spinalgpu.toolchain.KernelCorpus.{KernelExpectation, SuccessCheck}

abstract class StreamingMultiprocessorKernelIntegrationSpec extends StreamingMultiprocessorSingleSimSpec {
  protected def configureKernelCommand(dut: StreamingMultiprocessor, kernel: KernelCorpus.KernelCase): Unit = {
    dut.io.command.start #= false
    dut.io.command.clearDone #= false
    dut.io.command.command.entryPc #= kernel.command.entryPc
    dut.io.command.command.gridDimX #= kernel.command.gridDimX
    dut.io.command.command.gridDimY #= kernel.command.gridDimY
    dut.io.command.command.gridDimZ #= kernel.command.gridDimZ
    dut.io.command.command.blockDimX #= kernel.command.blockDimX
    dut.io.command.command.blockDimY #= kernel.command.blockDimY
    dut.io.command.command.blockDimZ #= kernel.command.blockDimZ
    dut.io.command.command.argBase #= kernel.command.argBase
    dut.io.command.command.sharedBytes #= kernel.command.sharedBytes
  }

  protected def withLaunchedKernelCase(
      kernel: KernelCorpus.KernelCase,
      memoryConfig: AxiMemorySimConfig = AxiMemorySimConfig()
  )(body: (StreamingMultiprocessor, AxiMemorySim) => Unit): Unit = {
    println(s"[progress][sm-integration] ${kernel.name} integration start")
    KernelCorpusTestUtils.compiledStreamingMultiprocessor(config).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.assertReset()
      configureKernelCommand(dut, kernel)
      dut.clockDomain.waitSampling()
      dut.clockDomain.deassertReset()

      val memory = AxiMemorySim(dut.io.memory, dut.clockDomain, memoryConfig)
      memory.start()
      KernelCorpusTestUtils.loadKernelCase(memory, kernel, config.byteCount)

      pulseStart(dut)
      body(dut, memory)

      memory.stop()
    }
    println(s"[progress][sm-integration] ${kernel.name} integration done")
  }

  protected def assertKernelSuccess(memory: AxiMemorySim, kernel: KernelCorpus.KernelCase): Unit =
    kernel.expectation match {
      case KernelExpectation.Success(checks) =>
        checks.foreach(KernelCorpusTestUtils.assertSuccessCheck(memory, _, config.byteCount))
      case other =>
        fail(s"expected success expectation for ${kernel.name}, got $other")
    }

  protected def firstExpectedWordCheck(kernel: KernelCorpus.KernelCase): (Long, Seq[Long]) =
    kernel.expectation match {
      case KernelExpectation.Success(checks) =>
        checks.collectFirst { case SuccessCheck.ExpectWords(base, values) => (base, values) }.getOrElse {
          fail(s"${kernel.name} does not expose a word expectation")
        }
      case other =>
        fail(s"expected success expectation for ${kernel.name}, got $other")
    }
}

class StreamingMultiprocessorPartitionActivationSpec extends StreamingMultiprocessorKernelIntegrationSpec {
  test("thread_id_store_256 activates all sub-SMs and binds every resident warp") {
    val kernel = KernelCorpus.threadIdStore256

    withLaunchedKernelCase(
      kernel,
      memoryConfig = AxiMemorySimConfig(readResponseDelay = 8, writeResponseDelay = 8)
    ) { (dut, memory) =>
      val sawSubSmOccupied = Array.fill(config.sm.subSmCount)(false)
      val sawSubSmFull = Array.fill(config.sm.subSmCount)(false)
      val sawSubSmActive = Array.fill(config.sm.subSmCount)(false)
      val observedWarpIds = mutable.Set.empty[Int]

      var cycles = 0
      while (!dut.io.command.executionStatus.done.toBoolean && cycles < kernel.timeoutCycles) {
        for (subSm <- 0 until config.sm.subSmCount) {
          val occupiedBits = dut.io.debug.subSmSlotOccupied(subSm).toBigInt.toInt
          val occupiedCount = java.lang.Integer.bitCount(occupiedBits)

          if (occupiedCount > 0) {
            sawSubSmOccupied(subSm) = true
          }
          if (occupiedCount == config.sm.residentWarpsPerSubSm) {
            sawSubSmFull(subSm) = true
          }
          if (dut.io.debug.subSmEngineStates(subSm).toBigInt != 0) {
            sawSubSmActive(subSm) = true
          }

          for (slot <- 0 until config.sm.residentWarpsPerSubSm) {
            if (((occupiedBits >> slot) & 0x1) != 0) {
              observedWarpIds += dut.io.debug.subSmBoundWarpIds(subSm)(slot).toBigInt.toInt
            }
          }
        }

        dut.clockDomain.waitSampling()
        cycles += 1
      }

      assert(
        dut.io.command.executionStatus.done.toBoolean,
        s"${kernel.name} did not complete after $cycles cycles"
      )
      dut.io.command.executionStatus.fault.toBoolean shouldBe false
      sawSubSmOccupied.foreach(_ shouldBe true)
      sawSubSmFull.foreach(_ shouldBe true)
      sawSubSmActive.foreach(_ shouldBe true)
      observedWarpIds shouldBe (0 until config.sm.residentWarpCount).toSet
      assertKernelSuccess(memory, kernel)
    }
  }
}

class StreamingMultiprocessorStallIsolationProgressSpec extends StreamingMultiprocessorKernelIntegrationSpec {
  test("warpid_stall_isolation lets non-zero warps complete before warp 0 retires") {
    val kernel = KernelCorpus.warpidStallIsolation
    val (outputBase, expectedWords) = firstExpectedWordCheck(kernel)
    val samplePeriod = 8

    withLaunchedKernelCase(kernel) { (dut, memory) =>
      val warpZeroDoneExpected = BigInt(expectedWords(4))
      val otherWarpDoneExpected = expectedWords.slice(5, 8).map(BigInt(_))
      var sawOtherWarpDoneBeforeWarpZero = false

      var cycles = 0
      while (!dut.io.command.executionStatus.done.toBoolean && cycles < kernel.timeoutCycles) {
        val warpZeroDone = ExecutionTestUtils.readWord(memory, outputBase + (4L * config.byteCount), config.byteCount)
        val otherWarpDone = otherWarpDoneExpected.zipWithIndex.exists { case (expected, index) =>
          val address = outputBase + ((5L + index) * config.byteCount)
          ExecutionTestUtils.readWord(memory, address, config.byteCount) == expected
        }

        if (warpZeroDone != warpZeroDoneExpected && otherWarpDone) {
          sawOtherWarpDoneBeforeWarpZero = true
        }

        dut.clockDomain.waitSampling(samplePeriod)
        cycles += samplePeriod
      }

      assert(
        dut.io.command.executionStatus.done.toBoolean,
        s"${kernel.name} did not complete after $cycles cycles"
      )
      dut.io.command.executionStatus.fault.toBoolean shouldBe false
      sawOtherWarpDoneBeforeWarpZero shouldBe true
      assertKernelSuccess(memory, kernel)
    }
  }
}

class StreamingMultiprocessorDelayedDrainSpec extends StreamingMultiprocessorKernelIntegrationSpec {
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
