# 062 — Foreground app detection

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

Read the package/activity in the foreground on demand, for Current app, the MCP tools and the audit header.

## Dependencies

- None beyond the transport.

## Scope

- `ForegroundAppCommand`: `dumpsys activity activities`, first `Resumed:`/`ResumedActivity:` line →
  package + activity component; launcher/System UI detection; nothing resumed → typed "none".

## Out of scope

- Polling policy and UI (Current app design pending).

## TDD plan

- Parser on dumps from API 29–36 forms (`mResumedActivity`, `topResumedActivity`, `ResumedActivity`), user ids, no match.

## Acceptance criteria

- Package and activity are read on every listed Android version.

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :adapters-adb:test architectureCheck` for the touched
modules, then `./gradlew clean build koverVerify` before the commit.
