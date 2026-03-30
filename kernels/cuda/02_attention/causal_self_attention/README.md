# Causal Self-Attention

## Problem statement

Compute single-head decoder-style causal self-attention with contiguous row-major `Q`, `K`, and `V`.

## Tensor shapes

- `Q[seq_len, head_dim]`
- `K[seq_len, head_dim]`
- `V[seq_len, head_dim]`
- `output[seq_len, head_dim]`

## Launch mapping

- one block owns one query position
- threads cover output dimensions within that query row

## Variant-by-variant delta

- `00_reference.cu`: correctness-first causal attention with separate max, sum, and output accumulation loops
- `01_flash_style_blocked.cu`: blocked online accumulation with shared-memory staging of `K` and `V`

## Expected bottleneck

The reference kernel is dominated by redundant score recomputation and global-memory traffic. The blocked version aims to reuse `Q`, `K`, and `V` tiles while staying causal.

## What to inspect later with profiling tools

- repeated score computation cost
- shared-memory reuse of `K` and `V`
- register pressure from online accumulation
- latency growth with sequence length
