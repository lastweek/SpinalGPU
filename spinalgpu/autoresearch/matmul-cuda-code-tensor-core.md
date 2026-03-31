# Matmul AutoResearch: CUDA Core vs Tensor Core

This file is the canonical autoresearch instruction surface for SpinalGPU matmul work.

## Goal

Improve single-SM matmul performance on the default `32x32x32` benchmark shape while tracking both:

- `cuda_core_cycles`
- `tcgen05_cycles`

The primary optimization target is `tcgen05_cycles`. `cuda_core_cycles` is measured every round and becomes the focus only after tcgen05 stalls for three consecutive non-improving rounds.

## User-Facing Commands

Use only these sibling entrypoints for the autoresearch workflow:

- `./run-matmul-autoresearch.sh --rounds <N> [--session <id>] [--resume <id>] [--agent-cmd "<cmd>"]`
- `./plot-matmul-autoresearch.py --session <id>`
- `./render-matmul-autoresearch-summary.py --session <id>`

Do not call raw `sbt` benchmark commands directly in normal use. The wrapper owns the session layout, round logging, summary refresh, and plot refresh.

## Default Round Flow

1. Baseline round 0 runs with no code changes.
2. Each later round should make one bounded change.
3. The launcher runs the fixed single-shape benchmark and records one JSONL log row.
4. A round is accepted only if:
   - tests pass
   - correctness passes
   - no benchmark fault is reported
   - `tcgen05_cycles` improves over the current accepted baseline
5. After every round, the launcher refreshes:
   - `summary.md`
   - `perf_evolution.csv`
   - `perf_evolution.png`

## Allowed Write Scope

- `kernels/benchmark/*.ptx`
- `src/main/scala/spinalgpu/Tcgen05Block.scala`
- `src/main/scala/spinalgpu/SmExecutionCore.scala`
- `src/main/scala/spinalgpu/SubSmPartition.scala`
- `src/main/scala/spinalgpu/StreamingMultiprocessor.scala`
- `src/main/scala/spinalgpu/autoresearch/**`
- `spinalgpu/autoresearch/**`

Do not start whole-chip or multi-SM optimization work from this loop.

## Agent Contract

If `--agent-cmd` is used, the launcher exports these environment variables before invoking the agent:

- `SPINALGPU_AUTORESEARCH_PROGRAM`
- `SPINALGPU_AUTORESEARCH_SESSION_DIR`
- `SPINALGPU_AUTORESEARCH_ROUND`
- `SPINALGPU_AUTORESEARCH_SUMMARY`
- `SPINALGPU_AUTORESEARCH_LOG`
- `SPINALGPU_AUTORESEARCH_SHAPE`

The agent may optionally write `agent-round.json` into the session directory with:

- `hypothesis`
- `notes`
- `next_focus`

If `agent-round.json` is absent, the launcher falls back to generic metadata for that round.

## Logs And Artifacts

All local session state lives under:

- `spinalgpu/autoresearch-logs/sessions/<session-id>/rounds.jsonl`
- `spinalgpu/autoresearch-logs/sessions/<session-id>/summary.md`
- `spinalgpu/autoresearch-logs/sessions/<session-id>/perf_evolution.csv`
- `spinalgpu/autoresearch-logs/sessions/<session-id>/perf_evolution.png`
- `spinalgpu/autoresearch-logs/sessions/<session-id>/artifacts/`

The plot uses:

- x-axis = round number
- y-axis = cycles
- line 1 = best-so-far CUDA-core cycles
- line 2 = best-so-far tcgen05 cycles

## What To Inspect Next

The internal round exporter records tcgen05 phase buckets:

- `collect_cycles`
- `shared_cycles`
- `tensor_cycles`
- `compute_cycles`
- `pack_cycles`
- `respond_cycles`

Use the largest tcgen05 bucket as the next optimization focus. If `compute_cycles` dominates, start with tcgen05 compute serialization. If tcgen05 stops improving for three rounds, allow one CUDA-core cleanup round before returning to tcgen05.
