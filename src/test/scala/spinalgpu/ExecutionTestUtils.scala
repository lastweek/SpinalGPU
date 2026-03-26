package spinalgpu

import scala.math.BigInt
import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axilite.sim._

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

  def loadProgram(memory: AxiMemorySim, baseAddress: Long, source: String, byteCount: Int): AssembledProgram = {
    val program = Isa.assemble(source)
    program.words.zipWithIndex.foreach { case (word, index) =>
      writeWord(memory, baseAddress + (index.toLong * byteCount), u32(word), byteCount)
    }
    program
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

  def launchKernel(driver: AxiLite4Driver, launch: HostLaunch): Unit = {
    driver.write(BigInt(ControlRegisters.EntryPc), BigInt(launch.entryPc))
    driver.write(BigInt(ControlRegisters.GridDimX), BigInt(launch.gridDimX))
    driver.write(BigInt(ControlRegisters.BlockDimX), BigInt(launch.blockDimX))
    driver.write(BigInt(ControlRegisters.ArgBase), BigInt(launch.argBase))
    driver.write(BigInt(ControlRegisters.SharedBytes), BigInt(launch.sharedBytes))
    driver.write(BigInt(ControlRegisters.Control), BigInt(1))
  }

  def clearDone(driver: AxiLite4Driver): Unit = {
    driver.write(BigInt(ControlRegisters.Control), BigInt(2))
  }

  def readStatus(driver: AxiLite4Driver): BigInt = {
    driver.read(BigInt(ControlRegisters.Status))
  }

  def readFaultCode(driver: AxiLite4Driver): BigInt = {
    driver.read(BigInt(ControlRegisters.FaultCode))
  }

  def readFaultPc(driver: AxiLite4Driver): BigInt = {
    driver.read(BigInt(ControlRegisters.FaultPc))
  }

  def waitForDone(
      driver: AxiLite4Driver,
      clockDomain: ClockDomain,
      timeoutCycles: Int = 20000,
      pollIntervalCycles: Int = 8
  ): BigInt = {
    clockDomain.waitSampling()
    var status = readStatus(driver)
    var cycles = 0
    while (((status >> 1) & 1) == 0 && cycles < timeoutCycles) {
      clockDomain.waitSampling(pollIntervalCycles)
      cycles += pollIntervalCycles
      status = readStatus(driver)
    }

    assert(
      ((status >> 1) & 1) == 1,
      s"kernel did not complete within $timeoutCycles cycles, last status=0x${status.toString(16)}"
    )
    status
  }
}
