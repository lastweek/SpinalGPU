// Purpose: Replace the full shared-memory tree in RMSNorm with warp-level reductions.
// Primary topic: rmsnorm
// Optimization stage: 02_warp_reduce
// Expected learning outcome: Learn how row-wise norm kernels usually shrink synchronization by reducing inside warps first.
// High-level execution flow:
// - Each thread accumulates a local sum of squares.
// - Warps reduce those partials with shuffle intrinsics.
// - One shared value per warp is combined into a row-wide inverse RMS scalar.
// Performance idea:
// - Cut most barrier traffic and keep more of the reduction in registers.
// Key CUDA features:
// - warp shuffle intrinsics
// - one shared partial per warp
// - row-wise normalization
// Correctness constraints:
// - Cleanest launch shapes use `blockDim.x` as a multiple of 32.
// - `gamma` and the row data must have `cols` elements.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/01_norm_softmax/rmsnorm/02_warp_reduce.cu`
// Profiling focus:
// - Compare barrier count and row latency against the shared-memory baseline.
// Relation to SpinalGPU PTX corpus:
// - This introduces warp intrinsics that are CUDA-specific and beyond the repo's current PTX subset.

#include <cuda_runtime.h>
#include <math.h>

__device__ __forceinline__ float warp_sum_rms(float value) {
  for (int offset = 16; offset > 0; offset >>= 1) {
    value += __shfl_down_sync(0xffffffffu, value, offset);
  }
  return value;
}

extern "C" __global__ void rmsnorm_warp_reduce(
    const float* __restrict__ x,
    const float* __restrict__ gamma,
    float* __restrict__ y,
    int rows,
    int cols,
    float eps
) {
  __shared__ float warp_sums[32];
  __shared__ float inv_rms_shared;

  const int row = blockIdx.x;
  const int tid = threadIdx.x;
  const int lane = tid & 31;
  const int warp = tid >> 5;
  const int warp_count = (blockDim.x + 31) >> 5;
  const int row_base = row * cols;

  if (row >= rows) {
    return;
  }

  float local_sq = 0.0f;
  for (int col = tid; col < cols; col += blockDim.x) {
    const float v = x[row_base + col];
    local_sq += v * v;
  }

  local_sq = warp_sum_rms(local_sq);
  if (lane == 0) {
    warp_sums[warp] = local_sq;
  }
  __syncthreads();

  if (warp == 0) {
    float block_sum = (lane < warp_count) ? warp_sums[lane] : 0.0f;
    block_sum = warp_sum_rms(block_sum);
    if (lane == 0) {
      inv_rms_shared = rsqrtf(block_sum / static_cast<float>(cols) + eps);
    }
  }
  __syncthreads();

  for (int col = tid; col < cols; col += blockDim.x) {
    y[row_base + col] = x[row_base + col] * inv_rms_shared * gamma[col];
  }
}
