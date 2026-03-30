# Row-Wise Softmax

## Problem statement

Compute a numerically stable softmax across each row of a score matrix.

## Tensor shapes

- `scores[rows, cols]`
- optional `mask[rows, cols]`
- `probs[rows, cols]`

## Launch mapping

- one block per row
- later variants keep the same row ownership but change how max/sum statistics are accumulated

## Variant-by-variant delta

- `00_safe_reference.cu`: classic max pass plus sum pass
- `01_online_max_sum.cu`: online max-and-sum accumulation
- `02_warp_shuffle.cu`: online statistics with warp-level reduction
- `03_scale_mask_fused.cu`: fold scale and additive mask into the same softmax pipeline

## Expected bottleneck

Softmax mixes memory traffic with numerically sensitive reductions. The main optimizations reduce passes, synchronization, and redundant exponent work.

## What to inspect later with profiling tools

- row latency as sequence length grows
- branch and replay behavior around masked elements
- exp instruction throughput
- synchronization cost across variants
