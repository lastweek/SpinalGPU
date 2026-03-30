# Token Pack

## Problem statement

Pack token rows into per-peer send buffers for a future expert-parallel all-to-all step.

## Tensor shapes

- `tokens[token_count, hidden]`
- per-token peer id and slot metadata
- `send_buffer[packed_tokens, hidden]`

## Launch mapping

- one thread per token

## Variant-by-variant delta

- `00_reference.cu`: scalar row copy into peer-major send layout
- `01_contiguous_pack.cu`: vectorized `float4` row copy for the same packed layout

## Expected bottleneck

This stage is pure data movement. The interesting tradeoff is between indirect address calculation and how contiguous the packed send rows become.

## What to inspect later with profiling tools

- write locality into peer-major send buffers
- copy throughput as hidden size changes
- tail overhead in the vectorized variant
- whether peer skew damages packing efficiency
