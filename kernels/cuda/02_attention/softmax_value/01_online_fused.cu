// Purpose: Fuse online softmax statistics with weighted value accumulation.
// Primary topic: softmax_value
// Optimization stage: 01_online_fused
// Expected learning outcome: Understand the core flash-attention-style idea of maintaining online normalization while accumulating the output.
// High-level execution flow:
// - One block owns one query row.
// - Each thread handles one or more output dimensions.
// - For each key, the thread recomputes the score, updates online `(max, sum)` statistics, and rescales its running output accumulator.
// Performance idea:
// - Eliminate the materialized probability matrix and keep normalization local to the output accumulation.
// Key CUDA features:
// - online softmax normalization
// - fused weighted value accumulation
// - one block per query row
// Correctness constraints:
// - This teaching variant intentionally recomputes score dots inside each output-dimension thread to keep the control flow easy to follow.
// - `scale` is usually `1 / sqrt(head_dim)`.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/02_attention/softmax_value/01_online_fused.cu`
// Profiling focus:
// - Compare memory traffic savings against the split-phase version and watch register pressure.
// Relation to SpinalGPU PTX corpus:
// - This is the CUDA learning step from explicit intermediate buffers toward fused attention kernels.

#include <float.h>
#include <math.h>

extern "C" __global__ void attention_online_fused_single_head(
    const float* __restrict__ q,
    const float* __restrict__ k,
    const float* __restrict__ v,
    float* __restrict__ output,
    int seq_len,
    int head_dim,
    float scale
) {
  const int query = blockIdx.x;

  if (query >= seq_len) {
    return;
  }

  for (int out_dim = threadIdx.x; out_dim < head_dim; out_dim += blockDim.x) {
    float running_max = -FLT_MAX;
    float running_sum = 0.0f;
    float acc = 0.0f;

    for (int key = 0; key < seq_len; ++key) {
      float score = 0.0f;
      for (int d = 0; d < head_dim; ++d) {
        score += q[query * head_dim + d] * k[key * head_dim + d];
      }
      score *= scale;

      const float new_max = fmaxf(running_max, score);
      const float alpha = expf(running_max - new_max);
      const float beta = expf(score - new_max);
      acc = acc * alpha + beta * v[key * head_dim + out_dim];
      running_sum = running_sum * alpha + beta;
      running_max = new_max;
    }

    output[query * head_dim + out_dim] = acc / running_sum;
  }
}
