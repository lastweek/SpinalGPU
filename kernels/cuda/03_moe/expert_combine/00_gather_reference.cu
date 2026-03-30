// Purpose: Provide the simplest top-2 expert-output gather and combine kernel.
// Primary topic: expert_combine
// Optimization stage: 00_gather_reference
// Expected learning outcome: Understand how routed expert outputs are merged back into token-major order.
// High-level execution flow:
// - One thread owns one token.
// - The thread reads both routed experts, slots, and weights.
// - It gathers two expert-output rows, forms a weighted sum, and writes the token output row.
// Performance idea:
// - Keep the top-2 combine logic explicit before introducing vectorization or fused gather patterns.
// Key CUDA features:
// - indirect reads from expert-major buffers
// - weighted combine across the hidden dimension
// - token-major output writeback
// Correctness constraints:
// - This file is intentionally top-2 specific.
// - The expert output layout is `[expert_count, expert_capacity, hidden]`.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/03_moe/expert_combine/00_gather_reference.cu`
// Profiling focus:
// - Inspect the cost of indirect expert reads and the hidden-dimension row combine.
// Relation to SpinalGPU PTX corpus:
// - This is a MoE runtime/dataflow kernel beyond the current PTX subset.

extern "C" __global__ void moe_combine_top2_reference(
    const float* __restrict__ expert_output,
    const int* __restrict__ expert0,
    const int* __restrict__ slot0,
    const float* __restrict__ weight0,
    const int* __restrict__ expert1,
    const int* __restrict__ slot1,
    const float* __restrict__ weight1,
    float* __restrict__ output,
    int token_count,
    int hidden,
    int expert_capacity
) {
  const int token = blockIdx.x * blockDim.x + threadIdx.x;
  if (token >= token_count) {
    return;
  }

  const int base0 = (expert0[token] * expert_capacity + slot0[token]) * hidden;
  const int base1 = (expert1[token] * expert_capacity + slot1[token]) * hidden;
  const int out_base = token * hidden;
  const float w0 = weight0[token];
  const float w1 = weight1[token];

  for (int h = 0; h < hidden; ++h) {
    output[out_base + h] = w0 * expert_output[base0 + h] + w1 * expert_output[base1 + h];
  }
}
