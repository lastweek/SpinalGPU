# Memory Coalescing

## Problem statement

Apply a simple FP32 scale to a row-major matrix while exploring how thread mapping changes memory behavior.

## Tensor shapes

- `input[rows, cols]`
- `output[rows, cols]`

## Launch mapping

- 2D grid and 2D blocks
- the slow variant maps `threadIdx.x` to rows
- the improved variants map `threadIdx.x` to contiguous columns

## Variant-by-variant delta

- `00_bad_strided.cu`: deliberately strided row-major access
- `01_coalesced.cu`: remap threads so neighboring lanes touch neighboring columns
- `02_vectorized.cu`: keep coalesced row-major access and widen traffic with `float4`

## Expected bottleneck

This ladder is also bandwidth-bound, but the key lesson is transaction efficiency rather than raw math.

## What to inspect later with profiling tools

- global memory transaction count
- sector and replay behavior
- achieved bandwidth versus the vector-add ladder
- branch overhead on tail handling
