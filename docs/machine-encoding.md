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
| `0x1A` | `setlt` | Set `1` when signed less-than, else `0` |
| `0x20` | `ldg` | Load from global memory |
| `0x21` | `stg` | Store to global memory |
| `0x22` | `lds` | Load from shared memory |
| `0x23` | `sts` | Store to shared memory |
| `0x30` | `bra` | Unconditional branch |
| `0x31` | `brz` | Uniform branch if all active lanes are zero |
| `0x32` | `brnz` | Uniform branch if all active lanes are non-zero |
| `0x33` | `exit` | Warp-wide exit |
| `0x34` | `trap` | Trap immediately |

Opcode ranges `0x40..0x4F` and `0x50..0x5F` remain reserved for future SFU and tensor-machine instructions.

## Register Model

- Machine registers: `r0..r31`
- `r0` is hardwired to zero
- Registers are per-thread, not per-warp
- Internal special registers:
  - `%tid.x`
  - `%laneid`
  - `%warpid`
  - `%ntid.x`
  - `%ctaid.x`
  - `%nctaid.x`
  - `%nwarpid`
  - `%smid`
  - `%nsmid`
  - `%gridid.lo`
  - `%gridid.hi`
  - `%argbase`

`%gridid` is exposed publicly only through a narrow `.u64` PTX path and lowers to the internal `%gridid.lo` / `%gridid.hi` pair. `%argbase` is an internal lowering helper used by the PTX compiler to service `.param` loads and is not part of the public PTX subset surface.
