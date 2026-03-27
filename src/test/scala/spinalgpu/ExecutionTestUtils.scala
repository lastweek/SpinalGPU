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
    if (path.normalize.startsWith(KernelCorpus.outputRoot) && !Files.exists(path)) {
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

  private def withAxiLiteTimeout[T](clockDomain: ClockDomain, timeoutCycles: Int, label: String)(body: => T): T = {
    var result: Option[T] = None
    var failure: Option[Throwable] = None
    var completed = false

    fork {
      try {
        result = Some(body)
      } catch {
        case error: Throwable =>
          failure = Some(error)
      } finally {
        completed = true
      }
    }

    var cycles = 0
    while (!completed && cycles < timeoutCycles) {
      clockDomain.waitSampling()
      cycles += 1
    }

    assert(completed, s"$label timed out after $timeoutCycles cycles")
    failure.foreach(throw _)
    result.get
  }

  def writeRegister(driver: AxiLite4Driver, clockDomain: ClockDomain, address: Int, data: BigInt, timeoutCycles: Int = 256): Unit = {
    withAxiLiteTimeout(clockDomain, timeoutCycles, f"AXI-Lite write 0x$address%X") {
      driver.write(BigInt(address), data)
    }
  }

  def readRegister(driver: AxiLite4Driver, clockDomain: ClockDomain, address: Int, timeoutCycles: Int = 256): BigInt = {
    withAxiLiteTimeout(clockDomain, timeoutCycles, f"AXI-Lite read 0x$address%X") {
      driver.read(BigInt(address))
    }
  }

  def submitKernelCommand(driver: AxiLite4Driver, clockDomain: ClockDomain, command: KernelCorpus.KernelCommand): Unit = {
    writeRegister(driver, clockDomain, ControlRegisters.EntryPc, BigInt(command.entryPc))
    writeRegister(driver, clockDomain, ControlRegisters.GridDimX, BigInt(command.gridDimX))
    writeRegister(driver, clockDomain, ControlRegisters.BlockDimX, BigInt(command.blockDimX))
    writeRegister(driver, clockDomain, ControlRegisters.ArgBase, BigInt(command.argBase))
    writeRegister(driver, clockDomain, ControlRegisters.SharedBytes, BigInt(command.sharedBytes))
    writeRegister(driver, clockDomain, ControlRegisters.Control, BigInt(1))
  }

  def clearDone(driver: AxiLite4Driver, clockDomain: ClockDomain): Unit = {
    writeRegister(driver, clockDomain, ControlRegisters.Control, BigInt(2))
  }

  def readExecutionStatus(driver: AxiLite4Driver, clockDomain: ClockDomain): BigInt = {
    readRegister(driver, clockDomain, ControlRegisters.Status)
  }

  def readFaultCode(driver: AxiLite4Driver, clockDomain: ClockDomain): BigInt = {
    readRegister(driver, clockDomain, ControlRegisters.FaultCode)
  }

  def readFaultPc(driver: AxiLite4Driver, clockDomain: ClockDomain): BigInt = {
    readRegister(driver, clockDomain, ControlRegisters.FaultPc)
  }

  def waitForExecutionComplete(
      driver: AxiLite4Driver,
      clockDomain: ClockDomain,
      timeoutCycles: Int = 20000,
      pollIntervalCycles: Int = 8
  ): BigInt = {
    clockDomain.waitSampling()
    var status = readExecutionStatus(driver, clockDomain)
    var cycles = 0
    while (((status >> 1) & 1) == 0 && cycles < timeoutCycles) {
      clockDomain.waitSampling(pollIntervalCycles)
      cycles += pollIntervalCycles
      status = readExecutionStatus(driver, clockDomain)
    }

    assert(
      ((status >> 1) & 1) == 1,
      s"kernel did not complete within $timeoutCycles cycles, last status=0x${status.toString(16)}"
    )
    status
  }
}
