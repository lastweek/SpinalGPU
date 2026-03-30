# Vector Add

## Problem statement

Add two contiguous FP32 vectors into a third output vector.

## Tensor shapes

- `A[n]`
- `B[n]`
- `C[n]`

## Launch mapping

- 1D grid and 1D blocks
- one thread maps to one element or one `float4` chunk, depending on the variant

## Variant-by-variant delta

- `00_reference.cu`: one thread per element with a simple bounds guard
- `01_grid_stride.cu`: grid-stride loop so one launch shape can cover many sizes
- `02_float4.cu`: vectorized `float4` loads/stores plus a scalar tail

## Expected bottleneck

This ladder is bandwidth-bound. Arithmetic is trivial; the interesting part is how efficiently the kernel moves bytes.

## What to inspect later with profiling tools

- achieved DRAM throughput
- global load/store efficiency
- branch efficiency for the tail path
- whether vectorized loads lower instruction count
