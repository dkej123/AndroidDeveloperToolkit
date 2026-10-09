package io.github.dkej123.devicecockpit.application.currentapp

import io.github.dkej123.devicecockpit.application.apps.AppLifecycleUseCase
import io.github.dkej123.devicecockpit.application.apps.ClearDataUseCase
import io.github.dkej123.devicecockpit.application.apps.UninstallUseCase
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.apps.AppLifecycleResult
import io.github.dkej123.devicecockpit.domain.apps.AppRestartResult
import io.github.dkej123.devicecockpit.domain.apps.ClearDataResult
import io.github.dkej123.devicecockpit.domain.apps.UninstallResult
import io.github.dkej123.devicecockpit.domain.device.DeviceCommandContext
import io.github.dkej123.devicecockpit.domain.device.SelectedDeviceState
import io.github.dkej123.devicecockpit.domain.device.toCommandContext
import io.github.dkej123.devicecockpit.domain.dispatch.DispatcherProvider
import io.github.dkej123.devicecockpit.domain.foreground.ForegroundState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An action of the Current app section (design §3a). */
enum class CurrentAppAction { Restart, Launch, Kill, ResetPermissions, ClearData, Uninstall }

/** What is shown (design §3a states). */
sealed interface CurrentAppDisplay {
    /** First query, nothing to show yet: the skeleton. */
    data object Reading : CurrentAppDisplay

    /** An app to act on; [killed]: force-stopped, kept here until another app comes to the front. */
    data class App(val snapshot: CurrentAppSnapshot, val app: ForegroundState.App, val killed: Boolean, val systemUi: Boolean = false) : CurrentAppDisplay

    data class Home(val launcherPackage: String, val lastApp: String?) : CurrentAppDisplay

    data object Locked : CurrentAppDisplay

    data class Error(val reason: String) : CurrentAppDisplay
}

data class CurrentAppViewState(
    val display: CurrentAppDisplay = CurrentAppDisplay.Reading,
    /** A detected change held while the user is busy with the section; applied on release or click. */
    val pendingPackage: String? = null,
    val busy: CurrentAppAction? = null,
    val refreshing: Boolean = false,
    val updatedAtMillis: Long? = null,
    val deviceOnline: Boolean = false,
)

/** Results the tool window announces as toasts (design §3a). */
sealed interface CurrentAppEvent {
    data class Done(val message: String) : CurrentAppEvent

    data class Failed(val message: String, val packageName: String) : CurrentAppEvent
}

/**
 * The Current app section (task 064, design §3a): reads the foreground app on events and every 3 s
 * while the Device view is visible and the IDE focused; a change is held while the pointer or focus
 * is in the section, an action runs or a confirmation is open, so content never moves under a click.
 * Kill keeps showing the killed app (with Launch) until another app comes to the front.
 */
class CurrentAppViewModel(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val currentApp: CurrentAppUseCase,
    private val lifecycle: AppLifecycleUseCase,
    private val clearData: ClearDataUseCase,
    private val uninstall: UninstallUseCase,
    selectedDeviceState: StateFlow<SelectedDeviceState>,
    private val now: () -> Long = System::currentTimeMillis,
    private val pollInterval: Duration = 3.seconds,
    private val afterActionDelay: Duration = 1.seconds,
    private val onEvent: (CurrentAppEvent) -> Unit = {},
) {
    private val _state = MutableStateFlow(CurrentAppViewState())
    val state: StateFlow<CurrentAppViewState> = _state.asStateFlow()

    private val context = MutableStateFlow(selectedDeviceState.value.toCommandContext())
    private val queryLock = Mutex()
    private var lastSnapshot: CurrentAppSnapshot? = null
    private var killedPackage: String? = null
    private var lastApp: String? = null
    private var pendingSnapshot: CurrentAppSnapshot? = null
    private var pollJob: Job? = null

    private var visible = false
    private var windowFocused = true
    private var pointerInside = false
    private var focusInside = false
    private var confirmOpen = false

    init {
        scope.launch(dispatchers.default) {
            selectedDeviceState.map { it.toCommandContext() }.distinctUntilChanged().collect { next ->
                context.value = next
                lastSnapshot = null
                killedPackage = null
                lastApp = null
                pendingSnapshot = null
                _state.value = CurrentAppViewState(deviceOnline = next is DeviceCommandContext.Eligible)
                if (next is DeviceCommandContext.Eligible) query(manual = false)
            }
        }
    }

    // ---- Freshness inputs (design §3a) ----

    fun setVisible(value: Boolean) {
        visible = value
        if (value) query(manual = false)
        updatePolling()
    }

    fun setWindowFocused(value: Boolean) {
        val regained = value && !windowFocused
        windowFocused = value
        if (regained && visible) query(manual = false)
        updatePolling()
    }

    fun setPointerInside(value: Boolean) {
        pointerInside = value
        if (!value) releaseHold()
    }

    fun setFocusInside(value: Boolean) {
        focusInside = value
        if (!value) releaseHold()
    }

    fun setConfirmOpen(value: Boolean) {
        confirmOpen = value
        if (!value) releaseHold()
    }

    /** Header meta click: an immediate query that never holds. */
    fun refreshNow() = query(manual = true)

    /** The held change's "Update" link. */
    fun applyPending() {
        pendingSnapshot?.let { show(it) }
    }

    private val held: Boolean get() = pointerInside || focusInside || confirmOpen || _state.value.busy != null

    private fun releaseHold() {
        if (!held) applyPending()
    }

    private fun updatePolling() {
        val shouldPoll = visible && windowFocused
        if (shouldPoll && pollJob?.isActive != true) {
            pollJob = scope.launch(dispatchers.default) {
                while (isActive) {
                    delay(pollInterval)
                    query(manual = false).join()
                }
            }
        } else if (!shouldPoll) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private fun eligibleSerial(): DeviceSerial? = (context.value as? DeviceCommandContext.Eligible)?.serial

    private fun query(manual: Boolean): Job = scope.launch(dispatchers.io) {
        val serial = eligibleSerial() ?: return@launch
        queryLock.withLock {
            _state.update { it.copy(refreshing = true) }
            val snapshot = runCatching { currentApp.read(serial, lastSnapshot) }.getOrElse {
                CurrentAppSnapshot(ForegroundState.Unknown(it.message ?: "adb error"))
            }
            if (eligibleSerial() != serial) return@withLock
            lastSnapshot = snapshot
            _state.update { it.copy(refreshing = false, updatedAtMillis = now()) }
            if (!manual && held && changesTarget(snapshot)) {
                pendingSnapshot = snapshot
                _state.update { it.copy(pendingPackage = packageOf(snapshot) ?: "Home screen") }
            } else {
                show(snapshot)
            }
        }
    }

    private fun currentTarget(): String? = when (val d = _state.value.display) {
        is CurrentAppDisplay.App -> d.app.packageName
        is CurrentAppDisplay.Home -> "home"
        CurrentAppDisplay.Locked -> "lock"
        else -> null
    }

    private fun packageOf(snapshot: CurrentAppSnapshot): String? = when (val f = snapshot.foreground) {
        is ForegroundState.App -> f.packageName
        is ForegroundState.SystemUi -> "System UI"
        else -> null
    }

    /** Whether showing [snapshot] would put a different target under the pointer. */
    private fun changesTarget(snapshot: CurrentAppSnapshot): Boolean {
        val next = when (val f = snapshot.foreground) {
            is ForegroundState.App -> f.packageName
            is ForegroundState.Home -> if (killedPackage != null) killedPackage else "home"
            ForegroundState.Locked -> "lock"
            is ForegroundState.SystemUi -> f.behind?.packageName ?: "System UI"
            else -> currentTarget()
        }
        return currentTarget() != null && next != currentTarget()
    }

    private fun show(snapshot: CurrentAppSnapshot) {
        pendingSnapshot = null
        val display = when (val f = snapshot.foreground) {
            is ForegroundState.App -> {
                if (f.packageName != killedPackage || snapshot.process != null) killedPackage = null
                lastApp = f.packageName
                CurrentAppDisplay.App(snapshot, f, killed = killedPackage == f.packageName)
            }
            is ForegroundState.SystemUi -> {
                val behind = f.behind ?: ForegroundState.App("com.android.systemui", "NotificationShade (window)")
                CurrentAppDisplay.App(snapshot, behind, killed = false, systemUi = true)
            }
            is ForegroundState.Home -> {
                val killed = killedPackage
                val previous = (_state.value.display as? CurrentAppDisplay.App)?.takeIf { it.app.packageName == killed }
                if (killed != null && previous != null) {
                    previous.copy(killed = true, snapshot = previous.snapshot.copy(process = null))
                } else {
                    CurrentAppDisplay.Home(f.launcherPackage, lastApp)
                }
            }
            ForegroundState.Locked -> CurrentAppDisplay.Locked
            ForegroundState.Nothing -> CurrentAppDisplay.Home("", lastApp)
            is ForegroundState.Unknown -> CurrentAppDisplay.Error(f.reason)
        }
        _state.update { it.copy(display = display, pendingPackage = null) }
    }

    // ---- Actions ----

    /** ⌥⇧⌘R: restart the app in front, or launch it when Current app shows it as killed (design §3a). */
    fun restartOrLaunchForeground() {
        when (val display = _state.value.display) {
            is CurrentAppDisplay.App -> if (!display.systemUi) {
                perform(if (display.killed) CurrentAppAction.Launch else CurrentAppAction.Restart, display.app.packageName)
            }
            is CurrentAppDisplay.Home -> display.lastApp?.let { perform(CurrentAppAction.Launch, it) }
            else -> Unit
        }
    }

    fun perform(action: CurrentAppAction, packageName: String) = perform(action, packageName, permission = null)

    /** Resets one runtime permission of [packageName] (task 067); Reset permissions without a choice resets all. */
    fun resetPermission(packageName: String, permission: String) = perform(CurrentAppAction.ResetPermissions, packageName, permission)

    private fun perform(action: CurrentAppAction, packageName: String, permission: String?) {
        val serial = eligibleSerial() ?: return
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = action) }
        scope.launch(dispatchers.io) {
            val event = run(action, serial, packageName, permission)
            _state.update { it.copy(busy = null) }
            onEvent(event)
            delay(afterActionDelay)
            query(manual = true)
        }
    }

    private suspend fun run(action: CurrentAppAction, serial: DeviceSerial, pkg: String, permission: String?): CurrentAppEvent = when (action) {
        CurrentAppAction.Restart -> when (val r = lifecycle.restart(serial, pkg)) {
            AppRestartResult.Success -> CurrentAppEvent.Done("Restarted $pkg").also { killedPackage = null }
            is AppRestartResult.ForceStopFailed -> CurrentAppEvent.Failed("Couldn’t restart $pkg — ${r.reason}", pkg)
            is AppRestartResult.LaunchFailedAfterForceStop -> CurrentAppEvent.Failed("Couldn’t launch $pkg — ${r.reason}", pkg).also { killedPackage = pkg }
            else -> CurrentAppEvent.Failed("$pkg is busy", pkg)
        }
        CurrentAppAction.Launch -> when (val r = lifecycle.launch(serial, pkg)) {
            AppLifecycleResult.Success -> CurrentAppEvent.Done("Launched $pkg").also { killedPackage = null }
            is AppLifecycleResult.Failure -> CurrentAppEvent.Failed("Couldn’t launch $pkg — ${r.reason}", pkg)
            AppLifecycleResult.RejectedDuplicate -> CurrentAppEvent.Failed("$pkg is busy", pkg)
        }
        CurrentAppAction.Kill -> when (val r = lifecycle.forceStop(serial, pkg)) {
            AppLifecycleResult.Success -> CurrentAppEvent.Done("Force-stopped $pkg").also { killed(pkg) }
            is AppLifecycleResult.Failure -> CurrentAppEvent.Failed("Couldn’t force-stop $pkg — ${r.reason}", pkg)
            AppLifecycleResult.RejectedDuplicate -> CurrentAppEvent.Failed("$pkg is busy", pkg)
        }
        CurrentAppAction.ResetPermissions -> when (val r = currentApp.resetPermissions(serial, pkg, permission = permission)) {
            is PermissionResetResult.Done -> CurrentAppEvent.Done(
                when {
                    permission != null && r.revoked > 0 -> "${permission.substringAfterLast('.')} reset for $pkg"
                    permission != null -> "${permission.substringAfterLast('.')} was not granted to $pkg"
                    else -> "Permissions reset for $pkg — " + if (r.revoked == 0) "nothing to revoke" else "${r.revoked} revoked"
                },
            ).also { if (r.revoked > 0) killed(pkg) }
            is PermissionResetResult.Failed -> CurrentAppEvent.Failed(r.reason, pkg)
        }
        CurrentAppAction.ClearData -> when (val r = clearData.clearData(serial, pkg)) {
            ClearDataResult.Success -> CurrentAppEvent.Done("Data cleared for $pkg").also { killed(pkg) }
            is ClearDataResult.Failure -> CurrentAppEvent.Failed("Couldn’t clear data for $pkg — ${r.reason}", pkg)
            ClearDataResult.RejectedDuplicate -> CurrentAppEvent.Failed("$pkg is busy", pkg)
        }
        CurrentAppAction.Uninstall -> when (val r = uninstall.uninstall(serial, pkg)) {
            UninstallResult.Success, is UninstallResult.AlreadyMissing -> CurrentAppEvent.Done("Uninstalled $pkg").also {
                killedPackage = null
                lastApp = null
            }
            is UninstallResult.Failure -> CurrentAppEvent.Failed("Couldn’t uninstall $pkg — ${r.reason}", pkg)
            else -> CurrentAppEvent.Failed("Uninstall of $pkg did not finish", pkg)
        }
    }

    /** Stopped by us: shown as "not running" with Launch until another app comes to the front. */
    private fun killed(pkg: String) {
        killedPackage = pkg
        val display = _state.value.display as? CurrentAppDisplay.App ?: return
        if (display.app.packageName == pkg) {
            _state.update { it.copy(display = display.copy(killed = true, snapshot = display.snapshot.copy(process = null))) }
        }
    }
}
