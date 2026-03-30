# Top-K Gating

## Problem statement

Pick the top experts for each token from a per-token expert-logit matrix and emit routing weights.

## Tensor shapes

- `logits[token_count, expert_count]`
- `topk_ids[token_count, k]`
- `topk_weights[token_count, k]`

## Launch mapping

- one thread per token

## Variant-by-variant delta

- `00_reference.cu`: top-2 gating with a simple serial scan over experts

## Expected bottleneck

This kernel is mostly limited by reading expert logits and doing a per-token selection scan. The point is to understand routing semantics before optimizing selection.

## What to inspect later with profiling tools

- memory traffic through the logits matrix
- branch divergence from per-token expert comparisons
- whether expert count or token count dominates runtime
- how often the routing output becomes the bottleneck instead of the logits load
