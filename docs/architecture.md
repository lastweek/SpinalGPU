# SM Architecture and Frontend

This repository now models a chip-level SpinalGPU cluster with one or more physical SM execution cores. The software contract remains a PTX subset ISA lowered into a custom 32-bit SpinalGPU machine encoding.

## Summary

- `GpuTop` still exposes one top-level AXI4 memory boundary and one AXI-Lite control boundary.
- `GpuTop` now instantiates a `GpuCluster`, not a bare SM.
- Compile-time config is now split by scope:
  - `GpuConfig` owns chip-level config such as cluster sizing and host-control boundary widths.
  - `SmConfig` owns SM-local execution, cache, register-file, and shared-memory parameters.
- `GpuCluster` owns chip-level orchestration:
  - one `GridDispatchController`
  - one `SmCtaController` per physical SM
  - one `SmExecutionCore` per physical SM
  - one `ClusterExternalMemoryArbiter`
  - one shared `ExternalMemoryAxiAdapter`
- `StreamingMultiprocessor` remains as the single-SM compatibility wrapper used by existing one-SM tests and harnesses.
- Each physical SM still contains 4 `SubSmPartition`s by default.
- Each sub-SM owns its local frontend and execution path:
  - local warp slot scheduler
  - local register-file slice
  - local instruction fetch frontend through an `L0InstructionCache`
  - local CUDA, LSU, SFU, legacy tensor, and tcgen05 tensor blocks
  - local `PendingWarpOp` tracking plus typed `SubSmStatus`
- SM-local resources are shared across the 4 partitions:
  - `WarpStateTable`
  - `WarpBinder`
  - `L1InstructionCache`
  - `L1DataSharedMemory`
  - `SharedMemory`
  - `L1TensorMemory`
  - `TensorMemory`
  - `ExternalMemoryArbiter`
- Cluster-level resources are shared across all physical SMs:
  - `GridDispatchController`
  - `ClusterExternalMemoryArbiter`
  - one top-level `ExternalMemoryAxiAdapter`
- SIMT control is still classic and intentionally simple:
  - one PC plus active mask per warp
  - no reconvergence stack
  - one kernel globally in flight
  - one resident CTA per physical SM in this phase
  - non-uniform branch still faults

## Execution Model

- `GridDispatchController` validates one kernel launch, latches one launch-global `gridId`, and walks the CTA grid in `x -> y -> z` order.
- CTAs are dispatched round-robin onto idle physical SMs.
- Each physical SM accepts at most one resident CTA in this phase.
- Within one physical SM, `SmCtaController` clears shared memory, initializes warp contexts, and tracks CTA completion/fault state.
- Warps are admitted into one SM-local architectural state table by `SmCtaController` or `SmAdmissionController`, depending on whether the chip is running through `GpuCluster` or the single-SM wrapper.
- Admitted warps begin unbound in an SM-local ready pool.
- `WarpBinder` assigns an unbound runnable warp to a sub-SM and local slot.
- After binding, the warp stays in that partition until exit or fault.
- Each `SubSmPartition` round-robins across its local resident warp slots and can issue one 32-thread warp instruction at a time.
- With the default config, one physical SM contains 4 sub-SMs and can actively execute 4 different warps concurrently inside the resident CTA.

## Program Loading Model

- Machine code, global data, and kernel arguments all live in unified external memory.
- The host writes the kernel image and data buffers before launch.
- AXI-Lite MMIO provides launch metadata:
  - `ENTRY_PC`
  - `GRID_DIM_{X,Y,Z}`
  - `BLOCK_DIM_{X,Y,Z}`
  - `ARG_BASE`
  - `SHARED_BYTES`
- In the cluster path, `GridDispatchController` validates the full 3D launch once and dispatches CTA descriptors to SMs.
- In the single-SM compatibility path, `SmAdmissionController` still validates the narrow one-CTA launch model for legacy tests.

## Module Responsibilities

| Module | Responsibility | Current Behavior |
| --- | --- | --- |
| `HostControlBlock` | Exposes command/status CSRs over AXI-Lite | Launch and execution-status control |
| `GpuCluster` | Chip-level multi-SM container | One kernel globally in flight, one shared AXI boundary |
| `GridDispatchController` | Validates launch, walks 3D CTA coordinates, and dispatches CTAs to idle SMs | Full 3D grid, one resident CTA per SM, first-fault stops further dispatch |
| `SmCtaController` | Owns one CTA lifecycle on one physical SM | Clears shared memory, initializes warps, and reports CTA completion/fault |
| `SmExecutionCore` | SM-local execution body without a direct AXI boundary | Warp state, sub-SMs, local caches, shared memory, and local fetch/LSU routing |
| `StreamingMultiprocessor` | Single-SM compatibility wrapper | Preserves the legacy AXI-backed harness shape for default one-SM tests |
| `SmAdmissionController` | Validates launches and initializes architectural warp state in the compatibility wrapper | One CTA only, `gridDim=(1,1,1)` only |
| `WarpStateTable` | Holds architectural warp context for all resident warps inside one SM | SM-local runtime state only |
| `WarpBinder` | Binds unbound ready warps into sub-SM local slots | Round-robin across sub-SMs and warps |
| `SubSmPartition` | Local warp scheduling, fetch, decode, issue, and writeback | One warp issue slot per partition with typed status, legacy pending-op completion, and a per-warp tcgen05 async scoreboard |
| `LocalWarpSlotTable` | Tracks local slot occupancy and bound warp IDs | Handles free-slot lookup and clear/rebind state |
| `LocalWarpScheduler` | Chooses the next ready local slot | Round-robin from a rotating local base |
| `WarpRegisterFile` | Holds per-thread registers for bound local warp slots | One local slice per sub-SM |
| `SpecialRegisterReadUnit` | Synthesizes `%tid`, `%ntid`, `%ctaid`, `%smid`, `%gridid`, and similar reads | Dedicated special-register datapath inside each partition |
| `L0InstructionCache` | Placeholder local instruction-cache stage | Pass-through structural cache level |
| `L1InstructionCache` | Shared instruction-side arbitration point | One outstanding fetch at a time |
| `CudaCoreArray` | Local CUDA arithmetic path inside each partition | Scalar FP32, scalar/packed FP16, packed FP8 conversion, integer ALU, and compare/select issue path |
| `LoadStoreUnit` | Local LSU inside each partition | Shared-memory routing plus 16-bit and 32-bit global-memory traffic |
| `SpecialFunctionUnit` | Local SFU inside each partition | Unary approximate FP32 / FP16 / packed-FP16x2 special math with active-mask-aware subwarp slicing |
| `TensorCoreBlock` | Local legacy tensor path inside each partition | Warp-synchronous `ldmatrix` / `mma.sync` / `stmatrix` v1 with serialized RF/shared-memory sequencing |
| `Tcgen05Block` | Local async tcgen05 tensor path inside each partition | Narrow descriptor-plus-TMEM tcgen05 slice with fixed per-warp TMEM windows, per-warp pending-class completion, and dense FP16 `cta_group::1` MMA |
| `L1DataSharedMemory` | Shared data/shared-memory fabric across sub-SMs | Arbitrates local LSU traffic |
| `SharedMemory` | SM-local shared memory backing store | Single-port word-addressed memory with clear support; the public PTX surface is still 32-bit only here |
| `L1TensorMemory` | Shared Tensor Memory fabric across sub-SMs | Arbitrates local tcgen05 TMEM traffic |
| `TensorMemory` | SM-local Tensor Memory backing store | Single-port word-addressed TMEM used only by the current tcgen05 teaching slice |
| `ExternalMemoryArbiter` | Shares the external memory path between instruction and data fabrics | One fetch side plus one LSU side |
| `ClusterExternalMemoryArbiter` | Shares the external memory path across physical SMs | One outstanding SM burst at a time in the current phase |
| `ExternalMemoryAxiAdapter` | Bridges internal burst req/rsp to AXI4 | Single shared AXI adapter at the cluster boundary |

## Config Defaults

- `GpuConfig.default` is the top-level chip contract.
- `GpuConfig.cluster.smCount = 1` by default.
- `GpuConfig.host.controlAddressWidth = 8` by default.
- `GpuConfig.sm = SmConfig.default` by default.
- Physical SM count: `1`
- Warp size: `32`
- Sub-SM count: `4`
- Resident warps per sub-SM: `2`
- Total resident warps per SM: `8` derived
- Sub-SM issue width: `32`
- Total active CUDA lanes per SM: `128` derived
- LSU count per sub-SM: `1`
- SFU count per sub-SM: `1`
- Tensor block count per sub-SM: `1`
- Shared memory banks: `32`
- Shared memory size: `4 KiB`
- SFU latency: `4` cycles per subwarp slice
- External memory boundary: `AXI4`
- Host control boundary: `AXI-Lite`

## SFU Datapath Sketch

The unary SFU path inside one `SubSmPartition` is intentionally simple and explicit:

```text
warp issue
  -> latch pending warp + active mask + opcode
  -> process lanes in groups of cudaLaneCount
  -> classify input / handle special cases
  -> table-based unary approximation
  -> optional FP16 widen-narrow wrapper
  -> write result buffer
  -> emit one warp response after sfuLatency * sliceCount cycles
```

## Sub-SM Partition FP16 Throughput

One sub-SM partition maps to one local `CudaCoreArray` issue slice. With `SmConfig.default`, that means `cudaLaneCount = subSmIssueWidth = 32`, so one partition covers a full 32-thread warp instruction.

The current throughput model is derived from the latency-gated `CudaCoreArray` issue path, not from an overlapped pipeline peak. A new CUDA arithmetic issue is accepted only when the current one completes, so sustained throughput is computed as work per instruction divided by the configured latency. For this figure, FMA counts as `2 FLOPs`.

- FP16 scalar add/mul: `32 lanes * 1 FLOP / 4 cycles = 8 * f_GHz GFLOP/s`
- FP16 scalar FMA: `32 lanes * 2 FLOPs / 4 cycles = 16 * f_GHz GFLOP/s`
- FP16 packed `f16x2` add/mul: `32 lanes * 2 FLOPs / 4 cycles = 16 * f_GHz GFLOP/s`
- FP8 arithmetic: `0 FLOPs` today on the CUDA path; the current RTL exposes packed FP8 conversion only.

![Sub-SM partition FP16 throughput vs frequency](figures/subsm_partition_fp16_throughput.png)

The plotted H100 NVL markers use the official NVIDIA H100 NVL product brief, which publishes GPU clocks of `1.080 GHz` base and `1.785 GHz` boost. NVIDIA B200 is intentionally omitted from the figure because the cited public NVIDIA B200 pages used in this repo note did not publish a GPU clock:

- H100 NVL product brief: <https://www.nvidia.com/content/dam/en-zz/Solutions/Data-Center/h100/PB-11773-001_v01.pdf>
- DGX B200 product page: <https://www.nvidia.com/en-us/data-center/dgx-b200/>
- DGX B200 user guide: <https://docs.nvidia.com/dgx/dgxb200-user-guide/introduction-to-dgxb200.html>

## Memory Hierarchy Shape

- Inside one physical SM:
  - instruction path:
    - `SubSmPartition -> L0InstructionCache -> shared L1InstructionCache -> ExternalMemoryArbiter -> SM external burst port`
  - data path:
    - `SubSmPartition LSU -> L1DataSharedMemory -> SharedMemory or ExternalMemoryArbiter -> SM external burst port`
  - tensor-memory path:
    - `SubSmPartition Tcgen05Block -> L1TensorMemory -> TensorMemory`
- At the chip boundary:
  - `SmExecutionCore burst ports -> ClusterExternalMemoryArbiter -> ExternalMemoryAxiAdapter -> AXI4`
- Shared memory is SM-local. There is no cross-SM shared-memory fabric in this phase.
- Tensor Memory is also SM-local in the current tcgen05 slice. It is distinct from shared memory and is statically partitioned per resident warp.
- The L0 and L1 cache blocks are still structural placeholders. They establish the intended partitioned topology first; detailed caching behavior can come later.

## Interface Rules

- Internal datapaths use typed request/response bundles.
- `Stream` is used where backpressure matters.
- `Flow` is used for debug and observability.
- AXI remains only at the top-level external memory boundary.
- AXI-Lite remains only at the host control boundary.

## ISA Layers

- Public ISA reference: [isa.md](isa.md)
- Internal encoding reference: [machine-encoding.md](machine-encoding.md)
- The current frontend supports:
  - PTX subset source compiled ahead of time
  - fixed 32-bit machine instruction words
  - PTX-visible special registers such as `%tid.x`, `%ctaid.x`, and `%smid`
  - integer, FP32, FP16, packed FP16x2, and packed FP8 conversion CUDA-core ops
  - unary approximate SFU ops for FP32 plus `ex2/tanh` on FP16 and FP16x2
  - 16-bit and 32-bit global load/store plus `.param` lowering
  - 32-bit shared-memory load/store
  - legacy warp-synchronous tensor v1 plus the narrow tcgen05 FP16 teaching slice
  - uniform branch, exit, and trap

## PTX Corpus Structure

- The teaching corpus is organized by primary feature:
  - `kernels/arithmetic/`
  - `kernels/control/`
  - `kernels/global_memory/`
  - `kernels/shared_memory/`
  - `kernels/sfu/`
  - `kernels/special_registers/`
- Success and fault expectations live in typed kernel metadata plus test expectations, not in directory names.

## Diagrams

### High-Level Cluster Diagram

Source: [diagrams/sm-overview.mmd](diagrams/sm-overview.mmd)

```mermaid
flowchart TB
  GpuTop[GpuTop]
  AXI[AXI4 Memory Boundary]
  CLUSTER[GpuCluster]
  DISPATCH[GridDispatchController]
  CMEM[ClusterExternalMemoryArbiter]
  AXIADAPT[ExternalMemoryAxiAdapter]
  SM0[SmExecutionCore 0]
  SM1[SmExecutionCore 1]

  GpuTop --> CLUSTER
  CLUSTER --> DISPATCH
  CLUSTER --> SM0
  CLUSTER --> SM1
  SM0 --> CMEM
  SM1 --> CMEM
  CMEM --> AXIADAPT --> AXI
```

### Dispatch And Dataflow Diagram

Source: [diagrams/dispatch-dataflow.mmd](diagrams/dispatch-dataflow.mmd)

```mermaid
flowchart LR
  GRID[GridDispatchController]
  CTA0[CTA desc -> SM 0]
  CTA1[CTA desc -> SM 1]
  READY[SM-local Ready Pool]
  BINDER[WarpBinder]
  SLOT0[SubSm Local Slots]
  EXEC0[CUDA / LSU / SFU / Tensor]
  WB[WarpStateTable + Writeback]

  GRID --> CTA0 --> READY
  GRID --> CTA1
  READY --> BINDER --> SLOT0 --> EXEC0 --> WB
```

### Memory Hierarchy And AXI Boundary Diagram

Source: [diagrams/memory-hierarchy-axi.mmd](diagrams/memory-hierarchy-axi.mmd)

```mermaid
flowchart LR
  SM0[SM 0 SubSm LSU / IFU]
  SM1[SM 1 SubSm LSU / IFU]
  L0I[L0 / L1 / Shared per SM]
  ARB[ClusterExternalMemoryArbiter]
  AXIADAPT[ExternalMemoryAxiAdapter]
  AXI[AXI4 Master Port]
  MEM[External Memory Model / Controller]

  SM0 --> L0I --> ARB
  SM1 --> L0I --> ARB
  ARB --> AXIADAPT --> AXI --> MEM
```

### Launch And Frontend Execution Diagram

Source: [diagrams/frontend-execution.mmd](diagrams/frontend-execution.mmd)

```mermaid
flowchart LR
  HOST[Host Test Harness]
  PTX[PTX Subset Source]
  TOOLCHAIN[PtxAssembler]
  BIN[SpinalGPU Machine Code]
  CSR[AXI-Lite Control]
  GPU[GpuTop]
  HCB[HostControlBlock]
  CLUSTER[GpuCluster]
  GRID[GridDispatchController]
  CTA[SmCtaController x SM]
  SM[SmExecutionCore x SM]
  SUBS[SubSmPartition x subSM]
  MEM[Unified External Memory]

  PTX --> TOOLCHAIN --> BIN --> MEM
  HOST --> CSR --> GPU
  GPU --> HCB --> CLUSTER
  CLUSTER --> GRID --> CTA --> SM --> SUBS
  SUBS --> MEM
```
