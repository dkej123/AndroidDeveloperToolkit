package dev.acme.adbtoolbox.domain.deeplinks

enum class DeepLinkSource { APK, PROJECT, DYNAMIC, RUNTIME_UNKNOWN }
enum class DeepLinkTargetKind { ACTIVITY, ACTIVITY_ALIAS }
enum class ParameterLocation { PATH, QUERY, FRAGMENT }
enum class ParameterRequirement { REQUIRED, OPTIONAL, UNKNOWN }

data class DeepLinkParameter(
    val name: String,
    val location: ParameterLocation,
    val type: String? = null,
    val source: DeepLinkSource,
    val requirement: ParameterRequirement,
    val defaultValue: String? = null,
    val nullable: Boolean = false,
)

enum class UriMatcherKind {
    PATH_LITERAL, PATH_PREFIX, PATH_SUFFIX, PATH_PATTERN, PATH_ADVANCED_PATTERN,
    QUERY_LITERAL, QUERY_PATTERN, QUERY_ADVANCED_PATTERN,
    FRAGMENT_LITERAL, FRAGMENT_PATTERN, FRAGMENT_ADVANCED_PATTERN,
    SSP_LITERAL, SSP_PREFIX, SSP_PATTERN, SSP_ADVANCED_PATTERN,
}

data class UriMatcher(val kind: UriMatcherKind, val value: String)

data class UriPattern(
    val schemes: Set<String> = emptySet(),
    val hosts: Set<String> = emptySet(),
    val ports: Set<String> = emptySet(),
    val mimeTypes: Set<String> = emptySet(),
    val ssp: List<UriMatcher> = emptyList(),
    val paths: List<UriMatcher> = emptyList(),
    val queries: List<UriMatcher> = emptyList(),
    val fragments: List<UriMatcher> = emptyList(),
    val autoVerify: Boolean = false,
)

data class DeepLinkTarget(
    val componentName: String,
    val kind: DeepLinkTargetKind,
    val targetActivity: String? = null,
    val patterns: List<UriPattern>,
    val hasDefaultCategory: Boolean,
    val sources: Set<DeepLinkSource> = setOf(DeepLinkSource.APK),
    val parameters: List<DeepLinkParameter> = emptyList(),
)

data class DeepLinkCatalog(
    val packageName: String,
    val targets: List<DeepLinkTarget>,
    val analyzedAtEpochMillis: Long? = null,
    val apkSources: List<String> = emptyList(),
)

data class DeepLinkAnalysis(
    val catalog: DeepLinkCatalog,
    val certificatesSha256: Set<String> = emptySet(),
    val verifications: List<AppLinkVerification> = emptyList(),
    val fromCache: Boolean = false,
)

sealed interface DeepLinkAnalysisResult {
    data class Success(val analysis: DeepLinkAnalysis) : DeepLinkAnalysisResult
    data class Failure(val message: String) : DeepLinkAnalysisResult
    data object NoCache : DeepLinkAnalysisResult
}

fun interface DeepLinkAnalyzer {
    suspend fun analyze(
        serial: dev.acme.adbtoolbox.domain.adb.DeviceSerial,
        androidUserId: Int,
        packageName: String,
        versionCode: String?,
        lastUpdateTime: String?,
        codePath: String?,
        refresh: Boolean,
    ): DeepLinkAnalysisResult
}

enum class DeviceLinkState { VERIFIED, APPROVED, DENIED, NONE, LEGACY_FAILURE, VENDOR }

data class DynamicRule(
    val exclude: Boolean = false,
    val path: String? = null,
    val fragment: String? = null,
    val query: Map<String, String> = emptyMap(),
)

data class AppLinkVerification(
    val host: String,
    val deviceState: DeviceLinkState,
    val deviceStateCode: Int? = null,
    val remoteValid: Boolean? = null,
    val dynamicRules: List<DynamicRule> = emptyList(),
    val validatedAtEpochMillis: Long? = null,
    val stale: Boolean = false,
    val error: String? = null,
)

sealed interface AssetLinksFetchResult {
    data class Success(val json: String) : AssetLinksFetchResult
    data class Failure(val message: String) : AssetLinksFetchResult
}

fun interface AssetLinksFetcher {
    suspend fun fetch(host: String): AssetLinksFetchResult
}
