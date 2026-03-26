package spinalgpu.toolchain

import java.nio.file.Path

final case class KernelArtifact(name: String, relativeSourcePath: String) {
  val sourcePath: Path = KernelCatalog.sourceRoot.resolve(relativeSourcePath).normalize()
  val relativeBinaryPath: String = relativeSourcePath.stripSuffix(".gpuasm") + ".bin"
  val binaryPath: Path = KernelCatalog.outputRoot.resolve(relativeBinaryPath).normalize()
}

object KernelCatalog {
  val sourceRoot: Path = Path.of("kernels")
  val outputRoot: Path = Path.of("generated", "kernels")

  val addStoreExit: KernelArtifact = KernelArtifact("add_store_exit", "smoke/add_store_exit.gpuasm")
  val threadIdStore: KernelArtifact = KernelArtifact("thread_id_store", "smoke/thread_id_store.gpuasm")
  val uniformLoop: KernelArtifact = KernelArtifact("uniform_loop", "smoke/uniform_loop.gpuasm")
  val sharedRoundtrip: KernelArtifact = KernelArtifact("shared_roundtrip", "smoke/shared_roundtrip.gpuasm")
  val vectorAdd1Warp: KernelArtifact = KernelArtifact("vector_add_1warp", "smoke/vector_add_1warp.gpuasm")
  val nonUniformBranch: KernelArtifact = KernelArtifact("non_uniform_branch", "fault/non_uniform_branch.gpuasm")
  val misalignedStore: KernelArtifact = KernelArtifact("misaligned_store", "fault/misaligned_store.gpuasm")
  val trap: KernelArtifact = KernelArtifact("trap", "fault/trap.gpuasm")

  val all: Seq[KernelArtifact] = Seq(
    addStoreExit,
    threadIdStore,
    uniformLoop,
    sharedRoundtrip,
    vectorAdd1Warp,
    nonUniformBranch,
    misalignedStore,
    trap
  )
}
