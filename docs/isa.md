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
| `PA12` | `PtxAssemblerSpec`: allocates `%h/%x/%b` from the shared physical pool and lowers the supported FP16 and FP8 conversion subset |
| `PA13` | `PtxAssemblerSpec`: lowers the minimal tensor PTX surface and rejects malformed tensor tuples/operands |
| `PA14` | `PtxAssemblerSpec`: lowers the supported unary SFU PTX surface and rejects unsupported SFU spellings such as `.ftz`, BF16, exact-rounding forms, and derived math |
| `TCG1` | `Tcgen05FrontendSpec`: encodes/decodes/disassembles the narrow tcgen05 machine-op slice and assembles the supported tcgen05 PTX spellings |
| `TCG2` | `Tcgen05BlockSpec`: exact tcgen05 TMEM load/store, dense FP16 MMA, and tcgen05 protocol fault behavior |
| `TCG3` | `StreamingMultiprocessorTcgen05Spec` / `GpuTopTcgen05Spec`: the tcgen05 round-trip, dense MMA, and hazard kernels complete through both execution harnesses |
| `TCG4` | `Tcgen05OverlapProgressSpec`: a pending tcgen05 load does not block another warp in the same sub-SM from making progress |
| `KM1` | `KernelCorpusSpec`: kernel corpus references every `.ptx` file exactly once and generated binaries exist |
| `KM2` | `KernelCorpusSpec`: kernel corpus metadata is complete and expectation types match teaching levels |
| `KM3` | `KernelCorpusSpec`: PTX headers match declarative metadata and follow the teaching template |
| `CU1` | `CudaCoreArraySpec`: integer subwarp slicing plus FP32 `fadd`/`ffma` return exact lane results |
| `CU2` | `CudaCoreArraySpec`: signed compare, branchless select, and scalar FP unary/compare ops return exact lane results |
| `CU3` | `CudaCoreArraySpec`: FP16 scalar and packed ops plus packed FP8 conversion ops return exact lane results |
| `SFU1` | `SfuArchitectureSpec`: the SFU path elaborates inside `SmExecutionCore` across default and reduced subwarp configs |
| `SFU2` | `SpecialFunctionUnitSpec`: unary SFU latency, masking, corner cases, reference vectors, and `tanh.approx.f32` behavior are checked directly |
| `LSU1` | `LoadStoreUnitSpec`: contiguous global accesses coalesce into bursts and sparse accesses split correctly |
| `LSU2` | `LoadStoreUnitSpec`: halfword global loads extract the addressed 16-bit lane from narrow bursts |
| `AXI1` | `ExternalMemoryAxiAdapterSpec`: burst reads and writes drive AXI multi-beat traffic correctly |
| `AXI2` | `ExternalMemoryAxiAdapterSpec`: halfword writes drive shifted AXI byte strobes correctly |
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
| `SM14` | `StreamingMultiprocessorKernelCorpusSpec`: `matrix_add_f32` completes and writes expected FP32 matrix sums |
| `SM15` | `StreamingMultiprocessorKernelCorpusSpec`: `matrix_mul_f32` completes and writes expected FP32 matrix products |
| `SM16` | `StreamingMultiprocessorKernelCorpusSpec`: `relu_clamp_f32` completes and writes expected branchless activation outputs |
| `SM17` | `StreamingMultiprocessorKernelCorpusSpec`: `linear_bias_relu_f32` completes and writes expected dense-layer outputs |
| `SM18` | `StreamingMultiprocessorKernelCorpusSpec`: `hinge_step_f32` completes and writes expected hinge-loss terms |
| `SM19` | `StreamingMultiprocessorKernelCorpusSpec`: `bitops_pack_u32` completes and writes expected bit-manipulation results |
| `SM20` | `StreamingMultiprocessorKernelCorpusSpec`: `vector_load_store_f32x2` completes and round-trips FP32 float2 tuples |
| `SM21` | `StreamingMultiprocessorKernelCorpusSpec`: `vector_load_store_f32x4` completes and round-trips FP32 float4 tuples |
| `SM22` | `StreamingMultiprocessorKernelCorpusSpec`: `vector_add_f32x4` completes and writes expected float4 sums |
| `SM23` | `StreamingMultiprocessorKernelCorpusSpec`: `matrix_copy_f32` completes and writes expected FP32 matrix copies |
| `SM24` | `StreamingMultiprocessorKernelCorpusSpec`: `matrix_transpose_f32` completes and writes expected FP32 matrix transposes |
| `SM25` | `StreamingMultiprocessorKernelCorpusSpec`: `scalar_add_f16` completes and writes expected FP16 scalar sums |
| `SM26` | `StreamingMultiprocessorKernelCorpusSpec`: `vector_add_f16x2` completes and writes expected packed FP16x2 sums |
| `SM27` | `StreamingMultiprocessorKernelCorpusSpec`: `matrix_add_f16` completes and writes expected FP16 matrix sums |
| `SM28` | `StreamingMultiprocessorKernelCorpusSpec`: `matrix_mul_f16_accum_f32` completes and writes expected FP32 matrix products from FP16 inputs |
| `SM29` | `StreamingMultiprocessorKernelCorpusSpec`: `scalar_convert_e4m3x2_f16x2` completes and writes expected packed FP16x2 values |
| `SM30` | `StreamingMultiprocessorKernelCorpusSpec`: `scalar_convert_e5m2x2_f16x2` completes and writes expected packed FP16x2 values |
| `SM31` | `StreamingMultiprocessorKernelCorpusSpec`: `vector_add_e4m3x2` completes and writes expected packed E4M3x2 results |
| `SM32` | `StreamingMultiprocessorKernelCorpusSpec`: `vector_add_e5m2x2` completes and writes expected packed E5M2x2 results |
| `SM33` | `StreamingMultiprocessorKernelCorpusSpec`: `matrix_mul_e4m3x2_accum_f32` completes and writes expected FP32 matrix products from packed E4M3x2 inputs |
| `SM34` | `StreamingMultiprocessorKernelCorpusSpec`: `matrix_mul_e5m2x2_accum_f32` completes and writes expected FP32 matrix products from packed E5M2x2 inputs |
| `SM35` | `StreamingMultiprocessorTensorSpec`: the tensor-only `ldmatrix/stmatrix` round-trip and FP16 MMA kernels complete through the SM harness |
| `SM36` | `StreamingMultiprocessorKernelCorpusSpec`: `scalar_special_f32` completes and writes expected FP32 SFU result streams |
| `SM37` | `StreamingMultiprocessorKernelCorpusSpec`: `trig_pair_f32` completes and writes expected FP32 `sin/cos` SFU result streams |
| `SM38` | `StreamingMultiprocessorKernelCorpusSpec`: `scalar_special_f16` completes and writes expected FP16 SFU results |
| `SM39` | `StreamingMultiprocessorKernelCorpusSpec`: `vector_special_f16x2` completes and writes expected packed FP16x2 SFU results |
| `GT1` | `GpuTopSimSpec`: `GpuTop` exposes idle AXI memory and AXI-Lite control boundaries |
| `GT2` | `ExecutionFrontendSimSpec`: `grid_id_store` increments across successive `GpuTop` launches |
| `GT3` | `ExecutionFrontendSimSpec`: `matrix_add_f32` executes through `GpuTop` and writes expected FP32 output |
| `GT4` | `ExecutionFrontendSimSpec`: `matrix_mul_f32` executes through `GpuTop` and writes expected FP32 output |
| `GT5` | `ExecutionFrontendSimSpec`: `linear_bias_relu_f32` executes through `GpuTop` and writes expected dense-layer output |
| `GT6` | `ExecutionFrontendSimSpec`: `vector_add_f32x4` executes through `GpuTop` and writes expected float4 output |
| `GT7` | `ExecutionFrontendSimSpec`: `matrix_copy_f32` executes through `GpuTop` and writes expected FP32 output |
| `GT8` | `ExecutionFrontendSimSpec`: `matrix_add_f16` executes through `GpuTop` and writes expected FP16 output |
| `GT9` | `ExecutionFrontendSimSpec`: `matrix_mul_f16_accum_f32` executes through `GpuTop` and writes expected FP32 output from FP16 inputs |
| `GT10` | `ExecutionFrontendSimSpec`: `vector_add_e4m3x2` executes through `GpuTop` and writes expected packed E4M3x2 output |
| `GT11` | `ExecutionFrontendSimSpec`: `matrix_mul_e5m2x2_accum_f32` executes through `GpuTop` and writes expected FP32 output from packed E5M2x2 inputs |
| `GT12` | `MultiSmGpuTopSpec`: `block_id_store` executes through `GpuTop` over a real 3D CTA grid and writes `%ctaid/%nctaid` records |
| `GT13` | `MultiSmGpuTopSpec`: `vector_add_multi_block` executes through `GpuTop` across multiple CTAs |
| `GT14` | `MultiSmGpuTopSpec`: `smid_store` executes through `GpuTop` and writes real `%smid/%nsmid` values across multiple SMs |
| `GT15` | `MultiSmGpuTopSpec`: `trap_block_one` executes through `GpuTop` and stops later CTA dispatch after the first CTA trap |
| `GT16` | `MultiSmGpuTopSpec`: `matrix_add_multi_block_f32` executes through `GpuTop` across a real 2D CTA grid |
| `GT17` | `GpuTopTensorSpec`: the tensor-only `ldmatrix/stmatrix` round-trip and FP16 MMA kernels complete through `GpuTop` |
| `GT18` | `ExecutionFrontendSimSpec`: `scalar_special_f32` executes through `GpuTop` and writes expected FP32 SFU outputs |
| `TC1` | `TensorCoreBlockSpec`: exact `ldmatrix`, `mma`, `stmatrix`, and tensor fault behavior for the FP16 tensor v1 slice |
| `GD1` | `GridDispatchControllerSpec`: dispatcher walks CTA coordinates in `x -> y -> z` order |
| `GD2` | `GridDispatchControllerSpec`: dispatcher round-robins CTAs across SMs and backfills idle SMs |
| `GD3` | `GridDispatchControllerSpec`: first SM fault stops further CTA dispatch and reports one kernel-global fault |

## Compatibility Summary

| PTX family | Overall status | Coverage | Notes |
| --- | --- | --- | --- |
| Execution and launch model | `Partial` | `Direct` | Classic SIMT v1 on one or more physical SMs, one kernel globally in flight, one resident CTA per SM, real 3D CTA grids, and no divergent reconvergence support |
| Module and entry contract | `Partial` | `Direct` | One `.visible .entry` per file, `.param .u32` only |
| Types, registers, and predicates | `Partial` | `Direct` | `.u32`, `.f32`, `.f16`, `.f16x2`, and `.b16` share one physical 32-bit register pool; PTX vector tuples reuse `%f` registers; `%gridid` remains the only narrow public `.u64` path |
| Special registers | `Partial` | `Direct` | `tid/ntid/ctaid/nctaid.{x,y,z}` plus core SIMT/SM builtins are covered directly; `%gridid` remains a narrow `.u64` path |
| Instruction surface | `Partial` | `Direct` | Integer, control, FP32 CUDA-core ops, unary SFU ops, FP16 scalar/packed ops, packed FP8 conversion ops, the legacy FP16 tensor v1 surface, the narrow tcgen05 FP16 v2 surface, PTX vector `.v2/.v4 .f32` tuple movement, and narrow `.u64` data movement are implemented for the documented subset |
| Memory spaces and addressing | `Partial` | `Direct` | `.param`, `.global`, and `.shared` only in the general PTX surface, plus the narrow tcgen05 Tensor Memory path; aligned 16-bit and 32-bit global accesses, FP16/FP32 global traffic, packed FP8 carriers in `.b16`, coalesced bursts, PTX vector tuple loads/stores lowered element-wise, lowered `st.global.u64`, the legacy tensor shared-memory row-address protocol, and fixed-window TMEM addressing through tcgen05 |
| Currently unsupported PTX families | `Rejected` | `Direct` | `.const`, `.local`, BF16, broad `cvt.*`, sync, atomics, calls, broad compiler-generated PTX, and SFU spellings beyond the documented unary `.approx` subset remain out of scope; tensor support is still limited to the legacy FP16 tensor v1 slice plus the narrow tcgen05 FP16 v2 slice below |

## Matrix V1 Note

Current matrix support in SpinalGPU should be read as **matrix v1**:

- the teaching ladder defaults to one CTA, even though the runtime now supports real multi-CTA grids
- untiled row-major kernels
- inputs and outputs in global memory
- scalar CUDA-core FP32 execution under the hood
- no general matrix tiling library, tensor scheduling overlap, or matrix-specific multi-CTA tiling/decomposition yet

The current teaching ladder for that matrix v1 path is `matrix_copy_f32`, `matrix_transpose_f32`, `matrix_add_f32`, and `matrix_mul_f32`.

The runtime also now includes one explicit multi-CTA matrix teaching case, `matrix_add_multi_block_f32`, to prove that CTA-grid decomposition works across multiple SMs without changing the untiled matrix-v1 execution model.

Low-precision CUDA-core kernels extend that same matrix v1 model rather than defining a separate matrix architecture:

- `matrix_add_f16` keeps FP16 inputs and FP16 outputs in global memory
- `matrix_mul_f16_accum_f32` uses FP16 inputs with FP32 accumulation and FP32 outputs
- `matrix_mul_e4m3x2_accum_f32` and `matrix_mul_e5m2x2_accum_f32` use packed FP8 carriers in global memory, convert them through `f16x2`, and accumulate in FP32

## Execution And Launch Model

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| SIMT warp execution | PTX kernels execute many logical threads grouped into warps and CTAs | `Implemented` | `Direct` | `SM1`, `SM3`-`SM7`, `GT12`, `GT13` | Each physical SM runs classic SIMT with one selected warp and one issued warp instruction at a time. The chip-level cluster dispatches CTAs across multiple SMs. |
| One warp PC + active mask model | The implementation must preserve PTX per-thread semantics across active lanes | `Partial` | `Direct` | `SM1`, `SM10` | SpinalGPU uses one PC plus one active mask per warp; no reconvergence stack or independent thread scheduling exists. |
| Real CTA grid scheduling | PTX launch semantics assume grid/block decomposition | `Implemented` | `Direct` | `GD1`, `GD2`, `GT12`, `GT13`, `GT14` | The cluster dispatcher walks the full 3D CTA grid, assigns CTAs to idle SMs round-robin, and keeps one resident CTA per SM in this phase. |
| CUDA-style 3D block and grid shape | PTX thread and CTA builtins are dimensioned in `x/y/z` | `Implemented` | `Direct` | `PA7`, `GD1`, `GT12` | `blockDim.{x,y,z}`, `tid.{x,y,z}`, `gridDim.{x,y,z}`, `ctaid.{x,y,z}`, and `nctaid.{x,y,z}` are all real in the current runtime. |
| Launch-time `ENTRY_PC / GRID_DIM_{X,Y,Z} / BLOCK_DIM_{X,Y,Z} / ARG_BASE / SHARED_BYTES` | The runtime must supply entry metadata, parameter base, and shared-memory size | `Implemented` | `Direct` | `SM1`, `GT1`, `GT3`, `GT4`, `GT12`, `GT13` | This is a repo-specific MMIO ABI. `ENTRY_PC` points at SpinalGPU machine code, not PTX source text. The grid fields are now consumed by the chip-level dispatcher, not just stored. |
| Completion and fault signaling | The runtime must observe completion and runtime failure | `Implemented` | `Direct` | `GT1`, `SM2`, `SM8`-`SM11`, `GD3`, `GT15` | `STATUS.done` is raised on both success and fault. In the multi-SM path, the first CTA fault stops later CTA dispatch and is reported once the cluster drains. |
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
| `.reg .f16` | PTX virtual registers for FP16 scalar values | `Implemented` | `Direct` | `PA12`, `CU3`, `SM25`, `SM27`, `SM28`, `GT8`, `GT9` | `.reg .f16 %h<N>;` declarations are accepted. Values live in the low 16 bits of the shared physical 32-bit register slot. |
| `.reg .f16x2` | PTX virtual registers for packed FP16x2 values | `Implemented` | `Direct` | `PA12`, `CU3`, `SM26`, `SM29`, `SM30`, `GT10`, `GT11` | `.reg .f16x2 %x<N>;` declarations are accepted. Each packed half2-style value occupies one physical 32-bit register slot. |
| `.reg .b16` | PTX virtual registers for packed FP8 carriers | `Implemented` | `Direct` | `PA12`, `CU3`, `SM29`, `SM30`, `SM31`, `SM32`, `SM33`, `SM34`, `GT10`, `GT11` | `.reg .b16 %b<N>;` declarations are accepted for packed `e4m3x2` / `e5m2x2` carrier words. Values live in the low 16 bits of the shared physical 32-bit register slot. |
| PTX vector brace tuples over `%f<N>` registers | PTX may spell `.v2/.v4 .f32` data movement with register tuples | `Partial` | `Direct` | `PA11`, `SM20`, `SM21`, `SM22`, `GT6` | Tuple operands such as `{%f0, %f1, %f2, %f3}` are accepted only for `.v2/.v4 .f32` `mov/ld/st.global`. There is no `.reg .v2/.v4` declaration family. |
| `.reg .u64` | PTX virtual registers for 64-bit scalar values | `Partial` | `Direct` | `PA6`, `SM13`, `GT2` | Supported only for `%gridid` materialization and `st.global.u64`; `.u64` arithmetic and loads remain unsupported. |
| 32-bit scalar integer execution | A backend must provide typed scalar integer execution for the supported subset | `Implemented` | `Direct` | `SM4`, `SM5`, `SM7`, `SM14`, `SM15` | Integer address arithmetic, loop control, predicates, and shared/global indexing execute on the CUDA-core integer path. |
| 32-bit scalar FP execution | A backend must provide typed FP32 execution for the supported subset | `Implemented` | `Direct` | `CU1`, `CU2`, `SM14`, `SM15`, `SM16`, `SM17`, `SM18`, `GT3`, `GT4`, `GT5` | `add.f32`, `mul.f32`, `sub.f32`, `neg.f32`, `abs.f32`, ordered `setp.*.f32`, `selp.f32`, and PTX `fma.rn.f32` are implemented on the CUDA-core path. The current `fma.rn.f32` lowering is a three-source multiply-add, not a single-round fused IEEE FMA. |
| FP16 scalar and packed execution | A backend must provide typed low-precision execution for the supported subset | `Implemented` | `Direct` | `PA12`, `PA14`, `CU3`, `SFU2`, `SM25`, `SM26`, `SM27`, `SM28`, `SM38`, `SM39`, `GT8`, `GT9` | `add.rn.f16`, `mul.rn.f16`, `fma.rn.f16`, `add.rn.f16x2`, `mul.rn.f16x2`, `cvt.f32.f16`, `cvt.rn.f16.f32`, `ex2.approx.f16`, `tanh.approx.f16`, `ex2.approx.f16x2`, and `tanh.approx.f16x2` are implemented. |
| Packed FP8 alternate-format conversion | PTX alternate floating-point formats must be representable when supported | `Implemented` | `Direct` | `PA12`, `CU3`, `SM29`, `SM30`, `SM31`, `SM32`, `SM33`, `SM34`, `GT10`, `GT11` | SpinalGPU supports packed `e4m3x2` and `e5m2x2` carriers via `.b16` plus conversion to and from `.f16x2`. There is no public scalar `.e4m3` or `.e5m2` register family. |
| `.pred` support | PTX uses predicate registers for condition evaluation and branch predication | `Partial` | `Direct` | `PA2`, `SM5`, `SM10` | Predicates lower to compiler-managed integer-backed condition values, not a native PTX-style predicate register file. |
| Wider and alternate integer widths beyond the narrow `%gridid` path (`.s64`, general `.u64`, `.u16`, `.u8`) | PTX supports multiple integer widths | `Rejected` | `Direct` | `PA4` | General non-`%gridid` wider integer support is still rejected by the frontend. |
| Floating-point families beyond the current FP32/FP16/packed-FP8 subset (`.f64`, BF16, unordered compares, and tensor formats) | PTX supports broader scalar FP, vector, and packed element types | `Partial` | `Direct` | `PA4`, `PA12`, `PA14` | The current accepted surface is limited to FP32 CUDA-core arithmetic, the unary `.approx` SFU subset, `.f16`, `.f16x2`, `.b16` packed FP8 carriers, and the explicit conversion/arithmetic forms documented below. |

## Special Registers

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `%tid.{x,y,z}` | Thread index within the current block | `Implemented` | `Direct` | `PA7`, `SM3`, `SM14`, `SM15` | `tid.x` is exercised by the existing integer kernels; `tid.y` and `tid.z` are now lowered and dimensioned from the launch-time 3D block shape. |
| `%ntid.{x,y,z}` | Block dimensions visible to each thread | `Implemented` | `Direct` | `PA7`, `SM12`, `SM14`, `SM15` | All three dimensions are carried in the host ABI and returned directly from the current command descriptor. |
| `%ctaid.{x,y,z}`, `%nctaid.{x,y,z}` | CTA coordinates and grid shape builtins | `Implemented` | `Direct` | `GT12`, `GD1` | These values come from the cluster-dispatched CTA descriptor and the launch-time 3D grid shape. |
| `%laneid`, `%warpid`, `%nwarpid`, `%smid`, `%nsmid` | PTX exposes lane, warp, block, and SM builtins | `Implemented` | `Direct` | `PA6`, `SM12`, `GT14` | `%laneid/%warpid/%nwarpid` are SM-local. `%smid/%nsmid` now come from the chip-level SM assignment and configured SM count. |
| `%gridid` | PTX exposes a temporal grid launch identifier | `Partial` | `Direct` | `PA6`, `SM13`, `GT2` | Supported only as `.u64` via `mov.u64 %rdX, %gridid`, backed by a launch counter and typically consumed with `st.global.u64`. |

Repo-specific note: `%argbase` is accepted by the current assembler as a SpinalGPU escape hatch for `.param` lowering. It is not part of the intended public PTX subset contract and is intentionally excluded from the matrix.

## Instruction Surface

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `mov.u32` | Register moves, immediates, and builtin reads for scalar integer code | `Implemented` | `Direct` | `PA2`, `SM3`, `SM4` | Supports register, immediate, supported special-register, and shared-symbol source forms. |
| `mov.f32` | Register moves and immediate zero materialization for FP32 code | `Partial` | `Direct` | `PA7`, `SM14`, `SM15` | Supports register-to-register moves and `mov.f32 %fX, 0f00000000`. Other float literal spellings remain rejected. |
| `mov.f16`, `mov.f16x2` | Register moves for the low-precision register families | `Implemented` | `Direct` | `PA12`, `SM25`, `SM26`, `SM29`, `SM30` | `mov.f16` supports register moves into `%h` and the narrowed extraction path used around packed conversions. `mov.f16x2` supports register-to-register packed moves on `%x`. |
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
| `rcp.approx.f32`, `sqrt.approx.f32`, `rsqrt.approx.f32`, `sin.approx.f32`, `cos.approx.f32`, `lg2.approx.f32`, `ex2.approx.f32`, `tanh.approx.f32` | Unary FP32 special-function instructions on the SFU datapath | `Implemented` | `Direct` | `PA14`, `SFU1`, `SFU2`, `SM36`, `SM37`, `GT18` | Lower directly to machine `frcp`, `fsqrt`, `frsqrt`, `fsin`, `fcos`, `flg2`, `fex2`, and `ftanh`. The repo targets PTX-visible edge behavior, not bit-exact NVIDIA MUFU output. |
| `fma.rn.f32` | FP32 three-source multiply-add on the CUDA-core datapath | `Implemented` | `Direct` | `PA7`, `CU1`, `SM15`, `SM17`, `GT4`, `GT5` | Lowered to machine `ffma` with a dedicated three-source encoding. The current repo implementation rounds the multiply and add stages separately rather than providing a single-round fused IEEE FMA. |
| `add.rn.f16`, `mul.rn.f16`, `fma.rn.f16` | Scalar FP16 arithmetic on the CUDA-core datapath | `Implemented` | `Direct` | `PA12`, `CU3`, `SM25`, `SM27`, `SM28`, `GT8`, `GT9` | Lower directly to machine `hadd`, `hmul`, and `hfma`. |
| `add.rn.f16x2`, `mul.rn.f16x2` | Packed FP16x2 arithmetic on the CUDA-core datapath | `Implemented` | `Direct` | `PA12`, `CU3`, `SM26` | Lower directly to machine `hadd2` and `hmul2`. |
| `ex2.approx.f16`, `tanh.approx.f16`, `ex2.approx.f16x2`, `tanh.approx.f16x2` | Low-precision unary SFU instructions | `Implemented` | `Direct` | `PA14`, `SFU2`, `SM38`, `SM39` | Lower directly to machine `hex2`, `htanh`, `hex2x2`, and `htanhx2`. The scalar and packed half forms widen through the FP32 SFU core and then narrow back. |
| `cvt.f32.f16`, `cvt.rn.f16.f32` | FP16/FP32 scalar conversion | `Implemented` | `Direct` | `PA12`, `CU3`, `SM28`, `SM33`, `SM34`, `GT9`, `GT11` | Lower directly to machine `cvtf32f16` and `cvtf16f32`. |
| `cvt.rn.f16x2.e4m3x2`, `cvt.rn.f16x2.e5m2x2` | Packed FP8 carrier to packed FP16x2 conversion | `Implemented` | `Direct` | `PA12`, `CU3`, `SM29`, `SM30`, `SM33`, `SM34`, `GT11` | Converts packed `.b16` carriers into one `%x` register. The supported alternate FP8 formats are `e4m3x2` and `e5m2x2` only. |
| `cvt.satfinite.e4m3x2.f16x2`, `cvt.satfinite.e5m2x2.f16x2` | Packed FP16x2 to packed FP8 carrier narrowing | `Implemented` | `Direct` | `PA12`, `CU3`, `SM31`, `SM32`, `GT10` | Narrows one `%x` register to one packed `.b16` carrier using satfinite semantics. |
| `min/max.{u32,s32,f32}` | Branchless min/max convenience forms | `Partial` | `Direct` | `PA10`, `SM16`, `SM18` | Lowered in the PTX frontend to compare plus `selp`; there is no dedicated machine min/max opcode. |
| `shl.b32` | Logical left shift for scalar integer values | `Implemented` | `Direct` | `SM3`, `SM6`, `SM7` | Used in the corpus for 4-byte address scaling. |
| `setp.{eq,ne,lt,le,gt,ge}.u32` | Unsigned integer compares producing a predicate value | `Implemented` | `Direct` | `PA2`, `PA7`, `SM5`, `SM10`, `SM14`, `SM15` | `eq`/`lt` lower directly; `ne`/`le`/`gt`/`ge` are derived in the frontend through negate and operand swap over the primitive compare ops. |
| `setp.{eq,ne,lt,le,gt,ge}.s32` | Signed integer compares producing a predicate value | `Implemented` | `Direct` | `PA8`, `CU2`, `SM19` | Lowered through machine `seteq`, machine `setlts`, plus frontend negate and operand swap. |
| `setp.{eq,ne,lt,le,gt,ge}.f32` | Ordered FP32 compares producing a predicate value | `Implemented` | `Direct` | `PA9`, `CU2`, `SM16`, `SM17`, `SM18` | Lowered through machine `fseteq` and `fsetlt` plus frontend negate, OR, and operand swap. Unordered compare variants remain rejected. |
| `selp.u32`, `selp.f32` | Branchless integer and FP32 value selection | `Implemented` | `Direct` | `PA8`, `PA9`, `CU2`, `SM16`, `SM17` | Lowered to machine `sel` and carried on the existing three-source CUDA issue path. |
| Narrow `tcgen05.*` teaching slice | Async TMEM-backed tensor load/store/commit/MMA surface for the documented subset | `Partial` | `Direct` | `TCG1`, `TCG2`, `TCG3`, `TCG4` | Supported spellings are `tcgen05.ld.sync.aligned.32x32b.x2.b32`, `tcgen05.st.sync.aligned.32x32b.x2.b32`, `tcgen05.wait::ld.sync.aligned`, `tcgen05.wait::st.sync.aligned`, `tcgen05.mma.cta_group::1.kind::f16`, and `tcgen05.commit.cta_group::1.sync.aligned`. Only dense FP16 `cta_group::1` is implemented, with `%r` tuples plus fixed per-warp TMEM windows. |
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
| `.global` plus `ld.global.f16` / `st.global.f16` | PTX global FP16 scalar loads and stores | `Implemented` | `Direct` | `PA12`, `SM25`, `SM27`, `SM28`, `GT8`, `GT9`, `LSU2`, `AXI2` | Global FP16 traffic uses aligned 16-bit accesses. The LSU extracts the addressed halfword from the returned 32-bit memory word and writes 16-bit stores with byte strobes. |
| `.global` plus `ld.global.f16x2` / `st.global.f16x2` | PTX global packed FP16x2 loads and stores | `Implemented` | `Direct` | `PA12`, `SM26`, `SM29`, `SM30`, `GT10`, `GT11`, `AXI1` | Packed half2-style values use aligned 32-bit global accesses and occupy one physical 32-bit register slot. |
| `.global` plus `ld.global.b16` / `st.global.b16` | PTX global packed FP8 carrier loads and stores | `Implemented` | `Direct` | `PA12`, `SM29`, `SM30`, `SM31`, `SM32`, `SM33`, `SM34`, `GT10`, `GT11`, `LSU2`, `AXI2` | Packed `e4m3x2` / `e5m2x2` carriers use aligned 16-bit accesses through the same halfword LSU path as scalar FP16. |
| `.global` plus `ld.global.v2/v4.f32` / `st.global.v2/v4.f32` | PTX vector tuple loads and stores over FP32 elements | `Partial` | `Direct` | `PA11`, `SM20`, `SM21`, `SM22`, `GT6`, `LSU1`, `AXI1` | Accepted only for `.v2/.v4 .f32` brace tuples. The assembler lowers them into ordered scalar `ld.global.f32` / `st.global.f32` operations, so alignment remains 4 bytes per element rather than a dedicated 8/16-byte machine transaction. |
| `st.global.u64` | PTX may store 64-bit values to global memory | `Partial` | `Direct` | `PA6`, `SM13`, `GT2` | Supported only for `%gridid`-backed `.u64` registers. The assembler lowers it to two ordered `st.global.u32` machine stores. |
| `.shared` declarations plus `ld.shared.u32` / `st.shared.u32` | PTX shared memory declarations and accesses | `Partial` | `Direct` | `PA3`, `SM6` | Declarations use `.shared .align N .b8 name[bytes];`. Execution still supports 32-bit word accesses only; low-precision shared-memory PTX is not exposed yet. |
| Byte-addressed addresses with aligned 32-bit words | PTX uses byte addresses across state spaces | `Partial` | `Direct` | `SM8`, `SM9` | Effective addresses are byte-based, but legal fetch/load/store access is aligned 32-bit only in v1. |
| Shared symbol materialization via `mov.u32 %rX, shared_symbol` | PTX code may need to form a shared-space byte offset from a symbol | `Implemented` | `None` | none | The assembler lowers a shared symbol to its byte offset, but no dedicated test exercises this spelling. |
| Shared symbol plus register addressing | PTX addressing commonly combines symbols, registers, and immediates | `Implemented` | `Direct` | `PA3`, `SM6` | Supported for `.shared` loads and stores and used for per-thread shared indexing. |
| Generic addressing and `cvta` | PTX commonly converts between state-space and generic addresses | `Rejected` | `None` | none | No generic-address instructions, `cvta`, or address-space conversion path exists. |

## Currently Unsupported PTX Families

| Capability | PTX requires | SpinalGPU status | Coverage | Evidence | Current notes |
| --- | --- | --- | --- | --- | --- |
| `.const` state space | PTX defines constant memory and constant-space loads | `Rejected` | `Direct` | `PA4` | `.const` declarations are rejected by the frontend today. |
| `.local` state space | PTX defines per-thread local memory and spill-like addressing | `Rejected` | `None` | none | No `.local` declarations, addressing, or lowering path exists. |
| Floating-point families beyond the current FP32/FP16/packed-FP8 subset | PTX supports broader FP ALU, conversions, rounding modes, unordered compares, richer literal forms, and native packed/vector arithmetic | `Rejected` | `Direct` | `PA4`, `PA12`, `PA14` | Today the accepted floating-point surface is the FP32 CUDA-core subset, the unary `.approx` SFU subset, FP16 scalar/packed arithmetic, scalar `f16 <-> f32` conversion, packed `e4m3x2/e5m2x2 <-> f16x2` conversion, `ld/st.global.f16`, `ld/st.global.f16x2`, and `ld/st.global.b16`. BF16, `.f64`, unordered FP compares, low-precision shared-memory PTX, native packed vector ALU beyond `f16x2`, and exact-rounding or `.ftz` SFU spellings remain unsupported. |
| Tensor and MMA instructions beyond the documented legacy FP16 tensor v1 slice plus the narrow tcgen05 FP16 v2 slice | PTX includes broad tensor fragment, MMA, sparse, and tensor-memory instruction families | `Rejected` | `Direct` | `PA13`, `TC1`, `TCG1`, `TCG2`, `TCG3`, `TCG4`, `SM35`, `GT17` | The public tensor surface currently accepts only the five legacy FP16 tensor-v1 spellings and the six documented tcgen05 v2 spellings above. `wmma.*`, sparse MMA, FP8/FP4 tcgen05, `cta_group::2`, alloc/dealloc, block scaling, and the rest of the NVIDIA tensor surface remain intentionally out of scope. |
| Barriers and synchronization | PTX includes CTA sync, async copy coordination, and barrier objects | `Rejected` | `None` | none | No PTX-visible barrier or synchronization instructions exist in the current frontend/runtime path. |
| Atomics and reductions | PTX includes atomic and reduction memory operations | `Rejected` | `None` | none | No atomic lowering or memory-ordering support exists. |
| Function calls and device functions | PTX modules may define functions and issue `call` instructions | `Rejected` | `Direct` | `PA4` | `call` is explicitly rejected. `ret` works only as kernel exit. |
| Memory-ordering qualifiers and cache modifiers | PTX supports qualifiers such as `.relaxed`, `.release`, `.volatile`, and cache modifiers | `Rejected` | `None` | none | Current memory ops are plain word loads/stores with no PTX memory-model qualifier surface. |
| Arbitrary `nvcc -ptx` output | PTX is normally broad enough for compiler-generated code distribution | `Rejected` | `Direct` | `PA4`, `KM3` | The repo frontend accepts only the constrained module shape and instruction subset documented on this page. |
