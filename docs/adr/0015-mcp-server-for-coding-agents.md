# 0015 — A local MCP server for coding agents

## Status

Accepted (2026-10-07). Builds on ADR 0004 (presentation/lifecycle), 0005 (transport) and 0014.

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
- **Security:** bound to the loopback address only; requests whose `Origin` header is present and
  not `http://localhost`/`http://127.0.0.1` are refused (DNS rebinding); the server runs only while
  the access level is not Off.
- **Access level** (per application, persisted): **Off** (default; server stopped), **Read only**
  (tools annotated read-only), **Full control**. Checked on every call, so a change applies at
  once. Tools carry MCP annotations (`readOnlyHint`, `destructiveHint`) so agents ask before
  destructive calls.
- **Port:** a fixed default (`47821`) so setup snippets are stable, configurable in Settings; if it is
  busy the server reports it instead of picking a random port.
- **Device scope:** tools act on the device selected in the tool window unless the call passes a
  `serial`; with none selected/online they return a tool error, not a protocol error.
- **Tools reuse the use cases** the UI uses (capture, apps, display toggles, logcat, app data) —
  no second ADB path. Output is compact text designed for models (UI tree with refs and dp,
  screenshots scaled to 1 px = 1 dp), following Oh My Android's tool set.

## Consequences

- Any local process can reach the port while the level is not Off — the same trust boundary as the
  adb server itself (port 5037), hence Off by default and the explicit level.
- One IDE instance owns the port; a second instance reports it busy.
- The UI for the level, setup snippets and the live indicator follows the design (pending).
