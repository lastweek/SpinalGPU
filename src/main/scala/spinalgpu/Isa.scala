package spinalgpu

// Internal SpinalGPU machine encoding used as the lowering target for the
// programmer-visible PTX subset ISA.
object Opcode {
  val NOP = 0x00
  val MOV = 0x01
  val MOVI = 0x02
  val S2R = 0x03
  val ADD = 0x10
  val ADDI = 0x11
  val SUB = 0x12
  val MULLO = 0x13
  val AND = 0x14
  val OR = 0x15
  val XOR = 0x16
  val SHL = 0x17
  val SHR = 0x18
  val SETEQ = 0x19
  val SETLT = 0x1A
  val FADD = 0x1B
  val FMUL = 0x1C
  val FFMA = 0x1D
  val LDG = 0x20
  val STG = 0x21
  val LDS = 0x22
  val STS = 0x23
  val BRA = 0x30
  val BRZ = 0x31
  val BRNZ = 0x32
  val EXIT = 0x33
  val TRAP = 0x34

  val sfuBase = 0x40
  val sfuLast = 0x4F
  val tensorBase = 0x50
  val tensorLast = 0x5F

  val names: Map[Int, String] = Map(
    NOP -> "nop",
    MOV -> "mov",
    MOVI -> "movi",
    S2R -> "s2r",
    ADD -> "add",
    ADDI -> "addi",
    SUB -> "sub",
    MULLO -> "mullo",
    AND -> "and",
    OR -> "or",
    XOR -> "xor",
    SHL -> "shl",
    SHR -> "shr",
    SETEQ -> "seteq",
    SETLT -> "setlt",
    FADD -> "fadd",
    FMUL -> "fmul",
    FFMA -> "ffma",
    LDG -> "ldg",
    STG -> "stg",
    LDS -> "lds",
    STS -> "sts",
    BRA -> "bra",
    BRZ -> "brz",
    BRNZ -> "brnz",
    EXIT -> "exit",
    TRAP -> "trap"
  )

  val byName: Map[String, Int] = names.map(_.swap)
}

object SpecialRegisterKind {
  val TidX = 0
  val TidY = 1
  val TidZ = 2
  val LaneId = 3
  val WarpId = 4
  val NtidX = 5
  val NtidY = 6
  val NtidZ = 7
  val CtaidX = 8
  val CtaidY = 9
  val CtaidZ = 10
  val NctaidX = 11
  val NctaidY = 12
  val NctaidZ = 13
  val ArgBase = 14
  val NwarpId = 15
  val SmId = 16
  val NsmId = 17
  val GridIdLo = 18
  val GridIdHi = 19

  val names: Map[Int, String] = Map(
    TidX -> "%tid.x",
    TidY -> "%tid.y",
    TidZ -> "%tid.z",
    LaneId -> "%laneid",
    WarpId -> "%warpid",
    NtidX -> "%ntid.x",
    NtidY -> "%ntid.y",
    NtidZ -> "%ntid.z",
    CtaidX -> "%ctaid.x",
    CtaidY -> "%ctaid.y",
    CtaidZ -> "%ctaid.z",
    NctaidX -> "%nctaid.x",
    NctaidY -> "%nctaid.y",
    NctaidZ -> "%nctaid.z",
    ArgBase -> "%argbase",
    NwarpId -> "%nwarpid",
    SmId -> "%smid",
    NsmId -> "%nsmid",
    GridIdLo -> "%gridid.lo",
    GridIdHi -> "%gridid.hi"
  )

  val byName: Map[String, Int] = Map(
    "%tid.x" -> TidX,
    "%tid.y" -> TidY,
    "%tid.z" -> TidZ,
    "%laneid" -> LaneId,
    "%warpid" -> WarpId,
    "%ntid.x" -> NtidX,
    "%ntid.y" -> NtidY,
    "%ntid.z" -> NtidZ,
    "%ctaid.x" -> CtaidX,
    "%ctaid.y" -> CtaidY,
    "%ctaid.z" -> CtaidZ,
    "%nctaid.x" -> NctaidX,
    "%nctaid.y" -> NctaidY,
    "%nctaid.z" -> NctaidZ,
    "%argbase" -> ArgBase,
    "%nwarpid" -> NwarpId,
    "%smid" -> SmId,
    "%nsmid" -> NsmId
  )
}

object FaultCode {
  val None = 0
  val InvalidLaunch = 1
  val MisalignedFetch = 2
  val IllegalOpcode = 3
  val MisalignedLoadStore = 4
  val NonUniformBranch = 5
  val Trap = 6
  val ExternalMemory = 7

  val names: Map[Int, String] = Map(
    None -> "none",
    InvalidLaunch -> "invalid_launch",
    MisalignedFetch -> "misaligned_fetch",
    IllegalOpcode -> "illegal_opcode",
    MisalignedLoadStore -> "misaligned_load_store",
    NonUniformBranch -> "non_uniform_branch",
    Trap -> "trap",
    ExternalMemory -> "external_memory"
  )
}

object ControlRegisters {
  val Control = 0x00
  val Status = 0x04
  val EntryPc = 0x08
  val GridDimX = 0x0C
  val GridDimY = 0x10
  val GridDimZ = 0x14
  val BlockDimX = 0x18
  val BlockDimY = 0x1C
  val BlockDimZ = 0x20
  val ArgBase = 0x24
  val SharedBytes = 0x28
  val FaultPc = 0x2C
  val FaultCode = 0x30
}

sealed trait InstructionFormat
object InstructionFormat {
  case object Rrr extends InstructionFormat
  case object Rrrr extends InstructionFormat
  case object Rri extends InstructionFormat
  case object Mem extends InstructionFormat
  case object Br extends InstructionFormat
  case object Sys extends InstructionFormat
}

final case class DecodedWord(
    format: InstructionFormat,
    opcode: Int,
    reg: Int,
    rs0: Int,
    rs1: Int,
    rs2: Int,
    immediate: Int,
    specialRegister: Int
)

object Isa {
  val instructionBytes = 4
  val registerCount = 32

  private def checkRegister(index: Int): Unit = {
    require(index >= 0 && index < registerCount, s"register index out of range: $index")
  }

  private def encodeSigned14(value: Int): Int = {
    require(value >= -(1 << 13) && value < (1 << 13), s"immediate out of 14-bit signed range: $value")
    value & 0x3FFF
  }

  private def signExtend14(value: Int): Int = {
    val masked = value & 0x3FFF
    if ((masked & 0x2000) != 0) masked | ~0x3FFF else masked
  }

  def encodeRrr(opcode: Int, rd: Int, rs0: Int, rs1: Int): Int = {
    checkRegister(rd)
    checkRegister(rs0)
    checkRegister(rs1)
    ((opcode & 0xFF) << 24) | ((rd & 0x1F) << 19) | ((rs0 & 0x1F) << 14) | ((rs1 & 0x1F) << 9)
  }

  def encodeRri(opcode: Int, rd: Int, rs0: Int, immediate: Int): Int = {
    checkRegister(rd)
    checkRegister(rs0)
    ((opcode & 0xFF) << 24) | ((rd & 0x1F) << 19) | ((rs0 & 0x1F) << 14) | encodeSigned14(immediate)
  }

  def encodeRrrr(opcode: Int, rd: Int, rs0: Int, rs1: Int, rs2: Int): Int = {
    checkRegister(rd)
    checkRegister(rs0)
    checkRegister(rs1)
    checkRegister(rs2)
    ((opcode & 0xFF) << 24) | ((rd & 0x1F) << 19) | ((rs0 & 0x1F) << 14) | ((rs1 & 0x1F) << 9) | ((rs2 & 0x1F) << 4)
  }

  def encodeMem(opcode: Int, reg: Int, base: Int, offset: Int): Int = {
    checkRegister(reg)
    checkRegister(base)
    ((opcode & 0xFF) << 24) | ((reg & 0x1F) << 19) | ((base & 0x1F) << 14) | encodeSigned14(offset)
  }

  def encodeBr(opcode: Int, rs0: Int, offset: Int): Int = {
    checkRegister(rs0)
    ((opcode & 0xFF) << 24) | ((rs0 & 0x1F) << 19) | encodeSigned14(offset)
  }

  def encodeSys(opcode: Int, rd: Int, specialRegister: Int): Int = {
    checkRegister(rd)
    require(
      SpecialRegisterKind.names.contains(specialRegister),
      s"unknown special register: $specialRegister"
    )
    ((opcode & 0xFF) << 24) | ((rd & 0x1F) << 19) | ((specialRegister & 0x1F) << 14)
  }

  def decodeWord(word: Int): DecodedWord = {
    val opcode = (word >>> 24) & 0xFF
    val reg = (word >>> 19) & 0x1F
    val rs0 = (word >>> 14) & 0x1F
    val rs1 = (word >>> 9) & 0x1F
    val rs2 = (word >>> 4) & 0x1F
    val imm = signExtend14(word)

    val format =
      opcode match {
        case Opcode.NOP | Opcode.EXIT | Opcode.TRAP => InstructionFormat.Br
        case Opcode.MOV | Opcode.ADD | Opcode.SUB | Opcode.MULLO | Opcode.AND | Opcode.OR | Opcode.XOR | Opcode.SHL |
            Opcode.SHR | Opcode.SETEQ | Opcode.SETLT | Opcode.FADD | Opcode.FMUL => InstructionFormat.Rrr
        case Opcode.FFMA => InstructionFormat.Rrrr
        case Opcode.MOVI | Opcode.ADDI => InstructionFormat.Rri
        case Opcode.LDG | Opcode.STG | Opcode.LDS | Opcode.STS => InstructionFormat.Mem
        case Opcode.BRA | Opcode.BRZ | Opcode.BRNZ => InstructionFormat.Br
        case Opcode.S2R => InstructionFormat.Sys
        case _ => throw new IllegalArgumentException(f"unknown opcode 0x$opcode%02X")
      }

    val specialRegister = if (format == InstructionFormat.Sys) rs0 else 0
    DecodedWord(format, opcode, reg, rs0, rs1, rs2, imm, specialRegister)
  }

  def disassemble(word: Int): String = {
    val decoded = decodeWord(word)
    val mnemonic = Opcode.names(decoded.opcode)
    decoded.format match {
      case InstructionFormat.Rrr =>
        s"$mnemonic r${decoded.reg}, r${decoded.rs0}, r${decoded.rs1}"
      case InstructionFormat.Rrrr =>
        s"$mnemonic r${decoded.reg}, r${decoded.rs0}, r${decoded.rs1}, r${decoded.rs2}"
      case InstructionFormat.Rri =>
        s"$mnemonic r${decoded.reg}, r${decoded.rs0}, ${decoded.immediate}"
      case InstructionFormat.Mem if decoded.opcode == Opcode.LDG || decoded.opcode == Opcode.LDS =>
        s"$mnemonic r${decoded.reg}, [r${decoded.rs0} + ${decoded.immediate}]"
      case InstructionFormat.Mem =>
        s"$mnemonic [r${decoded.rs0} + ${decoded.immediate}], r${decoded.reg}"
      case InstructionFormat.Br if decoded.opcode == Opcode.BRA =>
        s"$mnemonic ${decoded.immediate}"
      case InstructionFormat.Br if decoded.opcode == Opcode.BRZ || decoded.opcode == Opcode.BRNZ =>
        s"$mnemonic r${decoded.reg}, ${decoded.immediate}"
      case InstructionFormat.Br if decoded.opcode == Opcode.NOP || decoded.opcode == Opcode.EXIT || decoded.opcode == Opcode.TRAP =>
        mnemonic
      case InstructionFormat.Sys =>
        s"$mnemonic r${decoded.reg}, ${SpecialRegisterKind.names(decoded.specialRegister)}"
    }
  }

}
