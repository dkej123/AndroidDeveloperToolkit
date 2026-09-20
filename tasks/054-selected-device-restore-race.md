# 054 — SelectedDeviceViewModel/SelectedPackageViewModel restore-vs-select race

## Goal

Stop persistence restore from silently overwriting an explicit, already-handled device selection.

## Dependencies

- [009 — Selected device](009-selected-device.md)
- Discovered while building task 052's cross-feature integration harness.

## Background

`SelectedDeviceViewModel.restore()` (`application/src/main/kotlin/dev/acme/adbtoolbox/application/device/SelectedDeviceViewModel.kt`)
launches an async read of the persisted serial in `init`, and unconditionally does
`_selectedSerial.value = persisted` when that read resolves — with no check for whether a
`SelectedDeviceIntent.Select`/`ClearSelection` was already handled in the meantime. If `restore()`'s
persistence read is slow (or simply loses the race to a `Select` intent handled before the
dispatcher gets a chance to run the already-scheduled restore coroutine), the restore completion
clobbers the user's/caller's explicit selection back to whatever was last persisted (or `null`),
without any error, warning, or user-visible signal.

This was surfaced by a real ordering dependency in task 052's integration harness: constructing
`SelectedDeviceViewModel` and then immediately calling `handle(Select(serial))` before the
scheduler had ever run left `restore()`'s coroutine still pending; letting it execute afterward
silently reset the selection to `None`. Production risk is the same shape — any caller that selects
a device before restore has resolved (e.g. a fast-clicking user, or another coordinator selecting a
device programmatically at startup) can have that selection silently discarded.

## Scope

- Make `restore()` a no-op (or otherwise safe) if an explicit `Select`/`ClearSelection` intent has
  already been handled before the persisted-serial read resolves.
- Add a regression test that: constructs the view model, calls `handle(Select(serial))` before the
  scheduler runs at all, then lets everything settle — state must reflect the explicit selection,
  not the persisted value.
- Keep the existing "restore resolves before any explicit intent" behavior unchanged (persisted
  serial still wins when nothing else has happened yet).
- `application/src/main/kotlin/dev/acme/adbtoolbox/application/apps/SelectedPackageViewModel.kt`
  has the identical shape (`restore()` unconditionally sets `_selection.value = persisted`) — apply
  the same fix and an equivalent regression test there too.

## Out of scope

- Broader `SelectedDeviceViewModel` refactors, persistence format changes, or UI-visible restore
  affordances (e.g. a loading spinner) — this is a correctness fix for the specific race, not a
  redesign.

## TDD plan

1. Write a failing test reproducing the race (select before restore resolves; assert final state
   keeps the explicit selection).
2. Implement the minimal fix (e.g. track whether an explicit intent has been handled and have
   `restore()` skip applying `persisted` if so) to make it pass.

## Acceptance criteria

- An explicit `Select`/`ClearSelection` handled before restore resolves is never silently
  overwritten by the restore completing afterward.
- Restore-before-any-explicit-intent behavior is unchanged; existing `SelectedDeviceViewModelTest`
  cases still pass.

## Validation

- Run `:application:test` for `SelectedDeviceViewModelTest` plus the new regression test; confirm no
  regression in task 052's integration suite once wired against the fixed view model.
