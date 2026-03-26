package spinalgpu

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.KernelCorpus
import spinalgpu.toolchain.KernelCorpus.{KernelExpectation, KernelLevel}

class KernelCorpusSpec extends AnyFunSuite with Matchers {
  private def trimmedNonEmptyLines(path: Path): Seq[String] =
    Files.readAllLines(path).asScala.map(_.trim).filter(_.nonEmpty).toSeq

  test("kernel corpus references every .ptx file exactly once and generated binaries exist") {
    ExecutionTestUtils.ensureKernelCorpusBuilt()

    val corpusPaths = KernelCorpus.all.map(_.sourcePath.normalize()).toSet
    corpusPaths.foreach(path => Files.exists(path) shouldBe true)
    KernelCorpus.all.foreach { kernel =>
      Files.exists(kernel.binaryPath) shouldBe true
      kernel.binaryPath.startsWith(KernelCorpus.outputRoot) shouldBe true
    }

    val stream = Files.walk(KernelCorpus.sourceRoot)
    val discoveredPaths =
      try {
        stream.iterator().asScala
          .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".ptx"))
          .map((path: Path) => path.normalize())
          .toSet
      } finally {
        stream.close()
      }

    corpusPaths shouldBe discoveredPaths
  }

  test("kernel corpus metadata is complete and expectation types match teaching levels") {
    KernelCorpus.all.foreach { kernel =>
      kernel.purpose.trim should not be empty
      kernel.secondaryFeatures.distinct shouldBe kernel.secondaryFeatures
      kernel.secondaryFeatures should not contain kernel.primaryFeature
      kernel.harnessTargets should not be empty

      kernel.expectation match {
        case _: KernelExpectation.Fault =>
          kernel.teachingLevel shouldBe KernelLevel.Fault
        case _: KernelExpectation.Success =>
          kernel.teachingLevel should not equal KernelLevel.Fault
      }
    }
  }

  test("kernel corpus PTX headers match declarative metadata and follow the teaching template") {
    KernelCorpus.all.foreach { kernel =>
      val lines = trimmedNonEmptyLines(kernel.sourcePath)

      lines.head shouldBe s"// Purpose: ${kernel.purpose}"
      lines(1) shouldBe s"// Primary feature: ${kernel.primaryFeature.id}"
      lines(2) shouldBe s"// Expected outcome: ${kernel.expectedOutcomeId}"

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
        case _: KernelExpectation.Success =>
          exitIndex should be > coreIndex
          faultIndex shouldBe -1
        case _: KernelExpectation.Fault =>
          faultIndex should be > coreIndex
          exitIndex shouldBe -1
      }
    }
  }

  test("kernel corpus exposes at least one case for each enabled harness") {
    KernelCorpus.streamingMultiprocessorCases should not be empty
    KernelCorpus.gpuTopCases should not be empty
  }
}
