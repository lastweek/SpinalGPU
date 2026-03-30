// Purpose: Reduce online softmax statistics with warp shuffle intrinsics.
// Primary topic: softmax_rowwise
// Optimization stage: 02_warp_shuffle
// Expected learning outcome: See the common CUDA pattern for reducing softmax row statistics with fewer full-block barriers.
// High-level execution flow:
// - Each thread accumulates online `(max, sum)` statistics for a row slice.
// - Warps merge those statistics with shuffle intrinsics.
// - One warp combines the per-warp statistics and the block writes the normalized row.
// Performance idea:
// - Keep the online numerics while shrinking shared-memory traffic and barrier count.
// Key CUDA features:
// - online softmax merge rule
// - warp shuffle intrinsics
// - shared storage only for per-warp partials
// Correctness constraints:
// - Best launch shapes use `blockDim.x` as a multiple of 32.
// - The merge rule must preserve numerical stability when maxima differ.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/01_norm_softmax/softmax_rowwise/02_warp_shuffle.cu`
// Profiling focus:
// - Compare synchronization cost and row latency with the shared-memory online variant.
// Relation to SpinalGPU PTX corpus:
// - This file highlights CUDA warp intrinsics that the PTX teaching corpus has not introduced yet.

#include <cuda_runtime.h>
#include <float.h>
#include <math.h>

__device__ __forceinline__ void merge_softmax_stats(
    float other_max,
    float other_sum,
    float& current_max,
    float& current_sum
) {
  const float merged_max = fmaxf(current_max, other_max);
  current_sum =
      current_sum * expf(current_max - merged_max) +
      other_sum * expf(other_max - merged_max);
  current_max = merged_max;
}

extern "C" __global__ void softmax_online_warp(
    const float* __restrict__ scores,
    float* __restrict__ probs,
    int rows,
    int cols
) {
  __shared__ float warp_max[32];
  __shared__ float warp_sum[32];

  const int row = blockIdx.x;
  const int tid = threadIdx.x;
  const int lane = tid & 31;
  const int warp = tid >> 5;
  const int warp_count = (blockDim.x + 31) >> 5;
  const int row_base = row * cols;

  if (row >= rows) {
    return;
  }

  float local_max = -FLT_MAX;
  float local_sum = 0.0f;
  for (int col = tid; col < cols; col += blockDim.x) {
    const float score = scores[row_base + col];
    const float new_max = fmaxf(local_max, score);
    local_sum = local_sum * expf(local_max - new_max) + expf(score - new_max);
    local_max = new_max;
  }

  for (int offset = 16; offset > 0; offset >>= 1) {
    const float other_max = __shfl_down_sync(0xffffffffu, local_max, offset);
    const float other_sum = __shfl_down_sync(0xffffffffu, local_sum, offset);
    if (lane + offset < 32) {
      merge_softmax_stats(other_max, other_sum, local_max, local_sum);
    }
  }

  if (lane == 0) {
    warp_max[warp] = local_max;
    warp_sum[warp] = local_sum;
  }
  __syncthreads();

  if (warp == 0) {
    float block_max = (lane < warp_count) ? warp_max[lane] : -FLT_MAX;
    float block_sum = (lane < warp_count) ? warp_sum[lane] : 0.0f;
    for (int offset = 16; offset > 0; offset >>= 1) {
      const float other_max = __shfl_down_sync(0xffffffffu, block_max, offset);
      const float other_sum = __shfl_down_sync(0xffffffffu, block_sum, offset);
      if (lane + offset < 32) {
        merge_softmax_stats(other_max, other_sum, block_max, block_sum);
      }
    }
    if (lane == 0) {
      warp_max[0] = block_max;
      warp_sum[0] = block_sum;
    }
  }
  __syncthreads();

  const float row_max = warp_max[0];
  const float inv_sum = 1.0f / warp_sum[0];
  for (int col = tid; col < cols; col += blockDim.x) {
    probs[row_base + col] = expf(scores[row_base + col] - row_max) * inv_sum;
  }
}
