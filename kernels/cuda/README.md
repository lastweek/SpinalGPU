# CUDA Learning Ladder

This directory is a source-first CUDA study track that sits next to the executable PTX corpus.

The goal is to learn kernel structure and optimization tradeoffs step by step, starting from basic indexing and memory behavior and moving toward LLM-shaped kernels such as norm, softmax, attention, MoE routing, and expert-parallel data movement.

## Repo Contract

- This tree is source-only study material.
- `.cu` files here are not compiled by `sbt refreshKernels`.
- `.cu` files here are not loaded by `KernelCorpus` or the SpinalGPU simulation harnesses.
- `.cu` files here are not covered by repo automation in this first milestone.
- No generated PTX, cubin, or benchmark outputs are checked in here.

## Stage Order

- `00_foundations/`
  indexing, coalescing, reductions, tiled GEMM, and fused epilogues
- `01_norm_softmax/`
  RMSNorm, LayerNorm, and row-wise softmax
- `02_attention/`
  score computation, softmax-value application, and causal self-attention
- `03_moe/`
  top-k gating, token dispatch, expert grouping, and output combine
- `04_ep_data_movement/`
  token pack/unpack and expert-buffer layout transforms

## Topic Layout

Every topic directory follows the same layout:

- `README.md`
  explains the problem, shapes, launch mapping, variant deltas, likely bottlenecks, and what to inspect later with profiling tools
- `00_*.cu`, `01_*.cu`, ...
  an optimization ladder from simple and readable to more performance-aware variants

Every CUDA file starts with the same teaching header:

- `// Purpose:`
- `// Primary topic:`
- `// Optimization stage:`
- `// Expected learning outcome:`
- `// High-level execution flow:`
- `// Performance idea:`
- `// Key CUDA features:`
- `// Correctness constraints:`
- `// Build example:`
- `// Profiling focus:`
- `// Relation to SpinalGPU PTX corpus:`

## How To Use This Tree

- Read the topic `README.md` first.
- Start from `00_*.cu` and only move to the next variant after the current one is clear.
- Compare how each step changes memory access, launch mapping, synchronization, or reduction structure.
- Treat the build examples as future commands for a CUDA-capable machine, not part of the normal repo workflow today.

## Later, With CUDA Installed

When you eventually have a CUDA-capable environment, the build/profiling lines in each file are intended as starting points for local experiments. Until then, this directory is still useful as a curated handwritten kernel corpus to study and compare against the PTX examples under `kernels/`.
