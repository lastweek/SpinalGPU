# Tiled GEMM

## Problem statement

Multiply two row-major FP32 matrices and write a row-major FP32 output matrix.

## Tensor shapes

- `A[m, k]`
- `B[k, n]`
- `C[m, n]`

## Launch mapping

- 2D grid over output tiles
- the reference kernel maps one thread to one output element
- later variants tile the K dimension and reuse data in shared memory

## Variant-by-variant delta

- `00_naive.cu`: one thread per output element, no shared-memory reuse
- `01_shared_tiled.cu`: block tile in shared memory
- `02_register_tiled.cu`: shared-memory tiles plus a small register tile per thread

## Expected bottleneck

The baseline is dominated by redundant global loads. Later variants trade shared memory and registers for better data reuse.

## What to inspect later with profiling tools

- global-memory bytes per FLOP
- shared-memory bank behavior
- achieved occupancy versus register pressure
- tensor-core eligibility later, after the scalar baseline is clear
