// Purpose: Provide the simplest scalar transpose between expert-major and slot-major expert-buffer views.
// Primary topic: expert_buffer_transpose
// Optimization stage: 00_reference
// Expected learning outcome: Understand the layout mapping between `[expert, slot, hidden]` and `[slot, expert, hidden]`.
// High-level execution flow:
// - Each thread maps to one `(expert, slot)` pair.
// - The thread copies the corresponding hidden-state row from the source layout to the transposed layout.
// - The hidden dimension is copied with a scalar loop.
// Performance idea:
// - Keep the layout transform obvious before widening the memory traffic.
// Key CUDA features:
// - layout transform via index remapping
// - row copy across the hidden dimension
// - scalar memory traffic
// Correctness constraints:
// - Source layout is `[expert_count, capacity, hidden]`.
// - Destination layout is `[capacity, expert_count, hidden]`.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/04_ep_data_movement/expert_buffer_transpose/00_reference.cu`
// Profiling focus:
// - Measure the bandwidth cost of the layout transform and whether either side suffers from poor coalescing.
// Relation to SpinalGPU PTX corpus:
// - This is a data-layout teaching kernel, not part of the repo's executable PTX subset.

extern "C" __global__ void expert_buffer_transpose_reference(
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

  for (int h = 0; h < hidden; ++h) {
    transposed[dst_base + h] = source[src_base + h];
  }
}
