# 053 — Compatibility, packaging, and release verification

## Goal

Produce and verify a reproducible release-candidate artifact without feature or corrective production code in this commit.

## Dependencies

- [052 — Cross-feature integration](052-cross-feature-integration.md)
- `design/IMPLEMENTATION.md` §5 Definition of Done and the complete source routing in `tasks/README.md`.

## Scope

- Run clean build, full tests, coverage, static/architecture checks, plugin verifier, and packaging.
- Inspect the ZIP for runtime modules, metadata, final assets, and version compatibility.
- Smoke-test the oldest supported IDE and one current Android Studio target selected by ADR.
- Produce a requirements/Definition-of-Done evidence checklist and document unavoidable verifier warnings.
- If a product defect appears, stop this task, create a separate regression-fix task/commit, complete it,
  and restart release verification; do not hide fixes in the release commit.

## Out of scope

- Production behavior changes, Marketplace publishing, signing, telemetry, analytics, new features,
  and waived quality failures.

## TDD plan

This task introduces no product behavior. Verification/pipeline changes require focused failing checks
before configuration updates. Product defects are handled in separate TDD tasks and commits.

## Acceptance criteria

- All product requirements have evidence; coverage is at least 80%; architecture/lifecycle gates pass.
- The ZIP installs, opens, contains all resources/modules, and passes the declared verifier range or has
  explicitly owned/rationalized unavoidable warnings.

## Validation

- Run the complete clean CI-equivalent pipeline, inspect the ZIP, and execute the compatibility smoke matrix.
