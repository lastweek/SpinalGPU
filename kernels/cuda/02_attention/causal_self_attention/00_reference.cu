// Purpose: Provide a correctness-first single-head causal self-attention kernel.
// Primary topic: causal_self_attention
// Optimization stage: 00_reference
// Expected learning outcome: Understand the full causal attention algorithm before adding blocked online accumulation.
// High-level execution flow:
// - One block owns one query row.
// - Each thread handles one or more output dimensions.
// - For each output dimension, the thread computes the causal row max, the causal row softmax denominator, and then the weighted value sum.
// Performance idea:
// - Keep the whole algorithm explicit even though it recomputes scores heavily.
// Key CUDA features:
// - causal masking in the key loop
// - numerically stable softmax
// - weighted value accumulation
// Correctness constraints:
// - This teaching variant recomputes score dots multiple times; it is correctness-first, not throughput-first.
// - `scale` is usually `1 / sqrt(head_dim)`.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/02_attention/causal_self_attention/00_reference.cu`
// Profiling focus:
// - Measure the cost of redundant score recomputation and global-memory rereads.
// Relation to SpinalGPU PTX corpus:
// - This is the first end-to-end attention kernel in the learning ladder, analogous in spirit to how `matrix_mul_f32.ptx` is the end-to-end GEMM baseline.

#include <float.h>
#include <math.h>

extern "C" __global__ void causal_self_attention_reference(
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
    float row_max = -FLT_MAX;
    for (int key = 0; key <= query; ++key) {
      float score = 0.0f;
      for (int d = 0; d < head_dim; ++d) {
        score += q[query * head_dim + d] * k[key * head_dim + d];
      }
      row_max = fmaxf(row_max, score * scale);
    }

    float row_sum = 0.0f;
    for (int key = 0; key <= query; ++key) {
      float score = 0.0f;
      for (int d = 0; d < head_dim; ++d) {
        score += q[query * head_dim + d] * k[key * head_dim + d];
      }
      row_sum += expf(score * scale - row_max);
    }

    float acc = 0.0f;
    for (int key = 0; key <= query; ++key) {
      float score = 0.0f;
      for (int d = 0; d < head_dim; ++d) {
        score += q[query * head_dim + d] * k[key * head_dim + d];
      }
      const float weight = expf(score * scale - row_max) / row_sum;
      acc += weight * v[key * head_dim + out_dim];
    }

    output[query * head_dim + out_dim] = acc;
  }
}
