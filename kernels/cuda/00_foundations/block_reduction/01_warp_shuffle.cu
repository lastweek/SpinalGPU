// Purpose: Replace most shared-memory reduction steps with warp shuffle intrinsics.
// Primary topic: block_reduction
// Optimization stage: 01_warp_shuffle
// Expected learning outcome: See how to reduce shared-memory traffic by finishing most work inside each warp.
// High-level execution flow:
// - Each thread accumulates a grid-stride slice into a private sum.
// - Reduce that sum inside each warp with `__shfl_down_sync`.
// - Write one partial sum per warp to shared memory and reduce those warp sums.
// Performance idea:
// - Keep most of the reduction inside registers and warp crossbar hardware.
// Key CUDA features:
// - warp shuffle intrinsics
// - one shared value per warp
// - hybrid warp-plus-block reduction
// Correctness constraints:
// - Assumes `blockDim.x` is a multiple of 32 for the cleanest warp decomposition.
// - `block_sums` must contain at least one slot per block.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/00_foundations/block_reduction/01_warp_shuffle.cu`
// Profiling focus:
// - Compare shared-memory traffic and barrier count against the shared-tree baseline.
// Relation to SpinalGPU PTX corpus:
// - This introduces warp-level CUDA idioms that do not appear in the current PTX subset yet.

#include <cuda_runtime.h>

__device__ __forceinline__ float warp_sum(float value) {
  for (int offset = 16; offset > 0; offset >>= 1) {
    value += __shfl_down_sync(0xffffffffu, value, offset);
  }
  return value;
}

extern "C" __global__ void reduce_warp_shuffle(
    const float* __restrict__ input,
    float* __restrict__ block_sums,
    int n
) {
  __shared__ float warp_sums[32];

  const int tid = threadIdx.x;
  const int lane = tid & 31;
  const int warp = tid >> 5;
  const int warp_count = (blockDim.x + 31) >> 5;
  const int global_tid = blockIdx.x * blockDim.x + tid;
  const int stride = blockDim.x * gridDim.x;

  float local_sum = 0.0f;
  for (int idx = global_tid; idx < n; idx += stride) {
    local_sum += input[idx];
  }

  local_sum = warp_sum(local_sum);
  if (lane == 0) {
    warp_sums[warp] = local_sum;
  }
  __syncthreads();

  float block_sum = (tid < warp_count) ? warp_sums[lane] : 0.0f;
  if (warp == 0) {
    block_sum = warp_sum(block_sum);
    if (lane == 0) {
      block_sums[blockIdx.x] = block_sum;
    }
  }
}
