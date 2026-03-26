package spinalgpu

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.Assembler
import spinalgpu.toolchain.BuildKernelCorpus
import spinalgpu.toolchain.KernelBinaryIO

class AssemblerSpec extends AnyFunSuite with Matchers {
  test("assembler and disassembler round-trip representative instructions") {
    val cases = Seq(
      "add r3, r1, r2",
      "addi r4, r5, -7",
      "ldg r6, [r7 + 12]",
      "stg [r7 + 4], r6",
      "brnz r8, -4",
      "s2r r9, %tid.x",
      "exit"
    )

    cases.foreach { source =>
      val assembled = Assembler.assemble(source).words.head
      val disassembled = Isa.disassemble(assembled)
      Assembler.assemble(disassembled).words.head shouldBe assembled
    }
  }

  test("assembler resolves labels relative to pc plus four") {
    val program = Assembler.assemble(
      """movi r1, 2
        |loop:
        |addi r1, r1, -1
        |brnz r1, loop
        |exit
        |""".stripMargin
    )

    program.labels("loop") shouldBe 4
    Isa.decodeWord(program.words(2)).immediate shouldBe -8
  }

  test("assembler rejects malformed sources") {
    an[IllegalArgumentException] shouldBe thrownBy {
      Assembler.assemble("bogus r1, r2, r3")
    }
    an[IllegalArgumentException] shouldBe thrownBy {
      Assembler.assemble("bra missing_label")
    }
    an[IllegalArgumentException] shouldBe thrownBy {
      Assembler.assemble("movi r1, 100000")
    }
    an[IllegalArgumentException] shouldBe thrownBy {
      Assembler.assemble("add r32, r1, r2")
    }
  }

  test("assembler produces the expected raw words for a known kernel snippet") {
    val program = Assembler.assemble(
      """movi r1, 7
        |movi r2, 11
        |add r3, r1, r2
        |exit
        |""".stripMargin
    )

    program.words shouldBe Seq(
      Isa.encodeRri(Opcode.MOVI, rd = 1, rs0 = 0, immediate = 7),
      Isa.encodeRri(Opcode.MOVI, rd = 2, rs0 = 0, immediate = 11),
      Isa.encodeRrr(Opcode.ADD, rd = 3, rs0 = 1, rs1 = 2),
      Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0)
    )
  }

  test("kernel corpus builder emits raw little-endian binaries") {
    val outputRoot = Files.createTempDirectory("kernel-corpus")

    val outputPaths =
      try {
        BuildKernelCorpus.buildAll(outputRoot)
      } finally {
        ()
      }

    outputPaths should not be empty
    outputPaths.foreach(path => Files.exists(path) shouldBe true)

    val firstWords = KernelBinaryIO.readWords(outputPaths.head)
    firstWords should not be empty
  }
}
