# Expert Grouping

## Problem statement

Regroup routed token rows into expert-major contiguous batches that are easy to feed into one expert MLP at a time.

## Tensor shapes

- `packed_tokens[routed_tokens, hidden]`
- `sorted_expert_ids[routed_tokens]`
- `grouped_tokens[routed_tokens, hidden]`

## Launch mapping

- one thread per routed token

## Variant-by-variant delta

- `00_grouped_layout.cu`: direct regroup into expert-major contiguous rows

## Expected bottleneck

This kernel is mostly a row copy plus indirect address lookup. The core lesson is the layout transformation, not math throughput.

## What to inspect later with profiling tools

- memory traffic for regrouping
- effect of expert skew on write locality
- the cost of extra layout transforms before expert GEMM
- whether a prior sort or pack stage already gives enough locality
