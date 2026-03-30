# Block Reduction

## Problem statement

Reduce a large FP32 vector into partial sums and then into a final scalar sum.

## Tensor shapes

- `input[n]`
- `block_sums[gridDim.x]`
- `output[1]` for the final phase

## Launch mapping

- 1D grid and 1D blocks
- each block consumes a grid-stride slice of the input
- later variants reduce inside a warp before crossing warp boundaries

## Variant-by-variant delta

- `00_shared_tree.cu`: classic shared-memory tree reduction
- `01_warp_shuffle.cu`: warp-level reduction plus one shared value per warp
- `02_two_phase.cu`: explicit partial-sum and final-reduction phases

## Expected bottleneck

The early phase is bandwidth-bound while loading input; the final reduction is usually latency- and synchronization-sensitive.

## What to inspect later with profiling tools

- shared-memory traffic
- barrier count and stall reasons
- achieved occupancy versus register pressure
- the cost of the final small reduction
