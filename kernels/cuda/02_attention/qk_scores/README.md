# QK Score Computation

## Problem statement

Compute the single-head attention score matrix `Q * K^T` for one decoder-style attention head.

## Tensor shapes

- `Q[seq_len, head_dim]`
- `K[seq_len, head_dim]`
- `scores[seq_len, seq_len]`

## Launch mapping

- `00_reference.cu` maps one thread to one `(query, key)` score
- `01_shared_k_tile.cu` maps one block to one query row and a tile of keys

## Variant-by-variant delta

- `00_reference.cu`: direct dot product per output score
- `01_shared_k_tile.cu`: load the query chunk once and tile key vectors in shared memory

## Expected bottleneck

The baseline is dominated by redundant global loads of `Q` and `K`. The tiled version mainly reduces rereads of the same query row and key tile.

## What to inspect later with profiling tools

- global-memory bytes per score
- shared-memory reuse of the query row and key tile
- occupancy versus shared-memory footprint
- score throughput as `head_dim` grows
