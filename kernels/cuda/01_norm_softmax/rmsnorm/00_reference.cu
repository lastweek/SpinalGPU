// Purpose: Provide the simplest row-wise FP32 RMSNorm kernel.
// Primary topic: rmsnorm
// Optimization stage: 00_reference
// Expected learning outcome: Understand the baseline row reduction and normalization flow before adding vectorization or warp intrinsics.
// High-level execution flow:
// - One block owns one row.
// - Threads reduce the row's sum of squares into shared memory.
// - The block computes one inverse RMS value and then scales each element by `gamma`.
// Performance idea:
// - Keep the structure obvious so later changes to reduction and memory traffic are easy to compare.
// Key CUDA features:
// - row-wise reduction
// - dynamic shared memory
// - two-pass row traversal
// Correctness constraints:
// - Launch with at least `blockDim.x * sizeof(float)` bytes of dynamic shared memory.
// - `cols` must be positive and `gamma` must have `cols` elements.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/01_norm_softmax/rmsnorm/00_reference.cu`
// Profiling focus:
// - Measure the cost of the shared-memory reduction and the extra row pass.
// Relation to SpinalGPU PTX corpus:
// - This builds on the repo's FP32 vector kernels, but adds a reduction-shaped ML primitive that is not yet present in PTX.

#include <math.h>

extern "C" __global__ void rmsnorm_reference(
    const float* __restrict__ x,
    const float* __restrict__ gamma,
    float* __restrict__ y,
    int rows,
    int cols,
    float eps
) {
  extern __shared__ float shared[];

  const int row = blockIdx.x;
  const int tid = threadIdx.x;

  if (row >= rows) {
    return;
  }

  float local_sq = 0.0f;
  const int row_base = row * cols;
  for (int col = tid; col < cols; col += blockDim.x) {
    const float v = x[row_base + col];
    local_sq += v * v;
  }

  shared[tid] = local_sq;
  __syncthreads();

  for (int offset = blockDim.x / 2; offset > 0; offset >>= 1) {
    if (tid < offset) {
      shared[tid] += shared[tid + offset];
    }
    __syncthreads();
  }

  const float inv_rms = rsqrtf(shared[0] / static_cast<float>(cols) + eps);
  for (int col = tid; col < cols; col += blockDim.x) {
    y[row_base + col] = x[row_base + col] * inv_rms * gamma[col];
  }
}
