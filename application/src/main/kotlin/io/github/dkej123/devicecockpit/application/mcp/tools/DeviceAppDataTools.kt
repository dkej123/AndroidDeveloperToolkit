package io.github.dkej123.devicecockpit.application.mcp.tools

import io.github.dkej123.devicecockpit.application.appdetails.AppDataRead
import io.github.dkej123.devicecockpit.application.locale.LocaleResult
import io.github.dkej123.devicecockpit.application.locale.LocationResult
import io.github.dkej123.devicecockpit.application.mcp.McpArgumentException
import io.github.dkej123.devicecockpit.application.mcp.McpTool
import io.github.dkej123.devicecockpit.application.mcp.McpToolResult
import io.github.dkej123.devicecockpit.application.mcp.optionalBoolean
import io.github.dkej123.devicecockpit.application.mcp.optionalDouble
import io.github.dkej123.devicecockpit.application.mcp.optionalString
import io.github.dkej123.devicecockpit.application.mcp.requireString
import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbOutcome
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.ShellValue
import io.github.dkej123.devicecockpit.domain.appdata.PermissionCommands
import io.github.dkej123.devicecockpit.domain.appdata.SqlResult
import io.github.dkej123.devicecockpit.domain.apps.AppLifecycleResult
import io.github.dkej123.devicecockpit.domain.apps.AppRestartResult
import io.github.dkej123.devicecockpit.domain.apps.ClearDataResult
import io.github.dkej123.devicecockpit.domain.apps.UninstallResult
import io.github.dkej123.devicecockpit.domain.display.AnimationScaleCommand
import io.github.dkej123.devicecockpit.domain.display.AnimationScaleSetting
import io.github.dkej123.devicecockpit.domain.display.DarkThemeCommand
import io.github.dkej123.devicecockpit.domain.display.DisplaySettingRead
import io.github.dkej123.devicecockpit.domain.display.ShowTouchesCommand
import io.github.dkej123.devicecockpit.domain.display.density.DensityCommands
import io.github.dkej123.devicecockpit.domain.display.density.DensityParseResult
import io.github.dkej123.devicecockpit.domain.display.density.parseDensityText
import io.github.dkej123.devicecockpit.domain.display.fontscale.FontScaleCommands
import io.github.dkej123.devicecockpit.domain.display.toggles.AirplaneModeCommand
import io.github.dkej123.devicecockpit.domain.display.toggles.DeviceSettingToggleCommand
import io.github.dkej123.devicecockpit.domain.display.toggles.MobileDataCommand
import io.github.dkej123.devicecockpit.domain.display.toggles.ScreenRotation
import io.github.dkej123.devicecockpit.domain.display.toggles.ScreenRotationCommand
import io.github.dkej123.devicecockpit.domain.display.toggles.ShowLayoutBoundsCommand
import io.github.dkej123.devicecockpit.domain.display.toggles.WifiCommand
import io.github.dkej123.devicecockpit.domain.foreground.ForegroundState
import io.github.dkej123.devicecockpit.domain.foreground.PackageDetailsCommand
import io.github.dkej123.devicecockpit.domain.locale.LocaleTag
import io.github.dkej123.devicecockpit.domain.location.GeoPoint
import kotlinx.serialization.json.JsonObject

private fun shell(serial: DeviceSerial, vararg tokens: ShellToken) = AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(*tokens)))
private fun lit(text: String) = ShellToken.Literal(text)
private fun value(text: String) = ShellToken.Value(ShellValue.of(text))

/** On/off settings agents may read and change, with the names `set_device_settings` takes. */
private val SWITCHES: Map<String, DeviceSettingToggleCommand> = linkedMapOf(
    "wifi" to WifiCommand,
    "airplane_mode" to AirplaneModeCommand,
    "mobile_data" to MobileDataCommand,
    "layout_bounds" to ShowLayoutBoundsCommand,
)

/**
 * Device, Apps and Data tools (task 065): devices, device state and test settings, apps, logcat,
 * shared preferences and read-only database queries.
 *
 * Ported from Oh My Android, MIT — `Sources/MCP/Tools/DeviceTools.swift`, `AppTools.swift`, `DataTools.swift`.
 */
fun deviceAppDataTools(env: McpToolEnvironment): List<McpTool> = listOf(
    SimpleTool("list_devices", "Connected emulators and phones with their state; the one selected in Device Cockpit is marked.", readOnly = true, inputSchema = schema()) { _, _ ->
        val selected = runCatching { env.device(JsonObject(emptyMap())) }.getOrNull()
        val lines = env.devices.devices.value.map { d ->
            "${d.serial}  ${d.displayName}  ${d.state.label()}${if (d.serial == selected) "  (selected)" else ""}"
        }
        McpToolResult.text(lines.joinToString("\n").ifEmpty { "No devices. Start an emulator or connect a phone with USB debugging." })
    },
    SimpleTool(
        "get_device_state",
        "The app in front and the current test settings, with the names set_device_settings takes.",
        readOnly = true,
        inputSchema = schema(SERIAL),
    ) { args, _ -> McpToolResult.text(env.deviceState(env.device(args))) },
    SimpleTool(
        "set_device_settings",
        "Change test settings; pass only the ones to change. Applies live. Returns the new state. " +
            "location (\"lat,lon\") works on emulators only.",
        readOnly = false,
        inputSchema = schema(
            SERIAL,
            bool("dark_mode", "Dark theme."),
            num("font_scale", "0.85–2.0; 1 = default."),
            int("density", "Display density in dpi; 0 resets to physical."),
            str("locale", "BCP-47 tag, e.g. pl-PL, ar-EG, en-XA (pseudo), ar-XB (pseudo RTL); \"reset\" restores the original."),
            str("orientation", "Rotation.", oneOf = listOf("auto", "portrait", "landscape")),
            bool("animations", "false turns all animations off (for UI tests)."),
            bool("show_touches", "Show taps."),
            bool("wifi", "Wi-Fi (turning it off can drop a wireless adb connection)."),
            bool("airplane_mode", "Airplane mode."),
            bool("mobile_data", "Mobile data."),
            bool("layout_bounds", "Show layout bounds."),
            str("location", "Emulator GPS fix, \"latitude,longitude\"."),
        ),
    ) { args, _ ->
        val serial = env.device(args)
        val notes = mutableListOf<String>()
        args.optionalBoolean("dark_mode")?.let { env.transport.executeText(DarkThemeCommand.writeRequest(serial, it)) }
        args.optionalDouble("font_scale")?.let {
            if (it !in 0.25..5.0) throw McpArgumentException("font_scale must be 0.25–5.0")
            env.transport.executeText(FontScaleCommands.write(serial, it))
        }
        args.optionalDouble("density")?.toInt()?.let {
            val op = if (it <= 0) DensityCommands.reset() else DensityCommands.apply(it.coerceIn(72, 1200))
            env.transport.executeText(AdbDeviceRequest(serial, op))
        }
        args.optionalBoolean("animations")?.let { on ->
            AnimationScaleSetting.entries.forEach { env.transport.executeText(AnimationScaleCommand.writeRequest(serial, it, on)) }
        }
        args.optionalBoolean("show_touches")?.let { env.transport.executeText(ShowTouchesCommand.writeRequest(serial, it)) }
        SWITCHES.forEach { (name, command) ->
            args.optionalBoolean(name)?.let { on -> command.writeRequests(serial, on).forEach { env.transport.executeText(it) } }
        }
        args.optionalString("orientation")?.let { name ->
            val rotation = ScreenRotation.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw McpArgumentException("orientation must be auto, portrait or landscape")
            ScreenRotationCommand.writeRequests(serial, rotation).forEach { env.transport.executeText(it) }
        }
        args.optionalString("locale")?.let { text ->
            val result = if (text == "reset") {
                env.locale.reset(serial)
            } else {
                env.locale.set(serial, LocaleTag.of(text) ?: throw McpArgumentException("locale '$text' is not a language tag"))
            }
            if (result is LocaleResult.Failed) notes += "locale: ${result.reason}"
        }
        args.optionalString("location")?.let { text ->
            val point = GeoPoint.parse(text) ?: throw McpArgumentException("location must be \"latitude,longitude\" in range")
            when (val result = env.location.set(serial, point)) {
                LocationResult.NotAnEmulator -> notes += "location: emulator only (this device has no emulator console)"
                is LocationResult.Failed -> notes += "location: ${result.reason}"
                is LocationResult.Set -> Unit
            }
        }
        McpToolResult.text((notes.map { "Not applied — $it" } + env.deviceState(serial)).joinToString("\n"))
    },
    SimpleTool("list_apps", "Installed apps (third-party unless system=true). Marks the one in front.", readOnly = true, inputSchema = schema(SERIAL, bool("system", "Include system apps."), str("query", "Filter by package name."))) { args, _ ->
        val serial = env.device(args)
        val flag = if (args.optionalBoolean("system") == true) emptyArray() else arrayOf(lit("-3"))
        val output = env.transport.executeText(shell(serial, lit("pm"), lit("list"), lit("packages"), *flag)).stdout
        val front = (env.currentApp.read(serial).foreground as? ForegroundState.App)?.packageName
        val query = args.optionalString("query")
        val packages = output.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() && (query == null || it.contains(query, true)) }.sorted()
        McpToolResult.text(packages.joinToString("\n") { if (it == front) "$it  (in front)" else it }.ifEmpty { "No apps match." })
    },
    SimpleTool("open_app", "Launch an app by package, or open a deep link / URL. Give package or url.", readOnly = false, inputSchema = schema(SERIAL, str("package", "Package to launch."), str("url", "Deep link or URL to open."))) { args, _ ->
        val serial = env.device(args)
        val url = args.optionalString("url")
        val pkg = args.optionalString("package")
        when {
            url != null -> {
                val tokens = mutableListOf(lit("am"), lit("start"), lit("-W"), lit("-a"), lit("android.intent.action.VIEW"), lit("-d"), value(url))
                if (pkg != null) tokens += listOf(lit("-p"), value(pkg))
                val result = env.transport.executeText(shell(serial, *tokens.toTypedArray()))
                val text = (result.stdout + result.stderr).trim()
                if ("Error" in text) McpToolResult.error(text) else McpToolResult.text("Opened $url. " + text.lines().filter { "TotalTime" in it || "Activity:" in it }.joinToString(" "))
            }
            pkg != null -> when (val result = env.lifecycle.launch(serial, pkg)) {
                AppLifecycleResult.Success -> McpToolResult.text("Launched $pkg.")
                is AppLifecycleResult.Failure -> McpToolResult.error(result.reason)
                AppLifecycleResult.RejectedDuplicate -> McpToolResult.error("$pkg is already being launched.")
            }
            else -> throw McpArgumentException("Give package or url.")
        }
    },
    SimpleTool(
        "manage_app",
        "restart, force_stop, reset_permissions, clear_data or uninstall an app. Default: the app in front. " +
            "clear_data and uninstall ask the user in the IDE first.",
        readOnly = false,
        destructive = true,
        inputSchema = schema(
            SERIAL,
            str("action", "What to do.", required = true, oneOf = listOf("restart", "force_stop", "reset_permissions", "clear_data", "uninstall")),
            str("package", "Package. Default: the app in front."),
            str("permission", "reset_permissions only: just this runtime permission, e.g. android.permission.CAMERA."),
        ),
    ) { args, context ->
        val serial = env.device(args)
        val pkg = env.packageOrForeground(serial, args)
        when (val action = args.requireString("action")) {
            "restart" -> when (val r = env.lifecycle.restart(serial, pkg)) {
                AppRestartResult.Success -> McpToolResult.text("Restarted $pkg.")
                else -> McpToolResult.error("Restart of $pkg failed: $r")
            }
            "force_stop" -> when (val r = env.lifecycle.forceStop(serial, pkg)) {
                AppLifecycleResult.Success -> McpToolResult.text("Force-stopped $pkg.")
                is AppLifecycleResult.Failure -> McpToolResult.error(r.reason)
                AppLifecycleResult.RejectedDuplicate -> McpToolResult.error("$pkg is busy.")
            }
            "reset_permissions" -> when (val r = env.currentApp.resetPermissions(serial, pkg, permission = args.optionalString("permission"))) {
                is io.github.dkej123.devicecockpit.application.currentapp.PermissionResetResult.Done -> McpToolResult.text(
                    args.optionalString("permission")?.let { if (r.revoked > 0) "Reset $it of $pkg." else "$it is not granted to $pkg; nothing to reset." }
                        ?: "Permissions reset for $pkg — ${r.revoked} revoked.",
                )
                is io.github.dkej123.devicecockpit.application.currentapp.PermissionResetResult.Failed -> McpToolResult.error(r.reason)
            }
            "clear_data", "uninstall" -> {
                val kind = if (action == "clear_data") DestructiveAppAction.ClearData else DestructiveAppAction.Uninstall
                if (!env.confirmation.confirm(kind, serial, pkg, context.clientName)) {
                    McpToolResult.error("Declined by user: ${action.replace('_', ' ')} of $pkg was not done.")
                } else if (kind == DestructiveAppAction.ClearData) {
                    when (val r = env.clearData.clearData(serial, pkg)) {
                        ClearDataResult.Success -> McpToolResult.text("Data cleared for $pkg.")
                        is ClearDataResult.Failure -> McpToolResult.error(r.reason)
                        ClearDataResult.RejectedDuplicate -> McpToolResult.error("$pkg is busy.")
                    }
                } else {
                    when (val r = env.uninstall.uninstall(serial, pkg)) {
                        UninstallResult.Success -> McpToolResult.text("Uninstalled $pkg.")
                        is UninstallResult.AlreadyMissing -> McpToolResult.text("$pkg was not installed.")
                        is UninstallResult.Failure -> McpToolResult.error(r.reason)
                        else -> McpToolResult.error("Uninstall of $pkg did not finish: $r")
                    }
                }
            }
            else -> throw McpArgumentException("unknown action $action")
        }
    },
    SimpleTool(
        "list_permissions",
        "Runtime permissions of an app: granted or not, and which the system or a policy fixes. Default: the app in front.",
        readOnly = true,
        inputSchema = schema(SERIAL, str("package", "Package. Default: the app in front.")),
    ) { args, _ ->
        val serial = env.device(args)
        val pkg = env.packageOrForeground(serial, args)
        val details = PackageDetailsCommand.parse(env.transport.executeText(PackageDetailsCommand.request(serial, pkg)), pkg)
            ?: throw McpToolFailure("Couldn't read the permissions of $pkg.")
        val lines = details.runtimePermissions.map { p ->
            p.name + (if (p.granted) " granted" else " denied") + if (p.fixed) " (fixed by system or policy)" else ""
        }
        McpToolResult.text("$pkg runtime permissions:\n" + lines.ifEmpty { listOf("none") }.joinToString("\n"))
    },
    SimpleTool(
        "set_permission",
        "Grant, revoke or reset (revoke and clear \"don't ask again\") one runtime permission of an app. " +
            "Revoking stops the app. Default: the app in front; manage_app reset_permissions resets all.",
        readOnly = false,
        inputSchema = schema(
            SERIAL,
            str("permission", "e.g. android.permission.CAMERA.", required = true),
            str("action", "What to do.", required = true, oneOf = listOf("grant", "revoke", "reset")),
            str("package", "Package. Default: the app in front."),
        ),
    ) { args, _ ->
        val serial = env.device(args)
        val pkg = env.packageOrForeground(serial, args)
        val permission = args.requireString("permission")
        val action = args.requireString("action")
        val requests = try {
            when (action) {
                "grant" -> listOf(PermissionCommands.grant(serial, pkg, permission, 0))
                "revoke" -> listOf(PermissionCommands.revoke(serial, pkg, permission, 0))
                "reset" -> PermissionCommands.reset(serial, pkg, permission, 0)
                else -> throw McpArgumentException("action must be grant, revoke or reset")
            }
        } catch (invalid: IllegalArgumentException) {
            throw McpArgumentException(invalid.message.orEmpty())
        }
        val first = env.transport.executeText(requests.first())
        if (first.outcome !is AdbOutcome.Completed || first.stderr.contains("Exception")) {
            throw McpToolFailure(first.stderr.trim().ifEmpty { "pm $action $permission failed for $pkg." })
        }
        requests.drop(1).forEach { env.transport.executeText(it) }
        McpToolResult.text(
            when (action) {
                "grant" -> "Granted $permission to $pkg."
                "revoke" -> "Revoked $permission of $pkg."
                else -> "Reset $permission of $pkg."
            },
        )
    },
    SimpleTool(
        "logcat",
        "Recent log lines (threadtime). Filter by app (package), minimum level, text. crash=true reads the crash buffer (stack traces).",
        readOnly = true,
        inputSchema = schema(SERIAL, str("package", "Only this app's process."), str("level", "Minimum level.", oneOf = listOf("V", "D", "I", "W", "E")), str("contains", "Only lines containing this text."), int("lines", "How many, 1–500. Default 100."), bool("crash", "Crash buffer.")),
    ) { args, _ ->
        val serial = env.device(args)
        val count = (args.optionalDouble("lines") ?: 100.0).toInt().coerceIn(1, 500)
        val tokens = mutableListOf(lit("logcat"), lit("-d"), lit("-v"), lit("threadtime"))
        if (args.optionalBoolean("crash") == true) tokens += listOf(lit("-b"), lit("crash"))
        args.optionalString("package")?.let { pkg ->
            val pid = env.transport.executeText(shell(serial, lit("pidof"), lit("-s"), value(pkg))).stdout.trim().toIntOrNull()
                ?: return@SimpleTool McpToolResult.error("$pkg is not running.")
            tokens += lit("--pid=$pid")
        }
        tokens += listOf(lit("-t"), lit(if (args.optionalString("contains") == null) count.toString() else "5000"))
        args.optionalString("level")?.let { level ->
            if (level !in listOf("V", "D", "I", "W", "E")) throw McpArgumentException("level must be V, D, I, W or E")
            tokens += lit("*:$level")
        }
        val output = env.transport.executeText(shell(serial, *tokens.toTypedArray())).stdout.lines().filter { it.isNotBlank() && !it.startsWith("---------") }
        val contains = args.optionalString("contains")
        val lines = (if (contains != null) output.filter { it.contains(contains, ignoreCase = true) } else output).takeLast(count)
        McpToolResult.text(lines.joinToString("\n").ifEmpty { "No matching log lines." })
    },
    SimpleTool("read_preferences", "SharedPreferences of an app: key (type) = value, per file. Needs a debuggable app or a rooted device. Default: the app in front.", readOnly = true, inputSchema = schema(SERIAL, str("package", "Package."), str("file", "Only this file, e.g. settings.xml."))) { args, _ ->
        val serial = env.device(args)
        val pkg = env.packageOrForeground(serial, args)
        val (access, listing) = when (val l = env.appData.listing(serial, pkg)) {
            is AppDataRead.Failed -> return@SimpleTool McpToolResult.error(l.reason)
            is AppDataRead.Read -> l.value
        }
        val files = args.optionalString("file")?.let { listOf(it) } ?: listing.sharedPrefs
        if (files.isEmpty()) return@SimpleTool McpToolResult.text("$pkg has no shared preferences.")
        val text = files.map { file ->
            when (val prefs = env.appData.preferences(serial, pkg, access, file)) {
                is AppDataRead.Failed -> "$file: ${prefs.reason}"
                is AppDataRead.Read -> "$file\n" + prefs.value.joinToString("\n") { "  ${it.key} (${it.value.type?.label ?: "?"}) = ${it.value.display.replace("\n", ", ")}" }
            }
        }.joinToString("\n\n")
        McpToolResult.text(text)
    },
    SimpleTool(
        "query_database",
        "Read-only SQL on a copy of an app database (SELECT / WITH / PRAGMA). Without sql: lists the databases and their tables. " +
            "Needs a debuggable app or a rooted device. Default: the app in front.",
        readOnly = true,
        inputSchema = schema(SERIAL, str("package", "Package."), str("database", "Database file name."), str("sql", "Query."), int("max_rows", "1–500. Default 100.")),
    ) { args, _ ->
        val serial = env.device(args)
        val pkg = env.packageOrForeground(serial, args)
        val (access, listing) = when (val l = env.appData.listing(serial, pkg)) {
            is AppDataRead.Failed -> return@SimpleTool McpToolResult.error(l.reason)
            is AppDataRead.Read -> l.value
        }
        val database = args.optionalString("database")
        val sql = args.optionalString("sql")
        if (database == null || sql == null) {
            if (listing.databases.isEmpty()) return@SimpleTool McpToolResult.text("$pkg has no databases.")
            val text = listing.databases.map { db ->
                when (val tables = env.appData.query(serial, pkg, access, db, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", 200)) {
                    is AppDataRead.Read -> "$db: " + ((tables.value as? SqlResult.Rows)?.rows?.rows?.joinToString(", ") { it.first().display } ?: "?")
                    is AppDataRead.Failed -> "$db: ${tables.reason}"
                }
            }.joinToString("\n")
            return@SimpleTool McpToolResult.text(text)
        }
        val keyword = sql.trimStart().takeWhile { it.isLetter() }.uppercase()
        if (keyword !in setOf("SELECT", "WITH", "PRAGMA", "EXPLAIN")) throw McpArgumentException("only queries (SELECT, WITH, PRAGMA, EXPLAIN) are allowed")
        val maxRows = (args.optionalDouble("max_rows") ?: 100.0).toInt().coerceIn(1, 500)
        when (val result = env.appData.query(serial, pkg, access, database, sql, maxRows)) {
            is AppDataRead.Failed -> McpToolResult.error(result.reason)
            is AppDataRead.Read -> when (val r = result.value) {
                is SqlResult.Rows -> McpToolResult.text(
                    (listOf(r.rows.columns.joinToString(" | ")) + r.rows.rows.map { row -> row.joinToString(" | ") { it.display.take(200) } }).joinToString("\n") +
                        "\n(${r.rows.rows.size} rows)",
                )
                is SqlResult.Updated -> McpToolResult.text("${r.count} rows affected on the copy (the device was not changed).")
                is SqlResult.Failed -> McpToolResult.error(r.message)
            }
        }
    },
)

/** `get_device_state` text: app in front and settings with the names `set_device_settings` takes. */
internal suspend fun McpToolEnvironment.deviceState(serial: DeviceSerial): String {
    suspend fun <T> read(request: AdbDeviceRequest, parse: (io.github.dkej123.devicecockpit.domain.adb.AdbTextResult) -> DisplaySettingRead<T>): String =
        when (val r = parse(transport.executeText(request))) {
            is DisplaySettingRead.Value -> r.value.toString().lowercase()
            else -> "n/a"
        }
    val front = when (val f = currentApp.read(serial).foreground) {
        is ForegroundState.App -> "${f.packageName}/${f.activity}"
        is ForegroundState.Home -> "home screen"
        ForegroundState.Locked -> "lock screen"
        is ForegroundState.SystemUi -> "System UI (shade)"
        ForegroundState.Nothing -> "nothing"
        is ForegroundState.Unknown -> "unknown (${f.reason})"
    }
    val density = (parseDensityText(transport.executeText(AdbDeviceRequest(serial, DensityCommands.read())).stdout) as? DensityParseResult.Parsed)?.reading
    val locale = (locale.read(serial) as? LocaleResult.Applied)?.state
    return buildList {
        add("device: $serial")
        add("app_in_front: $front")
        add("dark_mode: ${read(DarkThemeCommand.readRequest(serial), DarkThemeCommand::parseRead)}")
        add("font_scale: ${transport.executeText(FontScaleCommands.read(serial)).stdout.trim().ifEmpty { "1.0" }.replace("null", "1.0")}")
        add("density: ${density?.let { "${it.overrideDpi ?: it.physicalDpi} dpi (physical ${it.physicalDpi})" } ?: "n/a"}")
        add("locale: ${locale?.let { if (it.overridden) "${it.current} (original ${it.original})" else it.current } ?: "n/a"}")
        add("orientation: ${read(ScreenRotationCommand.readRequest(serial), ScreenRotationCommand::parseRead)}")
        add("animations: ${read(AnimationScaleCommand.readRequest(serial, AnimationScaleSetting.entries.first()), AnimationScaleCommand::parseRead).let { if (it == "0.0") "false" else if (it == "n/a") it else "true" }}")
        add("show_touches: ${read(ShowTouchesCommand.readRequest(serial), ShowTouchesCommand::parseRead)}")
        SWITCHES.forEach { (name, command) -> add("$name: ${read(command.readRequest(serial), command::parseRead)}") }
    }.joinToString("\n")
}

internal fun io.github.dkej123.devicecockpit.domain.device.DeviceConnectionState.label(): String =
    toString().substringBefore('(').lowercase()
