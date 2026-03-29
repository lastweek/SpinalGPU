package spinalgpu

import scala.language.reflectiveCalls
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class GridDispatchControllerSpec extends AnyFunSuite with Matchers {
  private val singleSmConfig = GpuConfig.default.copy(cluster = GpuClusterConfig(smCount = 1))
  private val dualSmConfig = GpuConfig.default.copy(cluster = GpuClusterConfig(smCount = 2))

  private def smStartBit(dut: GridDispatchController, index: Int): Boolean =
    ((dut.io.smStart.toBigInt >> index) & 1) == 1

  private def initDefaults(dut: GridDispatchController, config: GpuConfig): Unit = {
    dut.io.start #= false
    dut.io.clearDone #= false
    dut.io.command.entryPc #= 0x100
    dut.io.command.gridDimX #= 1
    dut.io.command.gridDimY #= 1
    dut.io.command.gridDimZ #= 1
    dut.io.command.blockDimX #= 1
    dut.io.command.blockDimY #= 1
    dut.io.command.blockDimZ #= 1
    dut.io.command.argBase #= 0x200
    dut.io.command.sharedBytes #= 0
    dut.io.memoryFabricIdle #= true
    for (sm <- 0 until config.smCount) {
      dut.io.smExecutionStatus(sm).busy #= false
      dut.io.smExecutionStatus(sm).done #= false
      dut.io.smExecutionStatus(sm).fault #= false
      dut.io.smExecutionStatus(sm).faultPc #= 0
      dut.io.smExecutionStatus(sm).faultCode #= 0
    }
  }

  test("walks CTA coordinates in x-y-z order for a 3D grid") {
    SimConfig.withVerilator.compile(new GridDispatchController(singleSmConfig)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut, singleSmConfig)

      dut.io.command.gridDimX #= 2
      dut.io.command.gridDimY #= 2
      dut.io.command.gridDimZ #= 2
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      val seen = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt, BigInt)]
      for (_ <- 0 until 8) {
        while (!smStartBit(dut, 0)) {
          dut.clockDomain.waitSampling()
        }
        seen += ((
          dut.io.smCommand(0).ctaidX.toBigInt,
          dut.io.smCommand(0).ctaidY.toBigInt,
          dut.io.smCommand(0).ctaidZ.toBigInt
        ))

        dut.io.smExecutionStatus(0).busy #= true
        dut.clockDomain.waitSampling()
        dut.io.smExecutionStatus(0).busy #= false
        dut.io.smExecutionStatus(0).done #= true
        dut.clockDomain.waitSampling()
        dut.io.smExecutionStatus(0).done #= false
      }

      seen.toSeq shouldBe Seq(
        (0, 0, 0),
        (1, 0, 0),
        (0, 1, 0),
        (1, 1, 0),
        (0, 0, 1),
        (1, 0, 1),
        (0, 1, 1),
        (1, 1, 1)
      ).map { case (x, y, z) => (BigInt(x), BigInt(y), BigInt(z)) }
    }
  }

  test("round-robins CTA dispatch across SMs and backfills an idle SM") {
    SimConfig.withVerilator.compile(new GridDispatchController(dualSmConfig)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut, dualSmConfig)

      dut.io.command.gridDimX #= 4
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      while (!smStartBit(dut, 0)) {
        dut.clockDomain.waitSampling()
      }
      dut.io.smCommand(0).ctaidX.toBigInt shouldBe BigInt(0)
      dut.io.smExecutionStatus(0).busy #= true

      dut.clockDomain.waitSampling()
      smStartBit(dut, 1) shouldBe true
      dut.io.smCommand(1).ctaidX.toBigInt shouldBe BigInt(1)
      dut.io.smExecutionStatus(1).busy #= true

      dut.clockDomain.waitSampling()
      smStartBit(dut, 0) shouldBe false
      smStartBit(dut, 1) shouldBe false

      dut.io.smExecutionStatus(0).busy #= false
      dut.io.smExecutionStatus(0).done #= true
      dut.clockDomain.waitSampling()
      dut.io.smExecutionStatus(0).done #= false

      dut.clockDomain.waitSampling()
      smStartBit(dut, 0) shouldBe true
      dut.io.smCommand(0).ctaidX.toBigInt shouldBe BigInt(2)
    }
  }

  test("stops dispatching new CTAs after the first SM fault and reports a kernel-global fault") {
    SimConfig.withVerilator.compile(new GridDispatchController(dualSmConfig)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut, dualSmConfig)

      dut.io.command.gridDimX #= 4
      dut.io.start #= true
      dut.clockDomain.waitSampling()
      dut.io.start #= false

      while (!smStartBit(dut, 0)) {
        dut.clockDomain.waitSampling()
      }
      dut.io.smExecutionStatus(0).busy #= true

      dut.clockDomain.waitSampling()
      smStartBit(dut, 1) shouldBe true
      dut.io.smExecutionStatus(1).busy #= true

      dut.io.smExecutionStatus(1).busy #= false
      dut.io.smExecutionStatus(1).done #= true
      dut.io.smExecutionStatus(1).fault #= true
      dut.io.smExecutionStatus(1).faultPc #= 0x100
      dut.io.smExecutionStatus(1).faultCode #= FaultCode.Trap
      dut.clockDomain.waitSampling()
      dut.io.smExecutionStatus(1).done #= false
      dut.io.smExecutionStatus(1).fault #= false

      dut.io.smExecutionStatus(0).busy #= false
      dut.io.smExecutionStatus(0).done #= true
      dut.clockDomain.waitSampling()
      dut.io.smExecutionStatus(0).done #= false

      for (_ <- 0 until 4) {
        smStartBit(dut, 0) shouldBe false
        smStartBit(dut, 1) shouldBe false
        dut.clockDomain.waitSampling()
      }

      dut.io.executionStatus.done.toBoolean shouldBe true
      dut.io.executionStatus.fault.toBoolean shouldBe true
      dut.io.executionStatus.faultCode.toBigInt shouldBe BigInt(FaultCode.Trap)
      dut.io.executionStatus.faultPc.toBigInt shouldBe BigInt(0x100)
    }
  }
}
