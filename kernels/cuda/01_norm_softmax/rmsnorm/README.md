# RMSNorm

## Problem statement

Normalize each row by its root-mean-square and then apply a learned per-column scale vector.

## Tensor shapes

- `X[rows, cols]`
- `gamma[cols]`
- `Y[rows, cols]`

## Launch mapping

- early variants use one block per row
- the final variant uses one warp per row and multiple rows per block

## Variant-by-variant delta

- `00_reference.cu`: straightforward shared-memory reduction
- `01_vectorized.cu`: vectorized `float4` loads and stores
- `02_warp_reduce.cu`: warp-shuffle reduction instead of a full shared-memory tree
- `03_rows_per_block.cu`: multiple rows per block with one warp per row

## Expected bottleneck

These kernels mix memory bandwidth with row-wise reduction overhead. Later variants mainly cut synchronization and load/store instruction count.

## What to inspect later with profiling tools

- shared-memory traffic versus warp-shuffle traffic
- row throughput as `cols` changes
- register pressure from vectorized accumulation
- occupancy when multiple rows share a block
