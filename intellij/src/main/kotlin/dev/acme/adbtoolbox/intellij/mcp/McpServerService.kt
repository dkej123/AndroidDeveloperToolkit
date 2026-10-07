package dev.acme.adbtoolbox.intellij.mcp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import dev.acme.adbtoolbox.adapters.jvm.mcp.LoopbackMcpHttpServer
import dev.acme.adbtoolbox.adapters.jvm.mcp.McpHttpHandler
import dev.acme.adbtoolbox.adapters.jvm.mcp.McpHttpReply
import dev.acme.adbtoolbox.adapters.jvm.mcp.McpServerStart
import dev.acme.adbtoolbox.application.mcp.McpAccess
import dev.acme.adbtoolbox.application.mcp.McpCallContext
import dev.acme.adbtoolbox.application.mcp.McpServerCore
import dev.acme.adbtoolbox.application.mcp.McpSession
import dev.acme.adbtoolbox.application.mcp.McpTool
import dev.acme.adbtoolbox.application.mcp.McpToolResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/** What the status-bar chip and the Settings status box show (design §8, §11). */
data class McpStatus(
    val access: McpAccess = McpAccess.Off,
    val port: Int? = null,
    val error: String? = null,
    /** Agents that called within the last few minutes. */
    val sessions: List<McpSession> = emptyList(),
    val callInFlight: Boolean = false,
) {
    val running: Boolean get() = port != null
}

/**
 * The one MCP server of the IDE (ADR 0015): runs while the access level is not Off, on the
 * persisted port, and routes every tool call to the most recently active project's tools (each
 * project registers its own, bound to its device selection).
 */
@Service(Service.Level.APP)
class McpServerService : Disposable {
    private val settings get() = AdbToolboxAppSettings.getInstance()
    private val projects = LinkedHashMap<Project, List<McpTool>>()
    private var activeProject: Project? = null
    private var core: McpServerCore? = null
    private val inFlight = AtomicInteger()

    private val _status = MutableStateFlow(McpStatus())
    val status: StateFlow<McpStatus> = _status.asStateFlow()

    private val http = LoopbackMcpHttpServer(
        handler = object : McpHttpHandler {
            override suspend fun handle(body: String, sessionId: String?): McpHttpReply {
                val server = core ?: return McpHttpReply("""{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"No project is open"}}""")
                val reply = server.handle(body, sessionId)
                publish()
                return McpHttpReply(reply.body, reply.sessionId)
            }

            override fun endSession(sessionId: String) {
                core?.endSession(sessionId)
                publish()
            }
        },
        token = { settings.mcpToken() },
    )

    init {
        ApplicationManager.getApplication()?.messageBus?.connect(this)?.subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) {
                    ideFrame.project?.takeIf { it in projects }?.let { activeProject = it }
                }
            },
        )
    }

    /** A project's tools; the server starts with the first registration when access is not Off. */
    @Synchronized
    fun register(project: Project, tools: List<McpTool>) {
        projects[project] = tools
        activeProject = project
        if (core == null) core = McpServerCore(tools.map(::Delegating), { settings.mcpAccess }, pluginVersion())
        applyAccess()
    }

    @Synchronized
    fun unregister(project: Project) {
        projects.remove(project)
        if (activeProject == project) activeProject = projects.keys.lastOrNull()
        if (projects.isEmpty()) http.stop().also { publish() }
    }

    /** Starts, keeps or stops the server for the current access level (called after Settings change it). */
    @Synchronized
    fun applyAccess() {
        val access = settings.mcpAccess
        if (access == McpAccess.Off || projects.isEmpty()) {
            http.stop()
            core?.endAllSessions()
            _status.value = McpStatus(access = access)
            return
        }
        if (http.port == null) {
            when (val started = http.start(settings.mcpPort)) {
                is McpServerStart.Listening -> {
                    settings.mcpPort = started.port
                    _status.value = McpStatus(access = access, port = started.port)
                }
                is McpServerStart.Failed -> _status.value = McpStatus(access = access, error = started.reason)
            }
        } else {
            publish()
        }
    }

    /** New token: every agent must re-authenticate. */
    fun regenerateToken(): String {
        core?.endAllSessions()
        return settings.regenerateMcpToken().also { publish() }
    }

    fun toolCatalog(): List<McpTool> = projects.values.firstOrNull() ?: emptyList()

    private fun publish() {
        val cutoff = System.currentTimeMillis() - ACTIVE_WINDOW_MS
        _status.value = _status.value.copy(
            access = settings.mcpAccess,
            port = http.port,
            sessions = core?.sessions()?.filter { (it.lastCallAtMillis ?: System.currentTimeMillis()) >= cutoff }.orEmpty(),
            callInFlight = inFlight.get() > 0,
        )
    }

    private fun pluginVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId("com.github.dkwasniak.adbtoolbox"))?.version ?: "dev"

    /** A catalog entry whose calls go to the active project's tool of the same name. */
    private inner class Delegating(template: McpTool) : McpTool {
        override val name = template.name
        override val description = template.description
        override val inputSchema = template.inputSchema
        override val readOnly = template.readOnly
        override val destructive = template.destructive

        override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
            val project = activeProject ?: projects.keys.lastOrNull() ?: return McpToolResult.error("No project is open in the IDE")
            val tool = projects[project]?.firstOrNull { it.name == name } ?: return McpToolResult.error("$name is not available")
            inFlight.incrementAndGet()
            publish()
            return try {
                tool.call(arguments, context)
            } finally {
                inFlight.decrementAndGet()
                publish()
            }
        }
    }

    override fun dispose() {
        http.stop()
    }

    companion object {
        private const val ACTIVE_WINDOW_MS = 5 * 60 * 1000L

        fun getInstance(): McpServerService = service()
    }
}

/** Registers the project's MCP tools at startup when agents may connect (access not Off). */
class McpStartupActivity : com.intellij.openapi.startup.ProjectActivity {
    override suspend fun execute(project: Project) {
        if (AdbToolboxAppSettings.getInstance().mcpAccess != McpAccess.Off) {
            project.service<dev.acme.adbtoolbox.intellij.composition.AdbToolboxProjectService>().registerMcpTools()
        }
    }
}
