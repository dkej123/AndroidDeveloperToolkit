package dev.acme.adbtoolbox.adapters.adb.packages

import dev.acme.adbtoolbox.domain.adb.AdbOutcome
import dev.acme.adbtoolbox.domain.adb.AdbParseResult
import dev.acme.adbtoolbox.domain.adb.AdbTransport
import dev.acme.adbtoolbox.domain.adb.DeviceSerial
import dev.acme.adbtoolbox.domain.dispatch.DispatcherProvider
import dev.acme.adbtoolbox.domain.packages.PackageEntry
import dev.acme.adbtoolbox.domain.packages.PackageListCommand
import dev.acme.adbtoolbox.domain.packages.PackageListScope
import dev.acme.adbtoolbox.domain.packages.PackageListState
import dev.acme.adbtoolbox.domain.packages.PackageMetadataCommand
import dev.acme.adbtoolbox.domain.packages.PackageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private data class RefreshRequest(val serial: DeviceSerial, val scope: PackageListScope)

private const val DEFAULT_MAX_CONCURRENT_ENRICHMENT = 4

/**
 * The `:adapters-adb` [dev.acme.adbtoolbox.domain.packages.PackageRepository] (task 021): parses
 * `pm list packages` into an immediate, fallback-labeled [PackageListState.Content] snapshot, then
 * fills in each entry's label, icon and debuggable flag from the on-device [appInfoHelper] (ADR
 * 0007) in one run, republishing [state] once when it finishes. Packages the helper
 * does not report (or every package, when it is unavailable) fall back to a bounded-concurrency
 * `dumpsys package <pkg>` pipeline (never an unbounded serial N+1 scan), which yields the
 * debuggable flag but no label.
 *
 * [refresh] requests are collected with `collectLatest` (mirrors
 * [dev.acme.adbtoolbox.adapters.adb.device.AdbDeviceRepository]): a new call — for the same or a
 * different serial — cancels any in-flight list fetch or enrichment fan-out, so a slow response for
 * a superseded serial/scope can never land in [state] (stale-result suppression is structural, not
 * a manual staleness check).
 *
 * [scope] is owned by the caller (ADR 0004): the collector loop this class launches is a plain
 * child coroutine of [scope], so cancelling it tears the loop, and any in-flight enrichment fan-out,
 * down with no separate disposal step.
 */
class AdbPackageRepository(
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    private val transport: AdbTransport,
    private val maxConcurrentEnrichment: Int = DEFAULT_MAX_CONCURRENT_ENRICHMENT,
    private val appInfoHelper: AppInfoHelper? = null,
) : PackageRepository {

    private val _state = MutableStateFlow<PackageListState>(PackageListState.Loading)
    override val state: StateFlow<PackageListState> = _state.asStateFlow()

    // replay = 1, not extraBufferCapacity: a refresh() call that lands before the collector
    // coroutine below has actually started running (a real possibility — scope.launch merely
    // schedules it) must still be delivered once that collector subscribes. extraBufferCapacity
    // alone only helps an already-subscribed slow collector; it is not replayed to a collector
    // that subscribes afterward.
    private val requests = MutableSharedFlow<RefreshRequest>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val stateLock = Mutex()

    /**
     * Metadata already fetched per device, so a refresh (views re-read the device whenever the user
     * returns to them) only runs `dumpsys package` for packages it has not seen. Entries for
     * packages that disappear from the list are dropped. Only successful lookups are kept, so a
     * transient failure is retried on the next refresh.
     */
    private val enrichedCache = mutableMapOf<DeviceSerial, MutableMap<String, PackageEntry>>()

    init {
        scope.launch(dispatchers.io) {
            requests.collectLatest { request -> performRefresh(request) }
        }
    }

    override fun refresh(serial: DeviceSerial, scope: PackageListScope) {
        check(requests.tryEmit(RefreshRequest(serial, scope)))
    }

    private suspend fun performRefresh(request: RefreshRequest) {
        val listResult = transport.executeText(PackageListCommand.request(request.serial, request.scope))
        val outcome = listResult.outcome
        if (outcome !is AdbOutcome.Completed || (outcome.exitCode != null && outcome.exitCode != 0)) {
            _state.value = PackageListState.Error(
                serial = request.serial,
                scope = request.scope,
                message = describe(outcome),
            )
            return
        }

        val names = PackageListCommand.distinctPackageNames(listResult)
        val known = stateLock.withLock {
            enrichedCache.getOrPut(request.serial) { mutableMapOf() }.also { cache ->
                // Only prune on a full listing: a user-scoped list omits system packages, whose
                // metadata is still valid for the next all-packages listing.
                if (request.scope == PackageListScope.All) cache.keys.retainAll(names.toSet())
            }.toMap()
        }
        val entries = names.associateWith { known[it] ?: PackageEntry.unresolved(it) }
        publish(request, entries)

        enrich(request, entries, toFetch = names.filterNot(known::containsKey))
    }

    private suspend fun enrich(request: RefreshRequest, initialEntries: Map<String, PackageEntry>, toFetch: List<String>) {
        var entries = initialEntries
        val remaining = toFetch.toMutableSet()
        if (appInfoHelper != null && toFetch.isNotEmpty()) {
            var resolvedAny = false
            appInfoHelper.query(request.serial, toFetch) { info ->
                val entry = PackageEntry(
                    packageName = info.packageName,
                    label = info.label ?: info.packageName,
                    labelResolved = info.label != null,
                    isDebuggable = info.isDebuggable,
                    icon = info.icon,
                )
                stateLock.withLock {
                    // The helper may report more apps than asked for; they are cached for later lists.
                    enrichedCache.getOrPut(request.serial) { mutableMapOf() }[info.packageName] = entry
                    if (remaining.remove(info.packageName)) {
                        entries = entries + (info.packageName to entry)
                        resolvedAny = true
                    }
                }
            }
            // Published once, not per record: rows are sorted by label, so each publish can move
            // rows under the user's pointer while they are trying to click one.
            if (resolvedAny) stateLock.withLock { publish(request, entries) }
        }

        val semaphore = Semaphore(maxConcurrentEnrichment)
        coroutineScope {
            toFetch.filter(remaining::contains).forEach { packageName ->
                launch {
                    val enriched = semaphore.withPermit { fetchMetadata(request.serial, packageName) }
                    stateLock.withLock {
                        entries = entries + (packageName to enriched)
                        if (enriched.labelResolved || enriched.isDebuggable != null) {
                            enrichedCache.getOrPut(request.serial) { mutableMapOf() }[packageName] = enriched
                        }
                        publish(request, entries)
                    }
                }
            }
        }
    }

    private suspend fun fetchMetadata(serial: DeviceSerial, packageName: String): PackageEntry {
        val result = transport.executeText(PackageMetadataCommand.request(serial, packageName))
        val outcome = result.outcome
        if (outcome !is AdbOutcome.Completed || (outcome.exitCode != null && outcome.exitCode != 0)) {
            return PackageEntry.unresolved(packageName)
        }

        val parsed = PackageMetadataCommand.parse(packageName, result)
        if (parsed !is AdbParseResult.Parsed) {
            return PackageEntry.unresolved(packageName)
        }

        val label = parsed.value.label
        return PackageEntry(
            packageName = packageName,
            label = label ?: packageName,
            labelResolved = label != null,
            isDebuggable = parsed.value.isDebuggable,
        )
    }

    private fun publish(request: RefreshRequest, entries: Map<String, PackageEntry>) {
        _state.value = PackageListState.Content(
            serial = request.serial,
            scope = request.scope,
            packages = entries.values.sortedWith(
                compareBy({ it.label.lowercase() }, { it.packageName }),
            ),
        )
    }

    private fun describe(outcome: AdbOutcome): String = when (outcome) {
        is AdbOutcome.Completed -> "pm list packages exited with code ${outcome.exitCode}"
        AdbOutcome.TimedOut -> "pm list packages timed out"
        AdbOutcome.Cancelled -> "pm list packages was cancelled"
        is AdbOutcome.TransportFailure -> outcome.reason
        is AdbOutcome.Unsupported -> outcome.reason
    }
}
