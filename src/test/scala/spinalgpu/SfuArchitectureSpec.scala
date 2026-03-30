package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.SpinalConfig

class SfuArchitectureSpec extends AnyFunSuite with Matchers {
  test("SpecialFunctionUnit elaborates across default and reduced subwarp configs") {
    val configs = Seq(
      SmConfig.default,
      SmConfig(subSmCount = 1, residentWarpsPerSubSm = 1, subSmIssueWidth = 2, warpSize = 8, sharedMemoryBytes = 256, sfuLatency = 3)
    )

    configs.zipWithIndex.foreach { case (config, index) =>
      noException shouldBe thrownBy {
        SpinalConfig(targetDirectory = s"target/elaboration/sfu-$index").generateVerilog(new SpecialFunctionUnit(config))
      }
    }
  }
}
