// Purpose: Fold scale and additive mask application into the row-wise softmax kernel.
// Primary topic: softmax_rowwise
// Optimization stage: 03_scale_mask_fused
// Expected learning outcome: See the common attention-side softmax pattern where scaling and masking are fused into the same normalization pass.
// High-level execution flow:
// - One block owns one row of attention scores.
// - Threads apply scale and additive mask while accumulating online `(max, sum)` statistics.
// - The block writes final probabilities without materializing an intermediate scaled or masked buffer.
// Performance idea:
// - Remove extra memory round trips and keep the transformed scores local to the softmax pipeline.
// Key CUDA features:
// - fused scale and mask transform
// - online softmax statistics
// - warp-level plus block-level reduction
// Correctness constraints:
// - `mask` is additive and should contain `0` for valid positions and a large negative value for masked positions.
// - `scale` is typically `1 / sqrt(head_dim)` in attention kernels.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/01_norm_softmax/softmax_rowwise/03_scale_mask_fused.cu`
// Profiling focus:
// - Compare memory traffic and row latency versus a pipeline that materializes scaled or masked scores.
// Relation to SpinalGPU PTX corpus:
// - This is the CUDA-side precursor to later attention kernels that will combine score transform and normalization.

#include <cuda_runtime.h>
#include <float.h>
#include <math.h>

__device__ __forceinline__ void merge_softmax_stats_fused(
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

extern "C" __global__ void softmax_scale_mask_fused(
    const float* __restrict__ logits,
    const float* __restrict__ mask,
    float* __restrict__ probs,
    int rows,
    int cols,
    float scale
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
    const float transformed = logits[row_base + col] * scale + mask[row_base + col];
    const float new_max = fmaxf(local_max, transformed);
    local_sum = local_sum * expf(local_max - new_max) + expf(transformed - new_max);
    local_max = new_max;
  }

  for (int offset = 16; offset > 0; offset >>= 1) {
    const float other_max = __shfl_down_sync(0xffffffffu, local_max, offset);
    const float other_sum = __shfl_down_sync(0xffffffffu, local_sum, offset);
    if (lane + offset < 32) {
      merge_softmax_stats_fused(other_max, other_sum, local_max, local_sum);
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
        merge_softmax_stats_fused(other_max, other_sum, block_max, block_sum);
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
    const float transformed = logits[row_base + col] * scale + mask[row_base + col];
    probs[row_base + col] = expf(transformed - row_max) * inv_sum;
  }
}
