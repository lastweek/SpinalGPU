// Purpose: Separate attention softmax from weighted-value accumulation.
// Primary topic: softmax_value
// Optimization stage: 00_split_phase
// Expected learning outcome: See the cleanest correctness-first decomposition before moving to a fused attention kernel.
// High-level execution flow:
// - Kernel one computes a numerically stable row-wise softmax over the score matrix.
// - Kernel two multiplies each probability row by the value matrix to form the attention output row.
// - The probability matrix is materialized in global memory between the two phases.
// Performance idea:
// - Keep the pipeline easy to reason about before folding the stages together.
// Key CUDA features:
// - split kernel pipeline
// - row-wise softmax
// - matrix-vector style weighted value accumulation
// Correctness constraints:
// - Launch the softmax kernel with at least `blockDim.x * sizeof(float)` bytes of dynamic shared memory.
// - Launch the apply kernel so threads cover the output head dimension.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/02_attention/softmax_value/00_split_phase.cu`
// Profiling focus:
// - Inspect the cost of materializing `probs[seq_len, seq_len]` and rereading it in the second kernel.
// Relation to SpinalGPU PTX corpus:
// - This is a CUDA-side multi-kernel pipeline built from the repo's existing row-wise arithmetic patterns.

#include <float.h>
#include <math.h>

extern "C" __global__ void softmax_rows_reference(
    const float* __restrict__ scores,
    float* __restrict__ probs,
    int seq_len
) {
  extern __shared__ float shared[];

  const int row = blockIdx.x;
  const int tid = threadIdx.x;
  const int row_base = row * seq_len;

  if (row >= seq_len) {
    return;
  }

  float local_max = -FLT_MAX;
  for (int col = tid; col < seq_len; col += blockDim.x) {
    local_max = fmaxf(local_max, scores[row_base + col]);
  }

  shared[tid] = local_max;
  __syncthreads();
  for (int offset = blockDim.x / 2; offset > 0; offset >>= 1) {
    if (tid < offset) {
      shared[tid] = fmaxf(shared[tid], shared[tid + offset]);
    }
    __syncthreads();
  }

  const float row_max = shared[0];
  float local_sum = 0.0f;
  for (int col = tid; col < seq_len; col += blockDim.x) {
    local_sum += expf(scores[row_base + col] - row_max);
  }

  shared[tid] = local_sum;
  __syncthreads();
  for (int offset = blockDim.x / 2; offset > 0; offset >>= 1) {
    if (tid < offset) {
      shared[tid] += shared[tid + offset];
    }
    __syncthreads();
  }

  const float inv_sum = 1.0f / shared[0];
  for (int col = tid; col < seq_len; col += blockDim.x) {
    probs[row_base + col] = expf(scores[row_base + col] - row_max) * inv_sum;
  }
}

extern "C" __global__ void apply_attention_weights_reference(
    const float* __restrict__ probs,
    const float* __restrict__ v,
    float* __restrict__ output,
    int seq_len,
    int head_dim
) {
  const int query = blockIdx.x;
  const int out_dim = blockIdx.y * blockDim.x + threadIdx.x;

  if (query >= seq_len || out_dim >= head_dim) {
    return;
  }

  float acc = 0.0f;
  for (int key = 0; key < seq_len; ++key) {
    acc += probs[query * seq_len + key] * v[key * head_dim + out_dim];
  }

  output[query * head_dim + out_dim] = acc;
}
