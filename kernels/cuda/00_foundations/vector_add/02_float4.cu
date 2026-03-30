// Purpose: Show a vectorized memory-traffic version of vector add using `float4`.
// Primary topic: vector_add
// Optimization stage: 02_float4
// Expected learning outcome: See how vectorized loads and stores reduce per-element instruction overhead when alignment is friendly.
// High-level execution flow:
// - Map each thread to a four-element chunk.
// - Use `float4` loads and stores for the full-vector body.
// - Finish any leftover elements with a scalar tail path.
// Performance idea:
// - Increase bytes moved per instruction by batching four FP32 values per thread.
// Key CUDA features:
// - `float4` vector type
// - vectorized global loads and stores
// - scalar cleanup tail
// Correctness constraints:
// - Best results assume 16-byte alignment for `a`, `b`, and `c`.
// - The tail loop covers the case where `n` is not divisible by four.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/00_foundations/vector_add/02_float4.cu`
// Profiling focus:
// - Check load/store instruction count and effective memory throughput versus the scalar variants.
// Relation to SpinalGPU PTX corpus:
// - This is the CUDA C++ analogue of the repo's PTX vector-load/store examples such as `vector_load_store_f32x4.ptx`.

#include <cuda_runtime.h>

extern "C" __global__ void vector_add_float4(
    const float* __restrict__ a,
    const float* __restrict__ b,
    float* __restrict__ c,
    int n
) {
  const int vec_idx = blockIdx.x * blockDim.x + threadIdx.x;
  const int element = vec_idx * 4;

  if (element + 3 < n) {
    const float4 av = reinterpret_cast<const float4*>(a)[vec_idx];
    const float4 bv = reinterpret_cast<const float4*>(b)[vec_idx];
    float4 cv;
    cv.x = av.x + bv.x;
    cv.y = av.y + bv.y;
    cv.z = av.z + bv.z;
    cv.w = av.w + bv.w;
    reinterpret_cast<float4*>(c)[vec_idx] = cv;
    return;
  }

  for (int i = element; i < n && i < element + 4; ++i) {
    c[i] = a[i] + b[i];
  }
}
