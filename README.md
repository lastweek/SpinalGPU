# SpinalGPU

Milestone 1 project scaffold for an educational GPU written in SpinalHDL.

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

## What Each Command Does

- `sbt compile` resolves dependencies and compiles the SpinalHDL sources.
- `sbt test` runs the `SpinalSim` smoke test for `GpuTop` using the Verilator backend.
- `sbt run` elaborates `GpuTop` and emits Verilog into `generated/verilog`.

## Project Layout

- `src/main/scala/spinalgpu/GpuTop.scala`: empty top-level hardware entrypoint with explicit clock/reset pins for simulation.
- `src/main/scala/spinalgpu/GenerateGpuTop.scala`: Verilog generator entrypoint used by `sbt run`.
- `src/test/scala/spinalgpu/GpuTopSimSpec.scala`: minimal `SpinalSim` smoke test with an explicit Verilator backend.
- `scripts/`: small local workflow helpers for fast test, watch mode, Verilog generation, and full checks.

## Notes

- This milestone intentionally stops at an empty top module plus local build/test plumbing.
- No ISA, scheduler, memory system, or GPU execution logic is included yet.
