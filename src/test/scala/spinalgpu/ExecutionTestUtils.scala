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
import spinalgpu.toolchain.KernelCatalog

object ExecutionTestUtils {
  final case class HostLaunch(
      entryPc: Long,
      blockDimX: Int,
      argBase: Long = 0L,
      gridDimX: Long = 1L,
      sharedBytes: Int = 0
  )

  def u32(value: Int): BigInt = BigInt(value.toLong & 0xFFFFFFFFL)

  def writeWord(memory: AxiMemorySim, address: Long, value: BigInt, byteCount: Int): Unit = {
    memory.memory.writeBigInt(address, value, byteCount)
  }

  def readWord(memory: AxiMemorySim, address: Long, byteCount: Int): BigInt = {
    memory.memory.readBigInt(address, byteCount)
  }

  @volatile private var kernelCorpusReady: Boolean = false

  def ensureKernelCorpusBuilt(): Unit = synchronized {
    val missingBinary = KernelCatalog.all.exists(artifact => !Files.exists(artifact.binaryPath))
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

  def loadBinaryFile(memory: AxiMemorySim, baseAddress: Long, path: Path, byteCount: Int): Unit = {
    if (path.normalize.startsWith(KernelCatalog.outputRoot) && !Files.exists(path)) {
      ensureKernelCorpusBuilt()
    }
    loadBinary(memory, baseAddress, KernelBinaryIO.readWords(path), byteCount)
  }

  def writeArgBuffer(memory: AxiMemorySim, argBase: Long, values: Seq[Long], byteCount: Int): Unit = {
    values.zipWithIndex.foreach { case (value, index) =>
      writeWord(memory, argBase + (index.toLong * byteCount), BigInt(value), byteCount)
    }
  }

  def writeDataWords(memory: AxiMemorySim, base: Long, values: Seq[Int], byteCount: Int): Unit = {
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

  def launchKernel(driver: AxiLite4Driver, clockDomain: ClockDomain, launch: HostLaunch): Unit = {
    writeRegister(driver, clockDomain, ControlRegisters.EntryPc, BigInt(launch.entryPc))
    writeRegister(driver, clockDomain, ControlRegisters.GridDimX, BigInt(launch.gridDimX))
    writeRegister(driver, clockDomain, ControlRegisters.BlockDimX, BigInt(launch.blockDimX))
    writeRegister(driver, clockDomain, ControlRegisters.ArgBase, BigInt(launch.argBase))
    writeRegister(driver, clockDomain, ControlRegisters.SharedBytes, BigInt(launch.sharedBytes))
    writeRegister(driver, clockDomain, ControlRegisters.Control, BigInt(1))
  }

  def clearDone(driver: AxiLite4Driver, clockDomain: ClockDomain): Unit = {
    writeRegister(driver, clockDomain, ControlRegisters.Control, BigInt(2))
  }

  def readStatus(driver: AxiLite4Driver, clockDomain: ClockDomain): BigInt = {
    readRegister(driver, clockDomain, ControlRegisters.Status)
  }

  def readFaultCode(driver: AxiLite4Driver, clockDomain: ClockDomain): BigInt = {
    readRegister(driver, clockDomain, ControlRegisters.FaultCode)
  }

  def readFaultPc(driver: AxiLite4Driver, clockDomain: ClockDomain): BigInt = {
    readRegister(driver, clockDomain, ControlRegisters.FaultPc)
  }

  def waitForDone(
      driver: AxiLite4Driver,
      clockDomain: ClockDomain,
      timeoutCycles: Int = 20000,
      pollIntervalCycles: Int = 8
  ): BigInt = {
    clockDomain.waitSampling()
    var status = readStatus(driver, clockDomain)
    var cycles = 0
    while (((status >> 1) & 1) == 0 && cycles < timeoutCycles) {
      clockDomain.waitSampling(pollIntervalCycles)
      cycles += pollIntervalCycles
      status = readStatus(driver, clockDomain)
    }

    assert(
      ((status >> 1) & 1) == 1,
      s"kernel did not complete within $timeoutCycles cycles, last status=0x${status.toString(16)}"
    )
    status
  }
}
