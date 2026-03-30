// Purpose: Remap the same matrix scale so neighboring lanes issue coalesced row-major traffic.
// Primary topic: memory_coalescing
// Optimization stage: 01_coalesced
// Expected learning outcome: See how a launch remap alone can dramatically improve global-memory efficiency.
// High-level execution flow:
// - Map `threadIdx.x` to columns and `threadIdx.y` to rows.
// - Load one contiguous row-major FP32 element per lane.
// - Multiply by a scalar and store the result.
// Performance idea:
// - Preserve the same computation while changing only the lane-to-address mapping.
// Key CUDA features:
// - 2D launch indexing
// - row-major contiguous access across the warp
// - simple elementwise transform
// Correctness constraints:
// - The logical matrix extent is still `rows x cols`.
// - Each thread writes exactly one in-bounds output element.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/00_foundations/memory_coalescing/01_coalesced.cu`
// Profiling focus:
// - Compare global-memory efficiency and transaction count against the strided baseline.
// Relation to SpinalGPU PTX corpus:
// - This uses the same kind of address arithmetic as the PTX matrix kernels, but focuses on warp-friendly row-major mapping.

extern "C" __global__ void scale_coalesced(
    const float* __restrict__ input,
    float* __restrict__ output,
    int rows,
    int cols,
    float alpha
) {
  const int col = blockIdx.x * blockDim.x + threadIdx.x;
  const int row = blockIdx.y * blockDim.y + threadIdx.y;

  if (row < rows && col < cols) {
    const int idx = row * cols + col;
    output[idx] = alpha * input[idx];
  }
}
