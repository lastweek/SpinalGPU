// Purpose: Regroup already packed routed tokens into expert-major contiguous layout.
// Primary topic: expert_grouping
// Optimization stage: 00_grouped_layout
// Expected learning outcome: Understand the final buffer layout that many MoE expert kernels want to consume.
// High-level execution flow:
// - One thread owns one routed token row.
// - The thread reads the token's destination expert and within-expert slot.
// - It copies the row into the grouped expert-major buffer.
// Performance idea:
// - Keep the final layout transformation explicit before trying to fuse it with other MoE stages.
// Key CUDA features:
// - expert-major destination layout
// - row copy with indirect destination
// - data-only layout transform
// Correctness constraints:
// - `expert_offsets` must be the prefix sum of routed-token counts per expert.
// - `slot_in_expert` must index the token within its expert's segment.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/03_moe/expert_grouping/00_grouped_layout.cu`
// Profiling focus:
// - Measure whether this extra regrouping step is worth the improved expert-major layout.
// Relation to SpinalGPU PTX corpus:
// - This is part of the MoE data-pipeline learning ladder and not part of the current PTX execution path.

extern "C" __global__ void moe_grouped_layout_reference(
    const float* __restrict__ packed_tokens,
    const int* __restrict__ expert_ids,
    const int* __restrict__ slot_in_expert,
    const int* __restrict__ expert_offsets,
    float* __restrict__ grouped_tokens,
    int routed_tokens,
    int hidden
) {
  const int routed = blockIdx.x * blockDim.x + threadIdx.x;
  if (routed >= routed_tokens) {
    return;
  }

  const int expert = expert_ids[routed];
  const int grouped_row = expert_offsets[expert] + slot_in_expert[routed];
  const int src_base = routed * hidden;
  const int dst_base = grouped_row * hidden;

  for (int h = 0; h < hidden; ++h) {
    grouped_tokens[dst_base + h] = packed_tokens[src_base + h];
  }
}
