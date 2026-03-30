// Purpose: Pack token rows into a peer-major send buffer with a scalar row copy.
// Primary topic: token_pack
// Optimization stage: 00_reference
// Expected learning outcome: Understand the basic EP send-buffer address mapping before adding vectorized copies.
// High-level execution flow:
// - One thread owns one routed token row.
// - The thread reads the destination peer and slot within that peer's send segment.
// - It copies the token row into the packed send buffer.
// Performance idea:
// - Keep the send-buffer mapping easy to inspect before trying to widen the memory traffic.
// Key CUDA features:
// - peer-major packed layout
// - indirect destination addressing
// - scalar row copy
// Correctness constraints:
// - `peer_offsets[peer] + slot_in_peer[token]` must produce a valid packed row index.
// - The packed layout is `[total_send_rows, hidden]`.
// Build example:
// - `nvcc -O2 -arch=sm80 -c kernels/cuda/04_ep_data_movement/token_pack/00_reference.cu`
// Profiling focus:
// - Inspect copy throughput and write locality into the send buffer.
// Relation to SpinalGPU PTX corpus:
// - This is a device-side communication-preparation kernel, outside the current PTX execution scope.

extern "C" __global__ void ep_token_pack_reference(
    const float* __restrict__ tokens,
    const int* __restrict__ peer_ids,
    const int* __restrict__ slot_in_peer,
    const int* __restrict__ peer_offsets,
    float* __restrict__ send_buffer,
    int token_count,
    int hidden
) {
  const int token = blockIdx.x * blockDim.x + threadIdx.x;
  if (token >= token_count) {
    return;
  }

  const int peer = peer_ids[token];
  const int dst_row = peer_offsets[peer] + slot_in_peer[token];
  const int src_base = token * hidden;
  const int dst_base = dst_row * hidden;

  for (int h = 0; h < hidden; ++h) {
    send_buffer[dst_base + h] = tokens[src_base + h];
  }
}
