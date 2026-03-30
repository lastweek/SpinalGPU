// Purpose: Provide the simplest direct-scatter token-to-expert dispatch kernel.
// Primary topic: token_dispatch
// Optimization stage: 00_scatter_reference
// Expected learning outcome: Understand the core address mapping from `(token, expert, slot)` to expert-major storage.
// High-level execution flow:
// - One thread owns one routed token.
// - The thread reads the token's destination expert and slot.
// - It copies the hidden-state row into `expert_buffer[expert, slot, :]`.
// Performance idea:
// - Keep the address mapping obvious before introducing packed layouts or vectorized copies.
// Key CUDA features:
// - indirect addressing
// - row copy across the hidden dimension
// - expert-major output layout
// Correctness constraints:
// - `slot_indices[token]` must be less than `expert_capacity`.
// - The expert buffer layout is `[expert_count, expert_capacity, hidden]`.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/03_moe/token_dispatch/00_scatter_reference.cu`
// Profiling focus:
// - Inspect the cost of scattered writes and indirect destination addressing.
// Relation to SpinalGPU PTX corpus:
// - This is a higher-level runtime/data-layout kernel, not an executable PTX target for the current repo.

extern "C" __global__ void moe_dispatch_scatter_reference(
    const float* __restrict__ tokens,
    const int* __restrict__ expert_ids,
    const int* __restrict__ slot_indices,
    float* __restrict__ expert_buffer,
    int token_count,
    int hidden,
    int expert_capacity
) {
  const int token = blockIdx.x * blockDim.x + threadIdx.x;
  if (token >= token_count) {
    return;
  }

  const int expert = expert_ids[token];
  const int slot = slot_indices[token];
  const int src_base = token * hidden;
  const int dst_base = (expert * expert_capacity + slot) * hidden;

  for (int h = 0; h < hidden; ++h) {
    expert_buffer[dst_base + h] = tokens[src_base + h];
  }
}
