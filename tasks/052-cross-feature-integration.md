# 052 — Cross-feature integration tests

## Goal

Prove shared state and lifecycle behavior across otherwise isolated feature packages.

## Dependencies

- [051 — Responsive/scaling](051-responsive-scaling-verification.md)
- Shared-state and interaction contracts in `design/README.md` Interactions/State model and the ten
  Definition-of-Done items in `design/IMPLEMENTATION.md` §5.

## Scope

- Add integration tests for device switch/disconnect/reconnect, Apps selection → Logcat filter,
  override count/reset/reapply, feedback/status, scrcpy/recording coexistence policy, persistence,
  and full project/tool-window disposal.
- Stress Logcat with 10k+ lines and verify bounded memory/EDT batching.
- Keep default scenarios fake/fixture-based; real-device scenarios remain opt-in.
- If a product defect appears, stop, create a separate regression-fix task/commit for that logical
  defect, complete it, and resume integration; do not bundle unrelated fixes here.

## Out of scope

- Product fixes, packaging/verifier work, new features, broad refactors, and visual redesign.

## TDD plan

1. Write each cross-feature integration test first and confirm its expected failure against missing
   integration coverage/harness or a separately tracked defect.
2. Implement only integration fixtures/harness in this task; handle product fixes in separate TDD commits.

## Acceptance criteria

- Shared state never crosses serial/project boundaries and disposal leaves no process/listener/job/timer.
- All integration scenarios pass, default tests need no device, and coverage remains at least 80%.

## Validation

- Run full tests, coverage, architecture checks, race/stress tests, and fake end-to-end scenarios.
