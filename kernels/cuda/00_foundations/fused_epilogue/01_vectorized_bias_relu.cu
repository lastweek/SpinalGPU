// Purpose: Add a vectorized `float4` path to the fused bias-plus-ReLU epilogue.
// Primary topic: fused_epilogue
// Optimization stage: 01_vectorized_bias_relu
// Expected learning outcome: See how simple epilogues often benefit from vectorized memory traffic once the scalar version is clear.
// High-level execution flow:
// - Map each thread to a four-element output chunk.
// - Load one `float4` from the pre-epilogue buffer and one `float4` from the bias buffer.
// - Apply bias and ReLU lane by lane, then store one `float4`.
// Performance idea:
// - Reduce the number of load/store instructions while preserving the same fused epilogue logic.
// Key CUDA features:
// - `float4` vectorization
// - lane-wise fused epilogue math
// - scalar tail handling
// Correctness constraints:
// - Best results assume 16-byte alignment.
// - This flattened variant expects `bias` to be broadcast-ready across the flattened extent.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/00_foundations/fused_epilogue/01_vectorized_bias_relu.cu`
// Profiling focus:
// - Compare memory instruction count and bandwidth versus the scalar fused epilogue.
// Relation to SpinalGPU PTX corpus:
// - This extends the repo's scalar dense-layer epilogue idea with a more CUDA-specific vectorized memory surface.

#include <cuda_runtime.h>
#include <math.h>

extern "C" __global__ void bias_relu_float4(
    const float* __restrict__ input,
    const float* __restrict__ bias,
    float* __restrict__ output,
    int n
) {
  const int vec_idx = blockIdx.x * blockDim.x + threadIdx.x;
  const int element = vec_idx * 4;

  if (element + 3 < n) {
    const float4 in4 = reinterpret_cast<const float4*>(input)[vec_idx];
    const float4 bias4 = reinterpret_cast<const float4*>(bias)[vec_idx];
    float4 out4;
    out4.x = fmaxf(in4.x + bias4.x, 0.0f);
    out4.y = fmaxf(in4.y + bias4.y, 0.0f);
    out4.z = fmaxf(in4.z + bias4.z, 0.0f);
    out4.w = fmaxf(in4.w + bias4.w, 0.0f);
    reinterpret_cast<float4*>(output)[vec_idx] = out4;
    return;
  }

  for (int i = element; i < n && i < element + 4; ++i) {
    output[i] = fmaxf(input[i] + bias[i], 0.0f);
  }
}
