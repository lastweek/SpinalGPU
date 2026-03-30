# Fused Epilogue

## Problem statement

Apply a bias vector and ReLU activation to an FP32 output matrix or vector that already exists in memory.

## Tensor shapes

- `input[rows, cols]` or flattened `input[n]`
- `bias[cols]` or flattened `bias[n]`
- `output[...]`

## Launch mapping

- 1D launch over flattened elements
- later variant maps one thread to one `float4` chunk

## Variant-by-variant delta

- `00_bias_relu.cu`: scalar elementwise bias add and ReLU
- `01_vectorized_bias_relu.cu`: vectorized `float4` path plus scalar tail

## Expected bottleneck

This ladder is mainly about reducing memory traffic and launch count around a simple epilogue.

## What to inspect later with profiling tools

- global-memory bandwidth
- instruction count per output element
- branch behavior on the ReLU clamp
- whether fusion is saving an extra round-trip to memory
