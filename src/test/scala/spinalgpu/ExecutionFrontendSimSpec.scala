package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.sim._

class ExecutionFrontendSimSpec extends AnyFunSuite with Matchers {
  private val compiled = SimConfig.withVerilator.compile(new GpuTop)
  private val config = SmConfig.default

  private def withGpu(testBody: (GpuTop, AxiMemorySim, AxiLite4Driver) => Unit): Unit = {
    compiled.doSim { dut =>
      dut.coreClockDomain.forkStimulus(period = 10)
      dut.coreReset #= true
      dut.coreClockDomain.waitSampling()
      dut.coreReset #= false

      val memory = AxiMemorySim(dut.io.memory, dut.coreClockDomain, AxiMemorySimConfig(readResponseDelay = 0, writeResponseDelay = 0))
      val control = AxiLite4Driver(dut.io.control, dut.coreClockDomain)
      memory.start()
      control.reset()

      testBody(dut, memory, control)

      memory.stop()
    }
  }

  test("valid and invalid AXI-Lite launches update status correctly") {
    withGpu { (dut, memory, control) =>
      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x00, blockDimX = 0, argBase = 0))
      val invalidStatus = ExecutionTestUtils.waitForDone(control, dut.coreClockDomain)
      ((invalidStatus >> 2) & 1) shouldBe BigInt(1)
      ExecutionTestUtils.readFaultCode(control) shouldBe BigInt(FaultCode.InvalidLaunch)

      ExecutionTestUtils.clearDone(control)
      dut.coreClockDomain.waitSampling(2)
      ExecutionTestUtils.readStatus(control) shouldBe BigInt(0)

      val longProgram =
        """movi r1, 64
          |loop:
          |addi r1, r1, -1
          |brnz r1, loop
          |s2r r2, %argbase
          |ldg r3, [r2 + 0]
          |movi r4, 99
          |stg [r3 + 0], r4
          |exit
          |""".stripMargin

      ExecutionTestUtils.loadProgram(memory, 0x100, longProgram, config.byteCount)
      ExecutionTestUtils.writeArgBuffer(memory, 0x200, Seq(0x300), config.byteCount)
      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 1, argBase = 0x200))
      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 999, argBase = 0x200))

      val validStatus = ExecutionTestUtils.waitForDone(control, dut.coreClockDomain)
      ((validStatus >> 2) & 1) shouldBe BigInt(0)
      ExecutionTestUtils.readWord(memory, 0x300, config.byteCount) shouldBe BigInt(99)
    }
  }

  test("add_store_exit writes a computed result back to global memory") {
    withGpu { (dut, memory, control) =>
      ExecutionTestUtils.loadProgram(
        memory,
        0x100,
        """s2r r1, %argbase
          |ldg r2, [r1 + 0]
          |movi r3, 7
          |movi r4, 11
          |add r5, r3, r4
          |stg [r2 + 0], r5
          |exit
          |""".stripMargin,
        config.byteCount
      )
      ExecutionTestUtils.writeArgBuffer(memory, 0x200, Seq(0x300), config.byteCount)

      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 1, argBase = 0x200))
      val status = ExecutionTestUtils.waitForDone(control, dut.coreClockDomain)
      ((status >> 2) & 1) shouldBe BigInt(0)
      ExecutionTestUtils.readWord(memory, 0x300, config.byteCount) shouldBe BigInt(18)
    }
  }

  test("thread_id_store and two_warp_launch write per-thread ids to global memory") {
    withGpu { (dut, memory, control) =>
      val program =
        """s2r r1, %argbase
          |ldg r2, [r1 + 0]
          |s2r r3, %tid.x
          |movi r4, 2
          |shl r5, r3, r4
          |add r6, r2, r5
          |stg [r6 + 0], r3
          |exit
          |""".stripMargin

      ExecutionTestUtils.loadProgram(memory, 0x100, program, config.byteCount)
      ExecutionTestUtils.writeArgBuffer(memory, 0x200, Seq(0x400), config.byteCount)

      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 40, argBase = 0x200))
      ExecutionTestUtils.waitForDone(control, dut.coreClockDomain)

      (0 until 40).foreach { index =>
        ExecutionTestUtils.readWord(memory, 0x400 + index * 4L, config.byteCount) shouldBe BigInt(index)
      }
    }
  }

  test("uniform_loop executes correctly and returns through memory") {
    withGpu { (dut, memory, control) =>
      ExecutionTestUtils.loadProgram(
        memory,
        0x100,
        """movi r1, 3
          |loop:
          |addi r1, r1, -1
          |brnz r1, loop
          |s2r r2, %argbase
          |ldg r3, [r2 + 0]
          |stg [r3 + 0], r1
          |exit
          |""".stripMargin,
        config.byteCount
      )
      ExecutionTestUtils.writeArgBuffer(memory, 0x200, Seq(0x300), config.byteCount)

      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 1, argBase = 0x200))
      ExecutionTestUtils.waitForDone(control, dut.coreClockDomain)
      ExecutionTestUtils.readWord(memory, 0x300, config.byteCount) shouldBe BigInt(0)
    }
  }

  test("shared_roundtrip and vector_add_1warp use shared and global memory correctly") {
    withGpu { (dut, memory, control) =>
      val sharedProgram =
        """s2r r1, %argbase
          |ldg r2, [r1 + 0]
          |s2r r3, %tid.x
          |movi r4, 2
          |shl r5, r3, r4
          |sts [r5 + 0], r3
          |lds r6, [r5 + 0]
          |add r7, r2, r5
          |stg [r7 + 0], r6
          |exit
          |""".stripMargin

      ExecutionTestUtils.loadProgram(memory, 0x100, sharedProgram, config.byteCount)
      ExecutionTestUtils.writeArgBuffer(memory, 0x200, Seq(0x400), config.byteCount)
      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 8, argBase = 0x200, sharedBytes = 256))
      ExecutionTestUtils.waitForDone(control, dut.coreClockDomain)
      (0 until 8).foreach { index =>
        ExecutionTestUtils.readWord(memory, 0x400 + index * 4L, config.byteCount) shouldBe BigInt(index)
      }

      ExecutionTestUtils.clearDone(control)
      dut.coreClockDomain.waitSampling(2)

      val vectorProgram =
        """s2r r1, %argbase
          |ldg r2, [r1 + 0]
          |ldg r3, [r1 + 4]
          |ldg r4, [r1 + 8]
          |s2r r5, %tid.x
          |movi r6, 2
          |shl r7, r5, r6
          |add r8, r2, r7
          |add r9, r3, r7
          |add r10, r4, r7
          |ldg r11, [r8 + 0]
          |ldg r12, [r9 + 0]
          |add r13, r11, r12
          |stg [r10 + 0], r13
          |exit
          |""".stripMargin

      ExecutionTestUtils.loadProgram(memory, 0x180, vectorProgram, config.byteCount)
      ExecutionTestUtils.writeDataWords(memory, 0x500, (0 until 8).toSeq, config.byteCount)
      ExecutionTestUtils.writeDataWords(memory, 0x600, (0 until 8).map(_ * 10), config.byteCount)
      ExecutionTestUtils.writeArgBuffer(memory, 0x240, Seq(0x500, 0x600, 0x700), config.byteCount)
      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x180, blockDimX = 8, argBase = 0x240))
      ExecutionTestUtils.waitForDone(control, dut.coreClockDomain)

      (0 until 8).foreach { index =>
        ExecutionTestUtils.readWord(memory, 0x700 + index * 4L, config.byteCount) shouldBe BigInt(index + (index * 10))
      }
    }
  }

  test("misaligned fetch faults cleanly") {
    withGpu { (dut, memory, control) =>
      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x102, blockDimX = 1))
      ExecutionTestUtils.waitForDone(control, dut.coreClockDomain)
      ExecutionTestUtils.readFaultCode(control) shouldBe BigInt(FaultCode.MisalignedFetch)
      ExecutionTestUtils.readFaultPc(control) shouldBe BigInt(0x102)
    }
  }

  test("misaligned store faults cleanly") {
    withGpu { (dut, memory, control) =>
      ExecutionTestUtils.loadProgram(
        memory,
        0x140,
        """s2r r1, %argbase
          |ldg r2, [r1 + 0]
          |movi r3, 1
          |stg [r2 + 2], r3
          |exit
          |""".stripMargin,
        config.byteCount
      )
      ExecutionTestUtils.writeArgBuffer(memory, 0x280, Seq(0x300), config.byteCount)
      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x140, blockDimX = 1, argBase = 0x280))
      ExecutionTestUtils.waitForDone(control, dut.coreClockDomain)
      ExecutionTestUtils.readFaultCode(control) shouldBe BigInt(FaultCode.MisalignedLoadStore)
    }
  }

  test("trap instruction faults cleanly") {
    withGpu { (dut, memory, control) =>
      ExecutionTestUtils.loadProgram(memory, 0x160, "trap", config.byteCount)
      ExecutionTestUtils.launchKernel(control, ExecutionTestUtils.HostLaunch(entryPc = 0x160, blockDimX = 1))
      ExecutionTestUtils.waitForDone(control, dut.coreClockDomain)
      ExecutionTestUtils.readFaultCode(control) shouldBe BigInt(FaultCode.Trap)
    }
  }
}
