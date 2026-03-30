// Purpose: Add vectorized `float4` copies to the expert-buffer transpose.
// Primary topic: expert_buffer_transpose
// Optimization stage: 01_vectorized
// Expected learning outcome: See how layout transforms can still use vectorized row traffic when the hidden dimension is contiguous.
// High-level execution flow:
// - Each thread maps to one `(expert, slot)` pair.
// - The row copy uses `float4` chunks across the hidden dimension.
// - A scalar tail completes any leftover hidden elements.
// Performance idea:
// - Increase bytes moved per instruction while keeping the same layout transform.
// Key CUDA features:
// - `float4` row copies
// - layout transform via index remapping
// - scalar cleanup tail
// Correctness constraints:
// - Best results assume hidden rows are 16-byte aligned.
// - The tail path handles hidden sizes that are not divisible by four.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/04_ep_data_movement/expert_buffer_transpose/01_vectorized.cu`
// Profiling focus:
// - Compare copy throughput and instruction count with the scalar transpose.
// Relation to SpinalGPU PTX corpus:
// - This is the data-layout analogue of the repo's vectorized PTX memory examples.

#include <cuda_runtime.h>

extern "C" __global__ void expert_buffer_transpose_float4(
    const float* __restrict__ source,
    float* __restrict__ transposed,
    int expert_count,
    int capacity,
    int hidden
) {
  const int pair = blockIdx.x * blockDim.x + threadIdx.x;
  if (pair >= expert_count * capacity) {
    return;
  }

  const int expert = pair / capacity;
  const int slot = pair % capacity;
  const int src_base = (expert * capacity + slot) * hidden;
  const int dst_base = (slot * expert_count + expert) * hidden;
  const int vec_hidden = hidden / 4;

  for (int vec = 0; vec < vec_hidden; ++vec) {
    reinterpret_cast<float4*>(transposed + dst_base)[vec] =
        reinterpret_cast<const float4*>(source + src_base)[vec];
  }
  for (int h = vec_hidden * 4; h < hidden; ++h) {
    transposed[dst_base + h] = source[src_base + h];
  }
}
