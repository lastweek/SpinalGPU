# SpinalGPU Machine Encoding v1

This document describes the internal machine encoding executed by the current hardware. It is the lowering target for the public PTX subset ISA documented in [isa.md](isa.md).

## Overview

- Instruction width: `32` bits
- Loadable artifact: raw `.bin`, little-endian, flat 32-bit instruction words
- Branch targets are relative to `pc + 4`
- The current PTX compiler lowers PTX subset source into these machine words

## Word Formats

- `RRR`
  - `opcode[31:24] | rd[23:19] | rs0[18:14] | rs1[13:9] | reserved[8:0]`
- `RRI`
  - `opcode[31:24] | rd[23:19] | rs0[18:14] | imm14[13:0]`
- `RRRR`
  - `opcode[31:24] | rd[23:19] | rs0[18:14] | rs1[13:9] | rs2[8:4] | reserved[3:0]`
- `MEM`
  - `opcode[31:24] | reg[23:19] | base[18:14] | off14[13:0]`
- `BR`
  - `opcode[31:24] | rs0[23:19] | reserved[18:14] | off14[13:0]`
- `SYS`
  - `opcode[31:24] | rd[23:19] | sreg[18:14] | reserved[13:0]`

Immediate and branch offsets are signed 14-bit values.

## Opcode Table

| Opcode | Mnemonic | Meaning |
| --- | --- | --- |
| `0x00` | `nop` | No operation |
| `0x01` | `mov` | Register move |
| `0x02` | `movi` | Immediate move |
| `0x03` | `s2r` | Read special register |
| `0x10` | `add` | Add |
| `0x11` | `addi` | Add immediate |
| `0x12` | `sub` | Subtract |
| `0x13` | `mullo` | Low 32 bits of multiply |
| `0x14` | `and` | Bitwise and |
| `0x15` | `or` | Bitwise or |
| `0x16` | `xor` | Bitwise xor |
| `0x17` | `shl` | Logical shift left |
| `0x18` | `shr` | Logical shift right |
| `0x19` | `seteq` | Set `1` when equal, else `0` |
| `0x1A` | `setlt` | Set `1` when unsigned less-than, else `0` |
| `0x1B` | `fadd` | FP32 add |
| `0x1C` | `fmul` | FP32 multiply |
| `0x1D` | `ffma` | FP32 three-source multiply-add |
| `0x1E` | `setlts` | Set `1` when signed less-than, else `0` |
| `0x1F` | `fsub` | FP32 subtract |
| `0x20` | `ldg` | Load from global memory |
| `0x21` | `stg` | Store to global memory |
| `0x22` | `lds` | Load from shared memory |
| `0x23` | `sts` | Store to shared memory |
| `0x24` | `sel` | Select `rs0` or `rs1` using `rs2` as the condition |
| `0x25` | `fabs` | FP32 absolute value |
| `0x26` | `fneg` | FP32 negate |
| `0x27` | `fseteq` | Set `1` when ordered FP32 equal, else `0` |
| `0x28` | `fsetlt` | Set `1` when ordered FP32 less-than, else `0` |
| `0x29` | `ldg16` | Load one aligned 16-bit halfword from global memory |
| `0x2A` | `stg16` | Store one aligned 16-bit halfword to global memory |
| `0x2B` | `hadd` | FP16 scalar add |
| `0x2C` | `hmul` | FP16 scalar multiply |
| `0x2D` | `hfma` | FP16 scalar three-source multiply-add |
| `0x2E` | `hadd2` | Packed FP16x2 add |
| `0x2F` | `hmul2` | Packed FP16x2 multiply |
| `0x30` | `bra` | Unconditional branch |
| `0x31` | `brz` | Uniform branch if all active lanes are zero |
| `0x32` | `brnz` | Uniform branch if all active lanes are non-zero |
| `0x33` | `exit` | Warp-wide exit |
| `0x34` | `trap` | Trap immediately |
| `0x35` | `cvtf32f16` | Convert FP16 scalar to FP32 scalar |
| `0x36` | `cvtf16f32` | Convert FP32 scalar to FP16 scalar |
| `0x37` | `cvtf16x2e4m3x2` | Convert packed `e4m3x2` carrier to packed FP16x2 |
| `0x38` | `cvtf16x2e5m2x2` | Convert packed `e5m2x2` carrier to packed FP16x2 |
| `0x39` | `cvte4m3x2f16x2` | Convert packed FP16x2 to packed `e4m3x2` carrier with satfinite narrowing |
| `0x3A` | `cvte5m2x2f16x2` | Convert packed FP16x2 to packed `e5m2x2` carrier with satfinite narrowing |

Opcode ranges `0x40..0x4F` and `0x50..0x5F` remain reserved for future SFU and tensor-machine instructions.

## Register Model

- Machine registers: `r0..r31`
- `r0` is hardwired to zero
- Registers are per-thread, not per-warp
- PTX `%r<N>`, `%f<N>`, `%h<N>`, `%x<N>`, and `%b<N>` namespaces all lower onto this same 32-bit physical register file
- PTX vector brace tuples such as `{%f0, %f1, %f2, %f3}` reuse those same scalar `%f<N>` registers; there is no dedicated vector register file
- PTX `%h<N>` (scalar FP16) and `%b<N>` (packed FP8 carriers) occupy the low 16 bits of one physical register slot
- PTX `%x<N>` (packed FP16x2) occupies one full 32-bit physical register slot
- Internal special registers:
  - `%tid.x`
  - `%tid.y`
  - `%tid.z`
  - `%laneid`
  - `%warpid`
  - `%ntid.x`
  - `%ntid.y`
  - `%ntid.z`
  - `%ctaid.x`
  - `%ctaid.y`
  - `%ctaid.z`
  - `%nctaid.x`
  - `%nctaid.y`
  - `%nctaid.z`
  - `%nwarpid`
  - `%smid`
  - `%nsmid`
  - `%gridid.lo`
  - `%gridid.hi`
  - `%argbase`

`%gridid` is exposed publicly only through a narrow `.u64` PTX path and lowers to the internal `%gridid.lo` / `%gridid.hi` pair. `%argbase` is an internal lowering helper used by the PTX compiler to service `.param` loads and is not part of the public PTX subset surface.

## Execution Notes

- `fadd`, `fmul`, `ffma`, `fsub`, `fabs`, `fneg`, `fseteq`, `fsetlt`, `hadd`, `hmul`, `hfma`, `hadd2`, `hmul2`, the low-precision conversion opcodes, and `sel` execute on the CUDA-core datapath.
- `ffma`, `hfma`, and `sel` are the current users of the `RRRR` format.
- PTX `fma.rn.f32` lowers to machine `ffma`, but the current repo implementation computes it as FP32 multiply followed by FP32 add rather than a single-round fused IEEE FMA.
- PTX `fma.rn.f16` lowers to machine `hfma` on the same three-source issue path, using the low 16 bits of each operand/result slot.
- PTX `mov.v2/v4.f32`, `ld.global.v2/v4.f32`, and `st.global.v2/v4.f32` are frontend tuple spellings only. They lower into repeated scalar `mov`, `ldg`, and `stg` machine instructions, so no dedicated vector opcode exists.
- `ldg` and `stg` are typeless 32-bit machine-memory ops. PTX `.u32`, `.f32`, `.f16x2`, and the narrow lowered `%gridid` `.u64` path all reuse them.
- `ldg16` and `stg16` are typeless 16-bit machine-memory ops. PTX `.f16` and packed `.b16` FP8 carrier traffic reuse them.
- Instruction fetch remains single-word. Global LSU traffic is burst-capable and coalesces contiguous active-lane 32-bit accesses into multi-beat requests up to `cudaLaneCount` beats.
- Global LSU traffic now supports both 16-bit and 32-bit accesses. Shared memory remains word-addressed in the current public PTX surface.
