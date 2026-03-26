package spinalgpu.toolchain

import java.nio.file.Path

sealed trait KernelFeature {
  def id: String
}

object KernelFeature {
  case object Arithmetic extends KernelFeature {
    override val id: String = "arithmetic"
  }

  case object Control extends KernelFeature {
    override val id: String = "control"
  }

  case object GlobalMemory extends KernelFeature {
    override val id: String = "global_memory"
  }

  case object SharedMemory extends KernelFeature {
    override val id: String = "shared_memory"
  }

  case object SpecialRegisters extends KernelFeature {
    override val id: String = "special_registers"
  }
}

sealed trait KernelLevel {
  def id: String
}

object KernelLevel {
  case object Intro extends KernelLevel {
    override val id: String = "intro"
  }

  case object Core extends KernelLevel {
    override val id: String = "core"
  }

  case object Fault extends KernelLevel {
    override val id: String = "fault"
  }
}

final case class KernelArtifact(
    name: String,
    relativeSourcePath: String,
    primaryFeature: KernelFeature,
    secondaryFeatures: Seq[KernelFeature] = Seq.empty,
    teachingLevel: KernelLevel,
    description: String
) {
  require(description.trim.nonEmpty, s"kernel '$name' must have a non-empty description")
  require(secondaryFeatures.distinct.size == secondaryFeatures.size, s"kernel '$name' has duplicate secondary features")
  require(!secondaryFeatures.contains(primaryFeature), s"kernel '$name' lists its primary feature as secondary")

  val sourcePath: Path = KernelCatalog.sourceRoot.resolve(relativeSourcePath).normalize()
  val relativeBinaryPath: String = relativeSourcePath.stripSuffix(".ptx") + ".bin"
  val binaryPath: Path = KernelCatalog.outputRoot.resolve(relativeBinaryPath).normalize()
}

object KernelCatalog {
  val sourceRoot: Path = Path.of("kernels")
  val outputRoot: Path = Path.of("generated", "kernels")

  val addStoreExit: KernelArtifact = KernelArtifact(
    name = "add_store_exit",
    relativeSourcePath = "arithmetic/add_store_exit.ptx",
    primaryFeature = KernelFeature.Arithmetic,
    secondaryFeatures = Seq(KernelFeature.GlobalMemory),
    teachingLevel = KernelLevel.Intro,
    description = "Write a constant arithmetic result to global memory."
  )

  val threadIdStore: KernelArtifact = KernelArtifact(
    name = "thread_id_store",
    relativeSourcePath = "special_registers/thread_id_store.ptx",
    primaryFeature = KernelFeature.SpecialRegisters,
    secondaryFeatures = Seq(KernelFeature.GlobalMemory),
    teachingLevel = KernelLevel.Intro,
    description = "Store each thread's %tid.x to global memory."
  )

  val uniformLoop: KernelArtifact = KernelArtifact(
    name = "uniform_loop",
    relativeSourcePath = "control/uniform_loop.ptx",
    primaryFeature = KernelFeature.Control,
    secondaryFeatures = Seq(KernelFeature.Arithmetic, KernelFeature.GlobalMemory),
    teachingLevel = KernelLevel.Core,
    description = "Run a uniform countdown loop and store the terminal value."
  )

  val sharedRoundtrip: KernelArtifact = KernelArtifact(
    name = "shared_roundtrip",
    relativeSourcePath = "shared_memory/shared_roundtrip.ptx",
    primaryFeature = KernelFeature.SharedMemory,
    secondaryFeatures = Seq(KernelFeature.SpecialRegisters, KernelFeature.GlobalMemory),
    teachingLevel = KernelLevel.Core,
    description = "Round-trip %tid.x through shared memory and write it to global memory."
  )

  val vectorAdd1Warp: KernelArtifact = KernelArtifact(
    name = "vector_add_1warp",
    relativeSourcePath = "global_memory/vector_add_1warp.ptx",
    primaryFeature = KernelFeature.GlobalMemory,
    secondaryFeatures = Seq(KernelFeature.Arithmetic, KernelFeature.SpecialRegisters),
    teachingLevel = KernelLevel.Core,
    description = "Compute C[i] = A[i] + B[i] for one warp."
  )

  val registerStress: KernelArtifact = KernelArtifact(
    name = "register_stress",
    relativeSourcePath = "arithmetic/register_stress.ptx",
    primaryFeature = KernelFeature.Arithmetic,
    secondaryFeatures = Seq(KernelFeature.GlobalMemory),
    teachingLevel = KernelLevel.Core,
    description = "Store one value from every allocatable PTX register to global memory."
  )

  val nonUniformBranch: KernelArtifact = KernelArtifact(
    name = "non_uniform_branch",
    relativeSourcePath = "control/non_uniform_branch.ptx",
    primaryFeature = KernelFeature.Control,
    secondaryFeatures = Seq(KernelFeature.SpecialRegisters),
    teachingLevel = KernelLevel.Fault,
    description = "Branch on lane-varying state to provoke a non-uniform branch fault."
  )

  val misalignedStore: KernelArtifact = KernelArtifact(
    name = "misaligned_store",
    relativeSourcePath = "global_memory/misaligned_store.ptx",
    primaryFeature = KernelFeature.GlobalMemory,
    secondaryFeatures = Seq.empty,
    teachingLevel = KernelLevel.Fault,
    description = "Store to a misaligned global address to provoke a load/store fault."
  )

  val trap: KernelArtifact = KernelArtifact(
    name = "trap",
    relativeSourcePath = "control/trap.ptx",
    primaryFeature = KernelFeature.Control,
    secondaryFeatures = Seq.empty,
    teachingLevel = KernelLevel.Fault,
    description = "Raise an explicit trap."
  )

  val all: Seq[KernelArtifact] = Seq(
    addStoreExit,
    threadIdStore,
    uniformLoop,
    sharedRoundtrip,
    vectorAdd1Warp,
    registerStress,
    nonUniformBranch,
    misalignedStore,
    trap
  )
}
