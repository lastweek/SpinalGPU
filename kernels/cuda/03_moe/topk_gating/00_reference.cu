// Purpose: Provide a correctness-first top-2 expert gating kernel.
// Primary topic: topk_gating
// Optimization stage: 00_reference
// Expected learning outcome: Understand per-token expert selection and routing-weight formation before optimizing the selection algorithm.
// High-level execution flow:
// - One thread scans all expert logits for one token.
// - The thread keeps track of the best and second-best experts.
// - The selected logits are normalized into top-2 routing weights and written out.
// Performance idea:
// - Keep the routing logic obvious so later optimizations can focus on parallel selection or better memory locality.
// Key CUDA features:
// - one-thread-per-token mapping
// - serial top-k scan within the thread
// - per-token routing-weight normalization
// Correctness constraints:
// - This file is intentionally top-2 specific.
// - `topk_ids` and `topk_weights` store two values per token in contiguous layout.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/03_moe/topk_gating/00_reference.cu`
// Profiling focus:
// - Inspect the cost of the serial expert scan and how it scales with `expert_count`.
// Relation to SpinalGPU PTX corpus:
// - This introduces token-routing control flow that sits above the repo's current PTX execution examples.

#include <float.h>
#include <math.h>

extern "C" __global__ void top2_gating_reference(
    const float* __restrict__ logits,
    int* __restrict__ topk_ids,
    float* __restrict__ topk_weights,
    int token_count,
    int expert_count
) {
  const int token = blockIdx.x * blockDim.x + threadIdx.x;
  if (token >= token_count) {
    return;
  }

  const int base = token * expert_count;
  float best_logit = -FLT_MAX;
  float second_logit = -FLT_MAX;
  int best_expert = -1;
  int second_expert = -1;

  for (int expert = 0; expert < expert_count; ++expert) {
    const float logit = logits[base + expert];
    if (logit > best_logit) {
      second_logit = best_logit;
      second_expert = best_expert;
      best_logit = logit;
      best_expert = expert;
    } else if (logit > second_logit) {
      second_logit = logit;
      second_expert = expert;
    }
  }

  const float max_logit = fmaxf(best_logit, second_logit);
  const float w0 = expf(best_logit - max_logit);
  const float w1 = expf(second_logit - max_logit);
  const float inv_sum = 1.0f / (w0 + w1);

  topk_ids[token * 2 + 0] = best_expert;
  topk_ids[token * 2 + 1] = second_expert;
  topk_weights[token * 2 + 0] = w0 * inv_sum;
  topk_weights[token * 2 + 1] = w1 * inv_sum;
}
