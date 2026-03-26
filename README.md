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
./scripts/build-kernels.sh
sbt compile
sbt test
sbt run
```

## Quick Scripts

Use the small wrappers in `scripts/` for the common inner loop:

```bash
./scripts/build-kernels.sh
./scripts/test-fast.sh
./scripts/test-watch.sh
./scripts/gen-verilog.sh
./scripts/check.sh
```

- `./scripts/build-kernels.sh`: assembles `kernels/**/*.gpuasm` into raw `.bin` files under `generated/kernels`
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

## Kernel Corpus

- Canonical kernel source files live under `kernels/`.
- Generated raw kernel binaries are written under `generated/kernels/`.
- `kernels/smoke/` contains success-path programs.
- `kernels/fault/` contains programs that are expected to trap or fault.
- [`src/main/scala/spinalgpu/toolchain/KernelCatalog.scala`](src/main/scala/spinalgpu/toolchain/KernelCatalog.scala) is the authoritative source/binary catalog for the standalone assembler.
- [`src/test/scala/spinalgpu/KernelManifest.scala`](src/test/scala/spinalgpu/KernelManifest.scala) is the authoritative execution manifest for how each prebuilt binary is launched and checked.
- The current harness split is:
  - `GpuTop`: AXI-Lite/AXI boundary smoke
  - `StreamingMultiprocessor`: full externalized kernel corpus
  - `ExecutionFrontendSimSpec`: experimental top-level kernel smoke, kept out of the default regression set because the current AXI-Lite launch path is not yet stable enough for deterministic CI
- Recommended flow:
  - build binaries with `./scripts/build-kernels.sh`
  - run simulation tests against the generated `.bin` artifacts

## What Each Command Does

- `sbt compile` resolves dependencies and compiles the SpinalHDL sources.
- `sbt test` rebuilds the standalone kernel corpus and then runs the architecture skeleton tests and the `SpinalSim` integration checks.
- `sbt run` elaborates `GpuTop` and emits Verilog into `generated/verilog`.

## Project Layout

- `src/main/scala/spinalgpu/GpuTop.scala`: top-level wrapper exposing AXI4 memory and AXI-Lite control.
- `src/main/scala/spinalgpu/GenerateGpuTop.scala`: Verilog generator entrypoint used by `sbt run`.
- `src/main/scala/spinalgpu/StreamingMultiprocessor.scala`: launch, fetch, decode, issue, and writeback frontend for one SM.
- `src/main/scala/spinalgpu/toolchain/`: standalone assembler, binary I/O, and kernel corpus builder.
- `src/test/scala/spinalgpu/GpuTopSimSpec.scala`: top-level `SpinalSim` smoke test with AXI memory and AXI-Lite control.
- `src/test/scala/spinalgpu/ExecutionFrontendSimSpec.scala`: experimental top-level `GpuTop` kernel-launch smoke, currently ignored in the default regression set.
- `src/test/scala/spinalgpu/StreamingMultiprocessorSimSpec.scala`: full manifest-backed kernel corpus plus low-level launch/fetch frontend tests.
- `src/test/scala/spinalgpu/KernelManifest.scala`: kernel corpus manifest with launch params, preload hooks, and expected outcomes.
- `src/test/scala/spinalgpu/KernelManifestSpec.scala`: integrity test that keeps `kernels/` and the manifest in sync.
- `src/test/scala/spinalgpu/IsaSpec.scala`: encoder, decoder, and disassembler tests.
- `src/test/scala/spinalgpu/AssemblerSpec.scala`: standalone assembler and binary-emission tests.
- `src/test/scala/spinalgpu/ArchitectureSkeletonSpec.scala`: elaboration sweep and doc/diagram checks.
- `kernels/`: visible assembly corpus for end-to-end execution tests.
- `generated/kernels/`: generated raw binaries loaded by the simulation harnesses.
- `scripts/`: small local workflow helpers for fast test, watch mode, Verilog generation, and full checks.
- `docs/`: architecture notes, ISA reference, and Mermaid diagrams for the SM/frontend design.
- `AGENTS.md`: repo-specific Codex guidance for SpinalHDL architecture and testing conventions.

## Notes

- This milestone defines the v1 frontend: ISA, launch MMIO, warp runtime context, fetch/decode, register file, and program-driven tests.
- The assembler is now a standalone toolchain step. The GPU frontend and simulation harnesses load raw `.bin` only.
- The project is still intentionally educational and correctness-first, not performance-realistic.
