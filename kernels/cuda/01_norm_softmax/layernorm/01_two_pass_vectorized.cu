// Purpose: Add vectorized row traffic to the LayerNorm baseline.
// Primary topic: layernorm
// Optimization stage: 01_two_pass_vectorized
// Expected learning outcome: See how vectorized memory accesses help even when the normalization still needs two row-wide statistics.
// High-level execution flow:
// - One block owns one row.
// - Threads traverse the row with `float4` loads, accumulating sum and sum of squares.
// - The output pass uses vectorized affine normalization where possible.
// Performance idea:
// - Reduce load/store instruction count without changing the overall two-pass normalization structure.
// Key CUDA features:
// - `float4` vectorization
// - two row-wide statistics
// - scalar cleanup tail
// Correctness constraints:
// - Best results assume 16-byte alignment.
// - The scalar tail handles row lengths that are not divisible by four.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/01_norm_softmax/layernorm/01_two_pass_vectorized.cu`
// Profiling focus:
// - Compare memory instruction count and register pressure against the scalar baseline.
// Relation to SpinalGPU PTX corpus:
// - This extends the PTX corpus's vector-memory teaching ideas into a normalization kernel.

#include <cuda_runtime.h>
#include <math.h>

extern "C" __global__ void layernorm_float4(
    const float* __restrict__ x,
    const float* __restrict__ gamma,
    const float* __restrict__ beta,
    float* __restrict__ y,
    int rows,
    int cols,
    float eps
) {
  extern __shared__ float shared[];
  float* sum_shared = shared;
  float* sq_shared = shared + blockDim.x;

  const int row = blockIdx.x;
  const int tid = threadIdx.x;
  const int row_base = row * cols;

  if (row >= rows) {
    return;
  }

  const int vec_cols = cols / 4;
  float local_sum = 0.0f;
  float local_sq = 0.0f;
  for (int vec = tid; vec < vec_cols; vec += blockDim.x) {
    const float4 xv = reinterpret_cast<const float4*>(x + row_base)[vec];
    local_sum += xv.x + xv.y + xv.z + xv.w;
    local_sq += xv.x * xv.x + xv.y * xv.y + xv.z * xv.z + xv.w * xv.w;
  }
  for (int col = vec_cols * 4 + tid; col < cols; col += blockDim.x) {
    const float v = x[row_base + col];
    local_sum += v;
    local_sq += v * v;
  }

  sum_shared[tid] = local_sum;
  sq_shared[tid] = local_sq;
  __syncthreads();

  for (int offset = blockDim.x / 2; offset > 0; offset >>= 1) {
    if (tid < offset) {
      sum_shared[tid] += sum_shared[tid + offset];
      sq_shared[tid] += sq_shared[tid + offset];
    }
    __syncthreads();
  }

  const float mean = sum_shared[0] / static_cast<float>(cols);
  const float variance = sq_shared[0] / static_cast<float>(cols) - mean * mean;
  const float inv_std = rsqrtf(variance + eps);

  for (int vec = tid; vec < vec_cols; vec += blockDim.x) {
    const float4 xv = reinterpret_cast<const float4*>(x + row_base)[vec];
    const float4 gv = reinterpret_cast<const float4*>(gamma)[vec];
    const float4 bv = reinterpret_cast<const float4*>(beta)[vec];
    float4 outv;
    outv.x = ((xv.x - mean) * inv_std) * gv.x + bv.x;
    outv.y = ((xv.y - mean) * inv_std) * gv.y + bv.y;
    outv.z = ((xv.z - mean) * inv_std) * gv.z + bv.z;
    outv.w = ((xv.w - mean) * inv_std) * gv.w + bv.w;
    reinterpret_cast<float4*>(y + row_base)[vec] = outv;
  }
  for (int col = vec_cols * 4 + tid; col < cols; col += blockDim.x) {
    const float normalized = (x[row_base + col] - mean) * inv_std;
    y[row_base + col] = normalized * gamma[col] + beta[col];
  }
}
