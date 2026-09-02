# 031 — Host LAN IPv4 discovery

## Goal

Resolve a usable development-machine LAN IPv4 through an isolated JVM adapter and deterministic policy.

## Dependencies

- [001 — Project bootstrap](001-project-bootstrap-quality.md)
- “Use my computer IP” behavior in `design/README.md` §6 and JVM-not-adb requirement in
  `design/IMPLEMENTATION.md` §4.

## Scope

- Define platform-neutral interface/address candidates, selection policy, result, and actionable ambiguity/error.
- Implement a JVM network-interface adapter for macOS, Linux, and Windows without shell commands.
- Exclude loopback, link-local, down, and unusable interfaces; make ordering deterministic.

## Out of scope

- Device proxy commands, UI, IPv6/PAC/VPN behavior, and host network mutation.

## TDD plan

1. Write failing pure-policy tests for valid, loopback, link-local, down, virtual, multiple, and none.
2. Write failing adapter mapping/cancellation/error tests with synthetic interfaces.
3. Implement after failures; normal tests never inspect the real machine network.

## Acceptance criteria

- JVM networking APIs remain behind a port and no external process is invoked.
- Selection is deterministic/actionable and changed logic reaches 80% coverage.

## Validation

- Run policy/adapter tests, architecture checks, and an optional local smoke test.
