package spinalgpu

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.KernelCatalog
import spinalgpu.toolchain.KernelLevel

class KernelManifestSpec extends AnyFunSuite with Matchers {
  private def trimmedNonEmptyLines(path: Path): Seq[String] =
    Files.readAllLines(path).asScala.map(_.trim).filter(_.nonEmpty).toSeq

  test("kernel manifest references every .ptx file exactly once and generated binaries exist") {
    ExecutionTestUtils.ensureKernelCorpusBuilt()

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
          .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".ptx"))
          .map((path: Path) => path.normalize())
          .toSet
      } finally {
        stream.close()
      }

    manifestPaths shouldBe discoveredPaths
  }

  test("kernel catalog metadata is complete and matches manifest expectations") {
    KernelManifest.allCases.foreach { kernel =>
      kernel.description.trim should not be empty
      kernel.secondaryFeatures.distinct shouldBe kernel.secondaryFeatures
      kernel.secondaryFeatures should not contain kernel.primaryFeature

      kernel.expectation match {
        case _: KernelManifest.CompletionExpectation.Fault =>
          kernel.teachingLevel shouldBe KernelLevel.Fault
        case _: KernelManifest.CompletionExpectation.Success =>
          kernel.teachingLevel should not equal KernelLevel.Fault
      }
    }
  }

  test("PTX kernel corpus follows the teaching template") {
    KernelManifest.allCases.foreach { kernel =>
      val lines = trimmedNonEmptyLines(kernel.sourcePath)

      lines.head should startWith("// Purpose:")
      lines(1) should startWith("// Primary feature:")
      lines(2) should startWith("// Expected outcome:")

      val versionIndex = lines.indexOf(".version 8.0")
      val targetIndex = lines.indexOf(".target spinalgpu")
      val addressIndex = lines.indexOf(".address_size 32")
      val entryIndex = lines.indexWhere(_.startsWith(".visible .entry "))
      val bodyIndex = lines.indexOf("{")
      val setupIndex = lines.indexOf("// Setup")
      val coreIndex = lines.indexOf("// Core")
      val exitIndex = lines.indexOf("// Exit")
      val faultIndex = lines.indexOf("// Fault trigger")

      versionIndex should be >= 0
      targetIndex should be > versionIndex
      addressIndex should be > targetIndex
      entryIndex should be > addressIndex
      bodyIndex should be > entryIndex
      setupIndex should be > bodyIndex
      coreIndex should be > setupIndex

      val regIndex = lines.indexWhere(_.startsWith(".reg "))
      val predIndex = lines.indexWhere(_.startsWith(".pred "))
      val sharedIndex = lines.indexWhere(_.startsWith(".shared "))

      if (regIndex >= 0 && predIndex >= 0) {
        regIndex should be < predIndex
      }
      if (regIndex >= 0 && sharedIndex >= 0) {
        regIndex should be < sharedIndex
      }
      if (predIndex >= 0 && sharedIndex >= 0) {
        predIndex should be < sharedIndex
      }

      kernel.expectation match {
        case _: KernelManifest.CompletionExpectation.Success =>
          exitIndex should be > coreIndex
          faultIndex shouldBe -1
        case _: KernelManifest.CompletionExpectation.Fault =>
          faultIndex should be > coreIndex
          exitIndex shouldBe -1
      }
    }
  }
}
