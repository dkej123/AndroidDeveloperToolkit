package dev.acme.adbtoolbox.intellij.composition

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import dev.acme.adbtoolbox.adapters.adb.binary.BinaryAdbTransport
import dev.acme.adbtoolbox.adapters.adb.ddmlib.DdmlibAdbTransport
import dev.acme.adbtoolbox.adapters.adb.device.AdbDeviceRepository
import dev.acme.adbtoolbox.adapters.adb.device.DdmlibDeviceChangeListenerSource
import dev.acme.adbtoolbox.adapters.adb.packages.AdbPackageRepository
import dev.acme.adbtoolbox.adapters.adb.discovery.DefaultToolLocator
import dev.acme.adbtoolbox.adapters.jvm.capture.DesktopRevealInFileManager
import dev.acme.adbtoolbox.adapters.jvm.capture.SettingsBackedCaptureDestination
import dev.acme.adbtoolbox.adapters.jvm.discovery.EnvironmentAndroidSdkPlatformToolsSource
import dev.acme.adbtoolbox.adapters.jvm.discovery.JvmExecutableFileProbe
import dev.acme.adbtoolbox.adapters.jvm.discovery.JvmHostPlatformProvider
import dev.acme.adbtoolbox.adapters.jvm.discovery.JvmPathEnvironmentSource
import dev.acme.adbtoolbox.adapters.jvm.process.JvmProcessExecutor
import dev.acme.adbtoolbox.adapters.jvm.discovery.SettingsBackedConfiguredToolPathSource
import dev.acme.adbtoolbox.adapters.jvm.settings.JvmDirectoryProbe
import dev.acme.adbtoolbox.application.apps.AppLifecycleUseCase
import dev.acme.adbtoolbox.application.apps.AppLifecycleViewModel
import dev.acme.adbtoolbox.application.apps.AppsViewModel
import dev.acme.adbtoolbox.application.apps.ClearDataUseCase
import dev.acme.adbtoolbox.application.apps.ClearDataViewModel
import dev.acme.adbtoolbox.application.apps.SelectedPackageViewModel
import dev.acme.adbtoolbox.application.apps.UninstallUseCase
import dev.acme.adbtoolbox.application.apps.UninstallViewModel
import dev.acme.adbtoolbox.application.capture.CaptureScreenshotUseCase
import dev.acme.adbtoolbox.application.capture.CaptureViewModel
import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.application.devicebar.DeviceBarViewModel
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsUseCase
import dev.acme.adbtoolbox.application.deviceactions.DeviceActionsViewModel
import dev.acme.adbtoolbox.application.deviceactions.OpenShellUseCase
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewModel
import dev.acme.adbtoolbox.application.devicefacts.LoadDeviceFactsUseCase
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.mirroring.MirroringOptionsUseCase
import dev.acme.adbtoolbox.application.mirroring.MirroringOptionsViewModel
import dev.acme.adbtoolbox.application.mirroring.MirroringSessionManager
import dev.acme.adbtoolbox.application.mirroring.MirroringViewModel
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.application.recording.RecordingSessionManager
import dev.acme.adbtoolbox.application.recording.RecordingViewModel
import dev.acme.adbtoolbox.application.shell.ShellViewModel
import dev.acme.adbtoolbox.application.settings.SettingsUseCase
import dev.acme.adbtoolbox.application.settings.SettingsViewModel
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.apps.SelectedPackagePersistence
import dev.acme.adbtoolbox.domain.capture.CaptureDestination
import dev.acme.adbtoolbox.domain.capture.FileNamePolicy
import dev.acme.adbtoolbox.domain.capture.RevealInFileManager
import dev.acme.adbtoolbox.domain.capture.TimestampFileNamePolicy
import dev.acme.adbtoolbox.domain.device.DeviceListRefresher
import dev.acme.adbtoolbox.domain.device.DeviceRepository
import dev.acme.adbtoolbox.domain.device.DeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.deviceactions.TerminalLauncher
import dev.acme.adbtoolbox.domain.mirroring.MirroringOptionsRepository
import dev.acme.adbtoolbox.domain.devicefacts.ClipboardPort
import dev.acme.adbtoolbox.domain.discovery.ToolLocator
import dev.acme.adbtoolbox.domain.discovery.ToolId
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.MutableNavigationBadges
import dev.acme.adbtoolbox.domain.nav.NavigationBadges
import dev.acme.adbtoolbox.domain.nav.NavigationPersistence
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.domain.packages.PackageRepository
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.domain.time.SystemMonotonicClock
import dev.acme.adbtoolbox.domain.settings.SettingsDependency
import dev.acme.adbtoolbox.domain.settings.SettingsInvalidationPort
import dev.acme.adbtoolbox.domain.settings.SettingsRepository
import dev.acme.adbtoolbox.intellij.adb.IdeAndroidDebugBridgeDeviceSource
import dev.acme.adbtoolbox.intellij.apps.ClearDataConfirmationPresenter
import dev.acme.adbtoolbox.intellij.apps.UninstallConfirmationPresenter
import dev.acme.adbtoolbox.intellij.clipboard.ClipboardPortAdapter
import dev.acme.adbtoolbox.intellij.discovery.AndroidStudioSdkPlatformToolsSource
import dev.acme.adbtoolbox.intellij.dispatch.IdeDispatcherProvider
import dev.acme.adbtoolbox.intellij.persistence.AdbToolboxProjectState
import dev.acme.adbtoolbox.intellij.persistence.AppsSelectionPersistenceAdapter
import dev.acme.adbtoolbox.intellij.persistence.DeviceSelectionPersistenceAdapter
import dev.acme.adbtoolbox.intellij.persistence.MirroringOptionsPersistenceAdapter
import dev.acme.adbtoolbox.intellij.persistence.NavigationPersistenceAdapter
import dev.acme.adbtoolbox.intellij.persistence.SettingsPersistenceAdapter
import dev.acme.adbtoolbox.intellij.terminal.TerminalLauncherAdapter
import dev.acme.adbtoolbox.intellij.wifi.WifiPairingInputPresenter
import dev.acme.adbtoolbox.application.wifi.WifiPairingUseCase
import dev.acme.adbtoolbox.application.wifi.WifiPairingViewModel
import dev.acme.adbtoolbox.application.wifi.WifiPairingIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive

private val ANDROID_PLUGIN_ID = PluginId.getId("org.jetbrains.android")
private val TERMINAL_PLUGIN_ID = PluginId.getId("org.jetbrains.plugins.terminal")

/**
 * The `:intellij` composition root (task 007, ADR 0001): the one place `:application` use cases
 * are wired to `:adapters-jvm`/`:adapters-adb` implementations. A light [Service] (no `plugin.xml`
 * registration needed) so IntelliJ creates and disposes exactly one instance per project,
 * automatically calling [dispose] when the project closes since this class implements [Disposable].
 *
 * Owns the single project-scoped [CoroutineScope] ADR 0004 requires: tied to this service's own
 * disposal (never a bare `GlobalScope`), with each feature (today, only [shellViewModel]) getting
 * its own [SupervisorJob] child scope via [childScope] so one feature's failure never cancels
 * another's or the project scope itself.
 */
@Service(Service.Level.PROJECT)
class AdbToolboxProjectService(private val project: Project) : Disposable {

    val dispatcherProvider: DispatcherProvider = IdeDispatcherProvider()

    private val projectScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)

    val processExecutor: ProcessExecutor = JvmProcessExecutor()

    val settingsRepository: SettingsRepository =
        SettingsPersistenceAdapter(project.service<AdbToolboxProjectState>())

    private val executableFileProbe = JvmExecutableFileProbe()

    val toolLocator: ToolLocator = DefaultToolLocator(
        configuredPathSource = SettingsBackedConfiguredToolPathSource(settingsRepository),
        androidSdkSources = listOf(
            EnvironmentAndroidSdkPlatformToolsSource(),
            AndroidStudioSdkPlatformToolsSource(),
        ),
        pathEnvironmentSource = JvmPathEnvironmentSource(),
        executableProbe = executableFileProbe,
        hostPlatformProvider = JvmHostPlatformProvider(),
        processExecutor = processExecutor,
    )

    private val settingsInvalidation = SettingsInvalidationPort { changed ->
        if (SettingsDependency.AdbPath in changed) toolLocator.invalidate(ToolId.Adb)
        if (SettingsDependency.ScrcpyPath in changed) toolLocator.invalidate(ToolId.Scrcpy)
        // Capture reads the repository for each new target, so it has no cache to invalidate.
        // The Logcat runtime is composed by its own feature task and consumes the persisted size.
    }

    internal val settingsUseCase = SettingsUseCase(
        repository = settingsRepository,
        executableProbe = executableFileProbe,
        directoryProbe = JvmDirectoryProbe(),
        invalidation = settingsInvalidation,
    )

    val settingsViewModel: SettingsViewModel = SettingsViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        settings = settingsUseCase,
    )

    /**
     * Whether the Android plugin is both installed and enabled — checked once here at composition,
     * per ADR 0005, never per ADB call.
     */
    val androidPluginPresent: Boolean = PluginManagerCore.getPlugin(ANDROID_PLUGIN_ID)?.isEnabled == true

    // DdmlibAdbTransport (and the IdeAndroidDebugBridgeDeviceSource it wraps) must only ever be
    // constructed when androidPluginPresent is true: both types have compileOnly(libs.ddmlib)
    // constructor/method signatures (see the build.gradle.kts comments), so class-loading either
    // one when the Android plugin — the only runtime provider of ddmlib's classes — is absent would
    // risk NoClassDefFoundError instead of the safe binary-transport fallback ADR 0005 requires.
    // selectAdbTransport takes already-constructed transports for its own testability (no IntelliJ
    // Platform API needed to unit-test it), so that eager construction is kept out of its call here.
    private val binaryTransport: AdbTransport = BinaryAdbTransport(toolLocator, processExecutor)

    val adbTransport: AdbTransport = if (androidPluginPresent) {
        selectAdbTransport(
            androidPluginPresent = true,
            ddmlibTransport = DdmlibAdbTransport(IdeAndroidDebugBridgeDeviceSource(project)),
            binaryTransport = binaryTransport,
        )
    } else {
        binaryTransport
    }

    val shellViewModel: ShellViewModel = ShellViewModel(scope = childScope(), dispatchers = dispatcherProvider)

    // Static/global ddmlib hotplug listener (docs/adr/0005) — only ever registered when the
    // Android plugin (the sole provider of ddmlib's classes) is present, same guard as adbTransport
    // above. Absent it, AdbDeviceRepository still discovers devices via its own poll ticker alone.
    private val adbDeviceRepository: AdbDeviceRepository = AdbDeviceRepository(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        binaryTransport = binaryTransport,
        changeSignals = if (androidPluginPresent) DdmlibDeviceChangeListenerSource().events() else emptyFlow(),
    )
    val deviceRepository: DeviceRepository = adbDeviceRepository

    /** Task 011's manual "refresh now" trigger — [adbDeviceRepository] itself implements it (ADR 0005). */
    val deviceListRefresher: DeviceListRefresher = adbDeviceRepository

    val deviceSelectionPersistence: DeviceSelectionPersistence =
        DeviceSelectionPersistenceAdapter(project.service<AdbToolboxProjectState>())

    val selectedDeviceViewModel: SelectedDeviceViewModel = SelectedDeviceViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        deviceRepository = deviceRepository,
        persistence = deviceSelectionPersistence,
    )

    /** Task 011's device bar and picker presenter — reads/writes selection via [selectedDeviceViewModel]. */
    val deviceBarViewModel: DeviceBarViewModel = DeviceBarViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        deviceRepository = deviceRepository,
        selectedDeviceViewModel = selectedDeviceViewModel,
        refresher = deviceListRefresher,
    )

    val navigationPersistence: NavigationPersistence =
        NavigationPersistenceAdapter(project.service<AdbToolboxProjectState>())

    val navigationViewModel: NavigationViewModel = NavigationViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        persistence = navigationPersistence,
        defaultViewId = ViewId.Device,
    )

    /**
     * The task 012 badge aggregate (`design/README.md` §2's rail badge dot): one shared instance,
     * composed here so both the navigation rail (reads [NavigationBadges.state] — a later
     * presentation task, 043+) and each feature's own ViewModel (writes via
     * [MutableNavigationBadges.set] from its own file) reach the same map without either one
     * editing this composition root again.
     */
    val navigationBadges: NavigationBadges = MutableNavigationBadges()

    /** The task 013 non-modal feedback/status/toast channel — one instance shared by every feature. */
    val feedbackViewModel: FeedbackViewModel = FeedbackViewModel(scope = childScope(), dispatchers = dispatcherProvider)

    private val wifiPairingInputPresenter = WifiPairingInputPresenter(project, dispatcherProvider)
    private val wifiPairingUseCase = WifiPairingUseCase(adbTransport, deviceListRefresher)

    /**
     * Task 039's device-bar-triggered Wi-Fi pairing flow: collects input via a native dialog
     * ([wifiPairingInputPresenter]), pairs and connects (never targeting a selected device — ADR
     * 0005's server-scoped request), and refreshes [deviceListRefresher] on success.
     */
    val wifiPairingViewModel: WifiPairingViewModel = WifiPairingViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        inputPort = wifiPairingInputPresenter,
        useCase = wifiPairingUseCase,
        feedback = feedbackViewModel,
    )

    // Task 011's "Pair device over Wi-Fi…" picker footer link only emits an event
    // (DeviceBarViewModel.pairOverWifiRequests) — this is the one place that event is wired to
    // actually launching task 039's flow, mirroring settingsInvalidation's plain-callback wiring
    // above rather than a dedicated Coordinator (there is no panel for this feature to mount into).
    private val wifiPairingLauncher = deviceBarViewModel.pairOverWifiRequests
        .onEach { wifiPairingViewModel.handle(WifiPairingIntent.Launch) }
        .launchIn(childScope())

    /** Task 015's clipboard port adapter (`com.intellij.openapi.ide.CopyPasteManager`) — the "Copy report" action's platform seam. */
    val clipboardPort: ClipboardPort = ClipboardPortAdapter()

    private val loadDeviceFactsUseCase = LoadDeviceFactsUseCase(adbTransport)

    /** Task 015's Device-view facts section: android/resolution/density/battery/abi/uptime, driven by [selectedDeviceViewModel]. */
    val deviceFactsViewModel: DeviceFactsViewModel = DeviceFactsViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        selectedDeviceState = selectedDeviceViewModel.state,
        loadDeviceFacts = loadDeviceFactsUseCase,
        clipboard = clipboardPort,
    )

    /** Task 019's platform ports: a JVM filesystem capture destination and a Desktop Reveal action. */
    val captureDestination: CaptureDestination = SettingsBackedCaptureDestination(settingsRepository)
    val revealInFileManager: RevealInFileManager = DesktopRevealInFileManager()
    private val fileNamePolicy: FileNamePolicy = TimestampFileNamePolicy()

    private val captureScreenshotUseCase = CaptureScreenshotUseCase(
        adbTransport = adbTransport,
        captureDestination = captureDestination,
        fileNamePolicy = fileNamePolicy,
    )

    /** Task 019's minimal Device-view screenshot binding, driven by [selectedDeviceViewModel]. */
    val captureViewModel: CaptureViewModel = CaptureViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        selectedDeviceState = selectedDeviceViewModel.state,
        captureScreenshotUseCase = captureScreenshotUseCase,
        revealInFileManager = revealInFileManager,
        feedback = feedbackViewModel,
    )

    /**
     * Whether the Terminal plugin is both installed and enabled — checked once here at
     * composition, per ADR 0007, never per Open-shell call. Terminal is bundled with every
     * IntelliJ Platform install this module targets (unlike the optional Android plugin above),
     * but a user can still disable it, so [terminalLauncher] must not assume presence.
     */
    val terminalPluginPresent: Boolean = PluginManagerCore.getPlugin(TERMINAL_PLUGIN_ID)?.isEnabled == true

    /** Task 016/ADR 0007's Open-shell platform adapter. */
    val terminalLauncher: TerminalLauncher = TerminalLauncherAdapter(
        project = project,
        dispatchers = dispatcherProvider,
        terminalPluginPresent = terminalPluginPresent,
    )

    private val deviceActionsUseCase = DeviceActionsUseCase(adbTransport)
    private val openShellUseCase = OpenShellUseCase(toolLocator, terminalLauncher)

    /** Task 016's minimal Device-view Reboot/Open-shell/Wake binding, driven by [selectedDeviceViewModel]. */
    val deviceActionsViewModel: DeviceActionsViewModel = DeviceActionsViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        selectedDeviceState = selectedDeviceViewModel.state,
        deviceActionsUseCase = deviceActionsUseCase,
        openShellUseCase = openShellUseCase,
        feedback = feedbackViewModel,
    )

    /** Task 017's per-serial scrcpy mirroring session lifecycle, independent of any UI. */
    val mirroringSessionManager: MirroringSessionManager = MirroringSessionManager(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        toolLocator = toolLocator,
        processExecutor = processExecutor,
    )

    /** Task 040's persisted mirroring-options repository/use case, project-scoped (ADR 0006), matching
     * [settingsRepository]/[settingsUseCase]'s own adapter-behind-a-port shape. */
    val mirroringOptionsRepository: MirroringOptionsRepository =
        MirroringOptionsPersistenceAdapter(project.service<AdbToolboxProjectState>())

    internal val mirroringOptionsUseCase = MirroringOptionsUseCase(repository = mirroringOptionsRepository)

    /** Task 040's native options editor state, backing [dev.acme.adbtoolbox.intellij.ui.mirroring.MirroringOptionsDialog]
     * and the sole source [mirroringViewModel] reads from at `start()` time via [MirroringOptionsViewModel.currentOptions]. */
    val mirroringOptionsViewModel: MirroringOptionsViewModel = MirroringOptionsViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        options = mirroringOptionsUseCase,
    )

    /**
     * Task 018's Device-view mirroring binding and global-shortcut action, driven by
     * [selectedDeviceViewModel] and [mirroringSessionManager]; missing-tool errors route to
     * [navigationViewModel] ([ViewId.Settings]) and every other error/external-exit routes through
     * [feedbackViewModel]. Task 040's [mirroringOptionsViewModel] supplies the options a fresh
     * `start()` reads — never an already-running session's.
     */
    val mirroringViewModel: MirroringViewModel = MirroringViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        selectedDeviceState = selectedDeviceViewModel.state,
        sessionManager = mirroringSessionManager,
        feedback = feedbackViewModel,
        navigation = navigationViewModel,
        currentOptions = { mirroringOptionsViewModel.currentOptions.value },
    )

    /**
     * Task 020's per-serial remote `screenrecord` session lifecycle, independent of any UI. Reuses
     * task 019's [captureDestination] platform port verbatim (the same local-save destination
     * screenshots commit to) and a second, MP4-suffixed [TimestampFileNamePolicy] instance — the same
     * `screen-<ts>` naming scheme, just `extension = "mp4"` — rather than duplicating either port.
     */
    private val recordingFileNamePolicy: FileNamePolicy = TimestampFileNamePolicy(extension = "mp4")

    val recordingSessionManager: RecordingSessionManager = RecordingSessionManager(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        adbTransport = adbTransport,
        captureDestination = captureDestination,
        fileNamePolicy = recordingFileNamePolicy,
        monotonicClock = SystemMonotonicClock,
    )

    /**
     * Task 020's Device-view recording toggle, driven by [selectedDeviceViewModel] and
     * [recordingSessionManager]; every error/save routes through [feedbackViewModel] (task 013), the
     * same channel [captureViewModel]/[mirroringViewModel] already use.
     */
    val recordingViewModel: RecordingViewModel = RecordingViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        selectedDeviceState = selectedDeviceViewModel.state,
        sessionManager = recordingSessionManager,
        revealInFileManager = revealInFileManager,
        feedback = feedbackViewModel,
        monotonicClock = SystemMonotonicClock,
    )

    /** Task 022's persisted selected-package contract adapter — the same `AppsSelectionState` slice `:intellij`'s Apps feature reads/writes. */
    val selectedPackagePersistence: SelectedPackagePersistence =
        AppsSelectionPersistenceAdapter(project.service<AdbToolboxProjectState>())

    /** Task 022's shared selected-package contract, consumed by [appLifecycleViewModel] and (once wired) the Apps view/Logcat's default package filter. */
    val selectedPackageViewModel: SelectedPackageViewModel = SelectedPackageViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        persistence = selectedPackagePersistence,
    )

    /** Task 021's serial-scoped package repository, shared by Apps and post-action refreshes. */
    val packageRepository: PackageRepository = AdbPackageRepository(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        transport = adbTransport,
    )

    /** Task 022's searchable package presentation, composed once per project. */
    val appsViewModel: AppsViewModel = AppsViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        packageRepository = packageRepository,
        selectedDeviceViewModel = selectedDeviceViewModel,
        selectedPackageViewModel = selectedPackageViewModel,
    )

    private val appLifecycleUseCase = AppLifecycleUseCase(adbTransport)

    /**
     * Task 023's Force-stop/Launch/Restart binding, driven by [selectedDeviceViewModel] and
     * [selectedPackageViewModel]: both [dev.acme.adbtoolbox.intellij.apps.AppsCoordinator]'s panel
     * buttons and [dev.acme.adbtoolbox.intellij.apps.RestartAppAction]'s global shortcut forward to
     * this exact same shared instance, so they can never diverge into two different restart code
     * paths.
     */
    val appLifecycleViewModel: AppLifecycleViewModel = AppLifecycleViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        selectedDeviceState = selectedDeviceViewModel.state,
        selectedPackageState = selectedPackageViewModel.state,
        appLifecycleUseCase = appLifecycleUseCase,
        feedback = feedbackViewModel,
    )

    private val clearDataConfirmation = ClearDataConfirmationPresenter(project, dispatcherProvider)
    private val clearDataUseCase = ClearDataUseCase(adbTransport)

    /** Task 024's single confirm-before-command Clear-data workflow. */
    val clearDataViewModel: ClearDataViewModel = ClearDataViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        selectedDeviceState = selectedDeviceViewModel.state,
        selectedPackageState = selectedPackageViewModel.state,
        currentPackageScope = appsViewModel.currentPackageScope,
        clearDataUseCase = clearDataUseCase,
        confirmationPort = clearDataConfirmation,
        packageRepository = packageRepository,
        feedback = feedbackViewModel,
    )

    private val uninstallConfirmation = UninstallConfirmationPresenter(project, dispatcherProvider)
    private val uninstallUseCase = UninstallUseCase(adbTransport)

    /** Task 025's stale-context-safe, confirm-before-command uninstall workflow. */
    val uninstallViewModel: UninstallViewModel = UninstallViewModel(
        scope = childScope(),
        dispatchers = dispatcherProvider,
        selectedDeviceState = selectedDeviceViewModel.state,
        selectedPackageState = selectedPackageViewModel.state,
        currentPackageScope = appsViewModel.currentPackageScope,
        uninstallUseCase = uninstallUseCase,
        confirmationPort = uninstallConfirmation,
        packageRepository = packageRepository,
        selectedPackageViewModel = selectedPackageViewModel,
        feedback = feedbackViewModel,
    )

    /**
     * A feature-local child scope (ADR 0004): its [SupervisorJob] is a real structured-concurrency
     * child of [projectScope]'s job (not merely a fresh, detached one — `parentScope +
     * SupervisorJob()` without a parent argument would silently drop the link and leave the child
     * running after the project scope is cancelled), so disposing this service tears every child
     * scope down too, while one feature's own failure/cancellation never propagates back up to
     * [projectScope] or sideways to another feature's scope.
     */
    fun childScope(): CoroutineScope =
        CoroutineScope(projectScope.coroutineContext + SupervisorJob(parent = projectScope.coroutineContext[Job]))

    override fun dispose() {
        // feedbackViewModel.dispose() is distinct from cancelling projectScope (ADR 0004's scope
        // ownership alone does not reject an in-flight handle() call — see FeedbackViewModel's
        // class doc), so it is disposed explicitly here alongside the scope it is scoped under.
        feedbackViewModel.dispose()
        wifiPairingViewModel.dispose()
        // CoroutineScope.cancel() throws if already cancelled, which would make a second dispose
        // (project close after an earlier explicit dispose, or IntelliJ's own double-dispose
        // guards firing) blow up instead of being a safe no-op.
        if (projectScope.isActive) {
            projectScope.cancel()
        }
    }
}
