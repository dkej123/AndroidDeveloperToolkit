package dev.acme.adbtoolbox.adapters.jvm.deeplinks

import dev.acme.adbtoolbox.domain.deeplinks.AssetLinksFetchResult
import dev.acme.adbtoolbox.domain.deeplinks.AssetLinksFetcher
import java.net.InetAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Credential-free HTTPS fetcher with redirects disabled and a hard response-size limit. */
class JvmAssetLinksFetcher(
    private val maxBytes: Int = 1 shl 20,
    private val timeout: Duration = Duration.ofSeconds(10),
    proxySelector: ProxySelector = ProxySelector.getDefault(),
) : AssetLinksFetcher {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .proxy(proxySelector).connectTimeout(timeout).build()

    override suspend fun fetch(host: String): AssetLinksFetchResult = withContext(Dispatchers.IO) {
        if (!SAFE_HOST.matches(host)) return@withContext AssetLinksFetchResult.Failure("invalid host")
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse {
            return@withContext AssetLinksFetchResult.Failure("DNS lookup failed: ${it.message}")
        }
        if (addresses.any(::isPrivate)) {
            return@withContext AssetLinksFetchResult.Failure("private, loopback or link-local host requires user confirmation")
        }
        runCatching {
            val request = HttpRequest.newBuilder(URI("https://$host/.well-known/assetlinks.json"))
                .timeout(timeout).header("Accept", "application/json").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                if (response.statusCode() !in 200..299) return@withContext AssetLinksFetchResult.Failure("HTTP ${response.statusCode()}")
                val bytes = body.readNBytes(maxBytes + 1)
                if (bytes.size > maxBytes) return@withContext AssetLinksFetchResult.Failure("assetlinks.json exceeds $maxBytes bytes")
                AssetLinksFetchResult.Success(bytes.toString(Charsets.UTF_8))
            }
        }.getOrElse { AssetLinksFetchResult.Failure(it.message ?: it::class.simpleName.orEmpty()) }
    }

    private fun isPrivate(address: InetAddress): Boolean = address.isAnyLocalAddress || address.isLoopbackAddress ||
        address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress

    private companion object { val SAFE_HOST = Regex("""(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?""") }
}
