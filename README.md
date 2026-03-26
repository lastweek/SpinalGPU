# SpinalGPU

Educational GPU architecture exploration in SpinalHDL.

## Prerequisites

- JDK 17
- `sbt` on your `PATH`
- `verilator` on your `PATH`

If you install `openjdk@17` via Homebrew on macOS, you may need:

```bash
export PATH="/opt/homebrew/opt/openjdk@17/bin:/opt/homebrew/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
```

## Local Workflow

From the repository root:

```bash
sbt compile
sbt test
sbt run
```

## Quick Scripts

Use the small wrappers in `scripts/` for the common inner loop:

```bash
./scripts/test-fast.sh
./scripts/test-watch.sh
./scripts/gen-verilog.sh
./scripts/check.sh
```

- `./scripts/test-fast.sh`: runs the default smoke spec `spinalgpu.GpuTopSimSpec`
- `./scripts/test-fast.sh spinalgpu.SomeOtherSpec`: runs one specific test spec
- `./scripts/test-watch.sh`: reruns the default smoke spec whenever sources change
- `./scripts/gen-verilog.sh`: regenerates Verilog into `generated/verilog`
- `./scripts/check.sh`: runs the full `compile -> test -> run` contract

## Architecture Docs

- [SM architecture note](docs/architecture.md)
- [ISA reference](docs/isa.md)
- [SM overview diagram](docs/diagrams/sm-overview.mmd)
- [Dispatch and dataflow diagram](docs/diagrams/dispatch-dataflow.mmd)
- [Memory hierarchy and AXI boundary diagram](docs/diagrams/memory-hierarchy-axi.mmd)
- [Launch and frontend execution diagram](docs/diagrams/frontend-execution.mmd)
- [Repo agent guidelines](AGENTS.md)

## What Each Command Does

- `sbt compile` resolves dependencies and compiles the SpinalHDL sources.
- `sbt test` runs the architecture skeleton tests and the `SpinalSim` integration checks.
- `sbt run` elaborates `GpuTop` and emits Verilog into `generated/verilog`.

## Project Layout

- `src/main/scala/spinalgpu/GpuTop.scala`: top-level wrapper exposing AXI4 memory and AXI-Lite control.
- `src/main/scala/spinalgpu/GenerateGpuTop.scala`: Verilog generator entrypoint used by `sbt run`.
- `src/main/scala/spinalgpu/StreamingMultiprocessor.scala`: launch, fetch, decode, issue, and writeback frontend for one SM.
- `src/test/scala/spinalgpu/GpuTopSimSpec.scala`: top-level `SpinalSim` smoke test with AXI memory and AXI-Lite control.
- `src/test/scala/spinalgpu/StreamingMultiprocessorSimSpec.scala`: internal launch/fetch frontend tests.
- `src/test/scala/spinalgpu/IsaSpec.scala`: assembler, encoder, and decoder tests.
- `src/test/scala/spinalgpu/ArchitectureSkeletonSpec.scala`: elaboration sweep and doc/diagram checks.
- `scripts/`: small local workflow helpers for fast test, watch mode, Verilog generation, and full checks.
- `docs/`: architecture notes, ISA reference, and Mermaid diagrams for the SM/frontend design.
- `AGENTS.md`: repo-specific Codex guidance for SpinalHDL architecture and testing conventions.

## Notes

- This milestone defines the v1 frontend: ISA, launch MMIO, warp runtime context, fetch/decode, register file, and program-driven tests.
- The project is still intentionally educational and correctness-first, not performance-realistic.
