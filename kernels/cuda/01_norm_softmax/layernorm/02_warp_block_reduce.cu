// Purpose: Reduce LayerNorm statistics with warp-level intrinsics before crossing warp boundaries.
// Primary topic: layernorm
// Optimization stage: 02_warp_block_reduce
// Expected learning outcome: See the standard CUDA pattern for shrinking mean/variance reduction overhead.
// High-level execution flow:
// - Threads compute local row sums and squared sums.
// - Warps reduce those statistics with shuffle intrinsics.
// - One warp combines the per-warp partials and broadcasts mean and inverse standard deviation.
// Performance idea:
// - Keep more of the reduction in registers and reduce the number of full-block barriers.
// Key CUDA features:
// - warp shuffle intrinsics
// - shared warp partials
// - block-wide broadcast of row statistics
// Correctness constraints:
// - Best launch shapes use `blockDim.x` as a multiple of 32.
// - `gamma` and `beta` must have `cols` elements.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/01_norm_softmax/layernorm/02_warp_block_reduce.cu`
// Profiling focus:
// - Compare synchronization cost and row latency against the shared-memory baseline.
// Relation to SpinalGPU PTX corpus:
// - This is another CUDA-specific reduction idiom that sits beyond the current PTX frontend.

#include <cuda_runtime.h>
#include <math.h>

__device__ __forceinline__ float warp_sum_ln(float value) {
  for (int offset = 16; offset > 0; offset >>= 1) {
    value += __shfl_down_sync(0xffffffffu, value, offset);
  }
  return value;
}

extern "C" __global__ void layernorm_warp_block(
    const float* __restrict__ x,
    const float* __restrict__ gamma,
    const float* __restrict__ beta,
    float* __restrict__ y,
    int rows,
    int cols,
    float eps
) {
  __shared__ float warp_sums[32];
  __shared__ float warp_sq_sums[32];
  __shared__ float mean_shared;
  __shared__ float inv_std_shared;

  const int row = blockIdx.x;
  const int tid = threadIdx.x;
  const int lane = tid & 31;
  const int warp = tid >> 5;
  const int warp_count = (blockDim.x + 31) >> 5;
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

  local_sum = warp_sum_ln(local_sum);
  local_sq = warp_sum_ln(local_sq);
  if (lane == 0) {
    warp_sums[warp] = local_sum;
    warp_sq_sums[warp] = local_sq;
  }
  __syncthreads();

  if (warp == 0) {
    float block_sum = (lane < warp_count) ? warp_sums[lane] : 0.0f;
    float block_sq = (lane < warp_count) ? warp_sq_sums[lane] : 0.0f;
    block_sum = warp_sum_ln(block_sum);
    block_sq = warp_sum_ln(block_sq);
    if (lane == 0) {
      const float mean = block_sum / static_cast<float>(cols);
      const float variance = block_sq / static_cast<float>(cols) - mean * mean;
      mean_shared = mean;
      inv_std_shared = rsqrtf(variance + eps);
    }
  }
  __syncthreads();

  for (int col = tid; col < cols; col += blockDim.x) {
    const float normalized = (x[row_base + col] - mean_shared) * inv_std_shared;
    y[row_base + col] = normalized * gamma[col] + beta[col];
  }
}
