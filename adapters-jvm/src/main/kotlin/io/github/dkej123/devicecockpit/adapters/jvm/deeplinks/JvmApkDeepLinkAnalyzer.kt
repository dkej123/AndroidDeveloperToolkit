package io.github.dkej123.devicecockpit.adapters.jvm.deeplinks

import io.github.dkej123.devicecockpit.domain.adb.AdbDeviceRequest
import io.github.dkej123.devicecockpit.domain.adb.AdbOperation
import io.github.dkej123.devicecockpit.domain.adb.AdbShellCommand
import io.github.dkej123.devicecockpit.domain.adb.AdbTransport
import io.github.dkej123.devicecockpit.domain.adb.DeviceSerial
import io.github.dkej123.devicecockpit.domain.adb.ShellToken
import io.github.dkej123.devicecockpit.domain.adb.ShellValue
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkAnalysis
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkAnalysisResult
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkAnalyzer
import io.github.dkej123.devicecockpit.domain.deeplinks.DeepLinkCatalog
import io.github.dkej123.devicecockpit.domain.deeplinks.ManifestDeepLinkParser
import io.github.dkej123.devicecockpit.domain.deeplinks.ProjectDeepLinkEnrichment
import io.github.dkej123.devicecockpit.domain.deeplinks.ProjectDeepLinkProvider
import io.github.dkej123.devicecockpit.domain.deeplinks.AppLinkStateParser
import io.github.dkej123.devicecockpit.domain.deeplinks.AssetLinksFetchResult
import io.github.dkej123.devicecockpit.domain.deeplinks.AssetLinksFetcher
import io.github.dkej123.devicecockpit.domain.deeplinks.AssetLinksParser
import io.github.dkej123.devicecockpit.domain.deeplinks.AssetLinksResult
import io.github.dkej123.devicecockpit.domain.process.ByteSink
import io.github.dkej123.devicecockpit.domain.process.ProcessCommand
import io.github.dkej123.devicecockpit.domain.process.ProcessExecutor
import io.github.dkej123.devicecockpit.domain.process.ProcessOutcome
import io.github.dkej123.devicecockpit.domain.process.ProcessRequest
import io.github.dkej123.devicecockpit.domain.process.executeBuffered
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.name
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pulls base/split APKs, asks the SDK tools for their final manifests and keeps only parsed XML. */
class JvmApkDeepLinkAnalyzer(
    private val transport: AdbTransport,
    private val processes: ProcessExecutor,
    private val apkanalyzer: String,
    private val apksigner: String?,
    private val cacheRoot: Path,
    private val projectHash: String,
    private val assetLinksFetcher: AssetLinksFetcher? = null,
    private val projectDeepLinks: ProjectDeepLinkProvider? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : DeepLinkAnalyzer {
    private val cacheVersion = "1"
    override suspend fun analyze(
        serial: DeviceSerial,
        androidUserId: Int,
        packageName: String,
        versionCode: String?,
        lastUpdateTime: String?,
        codePath: String?,
        refresh: Boolean,
    ): DeepLinkAnalysisResult = withContext(Dispatchers.IO) {
        val key = sha256(listOf(cacheVersion, projectHash, serial.value, androidUserId, packageName, versionCode, lastUpdateTime, codePath).joinToString("\u0000"))
        val cache = cacheRoot.resolve(key)
        if (!refresh) return@withContext readCache(cache, packageName)
            ?.let { DeepLinkAnalysisResult.Success(enrich(it, packageName).copy(fromCache = true)) }
            ?: DeepLinkAnalysisResult.NoCache

        val previousRemote = readRemote(cache)
        val pathsResult = transport.executeText(AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(
            literal("pm"), literal("path"), literal("--user"), value(androidUserId.toString()), value(packageName),
        ))))
        val paths = pathsResult.stdout.lineSequence().map(String::trim).filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }.filter { it.startsWith('/') && '\n' !in it && '\r' !in it }.distinct().toList()
        if (paths.isEmpty()) return@withContext DeepLinkAnalysisResult.Failure("pm path returned no APKs for $packageName")

        val temp = Files.createTempDirectory("adb-toolbox-apk-")
        try {
            val manifests = mutableListOf<Pair<String, String>>()
            val localApks = mutableListOf<Path>()
            for ((index, remote) in paths.withIndex()) {
                val local = temp.resolve("$index-${Path.of(remote).name}")
                Files.newOutputStream(local).use { output ->
                    val outcome = transport.executeBinary(AdbDeviceRequest(serial, AdbOperation.Exec(listOf("cat", remote))), ByteSink(output::write))
                    if (outcome !is io.github.dkej123.devicecockpit.domain.adb.AdbOutcome.Completed) {
                        return@withContext DeepLinkAnalysisResult.Failure("Could not pull ${Path.of(remote).name}: $outcome")
                    }
                }
                localApks.add(local)
                val printed = processes.executeBuffered(ProcessRequest(
                    ProcessCommand(apkanalyzer, listOf("manifest", "print", local.toString())), timeout = 30.seconds,
                ), maxBytesPerStream = 8 shl 20)
                val printOutcome = printed.outcome
                if (printOutcome !is ProcessOutcome.Completed || printOutcome.exitCode != 0 || printed.stdout.isBlank()) {
                    return@withContext DeepLinkAnalysisResult.Failure("apkanalyzer failed for ${local.name}: ${printed.stderr.trim()}")
                }
                manifests += local.name to printed.stdout
            }
            val certificates = apksigner?.let { signer -> certificates(signer, localApks.first()) }.orEmpty()
            val catalog = merge(packageName, manifests, clockMillis())
            val linkState = transport.executeText(AdbDeviceRequest(serial, AdbOperation.Shell(AdbShellCommand.of(
                literal("pm"), literal("get-app-links"), literal("--user"), literal("cur"), value(packageName),
            ))))
            val deviceStates = AppLinkStateParser.parse(linkState.stdout).associateBy { it.host }
            val remote = linkedMapOf<String, CachedRemote>()
            val hosts = catalog.targets.flatMap { it.patterns }
                .filter { it.schemes.any { scheme -> scheme == "http" || scheme == "https" } }
                .flatMap { it.hosts }.map { it.removePrefix("*.") }.distinct()
            val verifications = hosts.map { host ->
                val device = deviceStates[host]
                when (val fetched = assetLinksFetcher?.fetch(host)) {
                    is AssetLinksFetchResult.Success -> {
                        when (val parsed = AssetLinksParser.parse(fetched.json, packageName, certificates)) {
                        is AssetLinksResult.Valid -> {
                            val validatedAt = clockMillis()
                            remote[host] = CachedRemote(fetched.json, validatedAt)
                            (device ?: io.github.dkej123.devicecockpit.domain.deeplinks.AppLinkVerification(host,
                                io.github.dkej123.devicecockpit.domain.deeplinks.DeviceLinkState.NONE)).copy(
                                remoteValid = true, dynamicRules = parsed.rules, validatedAtEpochMillis = validatedAt,
                            )
                        }
                        is AssetLinksResult.Invalid -> (device ?: io.github.dkej123.devicecockpit.domain.deeplinks.AppLinkVerification(host,
                            io.github.dkej123.devicecockpit.domain.deeplinks.DeviceLinkState.NONE)).copy(remoteValid = false, error = parsed.reason)
                        }
                    }
                    is AssetLinksFetchResult.Failure -> restoreRemote(host, device, packageName, certificates,
                        previousRemote[host], fetched.message)?.also { restored ->
                            remote[host] = previousRemote.getValue(host).copy(error = fetched.message)
                        } ?: (device ?: io.github.dkej123.devicecockpit.domain.deeplinks.AppLinkVerification(host,
                        io.github.dkej123.devicecockpit.domain.deeplinks.DeviceLinkState.NONE)).copy(error = fetched.message)
                    null -> device ?: io.github.dkej123.devicecockpit.domain.deeplinks.AppLinkVerification(host,
                        io.github.dkej123.devicecockpit.domain.deeplinks.DeviceLinkState.NONE)
                }
            } + deviceStates.values.filter { it.host !in hosts }
            writeCache(cache, manifests, certificates, linkState.stdout, remote)
            pruneCache()
            DeepLinkAnalysisResult.Success(enrich(
                DeepLinkAnalysis(catalog, certificatesSha256 = certificates, verifications = verifications),
                packageName,
            ))
        } finally {
            deleteTree(temp)
        }
    }

    private suspend fun certificates(signer: String, apk: Path): Set<String> {
        val result = processes.executeBuffered(ProcessRequest(
            ProcessCommand(signer, listOf("verify", "--print-certs", apk.toString())), timeout = 30.seconds,
        ))
        val outcome = result.outcome
        if (outcome !is ProcessOutcome.Completed || outcome.exitCode != 0) return emptySet()
        return Regex("""(?im)SHA-256 digest:\s*([0-9a-f:]+)""").findAll(result.stdout)
            .map { it.groupValues[1].replace(":", "").uppercase() }.toSet()
    }

    private fun merge(packageName: String, manifests: List<Pair<String, String>>, now: Long): DeepLinkCatalog {
        val parsed = manifests.map { (source, xml) -> ManifestDeepLinkParser.parse(xml, source) }
        return DeepLinkCatalog(packageName, parsed.flatMap { it.targets }.distinct(), now, manifests.map { it.first })
    }

    private fun enrich(analysis: DeepLinkAnalysis, packageName: String): DeepLinkAnalysis {
        val project = runCatching { projectDeepLinks?.load(packageName) }.getOrNull()
        return analysis.copy(catalog = ProjectDeepLinkEnrichment.merge(analysis.catalog, project))
    }

    private fun readCache(directory: Path, packageName: String): DeepLinkAnalysis? = runCatching {
        if (!Files.isDirectory(directory) || !Files.exists(directory.resolve("complete"))) return null
        val manifests = Files.list(directory).use { stream -> stream.filter { it.fileName.toString().endsWith(".xml") }.sorted().toList() }
            .map { it.fileName.toString().removeSuffix(".xml") to Files.readString(it) }
        if (manifests.isEmpty()) return null
        Files.setLastModifiedTime(directory, java.nio.file.attribute.FileTime.fromMillis(clockMillis()))
        val certs = directory.resolve("certificates").takeIf(Files::exists)?.let { Files.readAllLines(it).toSet() }.orEmpty()
        val deviceStates = directory.resolve("app-links").takeIf(Files::exists)
            ?.let { AppLinkStateParser.parse(Files.readString(it)) }.orEmpty().associateBy { it.host }.toMutableMap()
        readRemote(directory).forEach { (host, cached) ->
                val parsed = AssetLinksParser.parse(cached.json, packageName, certs) as? AssetLinksResult.Valid ?: return@forEach
                deviceStates[host] = (deviceStates[host] ?: io.github.dkej123.devicecockpit.domain.deeplinks.AppLinkVerification(host,
                    io.github.dkej123.devicecockpit.domain.deeplinks.DeviceLinkState.NONE)).copy(
                    remoteValid = true, dynamicRules = parsed.rules, stale = true,
                    validatedAtEpochMillis = cached.validatedAt, error = cached.error,
                )
        }
        DeepLinkAnalysis(merge(packageName, manifests, clockMillis()), certs, deviceStates.values.toList())
    }.getOrNull()

    private fun writeCache(
        directory: Path,
        manifests: List<Pair<String, String>>,
        certificates: Set<String>,
        appLinks: String,
        remote: Map<String, CachedRemote>,
    ) {
        val staging = Files.createTempDirectory(cacheRoot.also(Files::createDirectories), ".staging-")
        try {
            manifests.forEachIndexed { index, (_, xml) -> Files.writeString(staging.resolve("%03d.xml".format(index)), xml) }
            Files.write(staging.resolve("certificates"), certificates)
            Files.writeString(staging.resolve("app-links"), appLinks)
            Files.write(staging.resolve("assetlinks-cache"), remote.map { (host, cached) ->
                val error = cached.error?.let { Base64.getEncoder().encodeToString(it.toByteArray()) }.orEmpty()
                "$host\t${Base64.getEncoder().encodeToString(cached.json.toByteArray())}\t${cached.validatedAt ?: ""}\t$error"
            })
            Files.writeString(staging.resolve("complete"), "1")
            if (Files.exists(directory)) deleteTree(directory)
            Files.move(staging, directory)
        } finally {
            if (Files.exists(staging)) deleteTree(staging)
        }
    }

    private fun pruneCache() {
        val directories = Files.list(cacheRoot).use { it.filter(Files::isDirectory).toList() }
            .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
        directories.drop(100).forEach(::deleteTree)
    }

    private data class CachedRemote(val json: String, val validatedAt: Long? = null, val error: String? = null)

    private fun readRemote(directory: Path): Map<String, CachedRemote> = runCatching {
        val file = directory.resolve("assetlinks-cache")
        if (!Files.exists(file)) return emptyMap()
        Files.readAllLines(file).mapNotNull { line ->
            val fields = line.split('\t')
            val host = fields.getOrNull(0)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val json = fields.getOrNull(1)?.let { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) }
                ?: return@mapNotNull null
            val timestamp = fields.getOrNull(2)?.toLongOrNull()
            val error = fields.getOrNull(3)?.takeIf(String::isNotEmpty)
                ?.let { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) }
            host to CachedRemote(json, timestamp, error)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun restoreRemote(
        host: String,
        device: io.github.dkej123.devicecockpit.domain.deeplinks.AppLinkVerification?,
        packageName: String,
        certificates: Set<String>,
        cached: CachedRemote?,
        error: String,
    ): io.github.dkej123.devicecockpit.domain.deeplinks.AppLinkVerification? {
        val stored = cached ?: return null
        val parsed = AssetLinksParser.parse(stored.json, packageName, certificates) as? AssetLinksResult.Valid ?: return null
        return (device ?: io.github.dkej123.devicecockpit.domain.deeplinks.AppLinkVerification(host,
            io.github.dkej123.devicecockpit.domain.deeplinks.DeviceLinkState.NONE)).copy(
            remoteValid = true,
            dynamicRules = parsed.rules,
            validatedAtEpochMillis = stored.validatedAt,
            stale = true,
            error = error,
        )
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun literal(text: String) = ShellToken.Literal(text)
    private fun value(text: String) = ShellToken.Value(ShellValue.of(text))
}
