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
import dev.acme.adbtoolbox.adapters.adb.discovery.DefaultToolLocator
import dev.acme.adbtoolbox.adapters.jvm.discovery.EnvironmentAndroidSdkPlatformToolsSource
import dev.acme.adbtoolbox.adapters.jvm.discovery.InMemoryConfiguredToolPathSource
import dev.acme.adbtoolbox.adapters.jvm.discovery.JvmExecutableFileProbe
import dev.acme.adbtoolbox.adapters.jvm.discovery.JvmHostPlatformProvider
import dev.acme.adbtoolbox.adapters.jvm.discovery.JvmPathEnvironmentSource
import dev.acme.adbtoolbox.adapters.jvm.process.JvmProcessExecutor
import dev.acme.adbtoolbox.application.device.SelectedDeviceViewModel
import dev.acme.adbtoolbox.application.devicebar.DeviceBarViewModel
import dev.acme.adbtoolbox.application.devicefacts.DeviceFactsViewModel
import dev.acme.adbtoolbox.application.devicefacts.LoadDeviceFactsUseCase
import dev.acme.adbtoolbox.application.feedback.FeedbackViewModel
import dev.acme.adbtoolbox.application.nav.NavigationViewModel
import dev.acme.adbtoolbox.application.shell.ShellViewModel
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.device.DeviceListRefresher
import dev.acme.adbtoolbox.domain.device.DeviceRepository
import dev.acme.adbtoolbox.domain.device.DeviceSelectionPersistence
import dev.acme.adbtoolbox.domain.devicefacts.ClipboardPort
import dev.acme.adbtoolbox.domain.discovery.ToolLocator
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.nav.MutableNavigationBadges
import dev.acme.adbtoolbox.domain.nav.NavigationBadges
import dev.acme.adbtoolbox.domain.nav.NavigationPersistence
import dev.acme.adbtoolbox.domain.nav.ViewId
import dev.acme.adbtoolbox.domain.process.ProcessExecutor
import dev.acme.adbtoolbox.intellij.adb.IdeAndroidDebugBridgeDeviceSource
import dev.acme.adbtoolbox.intellij.clipboard.ClipboardPortAdapter
import dev.acme.adbtoolbox.intellij.discovery.AndroidStudioSdkPlatformToolsSource
import dev.acme.adbtoolbox.intellij.dispatch.IdeDispatcherProvider
import dev.acme.adbtoolbox.intellij.persistence.AdbToolboxProjectState
import dev.acme.adbtoolbox.intellij.persistence.DeviceSelectionPersistenceAdapter
import dev.acme.adbtoolbox.intellij.persistence.NavigationPersistenceAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive

private val ANDROID_PLUGIN_ID = PluginId.getId("org.jetbrains.android")

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

    val toolLocator: ToolLocator = DefaultToolLocator(
        configuredPathSource = InMemoryConfiguredToolPathSource(),
        androidSdkSources = listOf(
            EnvironmentAndroidSdkPlatformToolsSource(),
            AndroidStudioSdkPlatformToolsSource(),
        ),
        pathEnvironmentSource = JvmPathEnvironmentSource(),
        executableProbe = JvmExecutableFileProbe(),
        hostPlatformProvider = JvmHostPlatformProvider(),
        processExecutor = processExecutor,
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
        // CoroutineScope.cancel() throws if already cancelled, which would make a second dispose
        // (project close after an earlier explicit dispose, or IntelliJ's own double-dispose
        // guards firing) blow up instead of being a safe no-op.
        if (projectScope.isActive) {
            projectScope.cancel()
        }
    }
}
