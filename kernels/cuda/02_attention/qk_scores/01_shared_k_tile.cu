// Purpose: Tile key vectors in shared memory while reusing the query row.
// Primary topic: qk_scores
// Optimization stage: 01_shared_k_tile
// Expected learning outcome: See how one block can compute a tile of scores for one query while reusing staged query and key data.
// High-level execution flow:
// - One block owns one query row and a tile of keys.
// - Threads cooperatively stage one chunk of the query row and one key tile into shared memory.
// - The first `BLOCK_KEYS` threads accumulate one score each for the tile's keys.
// Performance idea:
// - Reduce repeated global reads of the same query row and the current key tile.
// Key CUDA features:
// - shared-memory staging of Q and K
// - tile loop over the head dimension
// - one block per query-row/key-tile pair
// Correctness constraints:
// - This teaching variant assumes `head_dim <= 4096` and uses `TILE_D` chunks.
// - Launch with at least `BLOCK_KEYS` useful threads; extra threads mainly help the cooperative loads.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/02_attention/qk_scores/01_shared_k_tile.cu`
// Profiling focus:
// - Compare shared-memory reuse and global-memory bytes per score with the direct baseline.
// Relation to SpinalGPU PTX corpus:
// - This is the attention analogue of the repo's move from untiled to tiled matrix kernels.

namespace {
constexpr int BLOCK_KEYS = 32;
constexpr int TILE_D = 32;
}

extern "C" __global__ void qk_scores_shared_k_tile(
    const float* __restrict__ q,
    const float* __restrict__ k,
    float* __restrict__ scores,
    int seq_len,
    int head_dim,
    float scale
) {
  __shared__ float q_tile[TILE_D];
  __shared__ float k_tile[BLOCK_KEYS][TILE_D];

  const int query = blockIdx.y;
  const int key_base = blockIdx.x * BLOCK_KEYS;
  const int tid = threadIdx.x;
  const int key_local = tid;
  const int key = key_base + key_local;

  if (query >= seq_len) {
    return;
  }

  float acc = 0.0f;
  for (int d0 = 0; d0 < head_dim; d0 += TILE_D) {
    if (tid < TILE_D) {
      q_tile[tid] = (d0 + tid < head_dim) ? q[query * head_dim + d0 + tid] : 0.0f;
    }
    for (int index = tid; index < BLOCK_KEYS * TILE_D; index += blockDim.x) {
      const int local_key = index / TILE_D;
      const int local_d = index % TILE_D;
      const int global_key = key_base + local_key;
      k_tile[local_key][local_d] =
          (global_key < seq_len && d0 + local_d < head_dim)
              ? k[global_key * head_dim + d0 + local_d]
              : 0.0f;
    }
    __syncthreads();

    if (tid < BLOCK_KEYS && key < seq_len) {
      const int limit = ((head_dim - d0) < TILE_D) ? (head_dim - d0) : TILE_D;
      for (int d = 0; d < limit; ++d) {
        acc += q_tile[d] * k_tile[key_local][d];
      }
    }
    __syncthreads();
  }

  if (tid < BLOCK_KEYS && key < seq_len) {
    scores[query * seq_len + key] = acc * scale;
  }
}
