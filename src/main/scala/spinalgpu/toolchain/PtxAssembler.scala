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

  private final case class MoveWideSpecialInstruction(destination: String, source: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 2

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      require(source == "%gridid", s"mov.u64 only supports %gridid, got: $source")
      val (rdLow, rdHigh) = layout.requireWideRegister(destination)
      Seq(
        Isa.encodeSys(Opcode.S2R, rd = rdLow, specialRegister = SpecialRegisterKind.GridIdLo),
        Isa.encodeSys(Opcode.S2R, rd = rdHigh, specialRegister = SpecialRegisterKind.GridIdHi)
      )
    }
  }

  private final case class InstructionSequence(instructions: Seq[PtxInstruction]) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int =
      instructions.map(_.machineWordCount(layout, context)).sum

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val words = mutable.ArrayBuffer.empty[Int]
      var currentPc = machinePc
      instructions.foreach { instruction =>
        val emitted = instruction.emit(currentPc, layout, context, labels)
        words ++= emitted
        currentPc += emitted.length * Isa.instructionBytes
      }
      words.toSeq
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

  private final case class FloatBinaryInstruction(opcode: Int, destination: String, lhs: String, rhs: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      Seq(
        Isa.encodeRrr(
          opcode,
          rd = layout.requireFloatRegister(destination),
          rs0 = layout.requireFloatRegister(lhs),
          rs1 = layout.requireFloatRegister(rhs)
        )
      )
    }
  }

  private final case class FloatUnaryInstruction(opcode: Int, destination: String, source: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      Seq(
        Isa.encodeRrr(
          opcode,
          rd = layout.requireFloatRegister(destination),
          rs0 = layout.requireFloatRegister(source),
          rs1 = 0
        )
      )
    }
  }

  private final case class RegisterUnaryInstruction(opcode: Int, destination: String, source: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] =
      Seq(Isa.encodeRrr(opcode, rd = layout.requireRegister(destination), rs0 = layout.requireRegister(source), rs1 = 0))
  }

  private final case class RegisterBinaryInstruction(opcode: Int, destination: String, lhs: String, rhs: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] =
      Seq(Isa.encodeRrr(opcode, rd = layout.requireRegister(destination), rs0 = layout.requireRegister(lhs), rs1 = layout.requireRegister(rhs)))
  }

  private final case class RegisterFmaInstruction(opcode: Int, destination: String, lhs: String, rhs: String, accumulate: String)
      extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] =
      Seq(
        Isa.encodeRrrr(
          opcode,
          rd = layout.requireRegister(destination),
          rs0 = layout.requireRegister(lhs),
          rs1 = layout.requireRegister(rhs),
          rs2 = layout.requireRegister(accumulate)
        )
      )
  }

  private final case class FmaInstruction(destination: String, lhs: String, rhs: String, accumulate: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      Seq(
        Isa.encodeRrrr(
          Opcode.FFMA,
          rd = layout.requireFloatRegister(destination),
          rs0 = layout.requireFloatRegister(lhs),
          rs1 = layout.requireFloatRegister(rhs),
          rs2 = layout.requireFloatRegister(accumulate)
        )
      )
    }
  }

  private final case class SelectInstruction(destination: String, whenTrue: String, whenFalse: String, predicate: String, floatData: Boolean)
      extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 1

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val rd =
        if (floatData) layout.requireFloatRegister(destination)
        else layout.requireIntegerRegister(destination)
      val rs0 =
        if (floatData) layout.requireFloatRegister(whenTrue)
        else layout.requireIntegerRegister(whenTrue)
      val rs1 =
        if (floatData) layout.requireFloatRegister(whenFalse)
        else layout.requireIntegerRegister(whenFalse)
      Seq(
        Isa.encodeRrrr(
          Opcode.SEL,
          rd = rd,
          rs0 = rs0,
          rs1 = rs1,
          rs2 = layout.requirePredicate(predicate)
        )
      )
    }
  }

  private final case class LoadInstruction(addressSpace: String, destination: String, source: MemoryOperand, accessWidthBytes: Int = 4)
      extends PtxInstruction {
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
          prefix :+ Isa.encodeMem(if (accessWidthBytes == 2) Opcode.LDG16 else Opcode.LDG, reg = rd, base = base, offset = immediate)
        case "shared" =>
          require(accessWidthBytes == 4, "shared-memory loads only support 32-bit accesses")
          val (prefix, base, immediate) = lowerStandardMemoryAddress(source, layout, context, allowSharedSymbol = true)
          prefix :+ Isa.encodeMem(Opcode.LDS, reg = rd, base = base, offset = immediate)
      }
    }
  }

  private final case class StoreInstruction(addressSpace: String, destination: MemoryOperand, source: String, accessWidthBytes: Int = 4)
      extends PtxInstruction {
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
          prefix :+ Isa.encodeMem(if (accessWidthBytes == 2) Opcode.STG16 else Opcode.STG, reg = rs, base = base, offset = immediate)
        case "shared" =>
          require(accessWidthBytes == 4, "shared-memory stores only support 32-bit accesses")
          val (prefix, base, immediate) = lowerStandardMemoryAddress(destination, layout, context, allowSharedSymbol = true)
          prefix :+ Isa.encodeMem(Opcode.STS, reg = rs, base = base, offset = immediate)
      }
    }
  }

  private final case class StoreWideInstruction(addressSpace: String, destination: MemoryOperand, source: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int =
      addressSpace match {
        case "global" => lowerStandardMemoryAddress(destination, layout, context, allowSharedSymbol = false)._1.length + 2
        case other => throw new IllegalArgumentException(s"unsupported 64-bit store address space: $other")
      }

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val (rsLow, rsHigh) = layout.requireWideRegister(source)
      addressSpace match {
        case "global" =>
          val (prefix, base, immediate) = lowerStandardMemoryAddress(destination, layout, context, allowSharedSymbol = false)
          prefix ++ Seq(
            Isa.encodeMem(Opcode.STG, reg = rsLow, base = base, offset = immediate),
            Isa.encodeMem(Opcode.STG, reg = rsHigh, base = base, offset = immediate + 4)
          )
      }
    }
  }

  private final case class SetPredicateInstruction(
      compareOpcode: Int,
      predicate: String,
      negateResult: Boolean,
      lhs: String,
      rhs: ValueOperand,
      swapOperands: Boolean = false
  )
      extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = {
      val compareCount = rhs match {
        case RegisterOperand(_) => 1
        case ImmediateOperand(0) => 1
        case ImmediateOperand(_) => 2
        case _ => throw new IllegalArgumentException("setp expects a register or immediate rhs")
      }
      compareCount + (if (negateResult) 2 else 0)
    }

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val rd = layout.requirePredicate(predicate)
      val lhsRegister = layout.requireIntegerRegister(lhs)
      val compareWords =
        rhs match {
          case RegisterOperand(name) =>
            val rhsRegister = layout.requireIntegerRegister(name)
            val rs0 = if (swapOperands) rhsRegister else lhsRegister
            val rs1 = if (swapOperands) lhsRegister else rhsRegister
            Seq(Isa.encodeRrr(compareOpcode, rd = rd, rs0 = rs0, rs1 = rs1))
          case ImmediateOperand(0) =>
            require(!swapOperands, "swapped compare does not support immediate rhs")
            Seq(Isa.encodeRrr(compareOpcode, rd = rd, rs0 = lhsRegister, rs1 = 0))
          case ImmediateOperand(value) =>
            require(!swapOperands, "swapped compare does not support immediate rhs")
            Seq(
              Isa.encodeRri(Opcode.MOVI, rd = layout.scratchRegister, rs0 = 0, immediate = value),
              Isa.encodeRrr(compareOpcode, rd = rd, rs0 = lhsRegister, rs1 = layout.scratchRegister)
            )
          case _ =>
            throw new IllegalArgumentException("setp expects a register or immediate rhs")
        }

      if (!negateResult) {
        compareWords
      } else {
        compareWords ++ Seq(
          Isa.encodeRri(Opcode.MOVI, rd = layout.scratchRegister, rs0 = 0, immediate = 1),
          Isa.encodeRrr(Opcode.XOR, rd = rd, rs0 = rd, rs1 = layout.scratchRegister)
        )
      }
    }
  }

  private final case class FloatSetPredicateInstruction(predicate: String, relation: String, lhs: String, rhs: String) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int =
      relation match {
        case "eq" | "ne" | "lt" | "gt" => if (relation == "ne") 3 else 1
        case "le" | "ge" => 3
        case other => throw new IllegalArgumentException(s"unsupported f32 predicate relation: $other")
      }

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val rd = layout.requirePredicate(predicate)
      val lhsRegister = layout.requireFloatRegister(lhs)
      val rhsRegister = layout.requireFloatRegister(rhs)

      relation match {
        case "eq" =>
          Seq(Isa.encodeRrr(Opcode.FSETEQ, rd = rd, rs0 = lhsRegister, rs1 = rhsRegister))
        case "ne" =>
          Seq(
            Isa.encodeRrr(Opcode.FSETEQ, rd = rd, rs0 = lhsRegister, rs1 = rhsRegister),
            Isa.encodeRri(Opcode.MOVI, rd = layout.scratchRegister, rs0 = 0, immediate = 1),
            Isa.encodeRrr(Opcode.XOR, rd = rd, rs0 = rd, rs1 = layout.scratchRegister)
          )
        case "lt" =>
          Seq(Isa.encodeRrr(Opcode.FSETLT, rd = rd, rs0 = lhsRegister, rs1 = rhsRegister))
        case "gt" =>
          Seq(Isa.encodeRrr(Opcode.FSETLT, rd = rd, rs0 = rhsRegister, rs1 = lhsRegister))
        case "le" =>
          Seq(
            Isa.encodeRrr(Opcode.FSETLT, rd = rd, rs0 = lhsRegister, rs1 = rhsRegister),
            Isa.encodeRrr(Opcode.FSETEQ, rd = layout.scratchRegister, rs0 = lhsRegister, rs1 = rhsRegister),
            Isa.encodeRrr(Opcode.OR, rd = rd, rs0 = rd, rs1 = layout.scratchRegister)
          )
        case "ge" =>
          Seq(
            Isa.encodeRrr(Opcode.FSETLT, rd = rd, rs0 = rhsRegister, rs1 = lhsRegister),
            Isa.encodeRrr(Opcode.FSETEQ, rd = layout.scratchRegister, rs0 = lhsRegister, rs1 = rhsRegister),
            Isa.encodeRrr(Opcode.OR, rd = rd, rs0 = rd, rs1 = layout.scratchRegister)
          )
      }
    }
  }

  private final case class MinMaxInstruction(destination: String, lhs: String, rhs: String, compareOpcode: Int, floatData: Boolean, selectMax: Boolean)
      extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 2

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val rd =
        if (floatData) layout.requireFloatRegister(destination)
        else layout.requireIntegerRegister(destination)
      val lhsRegister =
        if (floatData) layout.requireFloatRegister(lhs)
        else layout.requireIntegerRegister(lhs)
      val rhsRegister =
        if (floatData) layout.requireFloatRegister(rhs)
        else layout.requireIntegerRegister(rhs)
      val whenTrue = if (selectMax) rhsRegister else lhsRegister
      val whenFalse = if (selectMax) lhsRegister else rhsRegister
      Seq(
        Isa.encodeRrr(compareOpcode, rd = layout.scratchRegister, rs0 = lhsRegister, rs1 = rhsRegister),
        Isa.encodeRrrr(Opcode.SEL, rd = rd, rs0 = whenTrue, rs1 = whenFalse, rs2 = layout.scratchRegister)
      )
    }
  }

  private final case class MadLoInstruction(destination: String, lhs: String, rhs: String, addend: ValueOperand) extends PtxInstruction {
    override def machineWordCount(layout: RegisterLayout, context: ModuleContext): Int = 2

    override def emit(machinePc: Int, layout: RegisterLayout, context: ModuleContext, labels: Map[String, Int]): Seq[Int] = {
      val product = Isa.encodeRrr(
        Opcode.MULLO,
        rd = layout.scratchRegister,
        rs0 = layout.requireIntegerRegister(lhs),
        rs1 = layout.requireIntegerRegister(rhs)
      )

      val addWords =
        addend match {
          case RegisterOperand(name) =>
            Seq(Isa.encodeRrr(Opcode.ADD, rd = layout.requireIntegerRegister(destination), rs0 = layout.scratchRegister, rs1 = layout.requireIntegerRegister(name)))
          case ImmediateOperand(value) =>
            Seq(Isa.encodeRri(Opcode.ADDI, rd = layout.requireIntegerRegister(destination), rs0 = layout.scratchRegister, immediate = value))
          case _ =>
            throw new IllegalArgumentException("mad.lo.u32 expects a register or immediate addend")
        }

      product +: addWords
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
      integerRegisters: Map[String, Int],
      floatRegisters: Map[String, Int],
      halfRegisters: Map[String, Int],
      half2Registers: Map[String, Int],
      b16Registers: Map[String, Int],
      wideRegisters: Map[String, (Int, Int)],
      predicateRegisters: Map[String, Int],
      scratchRegister: Int
  ) {
    def requireRegister(name: String): Int =
      integerRegisters
        .get(name)
        .orElse(floatRegisters.get(name))
        .orElse(halfRegisters.get(name))
        .orElse(half2Registers.get(name))
        .orElse(b16Registers.get(name))
        .getOrElse(throw new IllegalArgumentException(s"unknown PTX register: $name"))

    def requireIntegerRegister(name: String): Int =
      integerRegisters.getOrElse(name, throw new IllegalArgumentException(s"unknown PTX integer register: $name"))

    def requireFloatRegister(name: String): Int =
      floatRegisters.getOrElse(name, throw new IllegalArgumentException(s"unknown PTX float register: $name"))

    def requireHalfRegister(name: String): Int =
      halfRegisters.getOrElse(name, throw new IllegalArgumentException(s"unknown PTX f16 register: $name"))

    def requireHalf2Register(name: String): Int =
      half2Registers.getOrElse(name, throw new IllegalArgumentException(s"unknown PTX f16x2 register: $name"))

    def requireB16Register(name: String): Int =
      b16Registers.getOrElse(name, throw new IllegalArgumentException(s"unknown PTX b16 register: $name"))

    def requireWideRegister(name: String): (Int, Int) =
      wideRegisters.getOrElse(name, throw new IllegalArgumentException(s"unknown PTX 64-bit register: $name"))

    def requirePredicate(name: String): Int =
      predicateRegisters.getOrElse(name, throw new IllegalArgumentException(s"unknown PTX predicate: $name"))

    def requireSpecialRegister(name: String): Int =
      SpecialRegisterKind.byName.getOrElse(name, throw new IllegalArgumentException(s"unsupported special register: $name"))
  }

  def assemble(source: String): AssembledProgram = {
    val parsed = parseModule(source)
    val layout = allocateRegisters(
      parsed.declaredIntegerRegisterCount,
      parsed.declaredFloatRegisterCount,
      parsed.declaredHalfRegisterCount,
      parsed.declaredHalf2RegisterCount,
      parsed.declaredB16RegisterCount,
      parsed.declaredWideRegisterCount,
      parsed.declaredPredicateCount
    )
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
      declaredIntegerRegisterCount: Int,
      declaredFloatRegisterCount: Int,
      declaredHalfRegisterCount: Int,
      declaredHalf2RegisterCount: Int,
      declaredB16RegisterCount: Int,
      declaredWideRegisterCount: Int,
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

    var declaredIntegerRegisterCount = 0
    var declaredFloatRegisterCount = 0
    var declaredHalfRegisterCount = 0
    var declaredHalf2RegisterCount = 0
    var declaredB16RegisterCount = 0
    var declaredWideRegisterCount = 0
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
              val FloatRegisterDecl = """\.reg\s+\.f32\s+%f<(\d+)>""".r
              val HalfRegisterDecl = """\.reg\s+\.f16\s+%h<(\d+)>""".r
              val Half2RegisterDecl = """\.reg\s+\.f16x2\s+%x<(\d+)>""".r
              val B16RegisterDecl = """\.reg\s+\.b16\s+%b<(\d+)>""".r
              val WideRegisterDecl = """\.reg\s+\.u64\s+%rd<(\d+)>""".r
              statement match {
                case RegisterDecl(count) =>
                  declaredIntegerRegisterCount += count.toInt
                case FloatRegisterDecl(count) =>
                  declaredFloatRegisterCount += count.toInt
                case HalfRegisterDecl(count) =>
                  declaredHalfRegisterCount += count.toInt
                case Half2RegisterDecl(count) =>
                  declaredHalf2RegisterCount += count.toInt
                case B16RegisterDecl(count) =>
                  declaredB16RegisterCount += count.toInt
                case WideRegisterDecl(count) =>
                  declaredWideRegisterCount += count.toInt
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
      declaredIntegerRegisterCount = declaredIntegerRegisterCount,
      declaredFloatRegisterCount = declaredFloatRegisterCount,
      declaredHalfRegisterCount = declaredHalfRegisterCount,
      declaredHalf2RegisterCount = declaredHalf2RegisterCount,
      declaredB16RegisterCount = declaredB16RegisterCount,
      declaredWideRegisterCount = declaredWideRegisterCount,
      declaredPredicateCount = declaredPredicateCount,
      paramOffsets = params.toMap,
      sharedOffsets = sharedOffsets.toMap,
      body = body.toSeq
    )
  }

  private def parseInstruction(statement: String, lineNumber: Int): PtxInstruction = {
    def parseIntegerRegisterName(token: String): String = {
      val trimmed = token.trim
      require(trimmed.startsWith("%r"), s"expected %r register, got: $trimmed")
      trimmed
    }

    def parseFloatRegisterName(token: String): String = {
      val trimmed = token.trim
      require(trimmed.startsWith("%f"), s"expected %f register, got: $trimmed")
      trimmed
    }

    def parseHalfRegisterName(token: String): String = {
      val trimmed = token.trim
      require(trimmed.startsWith("%h"), s"expected %h register, got: $trimmed")
      trimmed
    }

    def parseHalf2RegisterName(token: String): String = {
      val trimmed = token.trim
      require(trimmed.startsWith("%x"), s"expected %x register, got: $trimmed")
      trimmed
    }

    def parseB16RegisterName(token: String): String = {
      val trimmed = token.trim
      require(trimmed.startsWith("%b"), s"expected %b register, got: $trimmed")
      trimmed
    }

    def parsePredicateName(token: String): String = {
      val trimmed = token.trim
      require(trimmed.startsWith("%p"), s"expected %p predicate, got: $trimmed")
      trimmed
    }

    def parseAnyRegisterName(token: String): String = {
      val trimmed = token.trim
      require(
        trimmed.startsWith("%r") || trimmed.startsWith("%f") || trimmed.startsWith("%h") || trimmed.startsWith("%x") || trimmed.startsWith("%b"),
        s"expected PTX scalar register, got: $trimmed"
      )
      trimmed
    }

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

    def parseFloatMovOperand(token: String): ValueOperand = {
      val trimmed = token.trim
      if (trimmed.startsWith("%f")) {
        RegisterOperand(trimmed)
      } else if (trimmed == "0f00000000") {
        ImmediateOperand(0)
      } else {
        throw new IllegalArgumentException(s"PTX line $lineNumber: mov.f32 only supports %f registers and 0f00000000 immediates")
      }
    }

    def parseTypedRegisterOperand(token: String, prefix: String, kind: String): ValueOperand = {
      val trimmed = token.trim
      if (trimmed.startsWith(prefix)) {
        RegisterOperand(trimmed)
      } else {
        throw new IllegalArgumentException(s"PTX line $lineNumber: $kind only supports $prefix registers")
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

    def parseTopLevelOperands(text: String): Seq[String] = {
      val items = mutable.ArrayBuffer.empty[String]
      val current = new StringBuilder
      var braceDepth = 0
      var bracketDepth = 0

      text.foreach {
        case '{' =>
          braceDepth += 1
          current += '{'
        case '}' =>
          require(braceDepth > 0, s"unmatched '}' in operand list: $statement")
          braceDepth -= 1
          current += '}'
        case '[' =>
          bracketDepth += 1
          current += '['
        case ']' =>
          require(bracketDepth > 0, s"unmatched ']' in operand list: $statement")
          bracketDepth -= 1
          current += ']'
        case ',' if braceDepth == 0 && bracketDepth == 0 =>
          val token = current.toString.trim
          require(token.nonEmpty, s"empty operand in: $statement")
          items += token
          current.clear()
        case character =>
          current += character
      }

      require(braceDepth == 0, s"unterminated brace tuple in: $statement")
      require(bracketDepth == 0, s"unterminated memory operand in: $statement")

      val tail = current.toString.trim
      require(tail.nonEmpty, s"empty trailing operand in: $statement")
      items += tail
      items.toSeq
    }

    def parseOperands(text: String, expected: Int): Seq[String] = {
      val items = parseTopLevelOperands(text)
      require(items.length == expected, s"expected $expected operands, got ${items.length}: $statement")
      items
    }

    def parseFloatRegisterTuple(token: String, width: Int): Seq[String] = {
      val trimmed = token.trim
      require(trimmed.startsWith("{") && trimmed.endsWith("}"), s"expected brace tuple, got: $trimmed")
      val inner = trimmed.drop(1).dropRight(1).trim
      require(inner.nonEmpty, s"empty tuple operand: $trimmed")
      val items = parseTopLevelOperands(inner)
      require(items.length == width, s"expected tuple width $width, got ${items.length}: $trimmed")
      items.map(parseFloatRegisterName)
    }

    def withAdditionalOffset(operand: MemoryOperand, additionalBytes: Int): MemoryOperand =
      operand.copy(immediateOffset = operand.immediateOffset + additionalBytes)

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
              case "mov.b32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                MovInstruction(parseAnyRegisterName(destination), RegisterOperand(parseAnyRegisterName(source)))
              case "mov.f32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                MovInstruction(parseFloatRegisterName(destination), parseFloatMovOperand(source))
              case "mov.f16" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                MovInstruction(parseHalfRegisterName(destination), RegisterOperand(parseAnyRegisterName(source)))
              case "mov.f16x2" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                MovInstruction(parseHalf2RegisterName(destination), parseTypedRegisterOperand(source, "%x", "mov.f16x2"))
              case "mov.u64" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                MoveWideSpecialInstruction(destination, source)
              case "mov.v2.f32" =>
                val Seq(destinationTuple, sourceTuple) = parseOperands(rest, 2)
                val destinations = parseFloatRegisterTuple(destinationTuple, 2)
                val sources = parseFloatRegisterTuple(sourceTuple, 2)
                InstructionSequence(destinations.zip(sources).map { case (destination, source) =>
                  MovInstruction(destination, RegisterOperand(source))
                })
              case "mov.v4.f32" =>
                val Seq(destinationTuple, sourceTuple) = parseOperands(rest, 2)
                val destinations = parseFloatRegisterTuple(destinationTuple, 4)
                val sources = parseFloatRegisterTuple(sourceTuple, 4)
                InstructionSequence(destinations.zip(sources).map { case (destination, source) =>
                  MovInstruction(destination, RegisterOperand(source))
                })
              case "add.u32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.ADD, Some(Opcode.ADDI), destination, lhs, parseScalarOperand(rhs))
              case "add.f32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                FloatBinaryInstruction(Opcode.FADD, parseFloatRegisterName(destination), parseFloatRegisterName(lhs), parseFloatRegisterName(rhs))
              case "sub.u32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.SUB, None, destination, lhs, parseScalarOperand(rhs))
              case "and.b32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.AND, None, destination, lhs, parseScalarOperand(rhs))
              case "or.b32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.OR, None, destination, lhs, parseScalarOperand(rhs))
              case "xor.b32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.XOR, None, destination, lhs, parseScalarOperand(rhs))
              case "shr.b32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.SHR, None, destination, lhs, parseScalarOperand(rhs))
              case "mul.lo.u32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.MULLO, None, destination, lhs, parseScalarOperand(rhs))
              case "mad.lo.u32" =>
                val Seq(destination, lhs, rhs, addend) = parseOperands(rest, 4)
                MadLoInstruction(parseIntegerRegisterName(destination), parseIntegerRegisterName(lhs), parseIntegerRegisterName(rhs), parseScalarOperand(addend))
              case "mul.f32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                FloatBinaryInstruction(Opcode.FMUL, parseFloatRegisterName(destination), parseFloatRegisterName(lhs), parseFloatRegisterName(rhs))
              case "add.rn.f16" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                RegisterBinaryInstruction(Opcode.HADD, parseHalfRegisterName(destination), parseHalfRegisterName(lhs), parseHalfRegisterName(rhs))
              case "mul.rn.f16" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                RegisterBinaryInstruction(Opcode.HMUL, parseHalfRegisterName(destination), parseHalfRegisterName(lhs), parseHalfRegisterName(rhs))
              case "fma.rn.f16" =>
                val Seq(destination, lhs, rhs, accumulate) = parseOperands(rest, 4)
                RegisterFmaInstruction(Opcode.HFMA, parseHalfRegisterName(destination), parseHalfRegisterName(lhs), parseHalfRegisterName(rhs), parseHalfRegisterName(accumulate))
              case "add.rn.f16x2" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                RegisterBinaryInstruction(Opcode.HADD2, parseHalf2RegisterName(destination), parseHalf2RegisterName(lhs), parseHalf2RegisterName(rhs))
              case "mul.rn.f16x2" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                RegisterBinaryInstruction(Opcode.HMUL2, parseHalf2RegisterName(destination), parseHalf2RegisterName(lhs), parseHalf2RegisterName(rhs))
              case "sub.f32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                FloatBinaryInstruction(Opcode.FSUB, parseFloatRegisterName(destination), parseFloatRegisterName(lhs), parseFloatRegisterName(rhs))
              case "neg.f32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                FloatUnaryInstruction(Opcode.FNEG, parseFloatRegisterName(destination), parseFloatRegisterName(source))
              case "abs.f32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                FloatUnaryInstruction(Opcode.FABS, parseFloatRegisterName(destination), parseFloatRegisterName(source))
              case "fma.rn.f32" =>
                val Seq(destination, lhs, rhs, accumulate) = parseOperands(rest, 4)
                FmaInstruction(
                  parseFloatRegisterName(destination),
                  parseFloatRegisterName(lhs),
                  parseFloatRegisterName(rhs),
                  parseFloatRegisterName(accumulate)
                )
              case "shl.b32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                BinaryInstruction(Opcode.SHL, None, destination, lhs, parseScalarOperand(rhs))
              case "setp.eq.u32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETEQ, parsePredicateName(predicate), negateResult = false, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs))
              case "setp.ne.u32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETEQ, parsePredicateName(predicate), negateResult = true, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs))
              case "setp.lt.u32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETLT, parsePredicateName(predicate), negateResult = false, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs))
              case "setp.gt.u32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETLT, parsePredicateName(predicate), negateResult = false, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs), swapOperands = true)
              case "setp.le.u32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETLT, parsePredicateName(predicate), negateResult = true, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs), swapOperands = true)
              case "setp.ge.u32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETLT, parsePredicateName(predicate), negateResult = true, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs))
              case "setp.eq.s32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETEQ, parsePredicateName(predicate), negateResult = false, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs))
              case "setp.ne.s32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETEQ, parsePredicateName(predicate), negateResult = true, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs))
              case "setp.lt.s32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETLTS, parsePredicateName(predicate), negateResult = false, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs))
              case "setp.gt.s32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETLTS, parsePredicateName(predicate), negateResult = false, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs), swapOperands = true)
              case "setp.le.s32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETLTS, parsePredicateName(predicate), negateResult = true, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs), swapOperands = true)
              case "setp.ge.s32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                SetPredicateInstruction(Opcode.SETLTS, parsePredicateName(predicate), negateResult = true, lhs = parseIntegerRegisterName(lhs), rhs = parseScalarOperand(rhs))
              case "setp.eq.f32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                FloatSetPredicateInstruction(parsePredicateName(predicate), relation = "eq", lhs = parseFloatRegisterName(lhs), rhs = parseFloatRegisterName(rhs))
              case "setp.ne.f32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                FloatSetPredicateInstruction(parsePredicateName(predicate), relation = "ne", lhs = parseFloatRegisterName(lhs), rhs = parseFloatRegisterName(rhs))
              case "setp.lt.f32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                FloatSetPredicateInstruction(parsePredicateName(predicate), relation = "lt", lhs = parseFloatRegisterName(lhs), rhs = parseFloatRegisterName(rhs))
              case "setp.gt.f32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                FloatSetPredicateInstruction(parsePredicateName(predicate), relation = "gt", lhs = parseFloatRegisterName(lhs), rhs = parseFloatRegisterName(rhs))
              case "setp.le.f32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                FloatSetPredicateInstruction(parsePredicateName(predicate), relation = "le", lhs = parseFloatRegisterName(lhs), rhs = parseFloatRegisterName(rhs))
              case "setp.ge.f32" =>
                val Seq(predicate, lhs, rhs) = parseOperands(rest, 3)
                FloatSetPredicateInstruction(parsePredicateName(predicate), relation = "ge", lhs = parseFloatRegisterName(lhs), rhs = parseFloatRegisterName(rhs))
              case "selp.u32" =>
                val Seq(destination, whenTrue, whenFalse, predicate) = parseOperands(rest, 4)
                SelectInstruction(
                  destination = parseIntegerRegisterName(destination),
                  whenTrue = parseIntegerRegisterName(whenTrue),
                  whenFalse = parseIntegerRegisterName(whenFalse),
                  predicate = parsePredicateName(predicate),
                  floatData = false
                )
              case "selp.f32" =>
                val Seq(destination, whenTrue, whenFalse, predicate) = parseOperands(rest, 4)
                SelectInstruction(
                  destination = parseFloatRegisterName(destination),
                  whenTrue = parseFloatRegisterName(whenTrue),
                  whenFalse = parseFloatRegisterName(whenFalse),
                  predicate = parsePredicateName(predicate),
                  floatData = true
                )
              case "min.u32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                MinMaxInstruction(parseIntegerRegisterName(destination), parseIntegerRegisterName(lhs), parseIntegerRegisterName(rhs), Opcode.SETLT, floatData = false, selectMax = false)
              case "max.u32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                MinMaxInstruction(parseIntegerRegisterName(destination), parseIntegerRegisterName(lhs), parseIntegerRegisterName(rhs), Opcode.SETLT, floatData = false, selectMax = true)
              case "min.s32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                MinMaxInstruction(parseIntegerRegisterName(destination), parseIntegerRegisterName(lhs), parseIntegerRegisterName(rhs), Opcode.SETLTS, floatData = false, selectMax = false)
              case "max.s32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                MinMaxInstruction(parseIntegerRegisterName(destination), parseIntegerRegisterName(lhs), parseIntegerRegisterName(rhs), Opcode.SETLTS, floatData = false, selectMax = true)
              case "min.f32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                MinMaxInstruction(parseFloatRegisterName(destination), parseFloatRegisterName(lhs), parseFloatRegisterName(rhs), Opcode.FSETLT, floatData = true, selectMax = false)
              case "max.f32" =>
                val Seq(destination, lhs, rhs) = parseOperands(rest, 3)
                MinMaxInstruction(parseFloatRegisterName(destination), parseFloatRegisterName(lhs), parseFloatRegisterName(rhs), Opcode.FSETLT, floatData = true, selectMax = true)
              case "ld.param.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                LoadInstruction("param", destination, parseMemoryOperand(source))
              case "ld.global.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                LoadInstruction("global", destination, parseMemoryOperand(source))
              case "ld.global.f32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                LoadInstruction("global", parseFloatRegisterName(destination), parseMemoryOperand(source))
              case "ld.global.f16" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                LoadInstruction("global", parseHalfRegisterName(destination), parseMemoryOperand(source), accessWidthBytes = 2)
              case "ld.global.f16x2" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                LoadInstruction("global", parseHalf2RegisterName(destination), parseMemoryOperand(source))
              case "ld.global.b16" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                LoadInstruction("global", parseB16RegisterName(destination), parseMemoryOperand(source), accessWidthBytes = 2)
              case "ld.global.v2.f32" =>
                val Seq(destinationTuple, source) = parseOperands(rest, 2)
                val destinations = parseFloatRegisterTuple(destinationTuple, 2)
                val memory = parseMemoryOperand(source)
                InstructionSequence(destinations.zipWithIndex.map { case (destination, index) =>
                  LoadInstruction("global", destination, withAdditionalOffset(memory, index * 4))
                })
              case "ld.global.v4.f32" =>
                val Seq(destinationTuple, source) = parseOperands(rest, 2)
                val destinations = parseFloatRegisterTuple(destinationTuple, 4)
                val memory = parseMemoryOperand(source)
                InstructionSequence(destinations.zipWithIndex.map { case (destination, index) =>
                  LoadInstruction("global", destination, withAdditionalOffset(memory, index * 4))
                })
              case "st.global.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                StoreInstruction("global", parseMemoryOperand(destination), source)
              case "st.global.f32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                StoreInstruction("global", parseMemoryOperand(destination), parseFloatRegisterName(source))
              case "st.global.f16" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                StoreInstruction("global", parseMemoryOperand(destination), parseHalfRegisterName(source), accessWidthBytes = 2)
              case "st.global.f16x2" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                StoreInstruction("global", parseMemoryOperand(destination), parseHalf2RegisterName(source))
              case "st.global.b16" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                StoreInstruction("global", parseMemoryOperand(destination), parseB16RegisterName(source), accessWidthBytes = 2)
              case "st.global.v2.f32" =>
                val Seq(destination, sourceTuple) = parseOperands(rest, 2)
                val memory = parseMemoryOperand(destination)
                val sources = parseFloatRegisterTuple(sourceTuple, 2)
                InstructionSequence(sources.zipWithIndex.map { case (source, index) =>
                  StoreInstruction("global", withAdditionalOffset(memory, index * 4), source)
                })
              case "st.global.v4.f32" =>
                val Seq(destination, sourceTuple) = parseOperands(rest, 2)
                val memory = parseMemoryOperand(destination)
                val sources = parseFloatRegisterTuple(sourceTuple, 4)
                InstructionSequence(sources.zipWithIndex.map { case (source, index) =>
                  StoreInstruction("global", withAdditionalOffset(memory, index * 4), source)
                })
              case "st.global.u64" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                StoreWideInstruction("global", parseMemoryOperand(destination), source)
              case "ld.shared.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                LoadInstruction("shared", destination, parseMemoryOperand(source))
              case "st.shared.u32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                StoreInstruction("shared", parseMemoryOperand(destination), source)
              case "cvt.f32.f16" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                RegisterUnaryInstruction(Opcode.CVTF32F16, parseFloatRegisterName(destination), parseHalfRegisterName(source))
              case "cvt.rn.f16.f32" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                RegisterUnaryInstruction(Opcode.CVTF16F32, parseHalfRegisterName(destination), parseFloatRegisterName(source))
              case "cvt.rn.f16x2.e4m3x2" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                RegisterUnaryInstruction(Opcode.CVTF16X2E4M3X2, parseHalf2RegisterName(destination), parseB16RegisterName(source))
              case "cvt.rn.f16x2.e5m2x2" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                RegisterUnaryInstruction(Opcode.CVTF16X2E5M2X2, parseHalf2RegisterName(destination), parseB16RegisterName(source))
              case "cvt.satfinite.e4m3x2.f16x2" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                RegisterUnaryInstruction(Opcode.CVTE4M3X2F16X2, parseB16RegisterName(destination), parseHalf2RegisterName(source))
              case "cvt.satfinite.e5m2x2.f16x2" =>
                val Seq(destination, source) = parseOperands(rest, 2)
                RegisterUnaryInstruction(Opcode.CVTE5M2X2F16X2, parseB16RegisterName(destination), parseHalf2RegisterName(source))
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

  private def allocateRegisters(
      declaredIntegerRegisterCount: Int,
      declaredFloatRegisterCount: Int,
      declaredHalfRegisterCount: Int,
      declaredHalf2RegisterCount: Int,
      declaredB16RegisterCount: Int,
      declaredWideRegisterCount: Int,
      declaredPredicateCount: Int
  ): RegisterLayout = {
    val usableRegisterCount = Isa.registerCount - 1
    require(declaredIntegerRegisterCount >= 0, "declared PTX integer register count must be non-negative")
    require(declaredFloatRegisterCount >= 0, "declared PTX float register count must be non-negative")
    require(declaredHalfRegisterCount >= 0, "declared PTX f16 register count must be non-negative")
    require(declaredHalf2RegisterCount >= 0, "declared PTX f16x2 register count must be non-negative")
    require(declaredB16RegisterCount >= 0, "declared PTX b16 register count must be non-negative")
    require(declaredWideRegisterCount >= 0, "declared PTX 64-bit register count must be non-negative")
    require(declaredPredicateCount >= 0, "declared PTX predicate count must be non-negative")
    val requiredRegisters =
      declaredIntegerRegisterCount +
        declaredFloatRegisterCount +
        declaredHalfRegisterCount +
        declaredHalf2RegisterCount +
        declaredB16RegisterCount +
        (declaredWideRegisterCount * 2) +
        declaredPredicateCount +
        1
    require(
      requiredRegisters <= usableRegisterCount,
      s"PTX source declares $declaredIntegerRegisterCount integer registers, $declaredFloatRegisterCount float registers, $declaredHalfRegisterCount f16 registers, $declaredHalf2RegisterCount f16x2 registers, $declaredB16RegisterCount b16 registers, $declaredWideRegisterCount 64-bit registers, and $declaredPredicateCount predicates, but only $usableRegisterCount hardware registers are available including one scratch register"
    )

    var nextRegister = 1
    val integerRegisters =
      (0 until declaredIntegerRegisterCount).map { index =>
        val allocated = nextRegister
        nextRegister += 1
        s"%r$index" -> allocated
      }.toMap
    val floatRegisters =
      (0 until declaredFloatRegisterCount).map { index =>
        val allocated = nextRegister
        nextRegister += 1
        s"%f$index" -> allocated
      }.toMap
    val halfRegisters =
      (0 until declaredHalfRegisterCount).map { index =>
        val allocated = nextRegister
        nextRegister += 1
        s"%h$index" -> allocated
      }.toMap
    val half2Registers =
      (0 until declaredHalf2RegisterCount).map { index =>
        val allocated = nextRegister
        nextRegister += 1
        s"%x$index" -> allocated
      }.toMap
    val b16Registers =
      (0 until declaredB16RegisterCount).map { index =>
        val allocated = nextRegister
        nextRegister += 1
        s"%b$index" -> allocated
      }.toMap
    val wideRegisters =
      (0 until declaredWideRegisterCount).map { index =>
        val low = nextRegister
        nextRegister += 2
        s"%rd$index" -> (low, low + 1)
      }.toMap
    val predicateRegisters =
      (0 until declaredPredicateCount).map { index =>
        val allocated = nextRegister
        nextRegister += 1
        s"%p$index" -> allocated
      }.toMap
    val scratchRegister = nextRegister
    RegisterLayout(integerRegisters, floatRegisters, halfRegisters, half2Registers, b16Registers, wideRegisters, predicateRegisters, scratchRegister)
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
