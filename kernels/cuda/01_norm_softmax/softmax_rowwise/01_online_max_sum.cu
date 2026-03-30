// Purpose: Use online max-and-sum statistics for row-wise softmax.
// Primary topic: softmax_rowwise
// Optimization stage: 01_online_max_sum
// Expected learning outcome: Learn the numerically stable merge rule that lets softmax stats be accumulated online.
// High-level execution flow:
// - Each thread scans a strided slice of the row and keeps a running `(max, sum)` pair.
// - Shared memory merges those per-thread statistics with the online softmax merge rule.
// - The block writes normalized probabilities using the merged row statistics.
// Performance idea:
// - Keep softmax numerically stable while reducing the conceptual need for separate max and sum passes.
// Key CUDA features:
// - online softmax statistics
// - pairwise merge in shared memory
// - row-wise probability writeback
// Correctness constraints:
// - The merge rule must rescale both partial sums when maxima differ.
// - `cols` must be positive.
// Build example:
// - `nvcc -O3 -arch=sm80 -lineinfo -c kernels/cuda/01_norm_softmax/softmax_rowwise/01_online_max_sum.cu`
// Profiling focus:
// - Compare row pass structure and exponent work with the classic safe baseline.
// Relation to SpinalGPU PTX corpus:
// - This is a CUDA-only numerical optimization technique that does not appear in the current PTX corpus.

#include <float.h>
#include <math.h>

extern "C" __global__ void softmax_online_shared(
    const float* __restrict__ scores,
    float* __restrict__ probs,
    int rows,
    int cols
) {
  extern __shared__ float shared[];
  float* shared_max = shared;
  float* shared_sum = shared + blockDim.x;

  const int row = blockIdx.x;
  const int tid = threadIdx.x;
  const int row_base = row * cols;

  if (row >= rows) {
    return;
  }

  float local_max = -FLT_MAX;
  float local_sum = 0.0f;
  for (int col = tid; col < cols; col += blockDim.x) {
    const float score = scores[row_base + col];
    const float new_max = fmaxf(local_max, score);
    local_sum = local_sum * expf(local_max - new_max) + expf(score - new_max);
    local_max = new_max;
  }

  shared_max[tid] = local_max;
  shared_sum[tid] = local_sum;
  __syncthreads();

  for (int offset = blockDim.x / 2; offset > 0; offset >>= 1) {
    if (tid < offset) {
      const float other_max = shared_max[tid + offset];
      const float other_sum = shared_sum[tid + offset];
      const float new_max = fmaxf(shared_max[tid], other_max);
      shared_sum[tid] =
          shared_sum[tid] * expf(shared_max[tid] - new_max) +
          other_sum * expf(other_max - new_max);
      shared_max[tid] = new_max;
    }
    __syncthreads();
  }

  const float row_max = shared_max[0];
  const float inv_sum = 1.0f / shared_sum[0];
  for (int col = tid; col < cols; col += blockDim.x) {
    probs[row_base + col] = expf(scores[row_base + col] - row_max) * inv_sum;
  }
}
