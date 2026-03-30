// Purpose: Add a small register tile per thread on top of shared-memory tiling.
// Primary topic: tiled_gemm
// Optimization stage: 02_register_tiled
// Expected learning outcome: See how a thread can amortize tile loads over multiple output accumulators.
// High-level execution flow:
// - Each block covers a `32 x 32` output tile with `16 x 16` threads.
// - Shared memory holds one `A` tile and one `B` tile for the current K slice.
// - Each thread updates a `2 x 2` register tile before storing up to four outputs.
// Performance idea:
// - Increase arithmetic work per thread so each shared-memory load feeds more FMAs.
// Key CUDA features:
// - shared-memory tiling
// - per-thread register tiling
// - multiple outputs per thread
// Correctness constraints:
// - Launch with `blockDim == dim3(16, 16)`.
// - Out-of-bounds rows and columns are zero-filled or masked on store.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/00_foundations/tiled_gemm/02_register_tiled.cu`
// Profiling focus:
// - Compare register pressure, occupancy, and FLOP reuse against the simpler tiled kernel.
// Relation to SpinalGPU PTX corpus:
// - This is the CUDA-side extension of the repo's scalar matrix multiply toward more realistic performance structure.

namespace {
constexpr int BLOCK_M = 32;
constexpr int BLOCK_N = 32;
constexpr int BLOCK_K = 16;
}

extern "C" __global__ void gemm_register_tiled(
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
  __shared__ float a_tile[BLOCK_M][BLOCK_K];
  __shared__ float b_tile[BLOCK_K][BLOCK_N];

  const int tx = threadIdx.x;
  const int ty = threadIdx.y;
  const int row0 = blockIdx.y * BLOCK_M + ty;
  const int row1 = row0 + 16;
  const int col0 = blockIdx.x * BLOCK_N + tx;
  const int col1 = col0 + 16;

  float acc00 = 0.0f;
  float acc01 = 0.0f;
  float acc10 = 0.0f;
  float acc11 = 0.0f;

  for (int k0 = 0; k0 < k; k0 += BLOCK_K) {
    const int a_col = k0 + tx;
    const int b_row = k0 + ty;

    a_tile[ty][tx] = (row0 < m && a_col < k) ? a[row0 * lda + a_col] : 0.0f;
    a_tile[ty + 16][tx] = (row1 < m && a_col < k) ? a[row1 * lda + a_col] : 0.0f;
    b_tile[ty][tx] = (b_row < k && col0 < n) ? b[b_row * ldb + col0] : 0.0f;
    b_tile[ty][tx + 16] = (b_row < k && col1 < n) ? b[b_row * ldb + col1] : 0.0f;
    __syncthreads();

    for (int kk = 0; kk < BLOCK_K; ++kk) {
      const float a0 = a_tile[ty][kk];
      const float a1 = a_tile[ty + 16][kk];
      const float b0 = b_tile[kk][tx];
      const float b1 = b_tile[kk][tx + 16];
      acc00 += a0 * b0;
      acc01 += a0 * b1;
      acc10 += a1 * b0;
      acc11 += a1 * b1;
    }
    __syncthreads();
  }

  if (row0 < m && col0 < n) {
    c[row0 * ldc + col0] = acc00;
  }
  if (row0 < m && col1 < n) {
    c[row0 * ldc + col1] = acc01;
  }
  if (row1 < m && col0 < n) {
    c[row1 * ldc + col0] = acc10;
  }
  if (row1 < m && col1 < n) {
    c[row1 * ldc + col1] = acc11;
  }
}
