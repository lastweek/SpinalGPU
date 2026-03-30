// Purpose: Introduce the grid-stride loop version of vector add.
// Primary topic: vector_add
// Optimization stage: 01_grid_stride
// Expected learning outcome: Understand why grid-stride loops decouple kernel structure from a specific launch size.
// High-level execution flow:
// - Compute the starting element index for the thread.
// - Advance through the vector in steps of `gridDim.x * blockDim.x`.
// - Add and store one element on each iteration.
// Performance idea:
// - A grid-stride loop lets the same kernel cover small and large vectors without changing the inner body.
// Key CUDA features:
// - grid-stride loop
// - 1D global indexing
// - repeated coalesced vector traversal
// Correctness constraints:
// - The loop step must be positive.
// - Input and output pointers must reference at least `n` FP32 elements.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/00_foundations/vector_add/01_grid_stride.cu`
// Profiling focus:
// - Compare occupancy and launch flexibility against the reference kernel.
// Relation to SpinalGPU PTX corpus:
// - This keeps the same arithmetic shape as the PTX vector-add examples while moving to a more idiomatic CUDA launch pattern.

extern "C" __global__ void vector_add_grid_stride(
    const float* __restrict__ a,
    const float* __restrict__ b,
    float* __restrict__ c,
    int n
) {
  const int global_tid = blockIdx.x * blockDim.x + threadIdx.x;
  const int stride = blockDim.x * gridDim.x;

  for (int idx = global_tid; idx < n; idx += stride) {
    c[idx] = a[idx] + b[idx];
  }
}
