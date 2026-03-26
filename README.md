# SpinalGPU

Educational GPU architecture exploration in SpinalHDL.

SpinalGPU implements a PTX subset ISA with a custom binary encoding executed by the hardware.

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

- `./scripts/build-kernels.sh`: compiles `kernels/**/*.ptx` into raw `.bin` files under `generated/kernels`
- `./scripts/test-fast.sh`: runs the default smoke spec `spinalgpu.GpuTopSimSpec`
- `./scripts/test-fast.sh spinalgpu.SomeOtherSpec`: runs one specific test spec
- `./scripts/test-watch.sh`: reruns the default smoke spec whenever sources change
- `./scripts/gen-verilog.sh`: regenerates Verilog into `generated/verilog`
- `./scripts/check.sh`: runs the full `compile -> test -> run` contract

## Architecture Docs

- [SM architecture note](docs/architecture.md)
- [PTX subset ISA reference](docs/isa.md)
- [Machine encoding reference](docs/machine-encoding.md)
- [SM overview diagram](docs/diagrams/sm-overview.mmd)
- [Dispatch and dataflow diagram](docs/diagrams/dispatch-dataflow.mmd)
- [Memory hierarchy and AXI boundary diagram](docs/diagrams/memory-hierarchy-axi.mmd)
- [Launch and frontend execution diagram](docs/diagrams/frontend-execution.mmd)
- [Repo agent guidelines](AGENTS.md)

## Kernel Corpus

- Canonical PTX subset kernel source files live under `kernels/`.
- Generated raw machine-code binaries are written under `generated/kernels/`.
- The corpus is organized by primary feature:
  - `kernels/arithmetic/`
  - `kernels/control/`
  - `kernels/global_memory/`
  - `kernels/shared_memory/`
  - `kernels/special_registers/`
- [`src/main/scala/spinalgpu/toolchain/KernelCorpus.scala`](src/main/scala/spinalgpu/toolchain/KernelCorpus.scala) is the single source of truth for PTX source paths, generated binary paths, launch config, preload image, expected outcome, and harness coverage.
- [`src/test/scala/spinalgpu/KernelCorpusTestUtils.scala`](src/test/scala/spinalgpu/KernelCorpusTestUtils.scala) is the shared runner used by the corpus-backed simulation specs.
- Success vs fault classification is stored in typed declarative expectations, not in directory names.
- The current harness split is:
  - `GpuTop`: AXI-Lite/AXI boundary smoke
  - `StreamingMultiprocessor`: full externalized kernel corpus
  - `ExecutionFrontendSimSpec`: experimental top-level kernel smoke, kept out of the default regression set because the current AXI-Lite launch path is not yet stable enough for deterministic CI
- Recommended flow:
  - build binaries with `./scripts/build-kernels.sh`
  - run simulation tests against the generated `.bin` artifacts

## PTX File Template

Each teaching kernel follows one strict file template:

1. `// Purpose`, `// Primary feature`, and `// Expected outcome` header lines
2. `.version`, `.target`, `.address_size`
3. one `.visible .entry` signature with `.param` list
4. declarations ordered as `.reg`, then `.pred`, then `.shared`
5. `// Setup`
6. `// Core`
7. `// Exit` or `// Fault trigger`

“Layers” or “modules” in this repo mean directory structure and file sections for readability. They do not imply PTX includes, macros, imports, or multi-entry PTX modules.

## What Each Command Does

- `sbt compile` resolves dependencies and compiles the SpinalHDL sources plus the PTX toolchain.
- `sbt test` rebuilds the PTX kernel corpus and then runs the architecture skeleton tests and the `SpinalSim` integration checks.
- `sbt run` elaborates `GpuTop` and emits Verilog into `generated/verilog`.

## Project Layout

- `src/main/scala/spinalgpu/GpuTop.scala`: top-level wrapper exposing AXI4 memory and AXI-Lite control.
- `src/main/scala/spinalgpu/GenerateGpuTop.scala`: Verilog generator entrypoint used by `sbt run`.
- `src/main/scala/spinalgpu/StreamingMultiprocessor.scala`: launch, fetch, decode, issue, and writeback frontend for one SM.
- `src/main/scala/spinalgpu/toolchain/PtxAssembler.scala`: PTX subset compiler that lowers PTX source into SpinalGPU machine words.
- `src/main/scala/spinalgpu/toolchain/KernelBinaryIO.scala`: raw binary read/write helpers for generated machine code.
- `src/main/scala/spinalgpu/toolchain/BuildKernelCorpus.scala`: batch compiler for the kernel corpus.
- `src/main/scala/spinalgpu/toolchain/KernelCorpus.scala`: single declarative kernel corpus definition for source paths, launch config, preload image, and expectations.
- `src/test/scala/spinalgpu/GpuTopSimSpec.scala`: top-level `SpinalSim` smoke test with AXI memory and AXI-Lite control.
- `src/test/scala/spinalgpu/ExecutionFrontendSimSpec.scala`: experimental top-level `GpuTop` kernel-launch smoke, currently ignored in the default regression set.
- `src/test/scala/spinalgpu/StreamingMultiprocessorSimSpec.scala`: generated corpus-backed SM execution tests plus low-level launch/fetch frontend checks.
- `src/test/scala/spinalgpu/KernelCorpusTestUtils.scala`: shared preload, launch, and assertion helpers for corpus-backed simulation specs.
- `src/test/scala/spinalgpu/KernelCorpusSpec.scala`: integrity test that keeps `kernels/` and the declarative corpus in sync.
- `src/test/scala/spinalgpu/IsaSpec.scala`: machine-encoding encoder, decoder, and disassembler tests.
- `src/test/scala/spinalgpu/PtxAssemblerSpec.scala`: PTX subset compiler and binary-emission tests.
- `src/test/scala/spinalgpu/ArchitectureSkeletonSpec.scala`: elaboration sweep and doc/diagram checks.
- `kernels/`: feature-organized PTX subset corpus for end-to-end execution tests.
- `generated/kernels/`: generated raw binaries loaded by the simulation harnesses.
- `scripts/`: small local workflow helpers for fast test, watch mode, Verilog generation, and full checks.
- `docs/`: architecture notes, ISA reference, machine-encoding reference, and Mermaid diagrams for the SM/frontend design.
- `AGENTS.md`: repo-specific Codex guidance for SpinalHDL architecture and testing conventions.

## Notes

- The public software-visible ISA is a PTX subset, not the internal machine encoding.
- The current hardware still executes fixed-width 32-bit SpinalGPU machine words loaded from raw `.bin` files.
- The PTX compiler is intentionally educational and correctness-first, not a full `nvcc`-compatible PTX implementation.
