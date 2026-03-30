package spinalgpu

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GemmBenchmarkReporterSpec extends AnyFunSuite with Matchers {
  test("benchmark reporter emits markdown, csv, and svg with the expected labels") {
    val rows = Seq(
      GemmBenchmarkSupport.ComparisonRow(GemmBenchmarkSupport.GemmBenchmarkShape(16, 16, 16), cudaCycles = 1200, tcgen05Cycles = 320),
      GemmBenchmarkSupport.ComparisonRow(GemmBenchmarkSupport.GemmBenchmarkShape(32, 32, 32), cudaCycles = 8800, tcgen05Cycles = 1450)
    )

    val outputDir = Files.createTempDirectory("spinalgpu-gemm-benchmark-reporter")
    val artifacts = GemmBenchmarkSupport.writeArtifacts(rows, outputDir)

    val markdown = Files.readString(artifacts.markdown)
    markdown should include("Harness: `StreamingMultiprocessor`")
    markdown should include("Config: single-SM benchmark config, subSmCount=1, residentWarpsPerSubSm=4")
    markdown should include("| M | N | K | CUDA cycles | tcgen05 cycles | CUDA/tcgen05 speedup |")
    markdown should include("| 16 | 16 | 16 | 1200 | 320 | 3.75x |")

    val csv = Files.readString(artifacts.csv)
    csv.linesIterator.toSeq should have size 3
    csv should include("m,n,k,cuda_cycles,tcgen05_cycles,cuda_over_tcgen05_speedup")

    val svg = Files.readString(artifacts.svg)
    svg should include("CUDA core")
    svg should include("tcgen05")
  }
}
