# Expert Buffer Transpose

## Problem statement

Transpose expert buffers between expert-major and slot-major views so later kernels can pick the layout they want.

## Tensor shapes

- source layout `buffer[expert_count, capacity, hidden]`
- transposed layout `buffer_t[capacity, expert_count, hidden]`

## Launch mapping

- 2D grid over `hidden` chunks and `(expert, slot)` pairs

## Variant-by-variant delta

- `00_reference.cu`: scalar transpose over the hidden dimension
- `01_vectorized.cu`: vectorized `float4` transpose with the same logical mapping

## Expected bottleneck

This is a pure layout transform. The interesting question is whether the extra transpose earns enough downstream locality to justify the bandwidth cost.

## What to inspect later with profiling tools

- read and write coalescing in both layouts
- hidden-dimension copy throughput
- tail overhead in the vectorized variant
- whether later expert kernels benefit enough to amortize the transpose
