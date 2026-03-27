package spinalgpu

import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core._

class ArchitectureSkeletonSpec extends AnyFunSuite with Matchers {
  test("GpuTop elaborates across three SM configurations") {
    val configs = Seq(
      SmConfig.default,
      SmConfig(subSmCount = 1, residentWarpsPerSubSm = 1, subSmIssueWidth = 32, sharedMemoryBytes = 2048),
      SmConfig(subSmCount = 2, residentWarpsPerSubSm = 2, subSmIssueWidth = 32, sharedMemoryBytes = 8192)
    )

    configs.zipWithIndex.foreach { case (config, index) =>
      val targetDir = s"target/elaboration/architecture-$index"
      noException shouldBe thrownBy {
        SpinalConfig(targetDirectory = targetDir).generateVerilog(new GpuTop(config))
      }
    }
  }

  test("architecture docs, diagrams, and README links exist") {
    val requiredPaths = Seq(
      Path.of("AGENTS.md"),
      Path.of("docs/architecture.md"),
      Path.of("docs/isa.md"),
      Path.of("docs/machine-encoding.md"),
      Path.of("kernels"),
      Path.of("docs/diagrams/sm-overview.mmd"),
      Path.of("docs/diagrams/dispatch-dataflow.mmd"),
      Path.of("docs/diagrams/memory-hierarchy-axi.mmd"),
      Path.of("docs/diagrams/frontend-execution.mmd")
    )

    requiredPaths.foreach(path => Files.exists(path) shouldBe true)

    val readme = Files.readString(Path.of("README.md"))
    readme should include("AGENTS.md")
    readme should include("docs/architecture.md")
    readme should include("docs/isa.md")
    readme should include("docs/machine-encoding.md")
    readme should include("docs/diagrams/sm-overview.mmd")
    readme should include("docs/diagrams/dispatch-dataflow.mmd")
    readme should include("docs/diagrams/memory-hierarchy-axi.mmd")
    readme should include("docs/diagrams/frontend-execution.mmd")
    readme should include("kernels/")
    readme should include("build-kernels.sh")
    readme should include("generated/kernels/")
  }
}
