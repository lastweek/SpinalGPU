# SpinalGPU Agent Guidelines

This repository is an architecture-first educational GPU project in SpinalHDL. Future Codex work in this repo should follow these defaults unless the user explicitly overrides them.

## Project Defaults

- Design the SM architecture and interfaces before adding deep behavior.
- Keep the project focused on one educational SM before scaling out to multiple SMs or chip-level features.
- Start with classic SIMT semantics: one warp PC plus active mask. More advanced scheduling models come later.
- Placeholders are acceptable only when the module interfaces, wiring, and tests are real.
- Do not model SIMT lanes as an array of scalar CPU cores.

## SpinalHDL Design Rules

- Treat `SmConfig` as the single source of truth for compile-time architectural parameters.
- Define typed protocol bundles and enums before implementing module logic.
- Keep modules small and single-purpose. Split routing, arbitration, and state management into dedicated blocks instead of growing wiring-dense top modules.
- Centralize architectural state such as warp state tables instead of smearing state across execution units.
- Make timing intent explicit with registers, state machines, and named clock domains.
- Introduce new clock domains only when there is a real architectural boundary to justify them.

## Interface Rules

- Use AXI4 only at external memory boundaries.
- Use custom typed request/response bundles for internal SM protocols.
- Use `Stream` for backpressured datapaths.
- Use `Flow` only for fire-and-forget observability or debug-style signals.
- Keep adapter logic localized. Boundary translation belongs in dedicated adapter modules rather than inside execution units.

## Testing Expectations

- Every non-trivial architecture change must keep three layers of coverage:
  - elaboration or config-sweep tests
  - focused module simulation tests
  - top-level integration or smoke simulation tests
- Test protocol behavior, not just payload values: ready/valid, blocking, routing, and idle behavior matter.
- Keep the default repo contract green:
  - `sbt compile`
  - `sbt test`
  - `sbt run`

## Documentation Expectations

- Update architecture docs and diagrams alongside interface or hierarchy changes.
- Keep the docs specific to this repo’s SM design, not generic GPU summaries.
- Prefer concise text-first diagrams that live in the repo and can be reviewed with code changes.
- Future PTX kernels under `kernels/**/*.ptx` must keep the three metadata lines first:
  - `// Purpose:`
  - `// Primary feature:`
  - `// Expected outcome:`
- Future PTX kernels under `kernels/**/*.ptx` must keep the corpus section markers:
  - `// Setup`
  - `// Core`
  - `// Exit` or `// Fault trigger`
- Future PTX kernels under `kernels/**/*.ptx` must include the rich teaching-comment block after the
  metadata lines, using `//` comments only and these exact headings:
  - `// High-level execution flow:`
  - `// Where this fits:`
  - `// Key model details:`
  - `// CUDA-equivalent sketch (.cu):`
  - `// SpinalGPU note:`
- Future PTX kernels must always include a CUDA-equivalent sketch, even when the mapping is only approximate.
- Future PTX kernels must use `// SpinalGPU note:` to explain any mismatch from normal CUDA behavior, especially
  for one-CTA teaching launches, divergence faults, special-register access, alignment faults, or other repo-specific behavior.

## Anti-Patterns

- Do not push AXI4 deep into internal module boundaries.
- Do not let `StreamingMultiprocessor` become a permanent god module.
- Do not add deep behavior to a block before its interface and tests are stable.
- Do not hide architectural decisions in ad hoc constants or anonymous bundles when they belong in `SmConfig` or typed protocol definitions.
