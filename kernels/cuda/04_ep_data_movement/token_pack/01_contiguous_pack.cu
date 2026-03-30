// Purpose: Vectorize the peer-major token pack with `float4` copies.
// Primary topic: token_pack
// Optimization stage: 01_contiguous_pack
// Expected learning outcome: See how EP packing can use vectorized row copies once the scalar address mapping is clear.
// High-level execution flow:
// - One thread still owns one routed token row.
// - The row copy uses `float4` chunks where possible.
// - A scalar tail finishes any leftover hidden elements.
// Performance idea:
// - Increase bytes moved per instruction while preserving the same peer-major packed layout.
// Key CUDA features:
// - `float4` row copies
// - indirect destination addressing
// - scalar cleanup tail
// Correctness constraints:
// - Best results assume row pointers are 16-byte aligned.
// - The tail path handles hidden sizes that are not divisible by four.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/04_ep_data_movement/token_pack/01_contiguous_pack.cu`
// Profiling focus:
// - Compare memory instruction count and copy throughput against the scalar pack kernel.
// Relation to SpinalGPU PTX corpus:
// - This is the communication-side analogue of the PTX vector load/store ladder.

#include <cuda_runtime.h>

extern "C" __global__ void ep_token_pack_float4(
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
  const int vec_hidden = hidden / 4;

  for (int vec = 0; vec < vec_hidden; ++vec) {
    reinterpret_cast<float4*>(send_buffer + dst_base)[vec] =
        reinterpret_cast<const float4*>(tokens + src_base)[vec];
  }
  for (int h = vec_hidden * 4; h < hidden; ++h) {
    send_buffer[dst_base + h] = tokens[src_base + h];
  }
}
