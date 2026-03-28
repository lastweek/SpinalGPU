package spinalgpu

import java.nio.file.Files
import java.nio.file.Path
import scala.math.BigInt
import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.sim._
import spinalgpu.toolchain.BuildKernelCorpus
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

  /** Rebuilds generated kernel binaries on demand when a corpus-backed test references a missing `.bin`. */
  def ensureKernelCorpusBuilt(): Unit = synchronized {
    val missingBinary = KernelCorpus.all.exists(kernel => !Files.exists(kernel.binaryPath))
    if (!kernelCorpusReady || missingBinary) {
      BuildKernelCorpus.buildAll()
      kernelCorpusReady = true
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
      ensureKernelCorpusBuilt()
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

  def writeRegister(driver: AxiLite4Driver, address: Int, data: BigInt): Unit = {
    driver.write(BigInt(address), data)
  }

  def readRegister(driver: AxiLite4Driver, address: Int): BigInt = {
    driver.read(BigInt(address))
  }

  def submitKernelCommand(driver: AxiLite4Driver, command: KernelCorpus.KernelCommand): Unit = {
    writeRegister(driver, ControlRegisters.EntryPc, BigInt(command.entryPc))
    writeRegister(driver, ControlRegisters.GridDimX, BigInt(command.gridDimX))
    writeRegister(driver, ControlRegisters.GridDimY, BigInt(command.gridDimY))
    writeRegister(driver, ControlRegisters.GridDimZ, BigInt(command.gridDimZ))
    writeRegister(driver, ControlRegisters.BlockDimX, BigInt(command.blockDimX))
    writeRegister(driver, ControlRegisters.BlockDimY, BigInt(command.blockDimY))
    writeRegister(driver, ControlRegisters.BlockDimZ, BigInt(command.blockDimZ))
    writeRegister(driver, ControlRegisters.ArgBase, BigInt(command.argBase))
    writeRegister(driver, ControlRegisters.SharedBytes, BigInt(command.sharedBytes))
    writeRegister(driver, ControlRegisters.Control, BigInt(1))
  }

  def clearDone(driver: AxiLite4Driver): Unit = {
    writeRegister(driver, ControlRegisters.Control, BigInt(2))
  }

  def readExecutionStatus(driver: AxiLite4Driver): BigInt = {
    readRegister(driver, ControlRegisters.Status)
  }

  def readFaultCode(driver: AxiLite4Driver): BigInt = {
    readRegister(driver, ControlRegisters.FaultCode)
  }

  def readFaultPc(driver: AxiLite4Driver): BigInt = {
    readRegister(driver, ControlRegisters.FaultPc)
  }
}
