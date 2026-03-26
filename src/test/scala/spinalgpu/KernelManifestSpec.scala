package spinalgpu

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.KernelCatalog

class KernelManifestSpec extends AnyFunSuite with Matchers {
  test("kernel manifest references every .gpuasm file exactly once and generated binaries exist") {
    val manifestPaths = KernelManifest.allCases.map(_.sourcePath.normalize()).toSet
    manifestPaths.foreach(path => Files.exists(path) shouldBe true)
    KernelManifest.allCases.foreach { kernel =>
      Files.exists(kernel.binaryPath) shouldBe true
      kernel.binaryPath.startsWith(KernelCatalog.outputRoot) shouldBe true
    }

    val stream = Files.walk(KernelCatalog.sourceRoot)
    val discoveredPaths =
      try {
        stream.iterator().asScala
          .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".gpuasm"))
          .map((path: Path) => path.normalize())
          .toSet
      } finally {
        stream.close()
      }

    manifestPaths shouldBe discoveredPaths
  }
}
