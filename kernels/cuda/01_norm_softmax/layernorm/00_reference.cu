// Purpose: Provide the simplest row-wise FP32 LayerNorm kernel.
// Primary topic: layernorm
// Optimization stage: 00_reference
// Expected learning outcome: Understand the baseline two-statistic normalization flow before adding vectorization or warp-specialized reductions.
// High-level execution flow:
// - One block owns one row.
// - Threads reduce the row sum and sum of squares into shared memory.
// - The block computes mean and variance, then applies affine normalization.
// Performance idea:
// - Keep the reduction flow explicit so later optimizations are easy to compare against.
// Key CUDA features:
// - row-wise mean and variance reduction
// - dynamic shared memory
// - affine epilogue with `gamma` and `beta`
// Correctness constraints:
// - Launch with at least `2 * blockDim.x * sizeof(float)` bytes of dynamic shared memory.
// - `cols` must be positive.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/01_norm_softmax/layernorm/00_reference.cu`
// Profiling focus:
// - Measure the cost of doing two statistics in the same block reduction.
// Relation to SpinalGPU PTX corpus:
// - This is a higher-level ML kernel built from the same scalar FP32 arithmetic motifs present in the PTX corpus.

#include <math.h>

extern "C" __global__ void layernorm_reference(
    const float* __restrict__ x,
    const float* __restrict__ gamma,
    const float* __restrict__ beta,
    float* __restrict__ y,
    int rows,
    int cols,
    float eps
) {
  extern __shared__ float shared[];
  float* sum_shared = shared;
  float* sq_shared = shared + blockDim.x;

  const int row = blockIdx.x;
  const int tid = threadIdx.x;
  const int row_base = row * cols;

  if (row >= rows) {
    return;
  }

  float local_sum = 0.0f;
  float local_sq = 0.0f;
  for (int col = tid; col < cols; col += blockDim.x) {
    const float v = x[row_base + col];
    local_sum += v;
    local_sq += v * v;
  }

  sum_shared[tid] = local_sum;
  sq_shared[tid] = local_sq;
  __syncthreads();

  for (int offset = blockDim.x / 2; offset > 0; offset >>= 1) {
    if (tid < offset) {
      sum_shared[tid] += sum_shared[tid + offset];
      sq_shared[tid] += sq_shared[tid + offset];
    }
    __syncthreads();
  }

  const float mean = sum_shared[0] / static_cast<float>(cols);
  const float variance = sq_shared[0] / static_cast<float>(cols) - mean * mean;
  const float inv_std = rsqrtf(variance + eps);

  for (int col = tid; col < cols; col += blockDim.x) {
    const float normalized = (x[row_base + col] - mean) * inv_std;
    y[row_base + col] = normalized * gamma[col] + beta[col];
  }
}
