package spinalgpu

import spinal.lib.bus.amba4.axi.sim.AxiMemorySim
import spinalgpu.toolchain.KernelArtifact
import spinalgpu.toolchain.KernelCatalog

object KernelManifest {
  sealed trait HarnessTarget
  object HarnessTarget {
    case object GpuTop extends HarnessTarget
    case object StreamingMultiprocessor extends HarnessTarget
  }

  sealed trait CompletionExpectation
  object CompletionExpectation {
    type MemoryHook = (AxiMemorySim, Int) => Unit

    final case class Success(assertResults: MemoryHook = (_, _) => ()) extends CompletionExpectation
    final case class Fault(code: Int, faultPc: Option[Long] = None) extends CompletionExpectation
  }

  final case class KernelCase(
      artifact: KernelArtifact,
      entryPc: Long,
      launch: ExecutionTestUtils.HostLaunch,
      preload: CompletionExpectation.MemoryHook = (_, _) => (),
      expectation: CompletionExpectation,
      harnessTargets: Seq[HarnessTarget]
  ) {
    val name: String = artifact.name
    val relativeSourcePath: String = artifact.relativeSourcePath
    val sourcePath = artifact.sourcePath
    val binaryPath = artifact.binaryPath
  }

  private def assertWord(memory: AxiMemorySim, address: Long, expected: BigInt, byteCount: Int): Unit = {
    val actual = ExecutionTestUtils.readWord(memory, address, byteCount)
    assert(actual == expected, s"expected 0x${expected.toString(16)} at 0x${address.toHexString}, got 0x${actual.toString(16)}")
  }

  private def assertSequence(memory: AxiMemorySim, base: Long, expected: Seq[Int], byteCount: Int): Unit = {
    expected.zipWithIndex.foreach { case (value, index) =>
      assertWord(memory, base + (index.toLong * byteCount), BigInt(value), byteCount)
    }
  }

  import CompletionExpectation._
  import HarnessTarget._

  val addStoreExit: KernelCase = KernelCase(
    artifact = KernelCatalog.addStoreExit,
    entryPc = 0x100,
    launch = ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 1, argBase = 0x200),
    preload = (memory, byteCount) => {
      ExecutionTestUtils.writeArgBuffer(memory, 0x200, Seq(0x300), byteCount)
    },
    expectation = Success { (memory, byteCount) =>
      assertWord(memory, 0x300, BigInt(18), byteCount)
    },
    harnessTargets = Seq(GpuTop, StreamingMultiprocessor)
  )

  val threadIdStore: KernelCase = KernelCase(
    artifact = KernelCatalog.threadIdStore,
    entryPc = 0x100,
    launch = ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 40, argBase = 0x200),
    preload = (memory, byteCount) => {
      ExecutionTestUtils.writeArgBuffer(memory, 0x200, Seq(0x400), byteCount)
    },
    expectation = Success { (memory, byteCount) =>
      assertSequence(memory, 0x400, 0 until 40, byteCount)
    },
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val uniformLoop: KernelCase = KernelCase(
    artifact = KernelCatalog.uniformLoop,
    entryPc = 0x100,
    launch = ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 1, argBase = 0x200),
    preload = (memory, byteCount) => {
      ExecutionTestUtils.writeArgBuffer(memory, 0x200, Seq(0x300), byteCount)
    },
    expectation = Success { (memory, byteCount) =>
      assertWord(memory, 0x300, BigInt(0), byteCount)
    },
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val sharedRoundtrip: KernelCase = KernelCase(
    artifact = KernelCatalog.sharedRoundtrip,
    entryPc = 0x100,
    launch = ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 8, argBase = 0x200, sharedBytes = 256),
    preload = (memory, byteCount) => {
      ExecutionTestUtils.writeArgBuffer(memory, 0x200, Seq(0x400), byteCount)
    },
    expectation = Success { (memory, byteCount) =>
      assertSequence(memory, 0x400, 0 until 8, byteCount)
    },
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val vectorAdd1Warp: KernelCase = KernelCase(
    artifact = KernelCatalog.vectorAdd1Warp,
    entryPc = 0x100,
    launch = ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 8, argBase = 0x240),
    preload = (memory, byteCount) => {
      ExecutionTestUtils.writeDataWords(memory, 0x500, 0 until 8, byteCount)
      ExecutionTestUtils.writeDataWords(memory, 0x600, (0 until 8).map(_ * 10), byteCount)
      ExecutionTestUtils.writeArgBuffer(memory, 0x240, Seq(0x500, 0x600, 0x700), byteCount)
    },
    expectation = Success { (memory, byteCount) =>
      assertSequence(memory, 0x700, (0 until 8).map(index => index + (index * 10)), byteCount)
    },
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val nonUniformBranch: KernelCase = KernelCase(
    artifact = KernelCatalog.nonUniformBranch,
    entryPc = 0x100,
    launch = ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 8),
    expectation = Fault(FaultCode.NonUniformBranch),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val misalignedStore: KernelCase = KernelCase(
    artifact = KernelCatalog.misalignedStore,
    entryPc = 0x100,
    launch = ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 1, argBase = 0x280),
    preload = (memory, byteCount) => {
      ExecutionTestUtils.writeArgBuffer(memory, 0x280, Seq(0x300), byteCount)
    },
    expectation = Fault(FaultCode.MisalignedLoadStore),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val trap: KernelCase = KernelCase(
    artifact = KernelCatalog.trap,
    entryPc = 0x100,
    launch = ExecutionTestUtils.HostLaunch(entryPc = 0x100, blockDimX = 1),
    expectation = Fault(FaultCode.Trap),
    harnessTargets = Seq(StreamingMultiprocessor)
  )

  val allCases: Seq[KernelCase] = Seq(
    addStoreExit,
    threadIdStore,
    uniformLoop,
    sharedRoundtrip,
    vectorAdd1Warp,
    nonUniformBranch,
    misalignedStore,
    trap
  )

  val gpuTopCases: Seq[KernelCase] = allCases.filter(_.harnessTargets.contains(GpuTop))
  val streamingMultiprocessorCases: Seq[KernelCase] = allCases.filter(_.harnessTargets.contains(StreamingMultiprocessor))
}
