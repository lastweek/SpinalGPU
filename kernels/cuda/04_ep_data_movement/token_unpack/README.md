# Token Unpack

## Problem statement

Unpack received rows from a peer-major communication buffer back into token-major output order.

## Tensor shapes

- `recv_buffer[packed_rows, hidden]`
- `dest_token_ids[packed_rows]`
- `output[token_count, hidden]`

## Launch mapping

- one thread per packed row

## Variant-by-variant delta

- `00_reference.cu`: scalar row copy from the receive buffer into token-major order

## Expected bottleneck

This kernel is pure data movement with indirect destination writes. The point is to understand the inverse mapping of token pack.

## What to inspect later with profiling tools

- destination write locality
- copy throughput across hidden sizes
- skewed destination-token effects
- whether unpack can be fused with later expert-combine steps
