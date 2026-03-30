package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.SpinalConfig

class Tcgen05ArchitectureSpec extends AnyFunSuite with Matchers {
  test("SmExecutionCore elaborates with the tcgen05 tensor-memory path across representative configs") {
    val configs = Seq(
      SmConfig.default,
      SmConfig(subSmCount = 1, residentWarpsPerSubSm = 2, subSmIssueWidth = 32, sharedMemoryBytes = 2048, tensorMemoryBytesPerWarp = 512)
    )

    configs.zipWithIndex.foreach { case (config, index) =>
      noException shouldBe thrownBy {
        SpinalConfig(targetDirectory = s"target/elaboration/tcgen05-$index").generateVerilog(new SmExecutionCore(config))
      }
    }
  }
}
