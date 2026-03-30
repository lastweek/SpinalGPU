// Purpose: Provide the classic numerically stable row-wise softmax baseline.
// Primary topic: softmax_rowwise
// Optimization stage: 00_safe_reference
// Expected learning outcome: Understand the standard max-pass plus sum-pass softmax before introducing online statistics.
// High-level execution flow:
// - One block owns one row.
// - Threads reduce the row maximum into shared memory.
// - A second pass reduces the row sum of exponentials, then the block writes normalized probabilities.
// Performance idea:
// - Keep the numerically stable reference structure explicit and easy to reason about.
// Key CUDA features:
// - row-wise max reduction
// - row-wise sum reduction
// - exponent-based normalization
// Correctness constraints:
// - Launch with at least `blockDim.x * sizeof(float)` bytes of dynamic shared memory.
// - `cols` must be positive.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/01_norm_softmax/softmax_rowwise/00_safe_reference.cu`
// Profiling focus:
// - Measure the cost of the extra passes and shared-memory reductions.
// Relation to SpinalGPU PTX corpus:
// - This is the natural CUDA follow-on to the repo's row-wise FP32 arithmetic kernels.

#include <float.h>
#include <math.h>

extern "C" __global__ void softmax_safe_reference(
    const float* __restrict__ scores,
    float* __restrict__ probs,
    int rows,
    int cols
) {
  extern __shared__ float shared[];

  const int row = blockIdx.x;
  const int tid = threadIdx.x;
  const int row_base = row * cols;

  if (row >= rows) {
    return;
  }

  float local_max = -FLT_MAX;
  for (int col = tid; col < cols; col += blockDim.x) {
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
  for (int col = tid; col < cols; col += blockDim.x) {
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
  for (int col = tid; col < cols; col += blockDim.x) {
    probs[row_base + col] = expf(scores[row_base + col] - row_max) * inv_sum;
  }
}
