# 055 — UI and visual regression verification

## Goal

Add deterministic, reviewable evidence that the shipped Swing UI still matches the final design
and that its highest-risk user interactions work before a plugin ZIP is installed.

## Dependencies

- [051 — Responsive layout and scaling verification](051-responsive-scaling-verification.md)
- [052 — Cross-feature integration](052-cross-feature-integration.md)
- `design/README.md`, `design/tokens/tokens.json`, and the interactive plugin prototype.

## Scope

- Render representative production Swing components off-screen in the IntelliJ test sandbox.
- Compare rendered PNGs against reviewed golden images with a small anti-aliasing tolerance.
- Cover all five feature views, global device/navigation/status chrome, light/dark themes, and the
  supplied narrow/dock/wide widths without requiring adb, scrcpy, a display server, or a device.
- Save actual and visual-diff artifacts on mismatch and make them available from CI.
- Add UI interaction journeys for navigation, filtering, validation, toggles, destructive-action
  safety, Logcat controls, and no-device disablement.
- Document the visual review matrix and the result of the pre-install verification run.

## Out of scope

- Product behavior changes, invented design rules, a physical-device test, Marketplace publishing,
  signing, and local execution of `verifyPlugin`.

## TDD plan

1. Add the golden comparator test and confirm it fails when a reference is absent or changed.
2. Review freshly rendered candidates against the repository design source of truth and approve
   only matching baselines.
3. Add interaction journeys with assertions on resulting component state and callbacks.
4. Run focused tests, then the complete clean build, coverage, and architecture gates.

## Acceptance criteria

- A visual change fails with expected/actual/diff PNG paths in the error message.
- The checked-in matrix includes every primary view and each supplied responsive width class.
- Both light and dark design palettes are represented.
- UI journeys prove behavior independently from screenshots.
- `clean build`, `koverVerify`, and `architectureCheck` pass; the distributable ZIP is produced and
  inspected without running the locally prohibited plugin-verifier task.

## Validation

- Run `./gradlew :intellij:test --tests '*VisualRegressionTest'`.
- Run the focused UI journey suite.
- Run `./gradlew clean build koverVerify architectureCheck` and inspect the distribution ZIP.
