---
name: intellij-plugin-development
description: Rules for Android Studio / IntelliJ Platform integration work — plugin.xml, ToolWindow, Gradle/runIde setup, EDT and disposal discipline, and using the read-only as_plugin reference repo. Use automatically for any Android Studio / IntelliJ plugin integration task.
---

# IntelliJ Plugin Development

Apply whenever a task touches the IntelliJ Platform integration layer: Gradle/plugin build setup,
`plugin.xml`, ToolWindow, actions, services, or `runIde`.

## Technical reference (read-only)

`/Users/dkwasniak/Workspace/as_plugin` may be inspected for technical patterns only: Gradle
setup, IntelliJ Platform configuration, `plugin.xml` structure, ToolWindow registration, `runIde`,
packaging, and plugin verification. Never modify that repository. Never copy its visual design —
final UI design is supplied separately.

## Rules

- Use current IntelliJ Platform APIs; verify unfamiliar/uncertain APIs against this project's
  actual target platform version rather than assuming from memory or from the reference repo.
- Never block the EDT — offload work to background coroutines/threads and marshal results back.
- Dispose listeners, coroutine scopes, and processes correctly (tie to `Disposable`/project or
  tool window lifecycle as appropriate).
- Keep Swing/IntelliJ-specific code inside Android Studio-specific modules/packages — see the
  architecture-guardrails skill for the boundary this protects.
- Never let IntelliJ or Swing types leak into shared contracts consumed by `core`/`application`/
  `domain`.
- UI layer consumes state and emits actions/intents; it must not contain business logic.
- Respect the project's selected MVVM/MVI architecture for the UI layer.
- Final visual/UI design will be supplied separately — do not invent or copy design now.
