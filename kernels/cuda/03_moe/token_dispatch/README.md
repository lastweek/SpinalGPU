# Token Dispatch

## Problem statement

Move token activations from token-major layout into expert-major buffers according to routing decisions.

## Tensor shapes

- `tokens[token_count, hidden]`
- route metadata per token
- `expert_buffer[expert_count, capacity, hidden]` or a packed expert-major buffer

## Launch mapping

- one thread per token
- each thread copies one token row across the hidden dimension

## Variant-by-variant delta

- `00_scatter_reference.cu`: direct scatter into `expert, slot` layout
- `01_prefix_sum_pack.cu`: pack tokens contiguously using precomputed expert prefix offsets

## Expected bottleneck

These kernels are dominated by moving hidden-state rows. The interesting choice is whether the target layout is sparse-scatter friendly or contiguous for later expert GEMMs.

## What to inspect later with profiling tools

- write coalescing into expert buffers
- how hidden size changes copy efficiency
- memory divergence from expert assignment skew
- the cost of indirect address formation
