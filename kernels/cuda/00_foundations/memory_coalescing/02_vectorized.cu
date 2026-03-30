// Purpose: Add vectorized row-wise traffic on top of the coalesced mapping.
// Primary topic: memory_coalescing
// Optimization stage: 02_vectorized
// Expected learning outcome: See how coalesced access and vectorized loads/stores compound.
// High-level execution flow:
// - Map each thread to a four-column chunk within one row.
// - Use `float4` loads and stores for full-width chunks.
// - Finish the leftover columns with a scalar tail path.
// Performance idea:
// - Keep the warp on contiguous row-major traffic and increase bytes moved per memory instruction.
// Key CUDA features:
// - 2D launch indexing over rows and `float4` chunks
// - vectorized row loads/stores
// - scalar cleanup for tails
// Correctness constraints:
// - Best results assume row pointers are 16-byte aligned.
// - The scalar tail handles `cols % 4 != 0`.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/00_foundations/memory_coalescing/02_vectorized.cu`
// Profiling focus:
// - Compare instruction count, memory throughput, and branch overhead with the scalar coalesced variant.
// Relation to SpinalGPU PTX corpus:
// - This is the row-major CUDA analogue of the PTX vector load/store teaching kernels.

#include <cuda_runtime.h>

extern "C" __global__ void scale_coalesced_float4(
    const float* __restrict__ input,
    float* __restrict__ output,
    int rows,
    int cols,
    float alpha
) {
  const int row = blockIdx.y * blockDim.y + threadIdx.y;
  const int vec_col = blockIdx.x * blockDim.x + threadIdx.x;
  const int col = vec_col * 4;

  if (row >= rows) {
    return;
  }

  const int row_base = row * cols;
  if (col + 3 < cols) {
    const float4 in4 = reinterpret_cast<const float4*>(input + row_base)[vec_col];
    float4 out4;
    out4.x = alpha * in4.x;
    out4.y = alpha * in4.y;
    out4.z = alpha * in4.z;
    out4.w = alpha * in4.w;
    reinterpret_cast<float4*>(output + row_base)[vec_col] = out4;
    return;
  }

  for (int c = col; c < cols && c < col + 4; ++c) {
    output[row_base + c] = alpha * input[row_base + c];
  }
}
