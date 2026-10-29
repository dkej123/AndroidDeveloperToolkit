package io.github.dkej123.devicecockpit.application.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.*
import io.github.dkej123.devicecockpit.domain.deeplinks.AmStartOutput
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkCommands
import io.github.dkej123.devicecockpit.domain.deeplinks.LinkHandlerQuery
import io.github.dkej123.devicecockpit.domain.deeplinks.ResolvedHandler
import io.github.dkej123.devicecockpit.domain.packages.*
import io.github.dkej123.devicecockpit.application.display.fontscale.isSuccess
import kotlin.time.Duration.Companion.seconds

/** [handledBy] is the app that really took an Any app launch (from `am start -W`); empty when unknown or the chooser. */
data class RecentDeviceLink(val uri: String = "", val packageName: String = "", val lastRun: Long = 0, val handledBy: String = "")

/** Application-wide history; independent of the older per-project App details launcher memory. */
interface DeviceDeepLinkMemory {
    var lastPackage: String
    /** `am start -S` (handoff 7); remembered per application. */
    var coldStart: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) {}
    /** Command preview shows one argument per line (handoff 7); remembered per application. */
    var commandExpanded: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) {}
    fun entries(): List<RecentDeviceLink>
    fun replace(entries: List<RecentDeviceLink>)
}

sealed interface DeviceLinkResult {
    /**
     * What `am start -W` reported. [matchCount] is filled when the chooser was shown; [appLinkApp] is the
     * app that also declares an http(s) link the browser took instead (an unverified App Link).
     */
    data class Opened(
        val output: AmStartOutput,
        val raw: String,
        val matchCount: Int? = null,
        val appLinkApp: String? = null,
    ) : DeviceLinkResult
    data class Failed(val message: String, val unresolved: Boolean = false, val raw: String = "") : DeviceLinkResult
}

/** Who would take a link tap, from `query-activities` + `resolve-activity` (design/DEEP_LINK_DIALOG.md §2b). */
sealed interface LinkHandlers {
    val matches: List<String>
    data class OpensDirectly(val handler: String, override val matches: List<String>) : LinkHandlers
    data class Chooser(override val matches: List<String>) : LinkHandlers
    data class BrowserFallback(val browser: String, override val matches: List<String>, val host: String) : LinkHandlers
    data class HandledBy(val handler: String, override val matches: List<String>) : LinkHandlers
    data class NotHandledBy(val packageName: String, override val matches: List<String>) : LinkHandlers
    data object Nothing : LinkHandlers { override val matches = emptyList<String>() }
}

internal fun packageOf(component: String) = component.substringBefore('/')
internal fun hostOf(uri: String) = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://([^/?#:]*)""").find(uri)?.groupValues?.get(1).orEmpty()
internal fun isWebLink(uri: String) = uri.startsWith("http://", true) || uri.startsWith("https://", true)

class DeviceDeepLinkUseCase(
    private val transport: AdbTransport,
    private val memory: DeviceDeepLinkMemory,
    private val nowMillis: () -> Long,
) {
    private val browsersBySerial = mutableMapOf<DeviceSerial, Set<String>>()

    fun history(): List<RecentDeviceLink> = memory.entries()
    fun rememberedPackage(): String = memory.lastPackage
    /** Every Open in pick is remembered, not only successful launches (handoff 7 §3, §3a). */
    fun rememberPackage(packageName: String) { memory.lastPackage = packageName }
    var coldStart: Boolean
        get() = memory.coldStart
        set(value) { memory.coldStart = value }
    var commandExpanded: Boolean
        get() = memory.commandExpanded
        set(value) { memory.commandExpanded = value }

    fun remove(entry: RecentDeviceLink) {
        memory.replace(history().filterNot { it.uri == entry.uri && it.packageName == entry.packageName })
    }

    private fun previewArguments(serial: DeviceSerial, uri: String, packageName: String, coldStart: Boolean): List<String> {
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        fun word(value: String) = if (Regex("[A-Za-z0-9_.:-]+").matches(value)) value else quote(value)
        return listOfNotNull(
            "adb -s ${word(serial.value)} shell am start",
            "-W",
            "-S".takeIf { coldStart },
            "-a android.intent.action.VIEW",
            "-c android.intent.category.BROWSABLE",
            "-d ${quote(uri.ifBlank { "<uri>" })}",
            packageName.takeIf { it.isNotEmpty() }?.let { "-p ${word(it)}" },
        )
    }

    /** Expanded preview: one argument per line with ` \` continuation, including an empty-form template. */
    fun previewCommand(serial: DeviceSerial, uri: String, packageName: String, coldStart: Boolean = false): String =
        previewArguments(serial, uri, packageName, coldStart).mapIndexed { index, line -> if (index == 0) line else "    $line" }.joinToString(" \\\n")

    /** Collapsed preview (handoff 7 default): the same arguments on one line. Copy uses [terminalCommand]. */
    fun previewLine(serial: DeviceSerial, uri: String, packageName: String, coldStart: Boolean = false): String =
        previewArguments(serial, uri, packageName, coldStart).joinToString(" ")

    /** Quote the entire remote command for the host shell, preserving the device-side quoting. */
    fun terminalCommand(serial: DeviceSerial, uri: String, packageName: String, coldStart: Boolean = false): String {
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val remote = (DeepLinkCommands.open(serial, packageName, uri, coldStart).operation as AdbOperation.Shell).command.render()
        return "adb -s ${quote(serial.value)} shell ${quote(remote)}"
    }

    suspend fun packages(serial: DeviceSerial, known: List<PackageEntry>): List<PackageEntry> {
        val result = transport.executeText(PackageListCommand.request(serial, PackageListScope.User))
        check(result.outcome.isSuccess()) { result.stderr.lineSequence().firstOrNull().orEmpty().ifBlank { "Could not load installed apps" } }
        val entries = known.associateBy { it.packageName }
        return (PackageListCommand.distinctPackageNames(result).map { entries[it] ?: PackageEntry.unresolved(it) } + known)
            .distinctBy { it.packageName }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
    }

    /** Never launches anything; `null` when the device could not answer (the row is hidden). */
    suspend fun handlers(serial: DeviceSerial, uri: String, packageName: String): LinkHandlers? {
        val matches = query(LinkHandlerQuery.matches(serial, uri))?.let(LinkHandlerQuery::parseMatches) ?: return null
        if (packageName.isNotEmpty()) {
            val own = matches.firstOrNull { packageOf(it) == packageName }
            return when {
                own != null -> LinkHandlers.HandledBy(own, matches)
                matches.isEmpty() -> LinkHandlers.Nothing
                else -> LinkHandlers.NotHandledBy(packageName, matches)
            }
        }
        if (matches.isEmpty()) return LinkHandlers.Nothing
        return when (val resolved = query(LinkHandlerQuery.resolve(serial, uri))?.let(LinkHandlerQuery::parseResolved) ?: return null) {
            ResolvedHandler.Chooser -> LinkHandlers.Chooser(matches)
            ResolvedHandler.None -> LinkHandlers.Nothing
            is ResolvedHandler.Direct -> {
                val browsers = if (isWebLink(uri)) browsers(serial) else emptySet()
                val appMatches = matches.filter { packageOf(it) !in browsers }
                if (packageOf(resolved.component) in browsers && appMatches.isNotEmpty()) {
                    LinkHandlers.BrowserFallback(resolved.component, matches, hostOf(uri))
                } else LinkHandlers.OpensDirectly(resolved.component, matches)
            }
        }
    }

    /** Packages that take any web page: the matches for a neutral link, cached per device. */
    private suspend fun browsers(serial: DeviceSerial): Set<String> = browsersBySerial[serial] ?: run {
        val found = query(LinkHandlerQuery.matches(serial, NEUTRAL_WEB_LINK))?.let(LinkHandlerQuery::parseMatches)?.map(::packageOf)?.toSet()
            ?: return emptySet()
        found.also { browsersBySerial[serial] = it }
    }

    private suspend fun query(request: AdbDeviceRequest): String? {
        val result = kotlinx.coroutines.withTimeoutOrNull(3_000L) { transport.executeText(request) } ?: return null
        return if (result.outcome.isSuccess()) result.stdout else null
    }

    suspend fun open(serial: DeviceSerial, uri: String, packageName: String, coldStart: Boolean = false): DeviceLinkResult {
        if (DeepLinkCommands.isBrowserIntentUri(uri)) return DeviceLinkResult.Failed(INTENT_SCHEME_MESSAGE)
        val request = try {
            DeepLinkCommands.open(serial, packageName, uri, coldStart)
        } catch (invalid: IllegalArgumentException) {
            return DeviceLinkResult.Failed(invalid.message ?: "Invalid link")
        }
        val result = kotlinx.coroutines.withTimeoutOrNull(15_000L) { transport.executeText(request.copy(timeout = 15.seconds)) }
            ?: return DeviceLinkResult.Failed("Opening the link timed out. You can try again.")
        val output = (result.stderr + "\n" + result.stdout).trim()
        val launch = AmStartOutput.parse(output)
        if (launch.unresolved) return DeviceLinkResult.Failed("No app on this device handles this link", unresolved = true, raw = output)
        val error = launch.error
        if (!result.outcome.isSuccess() || error != null) {
            val line = error ?: output.lineSequence().firstOrNull { it.isNotBlank() } ?: "Could not open link: ${result.outcome}"
            return DeviceLinkResult.Failed(line, raw = output)
        }
        memory.lastPackage = packageName
        val entry = RecentDeviceLink(uri, packageName, nowMillis(), handledBy = launch.handlerPackage.orEmpty())
        memory.replace((listOf(entry) + history().filterNot { it.uri == uri && it.packageName == packageName }).take(50))
        return DeviceLinkResult.Opened(launch, output, matchCount(serial, uri, launch), appLinkApp(serial, uri, packageName, launch))
    }

    private suspend fun matchCount(serial: DeviceSerial, uri: String, launch: AmStartOutput): Int? =
        if (!launch.chooserShown) null else query(LinkHandlerQuery.matches(serial, uri))?.let(LinkHandlerQuery::parseMatches)?.size

    private suspend fun appLinkApp(serial: DeviceSerial, uri: String, packageName: String, launch: AmStartOutput): String? {
        val taken = launch.handlerPackage ?: return null
        if (packageName.isNotEmpty() || !isWebLink(uri)) return null
        val browsers = browsers(serial)
        if (taken !in browsers) return null
        val matches = query(LinkHandlerQuery.matches(serial, uri))?.let(LinkHandlerQuery::parseMatches) ?: return null
        return matches.map(::packageOf).firstOrNull { it !in browsers }
    }

    companion object {
        const val INTENT_SCHEME_MESSAGE = "intent: links are read by the browser, not sent as a link. Enter the URI they wrap, e.g. myapp://path"
        private const val NEUTRAL_WEB_LINK = "https://example.invalid/"
    }
}
