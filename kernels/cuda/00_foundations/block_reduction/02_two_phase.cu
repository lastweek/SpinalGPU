// Purpose: Make the reduction pipeline explicit with separate partial-sum and final-sum kernels.
// Primary topic: block_reduction
// Optimization stage: 02_two_phase
// Expected learning outcome: Understand the practical structure used when one block cannot reduce the entire input alone.
// High-level execution flow:
// - Phase one reduces the full input into one partial sum per block.
// - Phase two reduces the partial-sum vector into a single scalar output.
// - Both phases reuse the same warp-level reduction helper.
// Performance idea:
// - Separate the bandwidth-heavy input pass from the small final reduction to keep each launch simple.
// Key CUDA features:
// - multi-kernel reduction pipeline
// - warp shuffle reduction
// - explicit partial buffer
// Correctness constraints:
// - `partials` must contain at least one slot per phase-one block.
// - Launch phase two with enough threads to cover `partial_count`.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/00_foundations/block_reduction/02_two_phase.cu`
// Profiling focus:
// - Inspect the balance between the large input pass and the tiny final pass.
// Relation to SpinalGPU PTX corpus:
// - This file shows the kind of multi-launch workflow that will matter for future higher-level runtime studies.

#include <cuda_runtime.h>

__device__ __forceinline__ float warp_sum_two_phase(float value) {
  for (int offset = 16; offset > 0; offset >>= 1) {
    value += __shfl_down_sync(0xffffffffu, value, offset);
  }
  return value;
}

extern "C" __global__ void reduce_phase_one(
    const float* __restrict__ input,
    float* __restrict__ partials,
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

  local_sum = warp_sum_two_phase(local_sum);
  if (lane == 0) {
    warp_sums[warp] = local_sum;
  }
  __syncthreads();

  float block_sum = (tid < warp_count) ? warp_sums[lane] : 0.0f;
  if (warp == 0) {
    block_sum = warp_sum_two_phase(block_sum);
    if (lane == 0) {
      partials[blockIdx.x] = block_sum;
    }
  }
}

extern "C" __global__ void reduce_phase_two(
    const float* __restrict__ partials,
    float* __restrict__ output,
    int partial_count
) {
  __shared__ float warp_sums[32];

  const int tid = threadIdx.x;
  const int lane = tid & 31;
  const int warp = tid >> 5;
  const int warp_count = (blockDim.x + 31) >> 5;

  float value = (tid < partial_count) ? partials[tid] : 0.0f;
  value = warp_sum_two_phase(value);
  if (lane == 0) {
    warp_sums[warp] = value;
  }
  __syncthreads();

  float final_sum = (tid < warp_count) ? warp_sums[lane] : 0.0f;
  if (warp == 0) {
    final_sum = warp_sum_two_phase(final_sum);
    if (lane == 0) {
      output[0] = final_sum;
    }
  }
}
