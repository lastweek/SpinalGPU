// Purpose: Add vectorized row loads and stores to the RMSNorm baseline.
// Primary topic: rmsnorm
// Optimization stage: 01_vectorized
// Expected learning outcome: See how `float4` traffic changes a row-wise norm kernel once the scalar structure is clear.
// High-level execution flow:
// - One block still owns one row.
// - Threads accumulate sum-of-squares with `float4` chunks and a scalar tail.
// - The output pass also uses `float4` math when possible.
// Performance idea:
// - Increase bytes processed per instruction while keeping the same reduction pattern.
// Key CUDA features:
// - `float4` vectorization
// - scalar cleanup for row tails
// - row-wise reduction plus elementwise scale
// Correctness constraints:
// - Best results assume row pointers are 16-byte aligned.
// - The scalar tail covers rows where `cols` is not divisible by four.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/01_norm_softmax/rmsnorm/01_vectorized.cu`
// Profiling focus:
// - Compare instruction count, bandwidth, and register pressure against the scalar reference.
// Relation to SpinalGPU PTX corpus:
// - This is the CUDA-side next step after the repo's PTX vector-add and vector-load/store examples.

#include <cuda_runtime.h>
#include <math.h>

extern "C" __global__ void rmsnorm_float4(
    const float* __restrict__ x,
    const float* __restrict__ gamma,
    float* __restrict__ y,
    int rows,
    int cols,
    float eps
) {
  extern __shared__ float shared[];

  const int row = blockIdx.x;
  const int tid = threadIdx.x;
  const int row_base = row * cols;

  if (row >= rows) {
    return;
  }

  const int vec_cols = cols / 4;
  float local_sq = 0.0f;
  for (int vec = tid; vec < vec_cols; vec += blockDim.x) {
    const float4 xv = reinterpret_cast<const float4*>(x + row_base)[vec];
    local_sq += xv.x * xv.x + xv.y * xv.y + xv.z * xv.z + xv.w * xv.w;
  }
  for (int col = vec_cols * 4 + tid; col < cols; col += blockDim.x) {
    const float v = x[row_base + col];
    local_sq += v * v;
  }

  shared[tid] = local_sq;
  __syncthreads();

  for (int offset = blockDim.x / 2; offset > 0; offset >>= 1) {
    if (tid < offset) {
      shared[tid] += shared[tid + offset];
    }
    __syncthreads();
  }

  const float inv_rms = rsqrtf(shared[0] / static_cast<float>(cols) + eps);

  for (int vec = tid; vec < vec_cols; vec += blockDim.x) {
    const float4 xv = reinterpret_cast<const float4*>(x + row_base)[vec];
    const float4 gv = reinterpret_cast<const float4*>(gamma)[vec];
    float4 outv;
    outv.x = xv.x * inv_rms * gv.x;
    outv.y = xv.y * inv_rms * gv.y;
    outv.z = xv.z * inv_rms * gv.z;
    outv.w = xv.w * inv_rms * gv.w;
    reinterpret_cast<float4*>(y + row_base)[vec] = outv;
  }
  for (int col = vec_cols * 4 + tid; col < cols; col += blockDim.x) {
    y[row_base + col] = x[row_base + col] * inv_rms * gamma[col];
  }
}
