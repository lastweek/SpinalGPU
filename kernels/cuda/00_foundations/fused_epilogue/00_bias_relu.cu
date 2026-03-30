// Purpose: Show the basic fused bias-plus-ReLU epilogue on precomputed FP32 outputs.
// Primary topic: fused_epilogue
// Optimization stage: 00_bias_relu
// Expected learning outcome: See the simplest useful fusion step that often follows GEMM in neural-network kernels.
// High-level execution flow:
// - Compute one flattened output index.
// - Read the pre-epilogue value and the matching bias term.
// - Add bias, clamp with ReLU, and store the final output.
// Performance idea:
// - Fuse the epilogue into one pass so the output does not make multiple memory round trips.
// Key CUDA features:
// - flattened indexing
// - row/column decomposition for bias lookup
// - branchless ReLU with `fmaxf`
// Correctness constraints:
// - `cols` is required to recover the column index for the bias vector.
// - `input`, `bias`, and `output` must cover the full logical extent.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/00_foundations/fused_epilogue/00_bias_relu.cu`
// Profiling focus:
// - Measure whether the fused pass stays bandwidth-bound and compare it with a hypothetical unfused epilogue.
// Relation to SpinalGPU PTX corpus:
// - This is the CUDA-side companion to `kernels/arithmetic/linear_bias_relu_f32.ptx`.

#include <math.h>

extern "C" __global__ void bias_relu_reference(
    const float* __restrict__ input,
    const float* __restrict__ bias,
    float* __restrict__ output,
    int rows,
    int cols
) {
  const int idx = blockIdx.x * blockDim.x + threadIdx.x;
  const int total = rows * cols;

  if (idx < total) {
    const int col = idx % cols;
    const float value = input[idx] + bias[col];
    output[idx] = fmaxf(value, 0.0f);
  }
}
