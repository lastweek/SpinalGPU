package spinalgpu.autoresearch

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.sys.process._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MatmulAutoResearchScriptsSpec extends AnyFunSuite with Matchers {
  private val repoRoot: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath.normalize()
  private val autoResearchDir: Path = repoRoot.resolve("spinalgpu").resolve("autoresearch")
  private val launcher: Path = autoResearchDir.resolve("run-matmul-autoresearch.sh")
  private val plotter: Path = autoResearchDir.resolve("plot-matmul-autoresearch.py")
  private val renderer: Path = autoResearchDir.resolve("render-matmul-autoresearch-summary.py")

  private def shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

  private def runCommand(command: Seq[String], env: Seq[(String, String)] = Seq.empty): Int =
    Process(command, repoRoot.toFile, env: _*).!

  test("summary and plot scripts render artifacts from JSONL round history") {
    val logRoot = Files.createTempDirectory("spinalgpu-autoresearch-logs")
    val sessionId = "sample-session"
    val sessionDir = logRoot.resolve("sessions").resolve(sessionId)
    Files.createDirectories(sessionDir)

    val roundsPath = sessionDir.resolve("rounds.jsonl")
    val roundsJsonl =
      """{"round":0,"timestamp":"2026-03-31T00:00:00Z","shape":"32x32x32","git_head":"abc","changed_files":[],"hypothesis":"baseline","decision":"accepted","cuda_core_cycles":1600,"tcgen05_cycles":720,"tcgen05_state_cycles":{"collect_cycles":10,"shared_cycles":20,"tensor_cycles":30,"compute_cycles":40,"pack_cycles":5,"respond_cycles":3,"total_cycles":108},"tests_passed":true,"correctness_passed":true,"fault":false,"next_focus":"tcgen05_compute","notes":""}
{"round":1,"timestamp":"2026-03-31T00:10:00Z","shape":"32x32x32","git_head":"def","changed_files":["src/main/scala/spinalgpu/Tcgen05Block.scala"],"hypothesis":"trim tensor drain","decision":"accepted","cuda_core_cycles":1550,"tcgen05_cycles":640,"tcgen05_state_cycles":{"collect_cycles":10,"shared_cycles":18,"tensor_cycles":26,"compute_cycles":32,"pack_cycles":5,"respond_cycles":3,"total_cycles":94},"tests_passed":true,"correctness_passed":true,"fault":false,"next_focus":"tcgen05_compute","notes":"improved tensor phase"}
{"round":2,"timestamp":"2026-03-31T00:20:00Z","shape":"32x32x32","git_head":"ghi","changed_files":["src/main/scala/spinalgpu/Tcgen05Block.scala"],"hypothesis":"bad experiment","decision":"rejected","cuda_core_cycles":1525,"tcgen05_cycles":650,"tcgen05_state_cycles":{"collect_cycles":10,"shared_cycles":18,"tensor_cycles":26,"compute_cycles":33,"pack_cycles":5,"respond_cycles":3,"total_cycles":95},"tests_passed":true,"correctness_passed":true,"fault":false,"next_focus":"tcgen05_compute","notes":"not faster than baseline"}
"""
    Files.writeString(roundsPath, roundsJsonl, StandardCharsets.UTF_8)

    val env = Seq("SPINALGPU_AUTORESEARCH_LOG_ROOT" -> logRoot.toString)

    runCommand(Seq("python3", renderer.toString, "--session", sessionId), env) shouldBe 0
    runCommand(Seq("python3", plotter.toString, "--session", sessionId), env) shouldBe 0

    val summary = Files.readString(sessionDir.resolve("summary.md"))
    summary should include("Current Accepted Baseline")
    summary should include("round 1 (640 cycles)")
    summary should include("Best CUDA core: round 1 (1550 cycles)")
    summary should include("tcgen05_compute")

    val csv = Files.readString(sessionDir.resolve("perf_evolution.csv"))
    csv should include("round,best_cuda_core_cycles,best_tcgen05_cycles")
    csv should include("0,1600,720")
    csv should include("1,1550,640")
    csv should include("2,1550,640")

    val png = sessionDir.resolve("perf_evolution.png")
    Files.exists(png) shouldBe true
    Files.size(png) should be > 128L
    Files.readAllBytes(png).take(8).toSeq shouldBe Seq[Byte](0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte, '\r'.toByte, '\n'.toByte, 0x1A.toByte, '\n'.toByte)
  }

  test("launcher dry-run creates baseline logs and resume does not duplicate round zero") {
    val logRoot = Files.createTempDirectory("spinalgpu-autoresearch-dry-run")
    val sessionId = "dry-run-session"
    val helperScript = logRoot.resolve("stub-round.sh")
    Files.writeString(
      helperScript,
      """#!/usr/bin/env bash
set -euo pipefail
output="$1"
mkdir -p "$(dirname "$output")"
cat >"$output" <<'JSON'
{"shape":"32x32x32","cuda_core_cycles":1440,"tcgen05_cycles":580,"tcgen05_state_cycles":{"collect_cycles":10,"shared_cycles":22,"tensor_cycles":28,"compute_cycles":44,"pack_cycles":7,"respond_cycles":3,"total_cycles":114},"correctness_passed":true,"fault":false,"suggested_next_focus":"tcgen05_compute"}
JSON
""",
      StandardCharsets.UTF_8
    )

    val roundCommand = s"bash ${shellQuote(helperScript.toString)} \"$$BENCHMARK_JSON\""
    val env = Seq(
      "SPINALGPU_AUTORESEARCH_LOG_ROOT" -> logRoot.toString,
      "SPINALGPU_AUTORESEARCH_ROUND_CMD" -> roundCommand
    )

    runCommand(Seq("bash", launcher.toString, "--rounds", "1", "--session", sessionId, "--dry-run"), env) shouldBe 0

    val sessionDir = logRoot.resolve("sessions").resolve(sessionId)
    val roundsPath = sessionDir.resolve("rounds.jsonl")
    Files.exists(roundsPath) shouldBe true
    Files.readAllLines(roundsPath).size() shouldBe 1

    val baselineLine = Files.readAllLines(roundsPath).get(0)
    baselineLine should include(""""round": 0""")
    baselineLine should include(""""decision": "accepted"""")
    baselineLine should include(""""tcgen05_cycles": 580""")

    Files.exists(sessionDir.resolve("summary.md")) shouldBe true
    Files.exists(sessionDir.resolve("perf_evolution.csv")) shouldBe true
    Files.exists(sessionDir.resolve("perf_evolution.png")) shouldBe true

    runCommand(Seq("bash", launcher.toString, "--rounds", "1", "--resume", sessionId, "--dry-run"), env) shouldBe 0
    Files.readAllLines(roundsPath).size() shouldBe 1
  }
}
