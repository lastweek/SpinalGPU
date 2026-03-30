# LayerNorm

## Problem statement

Normalize each row by subtracting the row mean, dividing by the row standard deviation, and then applying learned scale and bias vectors.

## Tensor shapes

- `X[rows, cols]`
- `gamma[cols]`
- `beta[cols]`
- `Y[rows, cols]`

## Launch mapping

- one block per row in all three variants
- later variants shrink cross-thread reduction cost and vectorize row traffic

## Variant-by-variant delta

- `00_reference.cu`: shared-memory mean and variance reductions
- `01_two_pass_vectorized.cu`: vectorized row traversal with `float4`
- `02_warp_block_reduce.cu`: warp-level mean and variance reduction

## Expected bottleneck

LayerNorm has more reduction work than RMSNorm because it needs both the mean and the variance, so synchronization cost matters quickly.

## What to inspect later with profiling tools

- reduction cost for mean and variance
- extra passes over the row data
- vectorized memory efficiency
- numerical sensitivity when row length grows
