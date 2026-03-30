package spinalgpu

import scala.collection.mutable.ArrayBuffer
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class Tcgen05BlockSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 32,
    subSmCount = 1,
    residentWarpsPerSubSm = 1,
    subSmIssueWidth = 32,
    sharedMemoryBytes = 2048,
    tensorMemoryBytesPerWarp = 512
  )

  private lazy val compiled: SimCompiled[Tcgen05Block] = SimConfig.withVerilator.compile(new Tcgen05Block(config))

  private final case class Tcgen05Beat(
      completed: Boolean,
      writeEnable: Boolean,
      writeOffset: Int,
      error: Boolean,
      faultCode: Int,
      result: Seq[BigInt]
  )

  private def u32(value: Int): BigInt = BigInt(value.toLong & 0xFFFFFFFFL)

  private def packRowMajorWords(matrix: Array[Array[Int]]): Array[Int] =
    matrix.flatMap { rowValues =>
      rowValues.grouped(2).map { pair =>
        LowPrecisionCodec.packHalf2(pair.head, pair(1))
      }
    }

  private def setUniformRegister(registerFile: Array[Array[Int]], register: Int, value: Int): Unit =
    (0 until config.warpSize).foreach(lane => registerFile(register)(lane) = value)

  private def setPerLaneRegister(registerFile: Array[Array[Int]], register: Int, values: Seq[Int]): Unit =
    values.zipWithIndex.foreach { case (value, lane) => registerFile(register)(lane) = value }

  private def initDefaults(dut: Tcgen05Block): Unit = {
    dut.io.launch.valid #= false
    dut.io.launch.payload.warpId #= 0
    dut.io.launch.payload.localSlotId #= 0
    dut.io.launch.payload.opcode #= 0
    dut.io.launch.payload.activeMask #= 0
    dut.io.launch.payload.rdBase #= 0
    dut.io.launch.payload.rs0Base #= 0
    dut.io.launch.payload.rs1Base #= 0
    dut.io.launch.payload.rs2Base #= 0
    dut.io.event.ready #= false

    dut.io.sharedMemReq.ready #= false
    dut.io.sharedMemRsp.valid #= false
    dut.io.sharedMemRsp.payload.warpId #= 0
    dut.io.sharedMemRsp.payload.completed #= true
    dut.io.sharedMemRsp.payload.error #= false
    dut.io.sharedMemRsp.payload.readData #= 0
    dut.io.sharedMemRsp.payload.bankIndex #= 0

    dut.io.tensorMemReq.ready #= false
    dut.io.tensorMemRsp.valid #= false
    dut.io.tensorMemRsp.payload.warpId #= 0
    dut.io.tensorMemRsp.payload.completed #= true
    dut.io.tensorMemRsp.payload.error #= false
    dut.io.tensorMemRsp.payload.readData #= 0

    for (lane <- 0 until config.warpSize) {
      dut.io.readDataA(lane) #= 0
      dut.io.readDataB(lane) #= 0
      dut.io.readDataC(lane) #= 0
    }
  }

  private def waitUntil(timeoutCycles: Int = 256)(condition: => Boolean)(implicit dut: Tcgen05Block): Unit = {
    var remaining = timeoutCycles
    while (!condition && remaining > 0) {
      dut.clockDomain.waitSampling()
      remaining -= 1
    }
    condition shouldBe true
  }

  private def startRegisterFileDriver(dut: Tcgen05Block, registerFile: Array[Array[Int]]): Unit = {
    fork {
      while (true) {
        val readAddrA = dut.io.readAddrA.toInt
        val readAddrB = dut.io.readAddrB.toInt
        val readAddrC = dut.io.readAddrC.toInt
        for (lane <- 0 until config.warpSize) {
          dut.io.readDataA(lane) #= u32(registerFile(readAddrA)(lane))
          dut.io.readDataB(lane) #= u32(registerFile(readAddrB)(lane))
          dut.io.readDataC(lane) #= u32(registerFile(readAddrC)(lane))
        }
        sleep(1)
      }
    }
  }

  private def startSharedMemoryModel(dut: Tcgen05Block, sharedWords: Array[Int]): Unit = {
    fork {
      var pendingResponse: Option[(Int, Int)] = None

      while (true) {
        dut.io.sharedMemReq.ready #= pendingResponse.isEmpty
        dut.io.sharedMemRsp.valid #= pendingResponse.nonEmpty

        pendingResponse.foreach { case (warpId, readData) =>
          dut.io.sharedMemRsp.payload.warpId #= warpId
          dut.io.sharedMemRsp.payload.completed #= true
          dut.io.sharedMemRsp.payload.error #= false
          dut.io.sharedMemRsp.payload.readData #= u32(readData)
          dut.io.sharedMemRsp.payload.bankIndex #= 0
        }

        dut.clockDomain.waitSampling()

        if (pendingResponse.nonEmpty && dut.io.sharedMemRsp.ready.toBoolean) {
          pendingResponse = None
        }

        if (pendingResponse.isEmpty && dut.io.sharedMemReq.valid.toBoolean && dut.io.sharedMemReq.ready.toBoolean) {
          val address = dut.io.sharedMemReq.payload.address.toInt
          val warpId = dut.io.sharedMemReq.payload.warpId.toInt
          if (dut.io.sharedMemReq.payload.write.toBoolean) {
            sharedWords(address) = dut.io.sharedMemReq.payload.writeData.toBigInt.toInt
            pendingResponse = Some(warpId -> 0)
          } else {
            pendingResponse = Some(warpId -> sharedWords(address))
          }
        }
      }
    }
  }

  private def startTensorMemoryModel(dut: Tcgen05Block, tensorWords: Array[Int]): Unit = {
    fork {
      var pendingResponse: Option[(Int, Int)] = None

      while (true) {
        dut.io.tensorMemReq.ready #= pendingResponse.isEmpty
        dut.io.tensorMemRsp.valid #= pendingResponse.nonEmpty

        pendingResponse.foreach { case (warpId, readData) =>
          dut.io.tensorMemRsp.payload.warpId #= warpId
          dut.io.tensorMemRsp.payload.completed #= true
          dut.io.tensorMemRsp.payload.error #= false
          dut.io.tensorMemRsp.payload.readData #= u32(readData)
        }

        dut.clockDomain.waitSampling()

        if (pendingResponse.nonEmpty && dut.io.tensorMemRsp.ready.toBoolean) {
          pendingResponse = None
        }

        if (pendingResponse.isEmpty && dut.io.tensorMemReq.valid.toBoolean && dut.io.tensorMemReq.ready.toBoolean) {
          val address = dut.io.tensorMemReq.payload.address.toInt
          val warpId = dut.io.tensorMemReq.payload.warpId.toInt
          if (dut.io.tensorMemReq.payload.write.toBoolean) {
            tensorWords(address) = dut.io.tensorMemReq.payload.writeData.toBigInt.toInt
            pendingResponse = Some(warpId -> 0)
          } else {
            pendingResponse = Some(warpId -> tensorWords(address))
          }
        }
      }
    }
  }

  private def issueTcgen05Op(
      dut: Tcgen05Block,
      opcode: Int,
      rdBase: Int = 0,
      rs0Base: Int = 0,
      rs1Base: Int = 0,
      rs2Base: Int = 0,
      activeMask: Long = 0xFFFFFFFFL
  ): Unit = {
    dut.io.launch.valid #= true
    dut.io.launch.payload.warpId #= 0
    dut.io.launch.payload.localSlotId #= 0
    dut.io.launch.payload.opcode #= opcode
    dut.io.launch.payload.activeMask #= BigInt(activeMask & 0xFFFFFFFFL)
    dut.io.launch.payload.rdBase #= rdBase
    dut.io.launch.payload.rs0Base #= rs0Base
    dut.io.launch.payload.rs1Base #= rs1Base
    dut.io.launch.payload.rs2Base #= rs2Base

    while (!dut.io.launch.ready.toBoolean) {
      dut.clockDomain.waitSampling()
    }
    dut.clockDomain.waitSampling()
    dut.io.launch.valid #= false
  }

  private def collectEvents(dut: Tcgen05Block, timeoutCycles: Int = 4096): Seq[Tcgen05Beat] = {
    val beats = ArrayBuffer.empty[Tcgen05Beat]
    dut.io.event.ready #= true
    waitUntil(timeoutCycles) { dut.io.event.valid.toBoolean }(dut)

    var done = false
    while (!done) {
      beats += Tcgen05Beat(
        completed = dut.io.event.payload.completed.toBoolean,
        writeEnable = dut.io.event.payload.writeEnable.toBoolean,
        writeOffset = dut.io.event.payload.writeOffset.toInt,
        error = dut.io.event.payload.error.toBoolean,
        faultCode = dut.io.event.payload.faultCode.toInt,
        result = (0 until config.warpSize).map(lane => dut.io.event.payload.result(lane).toBigInt)
      )
      done = beats.last.error || beats.last.completed
      dut.clockDomain.waitSampling()
      if (!done) {
        waitUntil(timeoutCycles) { dut.io.event.valid.toBoolean }(dut)
      }
    }

    dut.io.event.ready #= false
    beats.toSeq
  }

  test("tcgen05.st and tcgen05.ld round-trip one 64-word TMEM tile") {
    compiled.doSim { dut =>
      implicit val implicitDut: Tcgen05Block = dut
      val registerFile = Array.fill(config.registerCount, config.warpSize)(0)
      val sharedWords = Array.fill(config.sharedWordCount)(0)
      val tensorWords = Array.fill(config.tensorWordCount)(0)
      val expectedWords = Array.tabulate(Tcgen05Layouts.TransferShapeWords)(index => (index << 5) | 0x11)

      setUniformRegister(registerFile, register = 1, value = 0)
      setPerLaneRegister(registerFile, register = 2, values = expectedWords.take(config.warpSize))
      setPerLaneRegister(registerFile, register = 3, values = expectedWords.drop(config.warpSize))

      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      startRegisterFileDriver(dut, registerFile)
      startSharedMemoryModel(dut, sharedWords)
      startTensorMemoryModel(dut, tensorWords)

      issueTcgen05Op(dut, opcode = Opcode.TCGEN05_ST_32X32B_X2, rs0Base = 1, rs1Base = 2)
      val storeBeats = collectEvents(dut)
      storeBeats.length shouldBe 1
      storeBeats.head.error shouldBe false
      storeBeats.head.writeEnable shouldBe false
      tensorWords.take(Tcgen05Layouts.TransferShapeWords) shouldBe expectedWords

      issueTcgen05Op(dut, opcode = Opcode.TCGEN05_LD_32X32B_X2, rdBase = 4, rs0Base = 1)
      val loadBeats = collectEvents(dut)
      loadBeats.length shouldBe 2
      loadBeats.map(_.writeOffset) shouldBe Seq(0, 1)
      loadBeats.foreach(_.error shouldBe false)
      loadBeats(0).completed shouldBe false
      loadBeats(1).completed shouldBe true
      loadBeats(0).result shouldBe expectedWords.take(config.warpSize).map(u32).toSeq
      loadBeats(1).result shouldBe expectedWords.drop(config.warpSize).map(u32).toSeq
    }
  }

  test("tcgen05.mma.cta_group::1.kind::f16 consumes shared descriptors and TMEM-backed D") {
    compiled.doSim { dut =>
      implicit val implicitDut: Tcgen05Block = dut
      val registerFile = Array.fill(config.registerCount, config.warpSize)(0)
      val sharedWords = Array.fill(config.sharedWordCount)(0)
      val tensorWords = Array.fill(config.tensorWordCount)(0)

      val aMatrix = Array.tabulate(Tcgen05Layouts.M, Tcgen05Layouts.K) { (row, col) =>
        LowPrecisionCodec.floatToHalfBits(((row % 4) + (col % 3) + 1).toFloat * 0.25f)
      }
      val bMatrix = Array.tabulate(Tcgen05Layouts.K, Tcgen05Layouts.N) { (row, col) =>
        LowPrecisionCodec.floatToHalfBits(((row % 5) - (col % 2) + 2).toFloat * 0.125f)
      }
      val cMatrix = Array.tabulate(Tcgen05Layouts.M, Tcgen05Layouts.N) { (row, col) =>
        LowPrecisionCodec.floatToHalfBits(((row - col).toFloat) * 0.0625f)
      }
      val expectedMatrix = Array.tabulate(Tcgen05Layouts.M, Tcgen05Layouts.N) { (row, col) =>
        var accBits = cMatrix(row)(col)
        var k = 0
        while (k < Tcgen05Layouts.K) {
          val a = LowPrecisionCodec.halfBitsToFloat(aMatrix(row)(k))
          val b = LowPrecisionCodec.halfBitsToFloat(bMatrix(k)(col))
          val c = LowPrecisionCodec.halfBitsToFloat(accBits)
          accBits = LowPrecisionCodec.floatToHalfBits((a * b) + c)
          k += 1
        }
        accBits
      }

      val packedA = packRowMajorWords(aMatrix)
      val packedB = packRowMajorWords(bMatrix)
      val packedC = packRowMajorWords(cMatrix)
      val expectedWords = packRowMajorWords(expectedMatrix)

      packedA.indices.foreach(index => sharedWords(index) = packedA(index))
      packedB.indices.foreach(index => sharedWords(128 + index) = packedB(index))
      packedC.indices.foreach(index => tensorWords(index) = packedC(index))

      setUniformRegister(registerFile, register = 1, value = 0)
      setUniformRegister(registerFile, register = 2, value = 0)
      setUniformRegister(registerFile, register = 3, value = 0)
      setUniformRegister(registerFile, register = 4, value = 512)
      setUniformRegister(registerFile, register = 5, value = 0)
      setUniformRegister(registerFile, register = 6, value = 17)
      setUniformRegister(registerFile, register = 7, value = 0)

      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      startRegisterFileDriver(dut, registerFile)
      startSharedMemoryModel(dut, sharedWords)
      startTensorMemoryModel(dut, tensorWords)

      issueTcgen05Op(dut, opcode = Opcode.TCGEN05_MMA_CTA1_F16, rdBase = 1, rs0Base = 2, rs1Base = 4, rs2Base = 6)
      val beats = collectEvents(dut, timeoutCycles = 16384)

      beats.length shouldBe 1
      beats.head.error shouldBe false
      beats.head.writeEnable shouldBe false
      beats.head.completed shouldBe true
      tensorWords.take(Tcgen05Layouts.DWordCount) shouldBe expectedWords
    }
  }

  test("tcgen05 ops fault on partial warp participation") {
    compiled.doSim { dut =>
      implicit val implicitDut: Tcgen05Block = dut
      val registerFile = Array.fill(config.registerCount, config.warpSize)(0)
      val sharedWords = Array.fill(config.sharedWordCount)(0)
      val tensorWords = Array.fill(config.tensorWordCount)(0)

      setUniformRegister(registerFile, register = 1, value = 0)

      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      startRegisterFileDriver(dut, registerFile)
      startSharedMemoryModel(dut, sharedWords)
      startTensorMemoryModel(dut, tensorWords)

      issueTcgen05Op(dut, opcode = Opcode.TCGEN05_LD_32X32B_X2, rdBase = 2, rs0Base = 1, activeMask = 0x00FFFFFFL)
      val beats = collectEvents(dut)

      beats.length shouldBe 1
      beats.head.error shouldBe true
      beats.head.faultCode shouldBe FaultCode.TensorProtocol
      beats.head.writeEnable shouldBe false
    }
  }

  test("tcgen05 Tensor Memory accesses fault on misaligned TMEM addresses") {
    compiled.doSim { dut =>
      implicit val implicitDut: Tcgen05Block = dut
      val registerFile = Array.fill(config.registerCount, config.warpSize)(0)
      val sharedWords = Array.fill(config.sharedWordCount)(0)
      val tensorWords = Array.fill(config.tensorWordCount)(0)

      setUniformRegister(registerFile, register = 1, value = 4)

      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      startRegisterFileDriver(dut, registerFile)
      startSharedMemoryModel(dut, sharedWords)
      startTensorMemoryModel(dut, tensorWords)

      issueTcgen05Op(dut, opcode = Opcode.TCGEN05_LD_32X32B_X2, rdBase = 2, rs0Base = 1)
      val beats = collectEvents(dut)

      beats.length shouldBe 1
      beats.head.error shouldBe true
      beats.head.faultCode shouldBe FaultCode.MisalignedLoadStore
      beats.head.writeEnable shouldBe false
    }
  }
}
