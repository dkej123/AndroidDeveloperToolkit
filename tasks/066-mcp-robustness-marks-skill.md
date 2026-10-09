# 066 — MCP: steadier UI capture, marked screenshots, UI after actions, agent skill

Date: 2026-10-09. Source: comparison with Google's Android CLI 1.0 (`android layout`,
`android screen capture --annotate`, `android screen resolve`, `android skills`); ADR 0015.

## Goal

Fewer failed and fewer wasted agent calls, a way to act on screens without a usable UI tree, and a
one-click way to teach agents how to use the server.

## Dependencies

- 063, 065.

## Scope

1. **Steadier capture.** `uiautomator dump` refuses while the screen animates. Retry with backoff
   (3 attempts, 500 ms then 1 s) instead of one retry after 600 ms; the final error tells the agent
   it can turn animations off (`set_device_settings animations=false`).
2. **Marked screenshot.** `screenshot` takes `annotate`: numbered boxes are drawn on the image —
   every interactive element of the UI tree labelled with its get_ui ref, plus `m1`, `m2`… for
   shapes found in the pixels where the tree has no interactive element (games, canvas, Flutter,
   some WebViews). The text part lists the marks with their frames. `tap` and `swipe` take
   `mark` for those boxes. Drawing and detection are an `:adapters-jvm` port (`ScreenMarker`).
3. **UI after actions.** `tap`, `swipe`, `type_text` and `press_key` take `return_ui`: after the
   action they wait for the screen to settle and append the interactive elements, as
   `get_ui interactive_only=true` would; a failed capture does not fail the action.
4. **Agent skill.** A `SKILL.md` (Agent Skills format) generated from the tool catalog: when to
   use the server and how (get_ui before tap, refs, dp, return_ui, annotate, serial). Settings ›
   AI agents installs it for Claude Code (`~/.claude/skills`), Codex CLI (`~/.codex/skills`) and
   Gemini CLI (`~/.gemini/skills`), shows whether each copy is current, and copies it for others.

## Out of scope

- An on-device accessibility helper as a second source of the UI tree.
- Project-level skill installation; Cursor rules.
- Journeys files or a runner.

## TDD plan

- `CaptureLayoutUseCase`: attempts and delays in virtual time; the final message.
- Tools: annotate passes tree refs and only uncovered detections to the marker, lists them;
  `tap mark=` hits the mark centre; unknown mark is an argument error; `return_ui` appends the
  tree after the settle delay; a failed capture after an action still reports the action.
- `AwtScreenMarker`: two drawn shapes are detected as two boxes; drawing keeps the size.
- `agentSkill`: frontmatter name/description within the Agent Skills limits; every tool listed.
- `AgentSkillInstaller`: install writes the file; status not installed / current / outdated.

## Acceptance criteria

- An agent can tap a shape on a screen without tree nodes using `screenshot annotate=true` then
  `tap mark=N`.
- An agent can tap and read the resulting screen in one call.
- Settings installs the skill for the three agents and reports its state.

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :intellij:test architectureCheck`,
then `./gradlew clean build koverVerify` before the commit.
