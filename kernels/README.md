# Kernel Corpus Guide

This directory contains the checked-in PTX teaching corpus for SpinalGPU.

Each `.ptx` file is a small, intentionally scoped kernel used to prove one part of the PTX frontend, machine lowering, or SM runtime. The categories are teaching-oriented rather than compiler-oriented:

- `arithmetic/`
  scalar, vector, matrix, and ML-shaped numeric kernels
- `control/`
  loop, branch, and trap behavior
- `global_memory/`
  address formation and global-memory fault behavior
- `shared_memory/`
  shared-memory round-trip behavior
- `special_registers/`
  PTX builtin register coverage

All kernels in this corpus follow the same source template:

- the first three lines declare `Purpose`, `Primary feature`, and `Expected outcome`
- `// Setup`, `// Core`, and `// Exit` or `// Fault trigger` divide the body
- the top teaching block explains the kernel at a CUDA-like level before the PTX

## Scalar vs Vector vs Matrix PTX

These three labels describe the *kernel pattern*, not three separate hardware backends.

### Scalar

Scalar kernels assign one thread to one scalar value.

Typical PTX surface:

```ptx
mov.u32 %r0, %tid.x;
ld.global.f32 %f0, [%r1];
abs.f32 %f1, %f0;
st.global.f32 [%r2], %f1;
```

Typical examples:

- `arithmetic/scalar_unary_f32.ptx`
- `arithmetic/scalar_mad_u32.ptx`

Practical meaning:

- one thread owns one element
- loads and stores are scalar
- arithmetic is scalar

### Vector

Vector kernels assign one thread to a small tuple such as `float2` or `float4`.

Typical PTX surface:

```ptx
ld.global.v4.f32 {%f0, %f1, %f2, %f3}, [%r0];
add.f32 %f0, %f0, %f4;
add.f32 %f1, %f1, %f5;
add.f32 %f2, %f2, %f6;
add.f32 %f3, %f3, %f7;
st.global.v4.f32 [%r1], {%f0, %f1, %f2, %f3};
```

Typical examples:

- `arithmetic/vector_load_store_f32x4.ptx`
- `arithmetic/vector_add_f32x4.ptx`

Practical meaning:

- one thread owns a tuple, not just one scalar
- PTX uses brace-tuple syntax such as `{%f0, %f1, %f2, %f3}`
- in this repo, vector PTX is still scalar hardware underneath
- the frontend lowers `mov/ld/st .v2/.v4.f32` into repeated scalar machine instructions

Important distinction:

- `global_memory/vector_add_1warp.ptx` is **not** a PTX vector kernel
- it is a scalar kernel with a vector-shaped workload, because each thread still handles exactly one scalar element

### Matrix

Matrix kernels map many threads onto 2D tensor coordinates such as `(row, col)`.

Typical PTX surface:

```ptx
mov.u32 %r0, %tid.x;
mov.u32 %r1, %tid.y;
mul.lo.u32 %r2, %r1, %rStride;
add.u32 %r2, %r2, %r0;
ld.global.f32 %f0, [%rA];
ld.global.f32 %f1, [%rB];
fma.rn.f32 %f2, %f0, %f1, %f2;
st.global.f32 [%rC], %f2;
```

Typical examples:

- `arithmetic/matrix_add_f32.ptx`
- `arithmetic/matrix_mul_f32.ptx`

Practical meaning:

- threads are mapped onto `row/col` positions with `%tid.x` and `%tid.y`
- arithmetic is still mostly scalar PTX
- the “matrix” part comes from the indexing pattern and loop structure, not from a separate PTX instruction family

## Repo-Specific Vector Rule

For the current SpinalGPU PTX subset:

- scalar kernels use scalar registers and scalar memory ops
- vector kernels use PTX tuple syntax over the existing `%f<N>` scalar registers
- matrix kernels are built from scalar PTX ops plus 2D indexing and loops

There is no separate packed vector machine ISA yet. Vector PTX is currently a frontend convenience surface over the scalar backend.
