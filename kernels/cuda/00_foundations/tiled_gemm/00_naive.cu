// Purpose: Provide the simplest one-thread-per-output FP32 GEMM baseline.
// Primary topic: tiled_gemm
// Optimization stage: 00_naive
// Expected learning outcome: Understand the untiled GEMM structure before introducing data reuse.
// High-level execution flow:
// - Map each thread to one output element `(row, col)`.
// - Loop over the shared K dimension.
// - Load one `A[row, kk]` and one `B[kk, col]` per iteration and accumulate into one FP32 register.
// Performance idea:
// - Keep everything obvious so shared-memory tiling has a clear baseline.
// Key CUDA features:
// - 2D output indexing
// - row-major address arithmetic
// - scalar FP32 multiply-add loop
// Correctness constraints:
// - `lda`, `ldb`, and `ldc` are element strides, not byte strides.
// - The output guard must respect both `m` and `n`.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/00_foundations/tiled_gemm/00_naive.cu`
// Profiling focus:
// - Observe redundant global loads and low arithmetic intensity.
// Relation to SpinalGPU PTX corpus:
// - This is the CUDA C++ analogue of `kernels/arithmetic/matrix_mul_f32.ptx`.

extern "C" __global__ void gemm_naive(
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
  const int col = blockIdx.x * blockDim.x + threadIdx.x;
  const int row = blockIdx.y * blockDim.y + threadIdx.y;

  if (row >= m || col >= n) {
    return;
  }

  float acc = 0.0f;
  for (int kk = 0; kk < k; ++kk) {
    acc += a[row * lda + kk] * b[kk * ldb + col];
  }

  c[row * ldc + col] = acc;
}
