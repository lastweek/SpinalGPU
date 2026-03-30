// Purpose: Establish the simplest one-thread-per-element vector add kernel.
// Primary topic: vector_add
// Optimization stage: 00_reference
// Expected learning outcome: See the base indexing and bounds-guard pattern before introducing any performance-oriented structure.
// High-level execution flow:
// - Compute one global element index from block and thread coordinates.
// - Guard against indices outside the vector length.
// - Load one FP32 value from each input vector, add them, and store the result.
// Performance idea:
// - Keep the kernel intentionally minimal so later optimizations are easy to compare against.
// Key CUDA features:
// - 1D launch indexing
// - basic bounds guard
// - straightforward global loads and stores
// Correctness constraints:
// - `n` is the logical element count.
// - Input and output pointers must reference at least `n` FP32 elements.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/00_foundations/vector_add/00_reference.cu`
// Profiling focus:
// - Confirm that this kernel is dominated by global memory traffic rather than arithmetic.
// Relation to SpinalGPU PTX corpus:
// - This is the CUDA C++ analogue of `kernels/global_memory/vector_add_1warp.ptx`, but without the one-warp teaching restriction.

extern "C" __global__ void vector_add_reference(
    const float* __restrict__ a,
    const float* __restrict__ b,
    float* __restrict__ c,
    int n
) {
  const int idx = blockIdx.x * blockDim.x + threadIdx.x;
  if (idx < n) {
    c[idx] = a[idx] + b[idx];
  }
}
