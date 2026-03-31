# SpinalGPU

Educational GPU architecture exploration in SpinalHDL.

SpinalGPU implements a PTX subset ISA with a custom binary encoding executed by the hardware.

## Default SpinalGPU Specification

### Microarchitecture

| Spec | Default | Config / derivation source |
| --- | --- | --- |
| Physical SMs | 1 | `GpuConfig.default.cluster.smCount` |
| Max resident CTAs per SM | 1 | Current execution model in [`docs/architecture.md`](docs/architecture.md) |
| Warp size | 32 | `SmConfig.default.warpSize` |
| Sub-SM partitions per SM | 4 | `SmConfig.default.subSmCount` |
| Resident warps per sub-SM | 2 | `SmConfig.default.residentWarpsPerSubSm` |
| Max resident warps per SM | 8 | `residentWarpCount = subSmCount * residentWarpsPerSubSm` |
| Max resident threads per SM | 256 | `maxBlockThreads = residentWarpCount * warpSize` |
| CUDA issue lanes per sub-SM | 32 | `SmConfig.default.subSmIssueWidth` |
| Active CUDA lanes per SM | 128 | `subSmCount * subSmIssueWidth` |
| Registers per thread | 32 | `SmConfig.default.registerCount` |
| Shared memory per SM | 4 KiB | `SmConfig.default.sharedMemoryBytes` |
| Shared memory banks | 32 | `SmConfig.default.sharedMemoryBankCount` |
| Tensor memory per warp | 512 B | `SmConfig.default.tensorMemoryBytesPerWarp` |
| Tensor memory per SM | 4 KiB | `tensorMemoryBytes = tensorMemoryBytesPerWarp * residentWarpCount` |

### Compute Throughput (sustained, per cycle)

All rows below show two aggregation levels:
- `Per sub-SM partition`: one local `CudaCoreArray` issue slice
- `Per physical SM`: the sum across all 4 default sub-SM partitions

With `SmConfig.default`, each sub-SM partition has `32` CUDA issue lanes and covers one full `32`-thread warp per issued instruction because `warpSize == subSmIssueWidth == 32`. If you want a sustained per-lane rate, divide the per-partition number by `32`.

| Operation | Per sub-SM partition | Per physical SM (default) | Config / derivation source |
| --- | --- | --- | --- |
| FP32 add / mul | 8 FLOPs/cycle | 32 FLOPs/cycle | `subSmIssueWidth(32) / fpAddLatency(4)` per partition, then `subSmCount(4) * ...` per SM |
| FP32 FMA | 12.8 FLOPs/cycle | 51.2 FLOPs/cycle | `subSmIssueWidth(32) * 2 / fpFmaLatency(5)` per partition, then `subSmCount(4) * ...` per SM |
| FP16 scalar add / mul | 8 FLOPs/cycle | 32 FLOPs/cycle | `subSmIssueWidth(32) / fp16ScalarLatency(4)` per partition, then `subSmCount(4) * ...` per SM |
| FP16 scalar FMA | 16 FLOPs/cycle | 64 FLOPs/cycle | `subSmIssueWidth(32) * 2 / fp16ScalarLatency(4)` per partition, then `subSmCount(4) * ...` per SM |
| FP16 packed `f16x2` add / mul | 16 FLOPs/cycle | 64 FLOPs/cycle | `subSmIssueWidth(32) * 2 / fp16x2Latency(4)` per partition, then `subSmCount(4) * ...` per SM |
| FP8 arithmetic | 0 FLOPs/cycle | 0 FLOPs/cycle | Current CUDA path implements FP8 conversion only |

These values describe `GpuConfig.default`; the source-of-truth configuration surfaces are [`GpuConfig.scala`](src/main/scala/spinalgpu/GpuConfig.scala) and [`SmConfig.scala`](src/main/scala/spinalgpu/SmConfig.scala). The terminology here stays architecture-accurate and the throughput rows reflect current implemented datapaths and sustained latency-gated behavior, not clock-based marketing peaks. `FMA` counts as 2 FLOPs.

Tensor note: legacy `TensorCoreBlock` and `tcgen05` are implemented, but their current inner compute loops serialize one FP16 FMA per cycle per partition, so this README intentionally omits a headline tensor-throughput row.

## Current Feature Status

Status labels below match the support meanings in [`docs/isa.md`](docs/isa.md): `Implemented`, `Partial`, and `Rejected`.

| Area | Status | Current scope | Details |
| --- | --- | --- | --- |
| Execution and launch model | `Partial` | Classic SIMT v1 with real 3D CTA grids | One kernel globally in flight, one resident CTA per SM, and no divergent reconvergence |
| PTX module and entry contract | `Partial` | Repo PTX subset only | One `.visible .entry` per file, `.param .u32` only, lowered into the custom SpinalGPU machine encoding |
| CUDA-core arithmetic | `Implemented` | INT32, FP32, FP16, FP16x2, and packed FP8 conversion | The documented CUDA-core subset executes on the current `CudaCoreArray` path |
| Special-function unit | `Implemented` | Unary `.approx` FP32 plus FP16 / FP16x2 `ex2` and `tanh` | The current SFU surface is intentionally limited to the documented unary subset |
| Memory surface | `Partial` | `.param`, `.global`, and `.shared` only | Global traffic supports aligned 16-bit and 32-bit accesses; shared-memory PTX is still word-oriented only |
| Special registers and grid builtins | `Implemented` | Core SIMT, SM, and grid builtins are real | `%gridid` remains a narrow `.u64` path even though `%tid/%ntid/%ctaid/%nctaid/%smid/%nsmid` are live |
| Tensor surface | `Partial` | Legacy FP16 tensor v1 plus narrow FP16 `tcgen05` | Broad NVIDIA tensor families remain intentionally out of scope |
| Explicitly unsupported PTX families | `Rejected` | Major PTX families stay out of scope in v1 | `.const`, `.local`, atomics, barriers, calls, BF16/FP64, broad `wmma.*`, broad `tcgen05`, and generic compiler-generated PTX are rejected |

`Partial` here means “implemented for a narrower educational subset than full PTX,” not “broken.” See [`docs/isa.md`](docs/isa.md) and [`docs/architecture.md`](docs/architecture.md) for the full compatibility and execution-model detail.

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
sbt devTest
sbt refreshKernels
sbt smokeTest
sbt multiSmSmoke
sbt test
sbt run
```

- `sbt devTest`: fast structural, unit, and controller regression loop; does not rebuild the kernel corpus
- `sbt refreshKernels`: rebuilds `generated/kernels/**` from `kernels/**/*.ptx`
- `sbt smokeTest`: curated SM and `GpuTop` execution smoke suite; does not rebuild the kernel corpus
- `sbt multiSmSmoke`: curated multi-SM `GpuTop(smCount = 2)` smoke suite; does not rebuild the kernel corpus
- `sbt test`: full regression and automatic kernel-corpus rebuild

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
- `./scripts/test-fast.sh`: rebuilds kernels and runs the default `sbt smokeTest` workflow
- `./scripts/test-fast.sh spinalgpu.SomeOtherSpec`: runs one specific test spec
- `./scripts/test-watch.sh`: reruns the default `sbt smokeTest` workflow whenever sources change
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

## Config Hierarchy

- `GpuConfig` is the chip-level configuration used by `GpuTop`, `GpuCluster`, host control, and the single-SM compatibility wrapper.
- `SmConfig` is the SM-local configuration used by execution-core modules such as sub-SMs, caches, shared memory, LSU, and CUDA cores.
- `GpuConfig.default` preserves the default single-SM repo contract by setting `cluster.smCount = 1` and `sm = SmConfig.default`.

## Kernel Corpus

- Canonical PTX subset kernel source files live under `kernels/`.
- CUDA learning kernels live under `kernels/cuda/` as source-only study material.
- Generated raw machine-code binaries are written under `generated/kernels/`.
- The corpus is organized by primary feature:
  - `kernels/arithmetic/`
  - `kernels/control/`
  - `kernels/global_memory/`
  - `kernels/shared_memory/`
  - `kernels/sfu/`
  - `kernels/special_registers/`
- `kernels/cuda/` is intentionally separate from the executable PTX corpus:
  - `.cu` files are not compiled by `sbt refreshKernels`
  - `.cu` files are not loaded by `KernelCorpus` or the simulation harnesses
  - `.cu` files are not covered by repo automation in this first milestone
- [`src/main/scala/spinalgpu/toolchain/KernelCorpus.scala`](src/main/scala/spinalgpu/toolchain/KernelCorpus.scala) is the single source of truth for PTX source paths, generated binary paths, launch config, preload image, expected outcome, and harness coverage.
- [`src/test/scala/spinalgpu/KernelCorpusTestUtils.scala`](src/test/scala/spinalgpu/KernelCorpusTestUtils.scala) is the shared runner used by the corpus-backed simulation specs.
- Success vs fault classification is stored in typed declarative expectations, not in directory names.
- The current harness split is:
  - `GpuTop`: AXI-Lite/AXI boundary smoke
  - `StreamingMultiprocessor`: full externalized kernel corpus
  - `ExecutionFrontendSimSpec`: grouped top-level kernel suites for full, smoke, and multi-SM execution coverage
- Recommended flow:
  - use `sbt devTest` for fast non-execution iteration
  - use `sbt refreshKernels` after PTX or PTX-toolchain changes
  - use `sbt smokeTest` and `sbt multiSmSmoke` for curated execution checks
  - use `sbt test` for the full regression contract

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
- `sbt devTest` runs the fast structural, unit, and controller suites without rebuilding the kernel corpus.
- `sbt refreshKernels` rebuilds the PTX kernel corpus under `generated/kernels`.
- `sbt smokeTest` runs the curated SM and `GpuTop` execution smoke suites without rebuilding the kernel corpus.
- `sbt multiSmSmoke` runs the curated multi-SM `GpuTop` smoke suite without rebuilding the kernel corpus.
- `sbt test` rebuilds the PTX kernel corpus and then runs the full regression suite.
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
- `src/test/scala/spinalgpu/ExecutionFrontendSimSpec.scala`: grouped `GpuTop` full, smoke, and multi-SM execution suites.
- `src/test/scala/spinalgpu/StreamingMultiprocessorSimSpec.scala`: grouped SM wrapper integration suite for launch, trap, and delayed-drain behavior.
- `src/test/scala/spinalgpu/StreamingMultiprocessorKernelCorpusSpec.scala`: grouped full and smoke kernel-corpus SM execution suites.
- `src/test/scala/spinalgpu/KernelCorpusTestUtils.scala`: shared preload, launch, and assertion helpers for corpus-backed simulation specs.
- `src/test/scala/spinalgpu/KernelCorpusSpec.scala`: integrity test that keeps `kernels/` and the declarative corpus in sync.
- `src/test/scala/spinalgpu/IsaSpec.scala`: machine-encoding encoder, decoder, and disassembler tests.
- `src/test/scala/spinalgpu/PtxAssemblerSpec.scala`: PTX subset compiler and binary-emission tests.
- `src/test/scala/spinalgpu/ArchitectureSkeletonSpec.scala`: elaboration sweep and doc/diagram checks.
- `kernels/`: feature-organized PTX subset corpus for end-to-end execution tests.
- `kernels/cuda/`: source-only CUDA learning ladder for performance-study kernels, organized from foundations to LLM-style building blocks.
- `generated/kernels/`: generated raw binaries loaded by the simulation harnesses.
- `scripts/`: small local workflow helpers for fast test, watch mode, Verilog generation, and full checks.
- `docs/`: architecture notes, ISA reference, machine-encoding reference, and Mermaid diagrams for the SM/frontend design.
- `AGENTS.md`: repo-specific Codex guidance for SpinalHDL architecture and testing conventions.

## Notes

- The public software-visible ISA is a PTX subset, not the internal machine encoding.
- The current hardware still executes fixed-width 32-bit SpinalGPU machine words loaded from raw `.bin` files.
- The PTX compiler is intentionally educational and correctness-first, not a full `nvcc`-compatible PTX implementation.
- The CUDA learning ladder is documentation-plus-source only; it becomes runnable later in a CUDA-capable environment, but it does not change the default repo contract today.
