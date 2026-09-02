# 032 — Network presentation and proxy recents

## Goal

Bind proxy behavior and host-IP fill into a functional Network view with per-project recent endpoints.

## Dependencies

- [010 — UI host](010-ui-host-infrastructure.md)
- [012 — Navigation](012-navigation-routing.md)
- [013 — Feedback/status](013-feedback-status.md)
- [014 — Device-context policies](014-device-context-policies.md)
- [030 — Proxy core](030-proxy-core.md)
- [031 — Host IP](031-host-ip-discovery.md)

## Scope

- Add reducer state for editing, validation, resolving IP, enabling/disabling, active, error, and device changes.
- Persist bounded deduplicated MRU endpoints in feature-local project state; selecting one only fills fields.
- Bind minimal controls, feedback, Network badge, and override contribution.
- Cancel stale host/device work and dispose subscriptions.

## Out of scope

- Proxy command/policy changes, reset-all/reapply, and final layout/colors/icons/typography.

## TDD plan

1. Write failing reducer/store tests for validation, MRU ordering/dedup/bound, restore/corrupt state,
   fill-without-apply, IP outcomes, readback, device switch, and aggregate contribution.
2. Write failing component/EDT/disposal tests, then implement minimally with fakes.

## Acceptance criteria

- Invalid input runs no ADB; recents never auto-enable; state is exact-serial truthful.
- UI contains no ADB/network enumeration and changed logic reaches 80% coverage.

## Validation

- Run Network reducer/store/component tests and architecture checks.
