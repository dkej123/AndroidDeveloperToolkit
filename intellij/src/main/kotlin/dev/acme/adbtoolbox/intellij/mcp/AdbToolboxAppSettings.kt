package dev.acme.adbtoolbox.intellij.mcp

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import dev.acme.adbtoolbox.application.mcp.McpAccess
import java.security.SecureRandom

/**
 * Application-level preferences (design §11 "Per application"): copy screenshots to the clipboard,
 * the MCP access level and port. The MCP token lives in the IDE's password safe, never in XML.
 */
@Service(Service.Level.APP)
@State(name = "AdbToolboxAppSettings", storages = [Storage("adbToolboxApp.xml")])
class AdbToolboxAppSettings : PersistentStateComponent<AdbToolboxAppSettings.State> {

    class State {
        var copyScreenshotsToClipboard: Boolean = true
        var mcpAccess: String = McpAccess.Off.name
        /** 0 until the server first starts; then the port it got, reused so setup snippets stay valid. */
        var mcpPort: Int = 0
        /** The tool window's "install the agent skill" tip was closed; it never comes back (task 066). */
        var skillTipDismissed: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    var copyScreenshotsToClipboard: Boolean
        get() = state.copyScreenshotsToClipboard
        set(value) {
            state.copyScreenshotsToClipboard = value
        }

    var mcpAccess: McpAccess
        get() = runCatching { McpAccess.valueOf(state.mcpAccess) }.getOrDefault(McpAccess.Off)
        set(value) {
            state.mcpAccess = value.name
        }

    var mcpPort: Int
        get() = state.mcpPort
        set(value) {
            state.mcpPort = value
        }

    var skillTipDismissed: Boolean
        get() = state.skillTipDismissed
        set(value) {
            state.skillTipDismissed = value
        }

    /** The bearer token agents send; created on first use. */
    fun mcpToken(): String = PasswordSafe.instance.getPassword(TOKEN) ?: regenerateMcpToken()

    fun regenerateMcpToken(): String {
        val bytes = ByteArray(20).also(SecureRandom()::nextBytes)
        val token = "atk_" + bytes.joinToString("") { "%02x".format(it) }
        PasswordSafe.instance.set(TOKEN, Credentials("adb-toolbox-mcp", token))
        return token
    }

    companion object {
        private val TOKEN = McpTokenCredentials.TOKEN

        fun getInstance(): AdbToolboxAppSettings = service()
    }
}
