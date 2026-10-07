# 063 — MCP server core

Date: 2026-10-07. Source of logic: Oh My Android (MIT), docs/adr/0014.

## Goal

A local MCP server per ADR 0015: JSON-RPC handling, Streamable HTTP endpoint, access level.

## Dependencies

- ADR 0015.

## Scope

- `:application`: JSON-RPC 2.0 dispatcher (initialize, ping, tools/list, tools/call, notifications), protocol
  version negotiation, tool registry with JSON schemas and annotations, access policy (Off / Read only /
  Full control, checked per call), tool errors vs protocol errors.
- `:adapters-jvm`: `com.sun.net.httpserver` endpoint on 127.0.0.1:<port>/mcp, POST only (GET → 405), Origin check,
  size limits, start/stop with the access level, busy-port error.
- Persisted settings: access level (default Off), port (default 47821).

## Out of scope

- Tools (065), UI (064).

## TDD plan

- Dispatcher: every method, malformed JSON, unknown method/tool, access denial per level, notifications without
  response. HTTP adapter: loopback bind, Origin refusal, 405, busy port, start/stop.

## Acceptance criteria

- `claude mcp add --transport http adb-toolbox http://127.0.0.1:47821/mcp` lists the tools against a running IDE.

## Validation

`./gradlew :domain:test :application:test :adapters-jvm:test :adapters-adb:test architectureCheck` for the touched
modules, then `./gradlew clean build koverVerify` before the commit.
