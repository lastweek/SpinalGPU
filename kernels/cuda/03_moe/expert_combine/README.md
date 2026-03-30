# Expert Combine

## Problem statement

Gather expert outputs back into token-major order and combine the routed expert contributions with gating weights.

## Tensor shapes

- expert outputs in expert-major layout
- per-token routing metadata for two experts
- `output[token_count, hidden]`

## Launch mapping

- one thread per token

## Variant-by-variant delta

- `00_gather_reference.cu`: direct top-2 gather and weighted combine

## Expected bottleneck

This kernel is dominated by indirect reads from expert-major buffers and a weighted row combine across the hidden dimension.

## What to inspect later with profiling tools

- indirect-read locality
- hidden-dimension copy efficiency
- route-weight application overhead
- imbalance from expert skew
