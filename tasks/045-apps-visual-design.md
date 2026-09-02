# 045 — Apply final design to Apps

## Goal

Apply the delivered Apps design to discovery, list/search, actions, and destructive confirmations.

## Dependencies

- Tasks 021–025 complete.
- [042 — Final design system/assets](042-final-design-system-assets.md)
- `design/README.md` §4 Apps; Apps states in `design/designs/ADB Toolbox Plugin.dc.html`;
  list/search/button/dialog specimens in the Design System; restart/forceStop/clearData/uninstall SVGs.

## Scope

- Apply supplied toolbar, virtualized rows, metadata/debug states, selection/empty/loading/error states,
  pinned actions, destructive grouping, dialogs, copy, icons, and responsive behavior.
- Preserve Cancel-default/Escape semantics and existing intents.

## Out of scope

- Package/action behavior changes or visuals absent from the final design.

## TDD plan

1. Add failing structural/render tests for every supplied Apps and confirmation state.
2. Apply the design after failures while retaining all destructive-interaction tests.

## Acceptance criteria

- Apps matches delivered references and destructive safety remains unchanged.
- Visual code has no ADB logic; measurable changes retain 80% coverage.

## Validation

- Run Apps component/dialog/render tests and supplied theme/width/scale comparisons.
