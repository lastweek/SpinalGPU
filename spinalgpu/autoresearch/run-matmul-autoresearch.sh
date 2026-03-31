#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run-matmul-autoresearch.sh --rounds <N> [--session <id>] [--resume <id>] [--agent-cmd "<cmd>"] [--dry-run]
EOF
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROGRAM_FILE="$SCRIPT_DIR/matmul-cuda-code-tensor-core.md"
PLOT_SCRIPT="$SCRIPT_DIR/plot-matmul-autoresearch.py"
SUMMARY_SCRIPT="$SCRIPT_DIR/render-matmul-autoresearch-summary.py"
LOG_ROOT="${SPINALGPU_AUTORESEARCH_LOG_ROOT:-$REPO_ROOT/spinalgpu/autoresearch-logs}"
DEFAULT_TEST_CMD='sbt "Test / testOnly spinalgpu.Tcgen05BlockSpec spinalgpu.Tcgen05OverlapProgressSpec spinalgpu.StreamingMultiprocessorGemmPerfComparisonSpec"'

ROUNDS=""
SESSION_ID=""
RESUME_ID=""
AGENT_CMD=""
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rounds)
      ROUNDS="$2"
      shift 2
      ;;
    --session)
      SESSION_ID="$2"
      shift 2
      ;;
    --resume)
      RESUME_ID="$2"
      shift 2
      ;;
    --agent-cmd)
      AGENT_CMD="$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$ROUNDS" ]]; then
  echo "--rounds is required" >&2
  usage >&2
  exit 1
fi

if [[ -n "$RESUME_ID" ]]; then
  SESSION_ID="$RESUME_ID"
fi

if [[ -z "$SESSION_ID" ]]; then
  SESSION_ID="$(date +%Y%m%d-%H%M%S)"
fi

if [[ -z "$AGENT_CMD" ]]; then
  DRY_RUN=1
fi

SESSION_DIR="$LOG_ROOT/sessions/$SESSION_ID"
ARTIFACT_DIR="$SESSION_DIR/artifacts"
ROUNDS_JSONL="$SESSION_DIR/rounds.jsonl"
SUMMARY_MD="$SESSION_DIR/summary.md"
BENCHMARK_JSON="$SESSION_DIR/benchmark-round.json"
AGENT_META_JSON="$SESSION_DIR/agent-round.json"
mkdir -p "$ARTIFACT_DIR"

ROUND_CMD_TEMPLATE="${SPINALGPU_AUTORESEARCH_ROUND_CMD:-sbt \"Compile / runMain spinalgpu.autoresearch.MatmulAutoResearchRound --shape 32 --output $BENCHMARK_JSON\"}"
TEST_CMD="${SPINALGPU_AUTORESEARCH_TEST_CMD:-$DEFAULT_TEST_CMD}"

run_round_measurement() {
  rm -f "$BENCHMARK_JSON"
  (cd "$REPO_ROOT" && eval "$ROUND_CMD_TEMPLATE")
}

append_round_record() {
  local round="$1"
  local decision="$2"
  local tests_passed="$3"
  local changed_files="$4"
  python3 - "$BENCHMARK_JSON" "$ROUNDS_JSONL" "$AGENT_META_JSON" "$round" "$decision" "$tests_passed" "$changed_files" <<'PY'
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

benchmark_path = Path(sys.argv[1])
rounds_path = Path(sys.argv[2])
agent_meta_path = Path(sys.argv[3])
round_index = int(sys.argv[4])
decision = sys.argv[5]
tests_passed = sys.argv[6].lower() == "true"
changed_files = [entry for entry in sys.argv[7].split(",") if entry]

if benchmark_path.exists():
    benchmark = json.loads(benchmark_path.read_text())
else:
    benchmark = {
        "shape": "32x32x32",
        "cuda_core_cycles": -1,
        "tcgen05_cycles": -1,
        "tcgen05_state_cycles": {
            "collect_cycles": 0,
            "shared_cycles": 0,
            "tensor_cycles": 0,
            "compute_cycles": 0,
            "pack_cycles": 0,
            "respond_cycles": 0,
            "total_cycles": 0,
        },
        "correctness_passed": False,
        "fault": True,
        "suggested_next_focus": "tcgen05_compute",
    }

agent_meta = {}
if agent_meta_path.exists():
    agent_meta = json.loads(agent_meta_path.read_text())

record = {
    "round": round_index,
    "timestamp": datetime.now(timezone.utc).isoformat(),
    "shape": benchmark["shape"],
    "git_head": os.popen("git rev-parse HEAD").read().strip(),
    "changed_files": changed_files,
    "hypothesis": agent_meta.get("hypothesis", "baseline/no-op" if round_index == 0 else f"agent round {round_index}"),
    "decision": decision,
    "cuda_core_cycles": benchmark["cuda_core_cycles"],
    "tcgen05_cycles": benchmark["tcgen05_cycles"],
    "tcgen05_state_cycles": benchmark["tcgen05_state_cycles"],
    "tests_passed": tests_passed,
    "correctness_passed": bool(benchmark["correctness_passed"]),
    "fault": bool(benchmark["fault"]),
    "next_focus": agent_meta.get("next_focus", benchmark.get("suggested_next_focus", "tcgen05_compute")),
    "notes": agent_meta.get("notes", ""),
}

rounds_path.parent.mkdir(parents=True, exist_ok=True)
with rounds_path.open("a", encoding="utf-8") as handle:
    handle.write(json.dumps(record, sort_keys=True) + "\n")
PY
}

current_changed_files() {
  (cd "$REPO_ROOT" && git status --short | awk '{print $2}' | paste -sd, -)
}

best_accepted_tcgen05_cycles() {
  python3 - "$ROUNDS_JSONL" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
if not path.exists():
    print("")
    raise SystemExit(0)

best = None
for line in path.read_text().splitlines():
    if not line.strip():
        continue
    row = json.loads(line)
    if row.get("decision") != "accepted":
        continue
    value = row.get("tcgen05_cycles", -1)
    if value is None or value < 0:
        continue
    if best is None or value < best:
        best = value
print("" if best is None else best)
PY
}

refresh_artifacts() {
  python3 "$SUMMARY_SCRIPT" --session "$SESSION_ID" >/dev/null
  python3 "$PLOT_SCRIPT" --session "$SESSION_ID" >/dev/null
}

run_baseline_round() {
  run_round_measurement
  append_round_record 0 accepted true ""
  refresh_artifacts
}

next_round_start=0
if [[ -f "$ROUNDS_JSONL" ]]; then
  next_round_start=$(( $(wc -l < "$ROUNDS_JSONL") ))
else
  run_baseline_round
  next_round_start=1
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  exit 0
fi

for (( round=next_round_start; round<ROUNDS; round++ )); do
  rm -f "$AGENT_META_JSON"
  export SPINALGPU_AUTORESEARCH_PROGRAM="$PROGRAM_FILE"
  export SPINALGPU_AUTORESEARCH_SESSION_DIR="$SESSION_DIR"
  export SPINALGPU_AUTORESEARCH_ROUND="$round"
  export SPINALGPU_AUTORESEARCH_SUMMARY="$SUMMARY_MD"
  export SPINALGPU_AUTORESEARCH_LOG="$ROUNDS_JSONL"
  export SPINALGPU_AUTORESEARCH_SHAPE="32x32x32"

  (cd "$REPO_ROOT" && sh -lc "$AGENT_CMD")

  if ! (cd "$REPO_ROOT" && eval "$TEST_CMD"); then
    rm -f "$BENCHMARK_JSON"
    append_round_record "$round" rejected false "$(current_changed_files)"
    refresh_artifacts
    echo "[autoresearch] test command failed on round $round" >&2
    exit 1
  fi

  if ! run_round_measurement; then
    append_round_record "$round" rejected true "$(current_changed_files)"
    refresh_artifacts
    echo "[autoresearch] benchmark command failed on round $round" >&2
    exit 1
  fi

  current_best="$(best_accepted_tcgen05_cycles)"
  current_tcgen="$(python3 - "$BENCHMARK_JSON" <<'PY'
import json
import sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text())
print(data["tcgen05_cycles"])
PY
)"

  decision="rejected"
  if [[ -z "$current_best" || "$current_tcgen" -lt "$current_best" ]]; then
    decision="accepted"
  fi

  append_round_record "$round" "$decision" true "$(current_changed_files)"
  refresh_artifacts

  if [[ "$decision" != "accepted" ]]; then
    echo "[autoresearch] round $round rejected; stopping to avoid compounding changes" >&2
    exit 1
  fi
done
