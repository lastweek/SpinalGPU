package spinalgpu.toolchain

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable
import spinalgpu._

final case class AssembledProgram(words: Seq[Int], labels: Map[String, Int])

object Assembler {
  def assemble(source: String): AssembledProgram = {
    val lines = source.linesIterator.toSeq.map(_.takeWhile(_ != ';').trim).filter(_.nonEmpty)
    val labels = mutable.LinkedHashMap.empty[String, Int]
    val instructions = mutable.ArrayBuffer.empty[(Int, String)]
    var pc = 0

    lines.foreach { line =>
      if (line.endsWith(":")) {
        labels += line.dropRight(1) -> pc
      } else {
        instructions += pc -> line
        pc += Isa.instructionBytes
      }
    }

    val frozenLabels = labels.toMap
    val words = instructions.map { case (address, line) =>
      assembleLine(address, line, frozenLabels)
    }.toSeq

    AssembledProgram(words, frozenLabels)
  }

  def assembleFile(path: Path): AssembledProgram = {
    try {
      assemble(Files.readString(path))
    } catch {
      case error: IllegalArgumentException =>
        throw new IllegalArgumentException(s"${path.toString}: ${error.getMessage}", error)
    }
  }

  private def assembleLine(address: Int, line: String, labels: Map[String, Int]): Int = {
    val lower = line.toLowerCase

    def parseRegister(token: String): Int = {
      require(token.startsWith("r"), s"expected register, got: $token")
      token.drop(1).toInt
    }

    def parseImmediate(token: String): Int = {
      val trimmed = token.trim
      labels.get(trimmed) match {
        case Some(labelAddress) => labelAddress - (address + Isa.instructionBytes)
        case None if trimmed.startsWith("0x") || trimmed.startsWith("-0x") =>
          Integer.decode(trimmed)
        case None => trimmed.toInt
      }
    }

    lower match {
      case "nop" => Isa.encodeBr(Opcode.NOP, 0, 0)
      case "exit" => Isa.encodeBr(Opcode.EXIT, 0, 0)
      case "trap" => Isa.encodeBr(Opcode.TRAP, 0, 0)
      case _ =>
        lower.split("\\s+", 2).toList match {
          case mnemonic :: rest :: Nil =>
            mnemonic match {
              case "mov" =>
                val Seq(rd, rs0) = rest.split(",").map(_.trim).toSeq
                Isa.encodeRrr(Opcode.MOV, parseRegister(rd), parseRegister(rs0), 0)
              case "movi" =>
                val Seq(rd, imm) = rest.split(",").map(_.trim).toSeq
                Isa.encodeRri(Opcode.MOVI, parseRegister(rd), 0, parseImmediate(imm))
              case "s2r" =>
                val Seq(rd, special) = rest.split(",").map(_.trim).toSeq
                Isa.encodeSys(Opcode.S2R, parseRegister(rd), SpecialRegisterKind.byName(special))
              case "add" | "sub" | "mullo" | "and" | "or" | "xor" | "shl" | "shr" | "seteq" | "setlt" =>
                val Seq(rd, rs0, rs1) = rest.split(",").map(_.trim).toSeq
                Isa.encodeRrr(Opcode.byName(mnemonic), parseRegister(rd), parseRegister(rs0), parseRegister(rs1))
              case "addi" =>
                val Seq(rd, rs0, imm) = rest.split(",").map(_.trim).toSeq
                Isa.encodeRri(Opcode.ADDI, parseRegister(rd), parseRegister(rs0), parseImmediate(imm))
              case "ldg" | "lds" =>
                val Seq(rd, mem) = rest.split(",").map(_.trim).toSeq
                val memMatch = "\\[(r\\d+)\\s*\\+\\s*([^\\]]+)\\]".r
                val memMatch(base, off) = mem
                Isa.encodeMem(Opcode.byName(mnemonic), parseRegister(rd), parseRegister(base), parseImmediate(off))
              case "stg" | "sts" =>
                val Seq(mem, rs) = rest.split(",").map(_.trim).toSeq
                val memMatch = "\\[(r\\d+)\\s*\\+\\s*([^\\]]+)\\]".r
                val memMatch(base, off) = mem
                Isa.encodeMem(Opcode.byName(mnemonic), parseRegister(rs), parseRegister(base), parseImmediate(off))
              case "bra" =>
                Isa.encodeBr(Opcode.BRA, 0, parseImmediate(rest.trim))
              case "brz" | "brnz" =>
                val Seq(rs0, target) = rest.split(",").map(_.trim).toSeq
                Isa.encodeBr(Opcode.byName(mnemonic), parseRegister(rs0), parseImmediate(target))
              case _ =>
                throw new IllegalArgumentException(s"unsupported mnemonic: $mnemonic")
            }
          case _ =>
            throw new IllegalArgumentException(s"unable to parse instruction: $line")
        }
    }
  }
}
