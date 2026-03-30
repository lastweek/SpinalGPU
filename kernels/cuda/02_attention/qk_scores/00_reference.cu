// Purpose: Provide the simplest one-thread-per-score QK^T kernel for one attention head.
// Primary topic: qk_scores
// Optimization stage: 00_reference
// Expected learning outcome: Understand the direct mapping from one thread to one attention score before introducing any tiling.
// High-level execution flow:
// - Each thread maps to one `(query, key)` pair.
// - The thread walks the full head dimension and accumulates one dot product.
// - The scaled score is written to the output matrix.
// Performance idea:
// - Keep the score kernel explicit so shared-memory tiling has a clear baseline.
// Key CUDA features:
// - 2D launch over query rows and key columns
// - row-major dot product
// - scaled score writeback
// Correctness constraints:
// - `scale` is usually `1 / sqrt(head_dim)`.
// - `scores` must have room for `seq_len * seq_len` FP32 values.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/02_attention/qk_scores/00_reference.cu`
// Profiling focus:
// - Measure redundant `Q` and `K` rereads per output score.
// Relation to SpinalGPU PTX corpus:
// - This extends the repo's untiled matrix-multiply PTX examples into the first attention-specific operation.

extern "C" __global__ void qk_scores_reference(
    const float* __restrict__ q,
    const float* __restrict__ k,
    float* __restrict__ scores,
    int seq_len,
    int head_dim,
    float scale
) {
  const int key = blockIdx.x * blockDim.x + threadIdx.x;
  const int query = blockIdx.y;

  if (query >= seq_len || key >= seq_len) {
    return;
  }

  const int q_base = query * head_dim;
  const int k_base = key * head_dim;
  float acc = 0.0f;
  for (int d = 0; d < head_dim; ++d) {
    acc += q[q_base + d] * k[k_base + d];
  }

  scores[query * seq_len + key] = acc * scale;
}
