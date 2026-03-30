// Purpose: Show the classic shared-memory tree reduction for FP32 sums.
// Primary topic: block_reduction
// Optimization stage: 00_shared_tree
// Expected learning outcome: Learn the baseline block-reduction structure before introducing warp intrinsics.
// High-level execution flow:
// - Each thread accumulates a grid-stride slice into a private sum.
// - The block writes those private sums into shared memory.
// - A tree reduction in shared memory produces one partial sum per block.
// Performance idea:
// - Use the standard shared-memory tree as the reference point for later warp-level improvements.
// Key CUDA features:
// - grid-stride load loop
// - dynamic shared memory
// - tree reduction with `__syncthreads()`
// Correctness constraints:
// - Launch with at least `blockDim.x * sizeof(float)` bytes of dynamic shared memory.
// - `block_sums` must contain at least one slot per block.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/00_foundations/block_reduction/00_shared_tree.cu`
// Profiling focus:
// - Measure barrier cost and shared-memory traffic.
// Relation to SpinalGPU PTX corpus:
// - The repo PTX corpus does not yet carry a reduction ladder; this topic fills that CUDA-learning gap.

extern "C" __global__ void reduce_shared_tree(
    const float* __restrict__ input,
    float* __restrict__ block_sums,
    int n
) {
  extern __shared__ float shared[];

  const int tid = threadIdx.x;
  const int global_tid = blockIdx.x * blockDim.x + tid;
  const int stride = blockDim.x * gridDim.x;

  float local_sum = 0.0f;
  for (int idx = global_tid; idx < n; idx += stride) {
    local_sum += input[idx];
  }

  shared[tid] = local_sum;
  __syncthreads();

  for (int offset = blockDim.x / 2; offset > 0; offset >>= 1) {
    if (tid < offset) {
      shared[tid] += shared[tid + offset];
    }
    __syncthreads();
  }

  if (tid == 0) {
    block_sums[blockIdx.x] = shared[0];
  }
}
