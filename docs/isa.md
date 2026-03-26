# PTX Subset ISA v1

SpinalGPU implements a PTX subset ISA with a custom binary encoding executed by the hardware.

- Programmers and tests author kernels in repo-scoped PTX subset source files under `kernels/**/*.ptx`.
- The PTX toolchain lowers that source into raw little-endian `.bin` machine code for the current SM.
- The hardware fetches and executes the custom SpinalGPU machine encoding described in [machine-encoding.md](machine-encoding.md).
- This page is a quick compatibility reference for the current PTX-visible surface. It is descriptive only.

## How To Read This Page

- `Status`
  - `Implemented`: accepted by the PTX frontend and backed by a real execution path for the stated subset
  - `Partial`: supported, but narrower than normal PTX expectations
  - `Rejected`: unsupported PTX is intentionally rejected by the frontend
  - `Missing`: no correct support path exists yet
- `Coverage`
  - `Direct`: explicitly exercised by a dedicated test or kernel case
  - `Indirect`: covered only through a shared implementation path
  - `None`: no current repo test directly or indirectly proves the behavior

## Evidence Legend

| ID | Evidence |
| --- | --- |
| `ISA1` | `IsaSpec`: machine-code encode/decode/disassemble coverage for representative instruction formats |
| `PA1` | `PtxAssemblerSpec`: lowers entry headers and `ld.param` to machine words |
| `PA2` | `PtxAssemblerSpec`: resolves labels and lowers predicated branches from predicate registers |
| `PA3` | `PtxAssemblerSpec`: lowers shared symbols to byte offsets in shared-memory instructions |
| `PA4` | `PtxAssemblerSpec`: rejects unsupported PTX constructs |
| `PA5` | `PtxAssemblerSpec`: kernel corpus builder emits raw little-endian binaries from PTX sources |
| `KM1` | `KernelManifestSpec`: kernel manifest references every `.ptx` file exactly once and generated binaries exist |
| `KM2` | `KernelManifestSpec`: kernel catalog metadata is complete and matches manifest expectations |
| `KM3` | `KernelManifestSpec`: PTX kernel corpus follows the teaching template |
| `SM1` | `StreamingMultiprocessorSimSpec`: launch controller initializes warp contexts and schedules multiple warps |
| `SM2` | `StreamingMultiprocessorSimSpec`: illegal opcode traps and latches fault status |
| `SM3` | `StreamingMultiprocessorSimSpec`: `thread_id_store` completes and writes expected results |
| `SM4` | `StreamingMultiprocessorSimSpec`: `add_store_exit` completes and writes the arithmetic result |
| `SM5` | `StreamingMultiprocessorSimSpec`: `uniform_loop` completes and writes the terminal value |
| `SM6` | `StreamingMultiprocessorSimSpec`: `shared_roundtrip` completes and writes shared-memory results |
| `SM7` | `StreamingMultiprocessorSimSpec`: `vector_add_1warp` completes and writes vector sums |
| `SM8` | `StreamingMultiprocessorSimSpec`: misaligned fetch traps and latches fault status |
| `SM9` | `StreamingMultiprocessorSimSpec`: `misaligned_store` traps and latches fault status |
| `SM10` | `StreamingMultiprocessorSimSpec`: `non_uniform_branch` traps and latches fault status |
| `SM11` | `StreamingMultiprocessorSimSpec`: `trap` kernel traps and latches fault status |
| `GT1` | `GpuTopSimSpec`: `GpuTop` exposes idle AXI memory and AXI-Lite control boundaries |

## Compatibility Summary

| PTX family | Overall status | Coverage | Notes |
| --- | --- | --- | --- |
| Execution and launch model | `Partial` | `Direct` | Classic SIMT v1 on one SM, one block per launch, no divergent reconvergence support |
| Module and entry contract | `Partial` | `Direct` | One `.visible .entry` per file, `.param .u32` only |
| Types, registers, and predicates | `Partial` | `Direct` | `.u32` scalar integer path only; predicates are compiler-managed |
| Special registers | `Partial` | `Direct` | `%tid.x` is exercised; other mapped PTX builtins lack direct tests |
| Instruction surface | `Partial` | `Direct` | Integer, control, and narrow data-movement subset only |
| Memory spaces and addressing | `Partial` | `Direct` | `.param`, `.global`, and `.shared` only; aligned 32-bit words only |
| Currently unsupported PTX families | `Rejected` | `Direct` | `.const`, `.local`, FP, SFU, tensor, sync, atomics, calls, and broad compiler-generated PTX remain out of scope |

## Execution And Launch Model

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| SIMT warp execution | PTX kernels execute many logical threads grouped into warps and CTAs | `Implemented` | `Direct` | `SM1`, `SM3`-`SM7` | Current frontend runs classic SIMT with one selected warp and one issued warp instruction at a time. |
| One warp PC + active mask model | The implementation must preserve PTX per-thread semantics across active lanes | `Partial` | `Direct` | `SM1`, `SM10` | SpinalGPU uses one PC plus one active mask per warp; no reconvergence stack or independent thread scheduling exists. |
| One block per launch | PTX launch semantics assume grid/block decomposition | `Partial` | `Direct` | `SM1`, `GT1` | `GRID_DIM_X` and `BLOCK_DIM_X` exist in the host ABI, but v1 only launches one block. |
| Launch-time `ENTRY_PC / GRID_DIM_X / BLOCK_DIM_X / ARG_BASE / SHARED_BYTES` | The runtime must supply entry metadata, parameter base, and shared-memory size | `Implemented` | `Direct` | `SM1`, `GT1` | This is a repo-specific MMIO ABI. `ENTRY_PC` points at SpinalGPU machine code, not PTX source text. |
| Completion and fault signaling | The runtime must observe completion and runtime failure | `Implemented` | `Direct` | `GT1`, `SM2`, `SM8`-`SM11` | `STATUS.done` is raised on both success and fault. `FAULT_PC` and `FAULT_CODE` disambiguate failure. |
| Divergent control flow and reconvergence | Lane-varying control flow must reconverge correctly | `Missing` | `Direct` | `SM10` | Non-uniform branches do not reconverge; they raise `non_uniform_branch`. |

## Module And Entry Contract

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `.version` | PTX modules declare a PTX ISA version | `Partial` | `Direct` | `PA1`, `KM3` | Presence is required, but the version value is not semantically validated beyond matching `.version ...`. |
| `.target spinalgpu` | PTX modules declare the target environment | `Implemented` | `Direct` | `PA1`, `KM3` | Exact `.target spinalgpu` is required. |
| `.address_size 32` | PTX modules declare the address width | `Implemented` | `Direct` | `PA1`, `KM3` | Exact `.address_size 32` is required. |
| One `.visible .entry` kernel per file | PTX must define an entry point and parameter list | `Partial` | `Direct` | `PA1`, `KM3` | Exactly one `.visible .entry` is supported in each PTX file. |
| Entry parameters via `.param .u32` | PTX entry ABI declares parameter type and ordering | `Partial` | `Direct` | `PA1`, `SM3`-`SM7` | Only `.param .u32` entry parameters are accepted. They lower to tightly packed 32-bit words at `ARG_BASE`. |
| Multiple entries per file | PTX may contain multiple entries and device functions in one module | `Rejected` | `None` | none | The parser errors on a second `.visible .entry`. |

## Types, Registers, And Predicates

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `.reg .u32` | PTX virtual registers for typed scalar values | `Implemented` | `Direct` | `PA1`, `KM3` | Only `.reg .u32 %r<N>;` declarations are accepted. |
| 32-bit scalar integer execution | A backend must provide typed scalar integer execution for the supported subset | `Implemented` | `Direct` | `SM4`, `SM5`, `SM7` | The current PTX-visible execution path is 32-bit integer only. |
| `.pred` support | PTX uses predicate registers for condition evaluation and branch predication | `Partial` | `Direct` | `PA2`, `SM5`, `SM10` | Predicates lower to compiler-managed integer-backed condition values, not a native PTX-style predicate register file. |
| Wider and alternate integer widths (`.u64`, `.s64`, `.u16`, `.u8`) | PTX supports multiple integer widths | `Rejected` | `None` | none | The frontend only accepts `.u32` declarations and `.u32` integer instructions. |
| Floating-point, vector, and packed/tensor data types (`.f32`, `.f64`, vectors, packed formats) | PTX supports scalar FP, vector, and packed element types | `Rejected` | `None` | none | No PTX-visible FP, vector, or packed-type execution path exists in v1. |

## Special Registers

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `%tid.x` | Thread index within the current block | `Implemented` | `Direct` | `PA2`, `SM3` | The assembler, machine encoding, and execution path are all exercised. |
| `%laneid`, `%warpid`, `%ntid.x`, `%ctaid.x`, `%nctaid.x` | PTX exposes lane, warp, block, and grid builtins | `Implemented` | `None` | none | These builtins are accepted by the assembler and mapped in hardware, but no dedicated execution test proves them yet. |

Repo-specific note: `%argbase` is accepted by the current assembler as a SpinalGPU escape hatch for `.param` lowering. It is not part of the intended public PTX subset contract and is intentionally excluded from the matrix.

## Instruction Surface

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `mov.u32` | Register moves, immediates, and builtin reads for scalar integer code | `Implemented` | `Direct` | `PA2`, `SM3`, `SM4` | Supports register, immediate, supported special-register, and shared-symbol source forms. |
| `add.u32` | Integer add in the scalar ALU path | `Implemented` | `Direct` | `SM4`, `SM5`, `SM7` | Register and immediate RHS forms are accepted. |
| `sub.u32` | Integer subtract in the scalar ALU path | `Implemented` | `None` | none | Parsed and lowered to machine `sub`, but no current test exercises it directly. |
| `mul.lo.u32` | Low 32-bit integer multiply | `Implemented` | `None` | none | Parsed and lowered to machine `mullo`, but no current test exercises it directly. |
| `shl.b32` | Logical left shift for scalar integer values | `Implemented` | `Direct` | `SM3`, `SM6`, `SM7` | Used in the corpus for 4-byte address scaling. |
| `setp.eq.u32` | Equality compare producing a predicate value | `Implemented` | `Indirect` | `PA2` | Shares the same lowering path as `setp.ne.u32`, but lacks a dedicated kernel or unit test. |
| `setp.ne.u32` | Inequality compare producing a predicate value | `Implemented` | `Direct` | `PA2`, `SM5`, `SM10` | Lowered through the same compare path plus optional negate. |
| `bra` | Local control-flow branch to a label | `Implemented` | `Indirect` | `PA2` | Branch target resolution is exercised, but there is no dedicated unconditional-branch corpus case. |
| `@%p bra` | Predicate-true conditional branch | `Implemented` | `Direct` | `PA2`, `SM5`, `SM10` | Works for the current predicate subset and local labels. |
| `@!%p bra` | Predicate-false conditional branch | `Implemented` | `Indirect` | `PA2` | Shares the predicated-branch lowering path, but no dedicated execution case uses the negated form. |
| `ret` | Return from a kernel entry or device function | `Partial` | `Direct` | `PA1`, `PA2`, `SM3`-`SM7` | Supported only as kernel exit. It lowers to warp-wide `exit`; there is no PTX function-call return model. |
| `trap` | Explicit runtime trap | `Implemented` | `Direct` | `SM11` | Traps latch `FAULT_CODE=trap` and complete through the normal fault path. |

## Memory Spaces And Addressing

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `.param` plus `ld.param.u32` | Entry parameters must be addressable through the PTX parameter state space | `Partial` | `Direct` | `PA1`, `SM3`-`SM7` | Only entry-scope `.param .u32` is supported. It lowers onto the host-provided `ARG_BASE` buffer. |
| `.global` plus `ld.global.u32` / `st.global.u32` | PTX global memory loads and stores | `Partial` | `Direct` | `SM3`, `SM4`, `SM5`, `SM7`, `SM9` | Only aligned 32-bit word accesses are supported. Misaligned accesses fault. |
| `.shared` declarations plus `ld.shared.u32` / `st.shared.u32` | PTX shared memory declarations and accesses | `Partial` | `Direct` | `PA3`, `SM6` | Declarations use `.shared .align N .b8 name[bytes];`. Execution supports 32-bit word accesses only. |
| Byte-addressed addresses with aligned 32-bit words | PTX uses byte addresses across state spaces | `Partial` | `Direct` | `SM8`, `SM9` | Effective addresses are byte-based, but legal fetch/load/store access is aligned 32-bit only in v1. |
| Shared symbol materialization via `mov.u32 %rX, shared_symbol` | PTX code may need to form a shared-space byte offset from a symbol | `Implemented` | `None` | none | The assembler lowers a shared symbol to its byte offset, but no dedicated test exercises this spelling. |
| Shared symbol plus register addressing | PTX addressing commonly combines symbols, registers, and immediates | `Implemented` | `Direct` | `PA3`, `SM6` | Supported for `.shared` loads and stores and used for per-thread shared indexing. |
| Generic addressing and `cvta` | PTX commonly converts between state-space and generic addresses | `Rejected` | `None` | none | No generic-address instructions, `cvta`, or address-space conversion path exists. |

## Currently Unsupported PTX Families

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `.const` state space | PTX defines constant memory and constant-space loads | `Rejected` | `Direct` | `PA4` | `.const` declarations are rejected by the frontend today. |
| `.local` state space | PTX defines per-thread local memory and spill-like addressing | `Rejected` | `None` | none | No `.local` declarations, addressing, or lowering path exists. |
| Floating-point arithmetic and conversion | PTX supports FP ALU, comparisons, rounding, and conversion instructions | `Rejected` | `None` | none | No PTX-visible FP execution path exists in v1. |
| SFU instructions | PTX includes special-function instructions such as reciprocal and transcendental ops | `Rejected` | `None` | none | No PTX SFU mnemonics are accepted yet. |
| Tensor and MMA instructions | PTX includes tensor fragment, MMA, and tensor-memory instructions | `Rejected` | `None` | none | No PTX tensor surface is exposed yet. |
| Barriers and synchronization | PTX includes CTA sync, async copy coordination, and barrier objects | `Rejected` | `None` | none | No PTX-visible barrier or synchronization instructions exist in the current frontend/runtime path. |
| Atomics and reductions | PTX includes atomic and reduction memory operations | `Rejected` | `None` | none | No atomic lowering or memory-ordering support exists. |
| Function calls and device functions | PTX modules may define functions and issue `call` instructions | `Rejected` | `Direct` | `PA4` | `call` is explicitly rejected. `ret` works only as kernel exit. |
| Memory-ordering qualifiers and cache modifiers | PTX supports qualifiers such as `.relaxed`, `.release`, `.volatile`, and cache modifiers | `Rejected` | `None` | none | Current memory ops are plain word loads/stores with no PTX memory-model qualifier surface. |
| Arbitrary `nvcc -ptx` output | PTX is normally broad enough for compiler-generated code distribution | `Rejected` | `Direct` | `PA4`, `KM3` | The repo frontend accepts only the constrained module shape and instruction subset documented on this page. |
