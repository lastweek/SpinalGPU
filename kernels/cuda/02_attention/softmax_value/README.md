# Softmax and Value Application

## Problem statement

Turn attention scores into normalized probabilities and then use those probabilities to form the weighted value output.

## Tensor shapes

- `scores[seq_len, seq_len]`
- `probs[seq_len, seq_len]`
- `V[seq_len, head_dim]`
- `output[seq_len, head_dim]`

## Launch mapping

- `00_split_phase.cu` uses separate kernels for row softmax and value accumulation
- `01_online_fused.cu` uses one block per query row and one thread per output dimension slice

## Variant-by-variant delta

- `00_split_phase.cu`: materialize probabilities, then apply them to `V`
- `01_online_fused.cu`: compute online softmax statistics while accumulating the weighted value output

## Expected bottleneck

The split-phase version pays an extra memory round-trip for the probability matrix. The fused version reduces memory traffic but increases per-thread work and score recomputation.

## What to inspect later with profiling tools

- memory traffic for the intermediate probability matrix
- per-row latency
- redundant score recomputation in the fused variant
- register pressure from keeping the online accumulator live
