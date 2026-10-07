# 062 — Foreground app and Current app actions (domain/application)

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

Read the package/activity in the foreground on demand, for Current app, the MCP tools and the audit header.

## Dependencies

- None beyond the transport.

## Scope

- `ForegroundAppCommand`: `dumpsys activity activities` (`topResumedActivity`, `ResumedActivity`,
  `mResumedActivity`), fallback `dumpsys window` (`mCurrentFocus` / `mFocusedApp`) → package, activity, user;
  home screen (the resolved HOME activity), lock screen (`isKeyguardShowing`), System UI shade; nothing → typed none.
- Current app facts (design §3a): label/icon via the existing app-info helper, version name/code, debuggable,
  system, target/min SDK (`dumpsys package`), PID (`pidof`) and running-for (`ps -o etime=`), cheap path
  comparing package + PID only.
- **Reset permissions**: for each granted runtime permission of `dumpsys package <pkg>` run `pm revoke` and
  `pm clear-permission-flags <pkg> <perm> user-set user-fixed`, skipping system-fixed/policy-fixed; result counts.
  Never `pm reset-permissions`.
- (The freshness policy — triggers, 3 s poll, held changes — depends on UI events and lives in task 064.)

## Out of scope

- Swing UI (task 064).

## TDD plan

- Parser on dumps from API 29–36 forms (`mResumedActivity`, `topResumedActivity`, `ResumedActivity`), user ids, no match.

## Acceptance criteria

- Package and activity are read on every listed Android version.

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :adapters-adb:test architectureCheck` for the touched
modules, then `./gradlew clean build koverVerify` before the commit.
