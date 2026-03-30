// Purpose: Provide the simplest receive-buffer unpack kernel for expert-parallel data movement.
// Primary topic: token_unpack
// Optimization stage: 00_reference
// Expected learning outcome: Understand the inverse mapping from packed communication rows back to token-major layout.
// High-level execution flow:
// - One thread owns one packed receive row.
// - The thread reads the destination token id.
// - It copies the row into the token-major output buffer.
// Performance idea:
// - Keep the inverse mapping obvious before attempting fusion with later MoE stages.
// Key CUDA features:
// - indirect destination addressing
// - scalar row copy
// - inverse of peer-major token pack
// Correctness constraints:
// - `dest_token_ids[packed_row]` must reference a valid output token row.
// - The output layout is `[token_count, hidden]`.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/04_ep_data_movement/token_unpack/00_reference.cu`
// Profiling focus:
// - Inspect the cost of indirect writes during unpack.
// Relation to SpinalGPU PTX corpus:
// - This is another device-side communication-preparation kernel outside the current PTX execution scope.

extern "C" __global__ void ep_token_unpack_reference(
    const float* __restrict__ recv_buffer,
    const int* __restrict__ dest_token_ids,
    float* __restrict__ output,
    int packed_rows,
    int hidden
) {
  const int packed_row = blockIdx.x * blockDim.x + threadIdx.x;
  if (packed_row >= packed_rows) {
    return;
  }

  const int token = dest_token_ids[packed_row];
  const int src_base = packed_row * hidden;
  const int dst_base = token * hidden;

  for (int h = 0; h < hidden; ++h) {
    output[dst_base + h] = recv_buffer[src_base + h];
  }
}
