package spinalgpu.toolchain

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable
import spinalgpu._

final case class AssembledProgram(words: Seq[Int], labels: Map[String, Int])

object PtxAssembler {
  private sealed trait BodyItem
  private final case class Label(name: String) extends BodyItem
  private final case class Statement(instruction: PtxInstruction, lineNumber: Int) extends BodyItem

  private sealed trait PtxInstruction {
    def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int
    def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int]
  }

  private sealed trait ValueOperand
  private final case class RegisterOperand(name: String) extends ValueOperand
  private final case class ImmediateOperand(value: Int) extends ValueOperand
  private final case class SpecialRegisterOperand(name: String) extends ValueOperand
  private final case class SharedSymbolOperand(name: String) extends ValueOperand

  private final case class MemoryOperand(baseRegister: Option[String], symbol: Option[String], immediateOffset: Int)

  private final case class MovInstruction(destination: String, source: ValueOperand) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val rd = layout.requireRegister(destination)
      source match {
        case RegisterOperand(name) =>
          Seq(Isa.encodeRrr(Opcode.MOV, rd = rd, rs0 = layout.requireRegister(name), rs1 = 0))
        case ImmediateOperand(value) =>
          Seq(Isa.encodeRri(Opcode.MOVI, rd = rd, rs0 = 0, immediate = value))
        case SpecialRegisterOperand(name) =>
          Seq(Isa.encodeSys(Opcode.S2R, rd = rd, specialRegister = layout.requireSpecialRegister(name)))
        case SharedSymbolOperand(name) =>
          Seq(Isa.encodeRri(Opcode.MOVI, rd = rd, rs0 = 0, immediate = context.requireSharedSymbol(name)))
      }
    }
  }

  private final case class BinaryInstruction(
      opcode: Int,
      immediateOpcode: Option[Int],
      destination: String,
      lhs: String,
      rhs: ValueOperand
  ) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int =
      rhs match {
        case RegisterOperand(_) => 1
        case ImmediateOperand(_) if immediateOpcode.nonEmpty => 1
        case ImmediateOperand(_) => 2
        case _ => throw new IllegalArgumentException("binary arithmetic expects a register or immediate rhs")
      }

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val rd = layout.requireRegister(destination)
      val rs0 = layout.requireRegister(lhs)
      rhs match {
        case RegisterOperand(name) =>
          Seq(Isa.encodeRrr(opcode, rd = rd, rs0 = rs0, rs1 = layout.requireRegister(name)))
        case ImmediateOperand(value) if immediateOpcode.nonEmpty =>
          Seq(Isa.encodeRri(immediateOpcode.get, rd = rd, rs0 = rs0, immediate = value))
        case ImmediateOperand(value) =>
          Seq(
            Isa.encodeRri(Opcode.MOVI, rd = layout.scratchRegister, rs0 = 0, immediate = value),
            Isa.encodeRrr(opcode, rd = rd, rs0 = rs0, rs1 = layout.scratchRegister)
          )
        case _ =>
          throw new IllegalArgumentException("binary arithmetic expects a register or immediate rhs")
      }
    }
  }

  private final case class LoadInstruction(addressSpace: String, destination: String, source: MemoryOperand) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int =
      addressSpace match {
        case "param" => 2
        case "global" => lowerStandardMemoryAddress(source, layout, context, allowSharedSymbol = false)._1.length + 1
        case "shared" => lowerStandardMemoryAddress(source, layout, context, allowSharedSymbol = true)._1.length + 1
        case other => throw new IllegalArgumentException(s"unsupported address space: $other")
      }

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val rd = layout.requireRegister(destination)
      addressSpace match {
        case "param" =>
          val paramOffset = context.requireParamAddress(source)
          Seq(
            Isa.encodeSys(Opcode.S2R, rd = layout.scratchRegister, specialRegister = SpecialRegisterKind.ArgBase),
            Isa.encodeMem(Opcode.LDG, reg = rd, base = layout.scratchRegister, offset = paramOffset)
          )
        case "global" =>
          val (prefix, base, immediate) = lowerStandardMemoryAddress(source, layout, context, allowSharedSymbol = false)
          prefix :+ Isa.encodeMem(Opcode.LDG, reg = rd, base = base, offset = immediate)
        case "shared" =>
          val (prefix, base, immediate) = lowerStandardMemoryAddress(source, layout, context, allowSharedSymbol = true)
          prefix :+ Isa.encodeMem(Opcode.LDS, reg = rd, base = base, offset = immediate)
      }
    }
  }

  private final case class StoreInstruction(addressSpace: String, destination: MemoryOperand, source: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int =
      addressSpace match {
        case "global" => lowerStandardMemoryAddress(destination, layout, context, allowSharedSymbol = false)._1.length + 1
        case "shared" => lowerStandardMemoryAddress(destination, layout, context, allowSharedSymbol = true)._1.length + 1
        case other => throw new IllegalArgumentException(s"unsupported address space: $other")
      }

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val rs = layout.requireRegister(source)
      addressSpace match {
        case "global" =>
          val (prefix, base, immediate) = lowerStandardMemoryAddress(destination, layout, context, allowSharedSymbol = false)
          prefix :+ Isa.encodeMem(Opcode.STG, reg = rs, base = base, offset = immediate)
        case "shared" =>
          val (prefix, base, immediate) = lowerStandardMemoryAddress(destination, layout, context, allowSharedSymbol = true)
          prefix :+ Isa.encodeMem(Opcode.STS, reg = rs, base = base, offset = immediate)
      }
    }
  }

  private final case class SetPredicateInstruction(predicate: String, negateEquality: Boolean, lhs: String, rhs: ValueOperand)
      extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = {
      val compareCount = rhs match {
        case RegisterOperand(_) => 1
        case ImmediateOperand(0) => 1
        case ImmediateOperand(_) => 2
        case _ => throw new IllegalArgumentException("setp expects a register or immediate rhs")
      }
      compareCount + (if (negateEquality) 2 else 0)
    }

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val rd = layout.requirePredicate(predicate)
      val rs0 = layout.requireRegister(lhs)
      val compareWords =
        rhs match {
          case RegisterOperand(name) =>
            Seq(Isa.encodeRrr(Opcode.SETEQ, rd = rd, rs0 = rs0, rs1 = layout.requireRegister(name)))
          case ImmediateOperand(0) =>
            Seq(Isa.encodeRrr(Opcode.SETEQ, rd = rd, rs0 = rs0, rs1 = 0))
          case ImmediateOperand(value) =>
            Seq(
              Isa.encodeRri(Opcode.MOVI, rd = layout.scratchRegister, rs0 = 0, immediate = value),
              Isa.encodeRrr(Opcode.SETEQ, rd = rd, rs0 = rs0, rs1 = layout.scratchRegister)
            )
          case _ =>
            throw new IllegalArgumentException("setp expects a register or immediate rhs")
        }

      if (!negateEquality) {
        compareWords
      } else {
        compareWords ++ Seq(
          Isa.encodeRri(Opcode.MOVI, rd = layout.scratchRegister, rs0 = 0, immediate = 1),
          Isa.encodeRrr(Opcode.XOR, rd = rd, rs0 = rd, rs1 = layout.scratchRegister)
        )
      }
    }
  }

  private final case class BranchInstruction(label: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      Seq(Isa.encodeBr(Opcode.BRA, rs0 = 0, offset = requireBranchOffset(label, machinePc, labels)))
    }
  }

  private final case class PredicatedBranchInstruction(predicate: String, negate: Boolean, label: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val opcode = if (negate) Opcode.BRZ else Opcode.BRNZ
      Seq(Isa.encodeBr(opcode, rs0 = layout.requirePredicate(predicate), offset = requireBranchOffset(label, machinePc, labels)))
    }
  }

  private case object TrapInstruction extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1
    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] =
      Seq(Isa.encodeBr(Opcode.TRAP, rs0 = 0, offset = 0))
  }

  private case object RetInstruction extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1
    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] =
      Seq(Isa.encodeBr(Opcode.EXIT, rs0 = 0, offset = 0))
  }

  private final case class ModuleContext(
      paramOffsets: Map[String, Int],
      sharedOffsets: Map[String, Int]
  ) {
    def requireSharedSymbol(name: String): Int =
      sharedOffsets.getOrElse(name, throw new IllegalArgumentException(s"unknown shared symbol: $name"))

    def requireParamAddress(memory: MemoryOperand): Int = {
      require(memory.baseRegister.isEmpty, "ld.param does not support register-based param addressing")
      val baseOffset = memory.symbol match {
        case Some(name) => paramOffsets.getOrElse(name, throw new IllegalArgumentException(s"unknown parameter: $name"))
        case None => throw new IllegalArgumentException("ld.param requires a named parameter operand")
      }
      baseOffset + memory.immediateOffset
    }
  }

  private final case class RegisterLayout(
      userRegisters: Map[String, Int],
      predicateRegisters: Map[String, Int],
      scratchRegister: Int
  ) {
    def requireRegister(name: String): Int =
      userRegisters.getOrElse(name, throw new IllegalArgumentException(s"unknown PTX register: $name"))

    def requirePredicate(name: String): Int =
      predicateRegisters.getOrElse(name, throw new IllegalArgumentException(s"unknown PTX predicate: $name"))

    def requireSpecialRegister(name: String): Int =
      SpecialRegisterKind.byName.getOrElse(name, throw new IllegalArgumentException(s"unsupported special register: $name"))
  }

  def assemble(source: String): AssembledProgram = {
    val parsed = parseModule(source)
    val layout = allocateRegisters(parsed.declaredRegisterCount, parsed.declaredPredicateCount)
    val context = ModuleContext(parsed.paramOffsets, parsed.sharedOffsets)
    val labels = assignLabelAddresses(parsed.body, layout, context)

    val words = mutable.ArrayBuffer.empty[Int]
    var machinePc = 0
    parsed.body.foreach {
      case Label(_) =>
      case Statement(instruction, lineNumber) =>
        try {
          val emitted = instruction.emit(machinePc, layout, context, labels)
          require(
            emitted.length == instruction.machineWordCount(layout, context),
            s"internal lowering mismatch at PTX line $lineNumber"
          )
          words ++= emitted
          machinePc += emitted.length * Isa.instructionBytes
        } catch {
          case error: IllegalArgumentException =>
            throw new IllegalArgumentException(s"PTX line $lineNumber: ${error.getMessage}", error)
        }
    }

    AssembledProgram(words.toSeq, labels)
  }

  def assembleFile(path: Path): AssembledProgram = {
    try {
      assemble(Files.readString(path))
    } catch {
      case error: IllegalArgumentException =>
        throw new IllegalArgumentException(s"${path.toString}: ${error.getMessage}", error)
    }
  }

  private final case class ParsedModule(
      declaredRegisterCount: Int,
      declaredPredicateCount: Int,
      paramOffsets: Map[String, Int],
      sharedOffsets: Map[String, Int],
      body: Seq[BodyItem]
  )

  private def parseModule(source: String): ParsedModule = {
    val lines = source.linesIterator.toVector
    val params = mutable.LinkedHashMap.empty[String, Int]
    val sharedOffsets = mutable.LinkedHashMap.empty[String, Int]
    val body = mutable.ArrayBuffer.empty[BodyItem]

    var declaredRegisterCount = 0
    var declaredPredicateCount = 0
    var nextParamOffset = 0
    var nextSharedOffset = 0

    var sawVersion = false
    var sawTarget = false
    var sawAddressSize = false
    var sawEntry = false
    var parsingParams = false
    var waitingForBodyOpen = false
    var inBody = false
    val paramFragments = mutable.ArrayBuffer.empty[String]

    def fail(lineNumber: Int, message: String): Nothing =
      throw new IllegalArgumentException(s"PTX line $lineNumber: $message")

    def parseParams(lineNumber: Int): Unit = {
      val combined = paramFragments.mkString(" ").trim
      paramFragments.clear()
      if (combined.isEmpty) {
        return
      }

      combined.split(",").iterator.map(_.trim).filter(_.nonEmpty).foreach { item =>
        val ParamDecl = """\.param\s+\.u32\s+([A-Za-z_]\w*)""".r
        item match {
          case ParamDecl(name) =>
            if (params.contains(name)) {
              fail(lineNumber, s"duplicate parameter declaration: $name")
            }
            params(name) = nextParamOffset
            nextParamOffset += 4
          case _ =>
            fail(lineNumber, s"unsupported entry parameter declaration: $item")
        }
      }
    }

    lines.zipWithIndex.foreach { case (rawLine, index) =>
      val lineNumber = index + 1
      val cleaned = rawLine.split("//", 2).head.trim

      if (cleaned.nonEmpty) {
        if (waitingForBodyOpen) {
          if (cleaned == "{") {
            waitingForBodyOpen = false
            inBody = true
          } else {
            fail(lineNumber, s"expected '{' after .entry header, got: $cleaned")
          }
        } else if (parsingParams) {
          val closeIndex = cleaned.indexOf(')')
          if (closeIndex >= 0) {
            val before = cleaned.substring(0, closeIndex).trim
            if (before.nonEmpty) {
              paramFragments += before
            }
            parseParams(lineNumber)
            parsingParams = false

            val after = cleaned.substring(closeIndex + 1).trim
            if (after.nonEmpty) {
              if (after == "{") {
                inBody = true
              } else {
                fail(lineNumber, s"unexpected trailing tokens after .entry parameters: $after")
              }
            } else {
              waitingForBodyOpen = true
            }
          } else {
            paramFragments += cleaned
          }
        } else if (!inBody) {
          if (cleaned.startsWith(".version ")) {
            sawVersion = true
          } else if (cleaned == ".target spinalgpu") {
            sawTarget = true
          } else if (cleaned == ".address_size 32") {
            sawAddressSize = true
          } else if (cleaned.startsWith(".visible .entry ")) {
            if (sawEntry) {
              fail(lineNumber, "only one .visible .entry is supported per PTX file")
            }
            sawEntry = true
            val EntryHeader = """\.visible\s+\.entry\s+([A-Za-z_]\w*)\s*\((.*)""".r
            cleaned match {
              case EntryHeader(_, remainder) =>
                val closeIndex = remainder.indexOf(')')
                if (closeIndex >= 0) {
                  val before = remainder.substring(0, closeIndex).trim
                  if (before.nonEmpty) {
                    paramFragments += before
                  }
                  parseParams(lineNumber)

                  val after = remainder.substring(closeIndex + 1).trim
                  if (after.nonEmpty) {
                    if (after == "{") {
                      inBody = true
                    } else {
                      fail(lineNumber, s"unexpected tokens after .entry header: $after")
                    }
                  } else {
                    waitingForBodyOpen = true
                  }
                } else {
                  parsingParams = true
                  if (remainder.trim.nonEmpty) {
                    paramFragments += remainder.trim
                  }
                }
              case _ =>
                fail(lineNumber, s"unable to parse .entry header: $cleaned")
            }
          } else {
            fail(lineNumber, s"unexpected PTX line before .entry body: $cleaned")
          }
        } else {
          if (cleaned == "}") {
            inBody = false
          } else if (cleaned.endsWith(":")) {
            body += Label(cleaned.dropRight(1).trim)
          } else {
            require(cleaned.endsWith(";"), s"PTX line $lineNumber: expected ';' terminator")
            val statement = cleaned.dropRight(1).trim
            if (statement.startsWith(".reg ")) {
              val RegisterDecl = """\.reg\s+\.u32\s+%r<(\d+)>""".r
              statement match {
                case RegisterDecl(count) =>
                  declaredRegisterCount += count.toInt
                case _ =>
                  fail(lineNumber, s"unsupported register declaration: $statement")
              }
            } else if (statement.startsWith(".pred ")) {
              val PredicateDecl = """\.pred\s+%p<(\d+)>""".r
              statement match {
                case PredicateDecl(count) =>
                  declaredPredicateCount += count.toInt
                case _ =>
                  fail(lineNumber, s"unsupported predicate declaration: $statement")
              }
            } else if (statement.startsWith(".shared ")) {
              val SharedDecl = """\.shared\s+\.align\s+(\d+)\s+\.b8\s+([A-Za-z_]\w*)\[(\d+)\]""".r
              statement match {
                case SharedDecl(alignmentText, name, sizeText) =>
                  val alignment = alignmentText.toInt
                  require(alignment > 0, s"PTX line $lineNumber: shared alignment must be positive")
                  if (sharedOffsets.contains(name)) {
                    fail(lineNumber, s"duplicate shared declaration: $name")
                  }
                  nextSharedOffset = alignUp(nextSharedOffset, alignment)
                  sharedOffsets(name) = nextSharedOffset
                  nextSharedOffset += sizeText.toInt
                case _ =>
                  fail(lineNumber, s"unsupported shared declaration: $statement")
              }
            } else {
              body += Statement(parseInstruction(statement, lineNumber), lineNumber)
            }
          }
        }
      }
    }

    require(sawVersion, "PTX source must declare .version")
    require(sawTarget, "PTX source must declare .target spinalgpu")
    require(sawAddressSize, "PTX source must declare .address_size 32")
    require(sawEntry, "PTX source must declare one .visible .entry")
    require(!parsingParams, "unterminated .entry parameter list")
    require(!waitingForBodyOpen, "missing .entry body")
    require(!inBody, "unterminated .entry body")

    ParsedModule(
      declaredRegisterCount = declaredRegisterCount,
      declaredPredicateCount = declaredPredicateCount,
      paramOffsets = params.toMap,
      sharedOffsets = sharedOffsets.toMap,
      body = body.toSeq
    )
  }

  private def parseInstruction(statement: String, lineNumber: Int): PtxInstruction = {
    def parseScalarOperand(token: String): ValueOperand = {
      val trimmed = token.trim
      if (trimmed.startsWith("%r")) {
        RegisterOperand(trimmed)
      } else if (trimmed.startsWith("%")) {
        SpecialRegisterOperand(trimmed)
      } else {
        ImmediateOperand(parseImmediate(trimmed))
      }
    }

    def parseMovOperand(token: String): ValueOperand = {
      val trimmed = token.trim
      if (trimmed.startsWith("%r")) {
        RegisterOperand(trimmed)
      } else if (trimmed.startsWith("%")) {
        SpecialRegisterOperand(trimmed)
      } else if (trimmed.matches("[A-Za-z_]\\w*")) {
        SharedSymbolOperand(trimmed)
      } else {
        ImmediateOperand(parseImmediate(trimmed))
      }
    }

    def parseMemoryOperand(token: String): MemoryOperand = {
      require(token.startsWith("[") && token.endsWith("]"), s"expected bracketed memory operand, got: $token")
      val content = token.drop(1).dropRight(1).replace(" ", "")
      require(content.nonEmpty, "empty memory operand")

      var baseRegister: Option[String] = None
      var symbol: Option[String] = None
      var immediateOffset = 0

      content.split("\\+").iterator.filter(_.nonEmpty).foreach { part =>
        if (part.startsWith("%r")) {
          require(baseRegister.isEmpty, s"duplicate base register in memory operand: $token")
          baseRegister = Some(part)
        } else if (part.matches("[A-Za-z_]\\w*")) {
          require(symbol.isEmpty, s"duplicate symbol in memory operand: $token")
          symbol = Some(part)
        } else {
          immediateOffset += parseImmediate(part)
        }
      }

      MemoryOperand(baseRegister = baseRegister, symbol = symbol, immediateOffset = immediateOffset)
    }

    def parseOperands(text: String, expected: Int): Seq[String] = {
      val items = text.split(",").map(_.trim).toSeq
      require(items.length == expected, s"expected $expected operands, got ${items.length}: $statement")
      items
    }

    statement match {
      case predicated if predicated.startsWith("@") =>
        val PredicatedBra = """@(!?%p\d+)\s+bra\s+([A-Za-z_]\w*)""".r
        predicated match {
          case PredicatedBra(predicateText, label) =>
            PredicatedBranchInstruction(predicate = predicateText.stripPrefix("!"), negate = predicateText.startsWith("!"), label = label)
          case _ =>
            throw new IllegalArgumentException(s"PTX line $lineNumber: only predicated bra is supported, got: $statement")
        }
      case plain =>
        plain.split("\\s+", 2).toList match {
          case mnemonic :: Nil =>
            mnemonic match {
              case "trap" => TrapInstruction
              case "ret" => RetInstruction
              case _ => throw new IllegalArgumentException(s"PTX line $lineNumber: unsupported PTX instruction: $statement")
            }
          case mnemonic :: rest :: Nil =>
            mnemonic match {
              case "mov.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                MovInstruction(destination, parseMovOperand(source))
              case "add.u32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.ADD, Some(Opcode.ADDI), destination, lhs, parseScalarOperand(rhs))
              case "sub.u32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.SUB, None, destination, lhs, parseScalarOperand(rhs))
              case "mul.lo.u32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.MULLO, None, destination, lhs, parseScalarOperand(rhs))
              case "shl.b32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.SHL, None, destination, lhs, parseScalarOperand(rhs))
              case "setp.eq.u32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(predicate, negateEquality = false, lhs = lhs, rhs = parseScalarOperand(rhs))
              case "setp.ne.u32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(predicate, negateEquality = true, lhs = lhs, rhs = parseScalarOperand(rhs))
              case "ld.param.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                LoadInstruction("param", destination, parseMemoryOperand(source))
              case "ld.global.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                LoadInstruction("global", destination, parseMemoryOperand(source))
              case "st.global.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                StoreInstruction("global", parseMemoryOperand(destination), source)
              case "ld.shared.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                LoadInstruction("shared", destination, parseMemoryOperand(source))
              case "st.shared.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                StoreInstruction("shared", parseMemoryOperand(destination), source)
              case "bra" =>
                BranchInstruction(rest.trim)
              case _ =>
                throw new IllegalArgumentException(s"PTX line $lineNumber: unsupported PTX instruction: $statement")
            }
          case _ =>
            throw new IllegalArgumentException(s"PTX line $lineNumber: unable to parse statement: $statement")
        }
    }
  }

  private def allocateRegisters(declaredRegisterCount: Int, declaredPredicateCount: Int): RegisterLayout = {
    val usableRegisterCount = Isa.registerCount - 1
    require(declaredRegisterCount >= 0, "declared PTX register count must be non-negative")
    require(declaredPredicateCount >= 0, "declared PTX predicate count must be non-negative")
    require(
      declaredRegisterCount + declaredPredicateCount + 1 <= usableRegisterCount,
      s"PTX source declares $declaredRegisterCount registers and $declaredPredicateCount predicates, but only $usableRegisterCount hardware registers are available including one scratch register"
    )

    val userRegisters = (0 until declaredRegisterCount).map(index => s"%r$index" -> (index + 1)).toMap
    val predicateRegisters =
      (0 until declaredPredicateCount).map(index => s"%p$index" -> (declaredRegisterCount + index + 1)).toMap
    val scratchRegister = declaredRegisterCount + declaredPredicateCount + 1
    RegisterLayout(userRegisters, predicateRegisters, scratchRegister)
  }

  private def assignLabelAddresses(body: Seq[BodyItem], layout: RegisterLayout, context: ModuleContext): Map[String, Int] = {
    val labels = mutable.LinkedHashMap.empty[String, Int]
    var machinePc = 0
    body.foreach {
      case Label(name) =>
        require(!labels.contains(name), s"duplicate label: $name")
        labels(name) = machinePc
      case Statement(instruction, _) =>
        machinePc += instruction.machineWordCount(layout, context) * Isa.instructionBytes
    }
    labels.toMap
  }

  private def lowerStandardMemoryAddress(
      operand: MemoryOperand,
      layout: RegisterLayout,
      context: ModuleContext,
      allowSharedSymbol: Boolean
  ): (Seq[Int], Int, Int) = {
    val symbolOffset = operand.symbol match {
      case Some(name) if allowSharedSymbol => context.requireSharedSymbol(name)
      case Some(name) => throw new IllegalArgumentException(s"unexpected symbol in global address: $name")
      case None => 0
    }

    val immediate = symbolOffset + operand.immediateOffset
    operand.baseRegister match {
      case Some(name) =>
        (Seq.empty, layout.requireRegister(name), immediate)
      case None =>
        (
          Seq(Isa.encodeRri(Opcode.MOVI, rd = layout.scratchRegister, rs0 = 0, immediate = immediate)),
          layout.scratchRegister,
          0
        )
    }
  }

  private def requireBranchOffset(label: String, machinePc: Int, labels: Map[String, Int]): Int = {
    val targetPc = labels.getOrElse(label, throw new IllegalArgumentException(s"unknown label: $label"))
    targetPc - (machinePc + Isa.instructionBytes)
  }

  private def alignUp(value: Int, alignment: Int): Int =
    ((value + alignment - 1) / alignment) * alignment

  private def parseImmediate(token: String): Int = {
    val trimmed = token.trim
    if (trimmed.startsWith("0x") || trimmed.startsWith("-0x")) {
      Integer.decode(trimmed)
    } else {
      trimmed.toInt
    }
  }
}
