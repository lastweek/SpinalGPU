// Purpose: Introduce blocked online causal attention with shared-memory staging of K and V.
// Primary topic: causal_self_attention
// Optimization stage: 01_flash_style_blocked
// Expected learning outcome: See the core flash-attention idea in a compact single-head teaching kernel.
// High-level execution flow:
// - One block owns one query row and stages the query vector once.
// - The block walks over causal key/value tiles.
// - Threads keep online `(max, sum)` statistics and rescale their output accumulators as each tile arrives.
// Performance idea:
// - Reuse staged `K` and `V` data, avoid materializing probabilities, and keep the accumulation causal.
// Key CUDA features:
// - online softmax accumulation
// - shared-memory staging of `Q`, `K`, and `V`
// - tiled causal key/value loop
// Correctness constraints:
// - This teaching kernel assumes `head_dim <= 128`.
// - Shared-memory footprint grows with `TILE_KEYS * head_dim`.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/02_attention/causal_self_attention/01_flash_style_blocked.cu`
// Profiling focus:
// - Compare memory traffic and row latency against the reference kernel.
// Relation to SpinalGPU PTX corpus:
// - This is the blocked attention analogue of the repo's move from untiled to tiled matrix kernels.

#include <float.h>
#include <math.h>

namespace {
constexpr int MAX_HEAD_DIM = 128;
constexpr int TILE_KEYS = 16;
}

extern "C" __global__ void causal_self_attention_flash_style(
    const float* __restrict__ q,
    const float* __restrict__ k,
    const float* __restrict__ v,
    float* __restrict__ output,
    int seq_len,
    int head_dim,
    float scale
) {
  __shared__ float q_tile[MAX_HEAD_DIM];
  __shared__ float k_tile[TILE_KEYS][MAX_HEAD_DIM];
  __shared__ float v_tile[TILE_KEYS][MAX_HEAD_DIM];

  const int query = blockIdx.x;
  if (query >= seq_len || head_dim > MAX_HEAD_DIM) {
    return;
  }

  for (int d = threadIdx.x; d < head_dim; d += blockDim.x) {
    q_tile[d] = q[query * head_dim + d];
  }
  __syncthreads();

  for (int out_dim = threadIdx.x; out_dim < head_dim; out_dim += blockDim.x) {
    float running_max = -FLT_MAX;
    float running_sum = 0.0f;
    float acc = 0.0f;

    for (int key_base = 0; key_base <= query; key_base += TILE_KEYS) {
      const int valid_keys = ((query - key_base + 1) < TILE_KEYS) ? (query - key_base + 1) : TILE_KEYS;

      for (int index = threadIdx.x; index < valid_keys * head_dim; index += blockDim.x) {
        const int key_local = index / head_dim;
        const int d = index % head_dim;
        const int key = key_base + key_local;
        k_tile[key_local][d] = k[key * head_dim + d];
        v_tile[key_local][d] = v[key * head_dim + d];
      }
      __syncthreads();

      for (int key_local = 0; key_local < valid_keys; ++key_local) {
        float score = 0.0f;
        for (int d = 0; d < head_dim; ++d) {
          score += q_tile[d] * k_tile[key_local][d];
        }
        score *= scale;

        const float new_max = fmaxf(running_max, score);
        const float alpha = expf(running_max - new_max);
        const float beta = expf(score - new_max);
        acc = acc * alpha + beta * v_tile[key_local][out_dim];
        running_sum = running_sum * alpha + beta;
        running_max = new_max;
      }
      __syncthreads();
    }

    output[query * head_dim + out_dim] = acc / running_sum;
  }
}
