// Purpose: Show a warp-per-row RMSNorm kernel that processes multiple rows in one block.
// Primary topic: rmsnorm
// Optimization stage: 03_rows_per_block
// Expected learning outcome: See how small or medium rows can benefit when a block carries several independent rows at once.
// High-level execution flow:
// - `threadIdx.y` selects the row within the block and `threadIdx.x` is the warp lane.
// - Each warp reduces one row's sum of squares.
// - The same warp writes the normalized output for that row.
// Performance idea:
// - Improve scheduling efficiency on short rows by letting one block host multiple warp-sized row kernels.
// Key CUDA features:
// - one warp per row
// - 2D block layout
// - warp-only reduction for each row
// Correctness constraints:
// - Launch with `blockDim.x == 32`.
// - `blockDim.y` determines how many rows each block covers.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/01_norm_softmax/rmsnorm/03_rows_per_block.cu`
// Profiling focus:
// - Watch occupancy and row throughput for short hidden sizes.
// Relation to SpinalGPU PTX corpus:
// - This kernel shape is a useful bridge from the repo's one-CTA teaching kernels toward warp-specialized CUDA layouts.

#include <cuda_runtime.h>
#include <math.h>

__device__ __forceinline__ float warp_sum_rows(float value) {
  for (int offset = 16; offset > 0; offset >>= 1) {
    value += __shfl_down_sync(0xffffffffu, value, offset);
  }
  return value;
}

extern "C" __global__ void rmsnorm_rows_per_block(
    const float* __restrict__ x,
    const float* __restrict__ gamma,
    float* __restrict__ y,
    int rows,
    int cols,
    float eps
) {
  __shared__ float inv_rms_shared[8];

  const int lane = threadIdx.x;
  const int row_in_block = threadIdx.y;
  const int row = blockIdx.x * blockDim.y + row_in_block;
  const int row_base = row * cols;

  if (row >= rows) {
    return;
  }

  float local_sq = 0.0f;
  for (int col = lane; col < cols; col += 32) {
    const float v = x[row_base + col];
    local_sq += v * v;
  }

  const float row_sum = warp_sum_rows(local_sq);
  if (lane == 0) {
    inv_rms_shared[row_in_block] = rsqrtf(row_sum / static_cast<float>(cols) + eps);
  }
  __syncwarp();

  const float inv_rms = inv_rms_shared[row_in_block];
  for (int col = lane; col < cols; col += 32) {
    y[row_base + col] = x[row_base + col] * inv_rms * gamma[col];
  }
}
