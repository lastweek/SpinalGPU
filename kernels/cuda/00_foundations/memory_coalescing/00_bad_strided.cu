// Purpose: Demonstrate a deliberately bad row-major access pattern that destroys coalescing.
// Primary topic: memory_coalescing
// Optimization stage: 00_bad_strided
// Expected learning outcome: See how a perfectly correct kernel can still waste bandwidth when neighboring lanes walk a large stride.
// High-level execution flow:
// - Map `threadIdx.x` to matrix rows and `threadIdx.y` to columns.
// - Load one FP32 element from a row-major matrix with a large row stride between neighboring lanes.
// - Multiply by a scalar and store the result.
// Performance idea:
// - This variant is intentionally bad so the remapped version has a clear baseline to beat.
// Key CUDA features:
// - 2D launch indexing
// - row-major linearization
// - intentionally strided global access
// Correctness constraints:
// - `rows * cols` describes the logical matrix extent.
// - `input` and `output` must reference at least `rows * cols` FP32 elements.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/00_foundations/memory_coalescing/00_bad_strided.cu`
// Profiling focus:
// - Look for poor global-load efficiency and extra memory transactions per warp.
// Relation to SpinalGPU PTX corpus:
// - This is the CUDA-side counterpart to the repo's simple global-memory kernels, but it isolates thread-to-address mapping as the teaching point.

extern "C" __global__ void scale_bad_strided(
    const float* __restrict__ input,
    float* __restrict__ output,
    int rows,
    int cols,
    float alpha
) {
  const int row = blockIdx.x * blockDim.x + threadIdx.x;
  const int col = blockIdx.y * blockDim.y + threadIdx.y;

  if (row < rows && col < cols) {
    const int idx = row * cols + col;
    output[idx] = alpha * input[idx];
  }
}
