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
| `PA6` | `PtxAssemblerSpec`: lowers extended special-register reads plus narrow `%gridid` `.u64` materialization/store |
| `PA7` | `PtxAssemblerSpec`: allocates `%f` registers from the shared physical pool and lowers FP32 instructions plus 2D/3D coordinate guards |
| `PA8` | `PtxAssemblerSpec`: lowers scalar integer bitwise ops, signed compare, and `selp.u32` |
| `PA9` | `PtxAssemblerSpec`: lowers scalar FP compare/select and unary FP ops |
| `PA10` | `PtxAssemblerSpec`: lowers `min/max` convenience ops and `mad.lo.u32` |
| `PA11` | `PtxAssemblerSpec`: lowers `.v2/.v4 .f32` tuple `mov/ld/st.global` and rejects malformed vector tuples |
| `KM1` | `KernelCorpusSpec`: kernel corpus references every `.ptx` file exactly once and generated binaries exist |
| `KM2` | `KernelCorpusSpec`: kernel corpus metadata is complete and expectation types match teaching levels |
| `KM3` | `KernelCorpusSpec`: PTX headers match declarative metadata and follow the teaching template |
| `CU1` | `CudaCoreArraySpec`: integer subwarp slicing plus FP32 `fadd`/`ffma` return exact lane results |
| `CU2` | `CudaCoreArraySpec`: signed compare, branchless select, and scalar FP unary/compare ops return exact lane results |
| `LSU1` | `LoadStoreUnitSpec`: contiguous global accesses coalesce into bursts and sparse accesses split correctly |
| `AXI1` | `ExternalMemoryAxiAdapterSpec`: burst reads and writes drive AXI multi-beat traffic correctly |
| `SM1` | `StreamingMultiprocessorSimSpec`: SM admission controller initializes warp contexts and schedules multiple warps |
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
| `SM12` | `StreamingMultiprocessorSimSpec`: `basic_special_register_store` completes and writes expected builtin values |
| `SM13` | `StreamingMultiprocessorSimSpec`: `grid_id_store` completes and writes the initial `%gridid` value |
| `SM14` | `StreamingMultiprocessorSimSpec`: `matrix_add_f32` completes and writes expected FP32 matrix sums |
| `SM15` | `StreamingMultiprocessorSimSpec`: `matrix_mul_f32` completes and writes expected FP32 matrix products |
| `SM16` | `StreamingMultiprocessorKernelCorpusSpec`: `relu_clamp_f32` completes and writes expected branchless activation outputs |
| `SM17` | `StreamingMultiprocessorKernelCorpusSpec`: `linear_bias_relu_f32` completes and writes expected dense-layer outputs |
| `SM18` | `StreamingMultiprocessorKernelCorpusSpec`: `hinge_step_f32` completes and writes expected hinge-loss terms |
| `SM19` | `StreamingMultiprocessorKernelCorpusSpec`: `bitops_pack_u32` completes and writes expected bit-manipulation results |
| `SM20` | `StreamingMultiprocessorKernelCorpusSpec`: `vector_load_store_f32x2` completes and round-trips FP32 float2 tuples |
| `SM21` | `StreamingMultiprocessorKernelCorpusSpec`: `vector_load_store_f32x4` completes and round-trips FP32 float4 tuples |
| `SM22` | `StreamingMultiprocessorKernelCorpusSpec`: `vector_add_f32x4` completes and writes expected float4 sums |
| `GT1` | `GpuTopSimSpec`: `GpuTop` exposes idle AXI memory and AXI-Lite control boundaries |
| `GT2` | `ExecutionFrontendSimSpec`: `grid_id_store` increments across successive `GpuTop` launches |
| `GT3` | `ExecutionFrontendSimSpec`: `matrix_add_f32` executes through `GpuTop` and writes expected FP32 output |
| `GT4` | `ExecutionFrontendSimSpec`: `matrix_mul_f32` executes through `GpuTop` and writes expected FP32 output |
| `GT5` | `ExecutionFrontendSimSpec`: `linear_bias_relu_f32` executes through `GpuTop` and writes expected dense-layer output |
| `GT6` | `ExecutionFrontendSimSpec`: `vector_add_f32x4` executes through `GpuTop` and writes expected float4 output |

## Compatibility Summary

| PTX family | Overall status | Coverage | Notes |
| --- | --- | --- | --- |
| Execution and launch model | `Partial` | `Direct` | Classic SIMT v1 on one SM, one block per launch, full 3D block-shape ABI, no divergent reconvergence support |
| Module and entry contract | `Partial` | `Direct` | One `.visible .entry` per file, `.param .u32` only |
| Types, registers, and predicates | `Partial` | `Direct` | `.u32` and `.f32` share one physical 32-bit register pool; PTX vector tuples reuse `%f` registers; `%gridid` remains the only narrow public `.u64` path |
| Special registers | `Partial` | `Direct` | `tid/ntid/ctaid/nctaid.{x,y,z}` plus core SIMT/SM builtins are covered directly; `%gridid` remains a narrow `.u64` path |
| Instruction surface | `Partial` | `Direct` | Integer, control, FP32 CUDA-core ops, PTX vector `.v2/.v4 .f32` tuple movement, and narrow `.u64` data movement are implemented for the documented subset |
| Memory spaces and addressing | `Partial` | `Direct` | `.param`, `.global`, and `.shared` only; aligned 32-bit words, FP32 global traffic, coalesced bursts, PTX vector tuple loads/stores lowered element-wise, plus lowered `st.global.u64` |
| Currently unsupported PTX families | `Rejected` | `Direct` | `.const`, `.local`, FP beyond the narrow `.f32` subset, packed/vector ALU beyond tuple movement, SFU, tensor, sync, atomics, calls, and broad compiler-generated PTX remain out of scope |

## Execution And Launch Model

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| SIMT warp execution | PTX kernels execute many logical threads grouped into warps and CTAs | `Implemented` | `Direct` | `SM1`, `SM3`-`SM7` | Current frontend runs classic SIMT with one selected warp and one issued warp instruction at a time. |
| One warp PC + active mask model | The implementation must preserve PTX per-thread semantics across active lanes | `Partial` | `Direct` | `SM1`, `SM10` | SpinalGPU uses one PC plus one active mask per warp; no reconvergence stack or independent thread scheduling exists. |
| One block per launch | PTX launch semantics assume grid/block decomposition | `Partial` | `Direct` | `SM1`, `GT1` | The host ABI carries full 3D grid/block fields, but v1 still requires `gridDim = (1,1,1)` and launches one CTA. |
| CUDA-style 3D block shape | PTX thread builtins are dimensioned in `x/y/z` | `Partial` | `Direct` | `PA7`, `SM14`, `SM15` | `blockDim.{x,y,z}` and `tid.{x,y,z}` are real. `ctaid.{x,y,z}` are fixed at zero and `nctaid.{x,y,z}` at one because only one CTA launches. |
| Launch-time `ENTRY_PC / GRID_DIM_{X,Y,Z} / BLOCK_DIM_{X,Y,Z} / ARG_BASE / SHARED_BYTES` | The runtime must supply entry metadata, parameter base, and shared-memory size | `Implemented` | `Direct` | `SM1`, `GT1`, `GT3`, `GT4` | This is a repo-specific MMIO ABI. `ENTRY_PC` points at SpinalGPU machine code, not PTX source text. |
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
| `.reg .u32` | PTX virtual registers for typed scalar values | `Implemented` | `Direct` | `PA1`, `KM3` | `.reg .u32 %r<N>;` declarations are accepted. |
| `.reg .f32` | PTX virtual registers for FP32 scalar values | `Implemented` | `Direct` | `PA7`, `SM14`, `SM15`, `GT3`, `GT4` | `.reg .f32 %f<N>;` declarations are accepted and allocate from the same physical 32-bit register file as `%r<N>`. |
| PTX vector brace tuples over `%f<N>` registers | PTX may spell `.v2/.v4 .f32` data movement with register tuples | `Partial` | `Direct` | `PA11`, `SM20`, `SM21`, `SM22`, `GT6` | Tuple operands such as `{%f0, %f1, %f2, %f3}` are accepted only for `.v2/.v4 .f32` `mov/ld/st.global`. There is no `.reg .v2/.v4` declaration family. |
| `.reg .u64` | PTX virtual registers for 64-bit scalar values | `Partial` | `Direct` | `PA6`, `SM13`, `GT2` | Supported only for `%gridid` materialization and `st.global.u64`; `.u64` arithmetic and loads remain unsupported. |
| 32-bit scalar integer execution | A backend must provide typed scalar integer execution for the supported subset | `Implemented` | `Direct` | `SM4`, `SM5`, `SM7`, `SM14`, `SM15` | Integer address arithmetic, loop control, predicates, and shared/global indexing execute on the CUDA-core integer path. |
| 32-bit scalar FP execution | A backend must provide typed FP32 execution for the supported subset | `Implemented` | `Direct` | `CU1`, `CU2`, `SM14`, `SM15`, `SM16`, `SM17`, `SM18`, `GT3`, `GT4`, `GT5` | `add.f32`, `mul.f32`, `sub.f32`, `neg.f32`, `abs.f32`, ordered `setp.*.f32`, `selp.f32`, and PTX `fma.rn.f32` are implemented on the CUDA-core path. The current `fma.rn.f32` lowering is a three-source multiply-add, not a single-round fused IEEE FMA. |
| `.pred` support | PTX uses predicate registers for condition evaluation and branch predication | `Partial` | `Direct` | `PA2`, `SM5`, `SM10` | Predicates lower to compiler-managed integer-backed condition values, not a native PTX-style predicate register file. |
| Wider and alternate integer widths beyond the narrow `%gridid` path (`.s64`, general `.u64`, `.u16`, `.u8`) | PTX supports multiple integer widths | `Rejected` | `Direct` | `PA4` | General non-`%gridid` wider integer support is still rejected by the frontend. |
| Floating-point families beyond the narrow FP32 subset (`.f64`, conversions, unordered compares, vector register types, and packed/tensor formats) | PTX supports broader scalar FP, vector, and packed element types | `Rejected` | `Direct` | `PA4` | Only scalar `.f32` register values plus the explicit `.v2/.v4 .f32` tuple data-movement spellings documented below are accepted in v1. |

## Special Registers

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `%tid.{x,y,z}` | Thread index within the current block | `Implemented` | `Direct` | `PA7`, `SM3`, `SM14`, `SM15` | `tid.x` is exercised by the existing integer kernels; `tid.y` and `tid.z` are now lowered and dimensioned from the launch-time 3D block shape. |
| `%ntid.{x,y,z}` | Block dimensions visible to each thread | `Implemented` | `Direct` | `PA7`, `SM12`, `SM14`, `SM15` | All three dimensions are carried in the host ABI and returned directly from the current command descriptor. |
| `%ctaid.{x,y,z}`, `%nctaid.{x,y,z}` | CTA coordinates and grid shape builtins | `Partial` | `Direct` | `SM12`, `SM14`, `SM15` | The full `x/y/z` surface exists, but v1 still launches exactly one CTA, so `%ctaid.* == 0` and `%nctaid.* == 1`. |
| `%laneid`, `%warpid`, `%nwarpid`, `%smid`, `%nsmid` | PTX exposes lane, warp, block, and SM builtins | `Implemented` | `Direct` | `PA6`, `SM12` | These builtins are accepted by the assembler and mapped directly in hardware for the current one-SM architecture. |
| `%gridid` | PTX exposes a temporal grid launch identifier | `Partial` | `Direct` | `PA6`, `SM13`, `GT2` | Supported only as `.u64` via `mov.u64 %rdX, %gridid`, backed by a launch counter and typically consumed with `st.global.u64`. |

Repo-specific note: `%argbase` is accepted by the current assembler as a SpinalGPU escape hatch for `.param` lowering. It is not part of the intended public PTX subset contract and is intentionally excluded from the matrix.

## Instruction Surface

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `mov.u32` | Register moves, immediates, and builtin reads for scalar integer code | `Implemented` | `Direct` | `PA2`, `SM3`, `SM4` | Supports register, immediate, supported special-register, and shared-symbol source forms. |
| `mov.f32` | Register moves and immediate zero materialization for FP32 code | `Partial` | `Direct` | `PA7`, `SM14`, `SM15` | Supports register-to-register moves and `mov.f32 %fX, 0f00000000`. Other float literal spellings remain rejected. |
| `mov.v2.f32`, `mov.v4.f32` | PTX tuple move across existing `%f` registers | `Partial` | `Direct` | `PA11`, `SM20`, `SM21`, `SM22` | Supported only with brace tuples over `%f` registers. The assembler lowers each tuple move into ordered scalar `mov.f32` machine operations. |
| `mov.u64` | 64-bit move for narrow builtin materialization | `Partial` | `Direct` | `PA6`, `SM13`, `GT2` | Supported only for `mov.u64 %rdX, %gridid`. Other `.u64` move forms are rejected. |
| `add.u32` | Integer add in the scalar ALU path | `Implemented` | `Direct` | `SM4`, `SM5`, `SM7` | Register and immediate RHS forms are accepted. |
| `sub.u32` | Integer subtract in the scalar ALU path | `Implemented` | `None` | none | Parsed and lowered to machine `sub`, but no current test exercises it directly. |
| `mul.lo.u32` | Low 32-bit integer multiply | `Implemented` | `Direct` | `SM15`, `GT4` | Used directly for matrix indexing and loop address arithmetic. |
| `mad.lo.u32` | Multiply-add convenience form for integer address arithmetic | `Partial` | `Direct` | `PA10` | Lowered in the PTX frontend to `mul.lo.u32` plus `add.u32`; there is no dedicated machine opcode. |
| `and.b32`, `or.b32`, `xor.b32`, `shr.b32` | Scalar bitwise and logical-right-shift integer ops | `Implemented` | `Direct` | `PA8`, `SM19` | Lower directly to machine `and`, `or`, `xor`, and `shr`. |
| `add.f32` | FP32 addition on the CUDA-core datapath | `Implemented` | `Direct` | `PA7`, `CU1`, `SM14`, `GT3` | Lowered to machine `fadd`. |
| `mul.f32` | FP32 multiplication on the CUDA-core datapath | `Implemented` | `Direct` | `PA7`, `SM15`, `GT4` | Lowered to machine `fmul`. |
| `sub.f32`, `neg.f32`, `abs.f32` | Scalar FP32 subtract and unary ops | `Implemented` | `Direct` | `PA9`, `CU2`, `SM18` | Lower directly to machine `fsub`, `fneg`, and `fabs`. |
| `fma.rn.f32` | FP32 three-source multiply-add on the CUDA-core datapath | `Implemented` | `Direct` | `PA7`, `CU1`, `SM15`, `SM17`, `GT4`, `GT5` | Lowered to machine `ffma` with a dedicated three-source encoding. The current repo implementation rounds the multiply and add stages separately rather than providing a single-round fused IEEE FMA. |
| `min/max.{u32,s32,f32}` | Branchless min/max convenience forms | `Partial` | `Direct` | `PA10`, `SM16`, `SM18` | Lowered in the PTX frontend to compare plus `selp`; there is no dedicated machine min/max opcode. |
| `shl.b32` | Logical left shift for scalar integer values | `Implemented` | `Direct` | `SM3`, `SM6`, `SM7` | Used in the corpus for 4-byte address scaling. |
| `setp.{eq,ne,lt,le,gt,ge}.u32` | Unsigned integer compares producing a predicate value | `Implemented` | `Direct` | `PA2`, `PA7`, `SM5`, `SM10`, `SM14`, `SM15` | `eq`/`lt` lower directly; `ne`/`le`/`gt`/`ge` are derived in the frontend through negate and operand swap over the primitive compare ops. |
| `setp.{eq,ne,lt,le,gt,ge}.s32` | Signed integer compares producing a predicate value | `Implemented` | `Direct` | `PA8`, `CU2`, `SM19` | Lowered through machine `seteq`, machine `setlts`, plus frontend negate and operand swap. |
| `setp.{eq,ne,lt,le,gt,ge}.f32` | Ordered FP32 compares producing a predicate value | `Implemented` | `Direct` | `PA9`, `CU2`, `SM16`, `SM17`, `SM18` | Lowered through machine `fseteq` and `fsetlt` plus frontend negate, OR, and operand swap. Unordered compare variants remain rejected. |
| `selp.u32`, `selp.f32` | Branchless integer and FP32 value selection | `Implemented` | `Direct` | `PA8`, `PA9`, `CU2`, `SM16`, `SM17` | Lowered to machine `sel` and carried on the existing three-source CUDA issue path. |
| `bra` | Local control-flow branch to a label | `Implemented` | `Indirect` | `PA2` | Branch target resolution is exercised, but there is no dedicated unconditional-branch corpus case. |
| `@%p bra` | Predicate-true conditional branch | `Implemented` | `Direct` | `PA2`, `SM5`, `SM10` | Works for the current predicate subset and local labels. |
| `@!%p bra` | Predicate-false conditional branch | `Implemented` | `Indirect` | `PA2` | Shares the predicated-branch lowering path, but no dedicated execution case uses the negated form. |
| `ret` | Return from a kernel entry or device function | `Partial` | `Direct` | `PA1`, `PA2`, `SM3`-`SM7` | Supported only as kernel exit. It lowers to warp-wide `exit`; there is no PTX function-call return model. |
| `trap` | Explicit runtime trap | `Implemented` | `Direct` | `SM11` | Traps latch `FAULT_CODE=trap` and complete through the normal fault path. |

## Memory Spaces And Addressing

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `.param` plus `ld.param.u32` | Entry parameters must be addressable through the PTX parameter state space | `Partial` | `Direct` | `PA1`, `SM3`-`SM7` | Only entry-scope `.param .u32` is supported. It lowers onto the host-provided `ARG_BASE` buffer. |
| `.global` plus `ld.global.u32` / `st.global.u32` | PTX global memory loads and stores | `Partial` | `Direct` | `SM3`, `SM4`, `SM5`, `SM7`, `SM9`, `LSU1`, `AXI1` | Only aligned 32-bit word accesses are supported. Misaligned accesses fault. Contiguous active-lane accesses coalesce into burst traffic on the external-memory path. |
| `.global` plus `ld.global.f32` / `st.global.f32` | PTX global FP32 loads and stores | `Implemented` | `Direct` | `PA7`, `SM14`, `SM15`, `GT3`, `GT4`, `LSU1`, `AXI1` | FP32 global traffic reuses the same aligned 32-bit global-memory path as integers; element type lives in the PTX register namespace, not the LSU opcode. |
| `.global` plus `ld.global.v2/v4.f32` / `st.global.v2/v4.f32` | PTX vector tuple loads and stores over FP32 elements | `Partial` | `Direct` | `PA11`, `SM20`, `SM21`, `SM22`, `GT6`, `LSU1`, `AXI1` | Accepted only for `.v2/.v4 .f32` brace tuples. The assembler lowers them into ordered scalar `ld.global.f32` / `st.global.f32` operations, so alignment remains 4 bytes per element rather than a dedicated 8/16-byte machine transaction. |
| `st.global.u64` | PTX may store 64-bit values to global memory | `Partial` | `Direct` | `PA6`, `SM13`, `GT2` | Supported only for `%gridid`-backed `.u64` registers. The assembler lowers it to two ordered `st.global.u32` machine stores. |
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
| Floating-point families beyond the narrow FP32 subset | PTX supports broader FP ALU, conversions, rounding modes, unordered compares, richer literal forms, and native packed/vector arithmetic | `Rejected` | `Direct` | `PA4` | Today the accepted FP32 surface is `mov.f32`, `mov.v2/v4.f32`, `add.f32`, `mul.f32`, `sub.f32`, `neg.f32`, `abs.f32`, `fma.rn.f32`, ordered `setp.*.f32`, `selp.f32`, `min/max.f32`, `ld.global.f32`, `ld.global.v2/v4.f32`, `st.global.f32`, and `st.global.v2/v4.f32`. |
| SFU instructions | PTX includes special-function instructions such as reciprocal and transcendental ops | `Rejected` | `None` | none | No PTX SFU mnemonics are accepted yet. |
| Tensor and MMA instructions | PTX includes tensor fragment, MMA, and tensor-memory instructions | `Rejected` | `None` | none | No PTX tensor surface is exposed yet. |
| Barriers and synchronization | PTX includes CTA sync, async copy coordination, and barrier objects | `Rejected` | `None` | none | No PTX-visible barrier or synchronization instructions exist in the current frontend/runtime path. |
| Atomics and reductions | PTX includes atomic and reduction memory operations | `Rejected` | `None` | none | No atomic lowering or memory-ordering support exists. |
| Function calls and device functions | PTX modules may define functions and issue `call` instructions | `Rejected` | `Direct` | `PA4` | `call` is explicitly rejected. `ret` works only as kernel exit. |
| Memory-ordering qualifiers and cache modifiers | PTX supports qualifiers such as `.relaxed`, `.release`, `.volatile`, and cache modifiers | `Rejected` | `None` | none | Current memory ops are plain word loads/stores with no PTX memory-model qualifier surface. |
| Arbitrary `nvcc -ptx` output | PTX is normally broad enough for compiler-generated code distribution | `Rejected` | `Direct` | `PA4`, `KM3` | The repo frontend accepts only the constrained module shape and instruction subset documented on this page. |
