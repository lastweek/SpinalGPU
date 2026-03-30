package spinalgpu

import scala.language.reflectiveCalls
import scala.collection.mutable.ArrayBuffer
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._

class TensorCoreBlockSpec extends AnyFunSuite with Matchers {
  private val config = SmConfig(
    warpSize = 32,
    subSmCount = 1,
    residentWarpsPerSubSm = 1,
    subSmIssueWidth = 32,
    sharedMemoryBytes = 1024
  )

  private lazy val compiled: SimCompiled[TensorCoreBlock] = SimConfig.withVerilator.compile(new TensorCoreBlock(config))

  private final case class TensorBeat(
      completed: Boolean,
      writeEnable: Boolean,
      writeOffset: Int,
      error: Boolean,
      faultCode: Int,
      result: Seq[BigInt]
  )

  private def u32(value: Int): BigInt = BigInt(value.toLong & 0xFFFFFFFFL)

  private def setHalf(word: Int, halfIndex: Int, value: Int): Int =
    if (halfIndex == 0) (word & 0xFFFF0000) | (value & 0xFFFF)
    else (word & 0x0000FFFF) | ((value & 0xFFFF) << 16)

  private def packRowMajorTile(tile: Array[Array[Int]]): Array[Int] = {
    val words = Array.fill(config.warpSize)(0)
    for (row <- 0 until TensorCoreLayouts.TileRows) {
      for (col <- 0 until TensorCoreLayouts.TileCols) {
        val lane = TensorCoreLayouts.rowMajorLane(row, col)
        val half = TensorCoreLayouts.rowMajorHalf(col)
        words(lane) = setHalf(words(lane), half, tile(row)(col))
      }
    }
    words
  }

  private def packTransposedBTile(tile: Array[Array[Int]]): Array[Int] = {
    val words = Array.fill(config.warpSize)(0)
    for (row <- 0 until TensorCoreLayouts.TileRows) {
      for (col <- 0 until TensorCoreLayouts.TileCols) {
        val lane = (col * 4) + (row & 0x3)
        val half = (row >> 2) & 0x1
        words(lane) = setHalf(words(lane), half, tile(row)(col))
      }
    }
    words
  }

  private def packARegisters(tile: Array[Array[Int]]): Array[Array[Int]] = {
    val banks = Array.fill(4, config.warpSize)(0)
    for (row <- 0 until TensorCoreLayouts.M) {
      for (col <- 0 until TensorCoreLayouts.K) {
        val bank = TensorCoreLayouts.aRegister(row, col)
        val lane = TensorCoreLayouts.aLane(row, col)
        val half = TensorCoreLayouts.aHalf(col)
        banks(bank)(lane) = setHalf(banks(bank)(lane), half, tile(row)(col))
      }
    }
    banks
  }

  private def packBRegisters(tile: Array[Array[Int]]): Array[Array[Int]] = {
    val banks = Array.fill(2, config.warpSize)(0)
    for (row <- 0 until TensorCoreLayouts.K) {
      for (col <- 0 until TensorCoreLayouts.N) {
        val bank = TensorCoreLayouts.bRegister(row)
        val lane = TensorCoreLayouts.bLane(row, col)
        val half = TensorCoreLayouts.bHalf(row)
        banks(bank)(lane) = setHalf(banks(bank)(lane), half, tile(row)(col))
      }
    }
    banks
  }

  private def packCdRegisters(tile: Array[Array[Int]]): Array[Array[Int]] = {
    val banks = Array.fill(2, config.warpSize)(0)
    for (row <- 0 until TensorCoreLayouts.M) {
      for (col <- 0 until TensorCoreLayouts.N) {
        val bank = TensorCoreLayouts.cdRegister(row)
        val lane = TensorCoreLayouts.cdLane(row, col)
        val half = TensorCoreLayouts.cdHalf(col)
        banks(bank)(lane) = setHalf(banks(bank)(lane), half, tile(row)(col))
      }
    }
    banks
  }

  private def setRegister(registerFile: Array[Array[Int]], register: Int, values: Seq[Int]): Unit =
    values.zipWithIndex.foreach { case (value, lane) => registerFile(register)(lane) = value }

  private def initDefaults(dut: TensorCoreBlock): Unit = {
    dut.io.issue.valid #= false
    dut.io.issue.payload.warpId #= 0
    dut.io.issue.payload.opcode #= 0
    dut.io.issue.payload.activeMask #= 0
    dut.io.issue.payload.rdBase #= 0
    dut.io.issue.payload.rs0Base #= 0
    dut.io.issue.payload.rs1Base #= 0
    dut.io.issue.payload.rs2Base #= 0
    dut.io.response.ready #= false
    dut.io.sharedMemReq.ready #= false
    dut.io.sharedMemRsp.valid #= false
    dut.io.sharedMemRsp.payload.warpId #= 0
    dut.io.sharedMemRsp.payload.completed #= true
    dut.io.sharedMemRsp.payload.error #= false
    dut.io.sharedMemRsp.payload.readData #= 0
    dut.io.sharedMemRsp.payload.bankIndex #= 0
    for (lane <- 0 until config.warpSize) {
      dut.io.readDataA(lane) #= 0
      dut.io.readDataB(lane) #= 0
      dut.io.readDataC(lane) #= 0
    }
  }

  private def waitUntil(timeoutCycles: Int = 256)(condition: => Boolean)(implicit dut: TensorCoreBlock): Unit = {
    var remaining = timeoutCycles
    while (!condition && remaining > 0) {
      dut.clockDomain.waitSampling()
      remaining -= 1
    }
    condition shouldBe true
  }

  private def startRegisterFileDriver(dut: TensorCoreBlock, registerFile: Array[Array[Int]]): Unit = {
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

  private def startSharedMemoryModel(dut: TensorCoreBlock, sharedWords: Array[Int]): Unit = {
    dut.io.sharedMemReq.ready #= true
    dut.io.sharedMemRsp.valid #= false

    fork {
      var pendingResponse: Option[(Int, Int)] = None

      while (true) {
        val acceptingRequest = pendingResponse.isEmpty
        dut.io.sharedMemReq.ready #= acceptingRequest
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

        if (acceptingRequest && dut.io.sharedMemReq.valid.toBoolean && dut.io.sharedMemReq.ready.toBoolean) {
          val wordAddress = dut.io.sharedMemReq.payload.address.toInt
          val warpId = dut.io.sharedMemReq.payload.warpId.toInt
          if (dut.io.sharedMemReq.payload.write.toBoolean) {
            sharedWords(wordAddress) = dut.io.sharedMemReq.payload.writeData.toBigInt.toInt
            pendingResponse = Some(warpId -> 0)
          } else {
            pendingResponse = Some(warpId -> sharedWords(wordAddress))
          }
        }
      }
    }
  }

  private def issueTensorOp(
      dut: TensorCoreBlock,
      opcode: Int,
      rdBase: Int = 0,
      rs0Base: Int = 0,
      rs1Base: Int = 0,
      rs2Base: Int = 0,
      activeMask: Long = 0xFFFFFFFFL
  ): Unit = {
    dut.io.issue.valid #= true
    dut.io.issue.payload.warpId #= 0
    dut.io.issue.payload.opcode #= opcode
    dut.io.issue.payload.activeMask #= BigInt(activeMask & 0xFFFFFFFFL)
    dut.io.issue.payload.rdBase #= rdBase
    dut.io.issue.payload.rs0Base #= rs0Base
    dut.io.issue.payload.rs1Base #= rs1Base
    dut.io.issue.payload.rs2Base #= rs2Base

    while (!dut.io.issue.ready.toBoolean) {
      dut.clockDomain.waitSampling()
    }
    dut.clockDomain.waitSampling()
    dut.io.issue.valid #= false
  }

  private def collectResponseBeats(dut: TensorCoreBlock, timeoutCycles: Int = 2048): Seq[TensorBeat] = {
    val beats = ArrayBuffer.empty[TensorBeat]
    dut.io.response.ready #= true
    waitUntil(timeoutCycles) { dut.io.response.valid.toBoolean }(dut)

    var done = false
    while (!done) {
      beats += TensorBeat(
        completed = dut.io.response.payload.completed.toBoolean,
        writeEnable = dut.io.response.payload.writeEnable.toBoolean,
        writeOffset = dut.io.response.payload.writeOffset.toInt,
        error = dut.io.response.payload.error.toBoolean,
        faultCode = dut.io.response.payload.faultCode.toInt,
        result = (0 until config.warpSize).map(lane => dut.io.response.payload.result(lane).toBigInt)
      )
      done = beats.last.error || beats.last.completed
      dut.clockDomain.waitSampling()
      if (!done) {
        waitUntil(timeoutCycles) { dut.io.response.valid.toBoolean }(dut)
      }
    }

    dut.io.response.ready #= false
    beats.toSeq
  }

  test("ldmatrix.x4 loads row-major shared tiles into four tensor register beats") {
    compiled.doSim { dut =>
      implicit val implicitDut: TensorCoreBlock = dut
      val registerFile = Array.fill(config.registerCount, config.warpSize)(0)
      val sharedWords = Array.fill(config.sharedWordCount)(0)
      val tileBases = Seq(0, 128, 256, 384)
      val expectedBanks = Array.fill(4, config.warpSize)(0)

      for (matrix <- 0 until 4) {
        val tile = Array.tabulate(TensorCoreLayouts.TileRows, TensorCoreLayouts.TileCols) { (row, col) =>
          (matrix << 12) | (row << 6) | col
        }
        val packed = packRowMajorTile(tile)
        expectedBanks(matrix) = packed
        for (lane <- 0 until config.warpSize) {
          sharedWords((tileBases(matrix) / 4) + lane) = packed(lane)
        }
        for (row <- 0 until TensorCoreLayouts.TileRows) {
          registerFile(1)(matrix * 8 + row) = tileBases(matrix) + (row * 16)
        }
      }

      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      startRegisterFileDriver(dut, registerFile)
      startSharedMemoryModel(dut, sharedWords)

      issueTensorOp(dut, opcode = Opcode.LDMATRIX_X4, rdBase = 4, rs0Base = 1)
      val beats = collectResponseBeats(dut)

      beats.length shouldBe 4
      beats.map(_.writeOffset) shouldBe Seq(0, 1, 2, 3)
      beats.foreach(_.error shouldBe false)
      beats.foreach(_.writeEnable shouldBe true)
      beats.last.completed shouldBe true

      for (bank <- 0 until 4) {
        beats(bank).result shouldBe expectedBanks(bank).map(u32).toSeq
      }
    }
  }

  test("ldmatrix.x2.trans packs B fragments from row-major shared tiles") {
    compiled.doSim { dut =>
      implicit val implicitDut: TensorCoreBlock = dut
      val registerFile = Array.fill(config.registerCount, config.warpSize)(0)
      val sharedWords = Array.fill(config.sharedWordCount)(0)
      val tileBases = Seq(0, 128)
      val expectedBanks = Array.fill(2, config.warpSize)(0)

      for (matrix <- 0 until 2) {
        val tile = Array.tabulate(TensorCoreLayouts.TileRows, TensorCoreLayouts.TileCols) { (row, col) =>
          (matrix << 12) | (row << 6) | (col << 1)
        }
        val packedRowMajor = packRowMajorTile(tile)
        expectedBanks(matrix) = packTransposedBTile(tile)
        for (lane <- 0 until config.warpSize) {
          sharedWords((tileBases(matrix) / 4) + lane) = packedRowMajor(lane)
        }
        for (row <- 0 until TensorCoreLayouts.TileRows) {
          registerFile(2)(matrix * 8 + row) = tileBases(matrix) + (row * 16)
        }
      }

      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      startRegisterFileDriver(dut, registerFile)
      startSharedMemoryModel(dut, sharedWords)

      issueTensorOp(dut, opcode = Opcode.LDMATRIX_X2_TRANS, rdBase = 8, rs0Base = 2)
      val beats = collectResponseBeats(dut)

      beats.length shouldBe 2
      beats.map(_.writeOffset) shouldBe Seq(0, 1)
      beats.foreach(_.error shouldBe false)
      beats.foreach(_.writeEnable shouldBe true)
      beats.last.completed shouldBe true

      for (bank <- 0 until 2) {
        beats(bank).result shouldBe expectedBanks(bank).map(u32).toSeq
      }
    }
  }

  test("mma.sync.m16n8k16.f16.f16.f16.f16 accumulates the exact fragment mapping") {
    compiled.doSim { dut =>
      implicit val implicitDut: TensorCoreBlock = dut
      val registerFile = Array.fill(config.registerCount, config.warpSize)(0)
      val sharedWords = Array.fill(config.sharedWordCount)(0)

      val aMatrix = Array.tabulate(TensorCoreLayouts.M, TensorCoreLayouts.K) { (row, col) =>
        LowPrecisionCodec.floatToHalfBits(((row % 4) + (col % 3) + 1).toFloat * 0.25f)
      }
      val bMatrix = Array.tabulate(TensorCoreLayouts.K, TensorCoreLayouts.N) { (row, col) =>
        LowPrecisionCodec.floatToHalfBits(((row % 5) - (col % 2) + 2).toFloat * 0.125f)
      }
      val cMatrix = Array.tabulate(TensorCoreLayouts.M, TensorCoreLayouts.N) { (row, col) =>
        LowPrecisionCodec.floatToHalfBits(((row - col).toFloat) * 0.0625f)
      }

      val packedA = packARegisters(aMatrix)
      val packedB = packBRegisters(bMatrix)
      val packedC = packCdRegisters(cMatrix)

      for (bank <- packedA.indices) {
        setRegister(registerFile, register = 4 + bank, values = packedA(bank))
      }
      for (bank <- packedB.indices) {
        setRegister(registerFile, register = 8 + bank, values = packedB(bank))
      }
      for (bank <- packedC.indices) {
        setRegister(registerFile, register = 10 + bank, values = packedC(bank))
      }

      val expectedMatrix = Array.tabulate(TensorCoreLayouts.M, TensorCoreLayouts.N) { (row, col) =>
        var accBits = cMatrix(row)(col)
        for (k <- 0 until TensorCoreLayouts.K) {
          val a = LowPrecisionCodec.halfBitsToFloat(aMatrix(row)(k))
          val b = LowPrecisionCodec.halfBitsToFloat(bMatrix(k)(col))
          val c = LowPrecisionCodec.halfBitsToFloat(accBits)
          accBits = LowPrecisionCodec.floatToHalfBits((a * b) + c)
        }
        accBits
      }
      val expectedBanks = packCdRegisters(expectedMatrix)

      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      startRegisterFileDriver(dut, registerFile)
      startSharedMemoryModel(dut, sharedWords)

      issueTensorOp(dut, opcode = Opcode.MMA_SYNC_F16_F16_F16_F16, rdBase = 10, rs0Base = 4, rs1Base = 8, rs2Base = 10)
      val beats = collectResponseBeats(dut, timeoutCycles = 4096)

      beats.length shouldBe 2
      beats.map(_.writeOffset) shouldBe Seq(0, 1)
      beats.foreach(_.error shouldBe false)
      beats.foreach(_.writeEnable shouldBe true)
      beats.last.completed shouldBe true
      beats(0).result shouldBe expectedBanks(0).map(u32).toSeq
      beats(1).result shouldBe expectedBanks(1).map(u32).toSeq
    }
  }

  test("stmatrix.x2 writes row-major tensor register tiles back to shared memory") {
    compiled.doSim { dut =>
      implicit val implicitDut: TensorCoreBlock = dut
      val registerFile = Array.fill(config.registerCount, config.warpSize)(0)
      val sharedWords = Array.fill(config.sharedWordCount)(0)
      val tileBases = Seq(0, 128)

      for (matrix <- 0 until 2) {
        val tile = Array.tabulate(TensorCoreLayouts.TileRows, TensorCoreLayouts.TileCols) { (row, col) =>
          (matrix << 12) | (row << 6) | (col << 1) | 1
        }
        val packed = packRowMajorTile(tile)
        setRegister(registerFile, register = 12 + matrix, values = packed)
        for (row <- 0 until TensorCoreLayouts.TileRows) {
          registerFile(3)(matrix * 8 + row) = tileBases(matrix) + (row * 16)
        }
      }

      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      startRegisterFileDriver(dut, registerFile)
      startSharedMemoryModel(dut, sharedWords)

      issueTensorOp(dut, opcode = Opcode.STMATRIX_X2, rs0Base = 3, rs1Base = 12)
      val beats = collectResponseBeats(dut)

      beats.length shouldBe 1
      beats.head.error shouldBe false
      beats.head.writeEnable shouldBe false
      beats.head.completed shouldBe true

      for (matrix <- 0 until 2) {
        for (lane <- 0 until config.warpSize) {
          sharedWords((tileBases(matrix) / 4) + lane) shouldBe registerFile(12 + matrix)(lane)
        }
      }
    }
  }

  test("tensor ops fault on partial warp participation") {
    compiled.doSim { dut =>
      implicit val implicitDut: TensorCoreBlock = dut
      val registerFile = Array.fill(config.registerCount, config.warpSize)(0)
      val sharedWords = Array.fill(config.sharedWordCount)(0)

      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      startRegisterFileDriver(dut, registerFile)
      startSharedMemoryModel(dut, sharedWords)

      issueTensorOp(dut, opcode = Opcode.MMA_SYNC_F16_F16_F16_F16, rdBase = 10, rs0Base = 4, rs1Base = 8, rs2Base = 10, activeMask = 0x00FFFFFFL)
      val beats = collectResponseBeats(dut)

      beats.length shouldBe 1
      beats.head.error shouldBe true
      beats.head.faultCode shouldBe FaultCode.TensorProtocol
      beats.head.writeEnable shouldBe false
      beats.head.completed shouldBe true
    }
  }

  test("tensor shared-memory loads fault on misaligned row addresses") {
    compiled.doSim { dut =>
      implicit val implicitDut: TensorCoreBlock = dut
      val registerFile = Array.fill(config.registerCount, config.warpSize)(0)
      val sharedWords = Array.fill(config.sharedWordCount)(0)

      for (lane <- 0 until config.warpSize) {
        registerFile(1)(lane) = lane * 16
      }
      registerFile(1)(0) = 4

      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      startRegisterFileDriver(dut, registerFile)
      startSharedMemoryModel(dut, sharedWords)

      issueTensorOp(dut, opcode = Opcode.LDMATRIX_X4, rdBase = 4, rs0Base = 1)
      val beats = collectResponseBeats(dut)

      beats.length shouldBe 1
      beats.head.error shouldBe true
      beats.head.faultCode shouldBe FaultCode.MisalignedLoadStore
      beats.head.writeEnable shouldBe false
      beats.head.completed shouldBe true
    }
  }
}
