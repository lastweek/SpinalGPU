# PTX-Inspired ISA v1

This repository uses a custom fixed-width 32-bit ISA inspired by PTX concepts and naming, but it is not PTX-compatible.

## Execution Model

- Classic SIMT v1:
  - one PC per warp
  - one active mask per warp
  - per-thread 32-bit registers
- One block per launch in v1.
- The kernel binary, argument buffer, and global data all live in unified external memory.
- The warp table stores runtime context only:
  - `pc`
  - `activeMask`
  - `threadBase`
  - `threadCount`
  - `runnable`
  - `outstanding`
  - `exited`
  - `faulted`

## Register Model

- General-purpose registers: `r0..r31`
- `r0` is hardwired to zero
- Registers are per-thread, not per-warp
- PTX-inspired special registers:
  - `%tid.x`
  - `%laneid`
  - `%warpid`
  - `%ntid.x`
  - `%ctaid.x`
  - `%nctaid.x`
  - `%argbase`

## Instruction Encoding

- Instruction width: `32` bits
- Opcode field: bits `[31:24]`
- Loadable artifact: raw `.bin`, little-endian, flat 32-bit instruction words

### Formats

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

Immediate and branch offsets are signed 14-bit values. Branch targets are computed relative to `pc + 4`.

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

Opcode ranges `0x40..0x4F` and `0x50..0x5F` are reserved for future SFU and Tensor instructions.

## Memory Model

- Architecturally, all addresses are byte addresses.
- v1 memory operations are restricted to aligned 32-bit words.
- Misaligned fetch or load/store raises a fault.
- Address spaces:
  - `global`: unified external memory holding code, args, and global data
  - `shared`: SM-local scratchpad memory

## Launch and Completion Model

- Launch is driven through AXI-Lite MMIO:
  - `0x00 CONTROL`
  - `0x04 STATUS`
  - `0x08 ENTRY_PC`
  - `0x0C GRID_DIM_X`
  - `0x10 BLOCK_DIM_X`
  - `0x14 ARG_BASE`
  - `0x18 SHARED_BYTES`
  - `0x1C FAULT_PC`
  - `0x20 FAULT_CODE`
- `ENTRY_PC` points into unified external memory.
- The host loads a prebuilt raw kernel binary into external memory before launch.
- The binary is headerless in v1:
  - no embedded entry point
  - no embedded load address
  - no embedded launch metadata
- `ARG_BASE` points at a 32-bit word argument buffer in global memory.
- Results are returned by program stores to global memory.
- Completion is reported through `STATUS.done`.

## Fault Model

- `invalid_launch`
- `misaligned_fetch`
- `illegal_opcode`
- `misaligned_load_store`
- `non_uniform_branch`
- `trap`
- `external_memory`

`STATUS.done` is set on both success and fault. `STATUS.fault`, `FAULT_PC`, and `FAULT_CODE` distinguish the failing case.
