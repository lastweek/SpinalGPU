package spinalgpu

import java.nio.file.Files
import java.nio.file.Path
import scala.math.BigInt
import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.AxiLite4
import spinal.lib.bus.amba4.axilite.sim._
import spinalgpu.toolchain.BuildKernelCorpus
import spinalgpu.toolchain.BuildTcgen05KernelCorpus
import spinalgpu.toolchain.BuildTensorKernelCorpus
import spinalgpu.toolchain.KernelBinaryIO
import spinalgpu.toolchain.KernelCorpus

object ExecutionTestUtils {
  def u32(value: Int): BigInt = BigInt(value.toLong & 0xFFFFFFFFL)
  def u32(value: Long): BigInt = BigInt(value & 0xFFFFFFFFL)
  def f32Bits(value: Float): Long = java.lang.Float.floatToRawIntBits(value).toLong & 0xFFFFFFFFL

  def writeWord(memory: AxiMemorySim, address: Long, value: BigInt, byteCount: Int): Unit = {
    memory.memory.writeBigInt(address, value, byteCount)
  }

  def readWord(memory: AxiMemorySim, address: Long, byteCount: Int): BigInt = {
    memory.memory.readBigInt(address, byteCount)
  }

  @volatile private var kernelCorpusReady: Boolean = false
  @volatile private var tensorKernelCorpusReady: Boolean = false
  @volatile private var tcgen05KernelCorpusReady: Boolean = false

  /** Rebuilds generated kernel binaries on demand when a corpus-backed test references a missing `.bin`. */
  def ensureKernelCorpusBuilt(): Unit = synchronized {
    val missingBinary = KernelCorpus.all.exists(kernel => !Files.exists(kernel.binaryPath))
    if (!kernelCorpusReady || missingBinary) {
      BuildKernelCorpus.buildAll()
      kernelCorpusReady = true
    }
  }

  /** Rebuilds only the tensor subset on demand so tensor-focused tests avoid the full corpus path. */
  def ensureTensorKernelCorpusBuilt(): Unit = synchronized {
    val missingBinary = KernelCorpus.tensorCases.exists(kernel => !Files.exists(kernel.binaryPath))
    if (!tensorKernelCorpusReady || missingBinary) {
      BuildTensorKernelCorpus.buildAll()
      tensorKernelCorpusReady = true
    }
  }

  /** Rebuilds only the tcgen05 subset on demand so tcgen05-focused tests avoid the full corpus path. */
  def ensureTcgen05KernelCorpusBuilt(): Unit = synchronized {
    val missingBinary = KernelCorpus.tcgen05Cases.exists(kernel => !Files.exists(kernel.binaryPath))
    if (!tcgen05KernelCorpusReady || missingBinary) {
      BuildTcgen05KernelCorpus.buildAll()
      tcgen05KernelCorpusReady = true
    }
  }

  def loadBinary(memory: AxiMemorySim, baseAddress: Long, words: Seq[Int], byteCount: Int): Unit = {
    words.zipWithIndex.foreach { case (word, index) =>
      writeWord(memory, baseAddress + (index.toLong * byteCount), u32(word), byteCount)
    }
  }

  /** Loads a generated machine-code file and auto-builds the corpus first if the requested `.bin` is missing. */
  def loadBinaryFile(memory: AxiMemorySim, baseAddress: Long, path: Path, byteCount: Int): Unit = {
    if (path.normalize.startsWith(KernelCorpus.outputRoot) && (!kernelCorpusReady || !Files.exists(path))) {
      if (KernelCorpus.tcgen05Cases.exists(_.binaryPath.normalize() == path.normalize())) {
        ensureTcgen05KernelCorpusBuilt()
      } else if (KernelCorpus.tensorCases.exists(_.binaryPath.normalize() == path.normalize())) {
        ensureTensorKernelCorpusBuilt()
      } else {
        ensureKernelCorpusBuilt()
      }
    }
    loadBinary(memory, baseAddress, KernelBinaryIO.readWords(path), byteCount)
  }

  /** Writes the packed launch argument buffer consumed by `ld.param` lowering. */
  def writeArgBuffer(memory: AxiMemorySim, argBase: Long, values: Seq[Long], byteCount: Int): Unit = {
    values.zipWithIndex.foreach { case (value, index) =>
      writeWord(memory, argBase + (index.toLong * byteCount), u32(value), byteCount)
    }
  }

  /** Writes raw 32-bit data words used by declarative corpus preload images. */
  def writeDataWords(memory: AxiMemorySim, base: Long, values: Seq[Long], byteCount: Int): Unit = {
    values.zipWithIndex.foreach { case (value, index) =>
      writeWord(memory, base + (index.toLong * byteCount), u32(value), byteCount)
    }
  }

  def writeDataF32(memory: AxiMemorySim, base: Long, values: Seq[Float], byteCount: Int): Unit = {
    values.zipWithIndex.foreach { case (value, index) =>
      writeWord(memory, base + (index.toLong * byteCount), u32(f32Bits(value)), byteCount)
    }
  }

  def writeDataF16(memory: AxiMemorySim, base: Long, values: Seq[Int]): Unit = {
    values.zipWithIndex.foreach { case (value, index) =>
      writeWord(memory, base + (index.toLong * 2L), BigInt(value & 0xFFFF), byteCount = 2)
    }
  }

  def writeDataPacked16(memory: AxiMemorySim, base: Long, values: Seq[Int]): Unit = {
    values.zipWithIndex.foreach { case (value, index) =>
      writeWord(memory, base + (index.toLong * 2L), BigInt(value & 0xFFFF), byteCount = 2)
    }
  }

  def idleAxiLite(bus: AxiLite4): Unit = {
    bus.aw.valid #= false
    bus.aw.addr #= 0
    bus.aw.prot #= 0
    bus.w.valid #= false
    bus.w.data #= 0
    bus.w.strb #= 0
    bus.b.ready #= false
    bus.ar.valid #= false
    bus.ar.addr #= 0
    bus.ar.prot #= 0
    bus.r.ready #= false
  }

  def initializeAxiLiteMaster(bus: AxiLite4, clockDomain: ClockDomain): Unit = {
    idleAxiLite(bus)
    clockDomain.waitSampling(2)
  }

  def writeRegister(bus: AxiLite4, clockDomain: ClockDomain, address: Int, data: BigInt, timeoutCycles: Int = 256): Unit = {
    idleAxiLite(bus)

    val fullStrb = (BigInt(1) << bus.w.strb.getWidth) - 1
    bus.aw.valid #= true
    bus.aw.addr #= address
    bus.aw.prot #= 0

    var cycles = 0
    var awDone = false
    while (!awDone && cycles < timeoutCycles) {
      val awWillFire = bus.aw.ready.toBoolean
      clockDomain.waitSampling()
      cycles += 1
      if (awWillFire) {
        awDone = true
        bus.aw.valid #= false
      }
    }
    assert(awDone, f"AXI-Lite write address handshake to 0x$address%X timed out after $cycles cycles")

    bus.w.valid #= true
    bus.w.data #= data
    bus.w.strb #= fullStrb
    bus.b.ready #= true
    cycles = 0
    while (!bus.b.valid.toBoolean && cycles < timeoutCycles) {
      clockDomain.waitSampling()
      cycles += 1
    }
    assert(
      bus.b.valid.toBoolean,
      f"AXI-Lite write data/response path for 0x$address%X timed out after $cycles cycles"
    )
    val resp = bus.b.resp.toBigInt
    bus.w.valid #= false
    clockDomain.waitSampling()
    bus.b.ready #= false
    idleAxiLite(bus)
    assert(resp == 0, f"AXI-Lite write to 0x$address%X returned error response 0x$resp%X")
  }

  def readRegister(bus: AxiLite4, clockDomain: ClockDomain, address: Int, timeoutCycles: Int = 256): BigInt = {
    idleAxiLite(bus)

    bus.ar.valid #= true
    bus.ar.addr #= address
    bus.ar.prot #= 0
    bus.r.ready #= true

    var cycles = 0
    while (!bus.r.valid.toBoolean && cycles < timeoutCycles) {
      clockDomain.waitSampling()
      cycles += 1
    }
    assert(
      bus.r.valid.toBoolean,
      f"AXI-Lite read address/response path for 0x$address%X timed out after $cycles cycles"
    )

    val data = bus.r.data.toBigInt
    val resp = bus.r.resp.toBigInt
    bus.ar.valid #= false
    clockDomain.waitSampling()
    bus.r.ready #= false
    idleAxiLite(bus)
    assert(resp == 0, f"AXI-Lite read from 0x$address%X returned error response 0x$resp%X")
    data
  }

  def submitKernelCommand(bus: AxiLite4, clockDomain: ClockDomain, command: KernelCorpus.KernelCommand): Unit = {
    writeRegister(bus, clockDomain, ControlRegisters.EntryPc, BigInt(command.entryPc))
    writeRegister(bus, clockDomain, ControlRegisters.GridDimX, BigInt(command.gridDimX))
    writeRegister(bus, clockDomain, ControlRegisters.GridDimY, BigInt(command.gridDimY))
    writeRegister(bus, clockDomain, ControlRegisters.GridDimZ, BigInt(command.gridDimZ))
    writeRegister(bus, clockDomain, ControlRegisters.BlockDimX, BigInt(command.blockDimX))
    writeRegister(bus, clockDomain, ControlRegisters.BlockDimY, BigInt(command.blockDimY))
    writeRegister(bus, clockDomain, ControlRegisters.BlockDimZ, BigInt(command.blockDimZ))
    writeRegister(bus, clockDomain, ControlRegisters.ArgBase, BigInt(command.argBase))
    writeRegister(bus, clockDomain, ControlRegisters.SharedBytes, BigInt(command.sharedBytes))
    writeRegister(bus, clockDomain, ControlRegisters.Control, BigInt(1))
  }

  def clearDone(bus: AxiLite4, clockDomain: ClockDomain): Unit = {
    writeRegister(bus, clockDomain, ControlRegisters.Control, BigInt(2))
  }

  def readExecutionStatus(bus: AxiLite4, clockDomain: ClockDomain): BigInt = {
    readRegister(bus, clockDomain, ControlRegisters.Status)
  }

  def readFaultCode(bus: AxiLite4, clockDomain: ClockDomain): BigInt = {
    readRegister(bus, clockDomain, ControlRegisters.FaultCode)
  }

  def readFaultPc(bus: AxiLite4, clockDomain: ClockDomain): BigInt = {
    readRegister(bus, clockDomain, ControlRegisters.FaultPc)
  }
}
