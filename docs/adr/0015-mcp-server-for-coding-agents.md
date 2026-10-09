# 0015 — A local MCP server for coding agents

## Status

Accepted (2026-10-07; token, port and confirmation policy aligned with the design handoff the same day). Builds on ADR 0004 (presentation/lifecycle), 0005 (transport) and 0014.

## Context

The user wants coding agents (Claude Code, Codex, Cursor, Gemini CLI…) to drive the device the
plugin is connected to: see the screen and UI tree, tap and type, change device settings, manage
apps, read logcat and app data — as Oh My Android's stdio MCP server does. A stdio server is started
by the agent as a child process; our code lives inside the IDE, so the agent must connect to it.

Options: the IDE's built-in web server (port 63342, shared with other plugins, its own origin and
authentication rules, changed between platform versions); JetBrains' MCP server plugin (2025.2+,
above our 2024.2 baseline, ADR 0003); an own loopback HTTP server. MCP's Streamable HTTP transport
is supported by all target agents (`claude mcp add --transport http …`).

## Decision

- **Transport:** MCP Streamable HTTP, one endpoint `POST http://127.0.0.1:<port>/mcp`, JSON
  responses only (no SSE stream; `GET` answers 405), protocol versions 2025-03-26 and later.
  Implemented with the JDK's `com.sun.net.httpserver` (in JBR, no new dependency) in
  `:adapters-jvm`; JSON-RPC handling, tool catalog and access policy are plain Kotlin in
  `:application` on `kotlinx.serialization` (already a dependency).
- **Security:** bound to the loopback address only; every request needs
  `Authorization: Bearer <token>` (a random per-installation token, shown masked in Settings, with
  Copy and Regenerate — regenerating disconnects every agent); requests whose `Origin` header is
  present and not `http://localhost`/`http://127.0.0.1` are refused (DNS rebinding); the server
  runs only while the access level is not Off.
- **Access level** (per application, persisted): **Off** (default; server stopped), **Read only**
  (only read tools are listed to agents at all), **Full control**. Checked on every call, so a
  change applies at once. Tools carry MCP annotations (`readOnlyHint`, `destructiveHint`).
  **Uninstall and Clear data always ask in the IDE**, also in Full control: the tool window's own
  confirmation, naming the agent; Cancel or no answer within 60 s returns "declined by user".
- **Port:** picked from the free ephemeral range on the first start, then persisted and reused so
  setup snippets stay valid; if it is later busy, the server reports it (Settings shows it) rather
  than silently moving.
- **Device scope:** tools act on the device selected in the tool window unless the call passes a
  `serial`; with none selected/online they return a tool error, not a protocol error.
- **Tools reuse the use cases** the UI uses (capture, apps, display toggles, logcat, app data) —
  no second ADB path. Output is compact text designed for models (UI tree with refs and dp,
  screenshots scaled to 1 px = 1 dp), following Oh My Android's tool set.

- **Addendum 2026-10-09 (task 066, after comparing Google's Android CLI 1.0):** the server does not
  call or bundle Android CLI (separate install, telemetry on by default, undocumented output, a second
  ADB path). It takes three of its ideas instead: layout capture retries with backoff while the screen
  animates; `screenshot annotate=true` draws numbered boxes — tree elements by ref, shapes found in the
  pixels (an `:adapters-jvm` `ScreenMarker`) as `mN` for `tap`/`swipe mark=` — like
  `android screen capture --annotate`; actions take `return_ui` to return the next screen in one call.
  A generated Agent Skill (`SKILL.md`) is installed from Settings or the tool window's tip into the
  user-level skill folders of Claude Code, Codex CLI and Gemini CLI.

## Consequences

- The token keeps other local processes and web pages out; it is stored in the IDE's password safe.
- One IDE instance owns the port; a second instance reports it busy.
- UI: design/README.md §8 (status-bar MCP chip) and §11 (Settings › AI agents).
