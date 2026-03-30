// Purpose: Introduce a shared-memory tiled FP32 GEMM.
// Primary topic: tiled_gemm
// Optimization stage: 01_shared_tiled
// Expected learning outcome: See how shared-memory tiles reduce redundant global-memory traffic across the block.
// High-level execution flow:
// - Each block covers one `TILE x TILE` output tile.
// - Threads cooperatively load one `A` tile and one `B` tile into shared memory.
// - The block multiplies those tiles before advancing along the K dimension.
// Performance idea:
// - Reuse each loaded matrix element across many multiply-add operations inside the block.
// Key CUDA features:
// - shared-memory tiles
// - cooperative loads
// - tiled K loop with block-wide barriers
// Correctness constraints:
// - Launch with `blockDim.x == TILE` and `blockDim.y == TILE`.
// - Out-of-bounds tile elements are zero-filled.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/00_foundations/tiled_gemm/01_shared_tiled.cu`
// Profiling focus:
// - Compare global-memory traffic and shared-memory utilization with the naive kernel.
// Relation to SpinalGPU PTX corpus:
// - This is the next CUDA learning step after the repo's untiled PTX matrix-multiply kernel.

namespace {
constexpr int TILE = 16;
}

extern "C" __global__ void gemm_shared_tiled(
    const float* __restrict__ a,
    const float* __restrict__ b,
    float* __restrict__ c,
    int m,
    int n,
    int k,
    int lda,
    int ldb,
    int ldc
) {
  __shared__ float a_tile[TILE][TILE];
  __shared__ float b_tile[TILE][TILE];

  const int tx = threadIdx.x;
  const int ty = threadIdx.y;
  const int row = blockIdx.y * TILE + ty;
  const int col = blockIdx.x * TILE + tx;

  float acc = 0.0f;

  for (int k0 = 0; k0 < k; k0 += TILE) {
    a_tile[ty][tx] = (row < m && k0 + tx < k) ? a[row * lda + (k0 + tx)] : 0.0f;
    b_tile[ty][tx] = (k0 + ty < k && col < n) ? b[(k0 + ty) * ldb + col] : 0.0f;
    __syncthreads();

    for (int kk = 0; kk < TILE; ++kk) {
      acc += a_tile[ty][kk] * b_tile[kk][tx];
    }
    __syncthreads();
  }

  if (row < m && col < n) {
    c[row * ldc + col] = acc;
  }
}
