package dev.acme.adbtoolbox.application.mcp.tools

import dev.acme.adbtoolbox.application.mcp.McpTool

/** Every MCP tool, grouped See / Act / Device / Apps / Data (design §11). */
fun mcpTools(env: McpToolEnvironment): List<McpTool> = screenAndInputTools(env) + deviceAppDataTools(env)
