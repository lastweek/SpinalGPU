ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.16"
ThisBuild / organization := "spinalgpu"

val spinalVersion = "1.14.1"
val scalaTestVersion = "3.2.18"

val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin =
  compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion % Test
val buildKernelCorpus = taskKey[Unit]("Compile the PTX kernel corpus into generated/kernels")

lazy val root = (project in file("."))
  .settings(
    name := "SpinalGPU",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies ++= Seq(
      spinalCore,
      spinalLib,
      spinalIdslPlugin,
      scalaTest
    ),
    Compile / run / mainClass := Some("spinalgpu.GenerateGpuTop"),
    Test / parallelExecution := false,
    buildKernelCorpus := Def.taskDyn {
      (Compile / runMain).toTask(" spinalgpu.toolchain.BuildKernelCorpus")
    }.value,
    Test / test := ((Test / test) dependsOn buildKernelCorpus).value
  )

fork := true
