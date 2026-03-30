// Purpose: Pack routed tokens contiguously per expert using precomputed expert offsets.
// Primary topic: token_dispatch
// Optimization stage: 01_prefix_sum_pack
// Expected learning outcome: See how a prefix-sum layout turns dispatch into a contiguous expert-major pack step.
// High-level execution flow:
// - One thread owns one routed token.
// - The thread reads its expert id and within-expert slot.
// - A precomputed `expert_offsets` array turns that pair into one contiguous packed destination row.
// Performance idea:
// - Move from sparse expert scatter toward a compact layout that is easier to feed into expert GEMMs.
// Key CUDA features:
// - prefix-offset addressing
// - packed expert-major layout
// - row copy across the hidden dimension
// Correctness constraints:
// - `expert_offsets[expert]` and `slot_in_expert[token]` must describe a valid packed row index.
// - The packed layout is `[total_routed_tokens, hidden]`.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/03_moe/token_dispatch/01_prefix_sum_pack.cu`
// Profiling focus:
// - Compare write locality and later-GEMM friendliness against the direct scatter variant.
// Relation to SpinalGPU PTX corpus:
// - This is a learning kernel for MoE data movement, outside the PTX subset executed by the repo today.

extern "C" __global__ void moe_dispatch_prefix_pack(
    const float* __restrict__ tokens,
    const int* __restrict__ expert_ids,
    const int* __restrict__ slot_in_expert,
    const int* __restrict__ expert_offsets,
    float* __restrict__ packed_tokens,
    int token_count,
    int hidden
) {
  const int token = blockIdx.x * blockDim.x + threadIdx.x;
  if (token >= token_count) {
    return;
  }

  const int expert = expert_ids[token];
  const int dst_row = expert_offsets[expert] + slot_in_expert[token];
  const int src_base = token * hidden;
  const int dst_base = dst_row * hidden;

  for (int h = 0; h < hidden; ++h) {
    packed_tokens[dst_base + h] = tokens[src_base + h];
  }
}
