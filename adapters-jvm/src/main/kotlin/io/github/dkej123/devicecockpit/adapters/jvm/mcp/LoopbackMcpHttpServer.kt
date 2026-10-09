package io.github.dkej123.devicecockpit.adapters.jvm.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/** What the MCP endpoint answers: a JSON body (or none → 202) and the session created by `initialize`. */
data class McpHttpReply(val body: String?, val sessionId: String? = null)

/** The protocol side the HTTP endpoint delegates to (the application layer's MCP server core). */
interface McpHttpHandler {
    suspend fun handle(body: String, sessionId: String?): McpHttpReply

    fun endSession(sessionId: String)
}

sealed interface McpServerStart {
    data class Listening(val port: Int) : McpServerStart

    data class Failed(val reason: String) : McpServerStart
}

/**
 * The MCP Streamable HTTP endpoint (ADR 0015): `POST http://127.0.0.1:<port>/mcp`, JSON responses
 * only. Bound to the loopback address; every request needs `Authorization: Bearer <token>`; a
 * browser `Origin` other than localhost is refused (DNS rebinding). `GET` (SSE stream) is not
 * offered (405); `DELETE` ends the session.
 */
class LoopbackMcpHttpServer(
    private val handler: McpHttpHandler,
    private val token: () -> String,
    private val maxBodyBytes: Int = 4 * 1024 * 1024,
) {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    val port: Int? get() = server?.address?.port

    /** Starts on [port] (0 = any free port) and reports the bound port, or why it could not start. */
    @Synchronized
    fun start(port: Int): McpServerStart {
        stop()
        val created = try {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        } catch (busy: BindException) {
            return McpServerStart.Failed("Port $port is in use by another program")
        } catch (failure: IOException) {
            return McpServerStart.Failed(failure.message ?: failure.toString())
        }
        val threads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "ADB Toolbox MCP ${threads.incrementAndGet()}").apply { isDaemon = true }
        }
        created.createContext(PATH) { exchange -> exchange.use { respond(it) } }
        created.executor = pool
        created.start()
        server = created
        executor = pool
        return McpServerStart.Listening(created.address.port)
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        executor?.shutdownNow()
        server = null
        executor = null
    }

    private fun respond(exchange: HttpExchange) {
        if (exchange.requestURI.path != PATH) return exchange.send(404, null)
        if (!originAllowed(exchange.requestHeaders.getFirst("Origin"))) return exchange.send(403, error("Origin not allowed"))
        if (!authorized(exchange.requestHeaders.getFirst("Authorization"))) {
            exchange.responseHeaders.add("WWW-Authenticate", "Bearer")
            return exchange.send(401, error("Missing or wrong token — copy it from Settings › Tools › ADB Toolbox › AI agents"))
        }
        val sessionId = exchange.requestHeaders.getFirst(SESSION_HEADER)
        when (exchange.requestMethod) {
            "POST" -> {
                val bytes = exchange.requestBody.readNBytes(maxBodyBytes + 1)
                if (bytes.size > maxBodyBytes) return exchange.send(413, error("Request too large"))
                val reply = runBlocking { handler.handle(bytes.decodeToString(), sessionId) }
                reply.sessionId?.let { exchange.responseHeaders.add(SESSION_HEADER, it) }
                if (reply.body == null) exchange.send(202, null) else exchange.send(200, reply.body)
            }
            "DELETE" -> {
                sessionId?.let(handler::endSession)
                exchange.send(200, null)
            }
            else -> {
                exchange.responseHeaders.add("Allow", "POST, DELETE")
                exchange.send(405, null)
            }
        }
    }

    private fun authorized(header: String?): Boolean {
        val expected = "Bearer ${token()}".toByteArray()
        return header != null && MessageDigest.isEqual(header.trim().toByteArray(), expected)
    }

    private fun originAllowed(origin: String?): Boolean {
        if (origin == null) return true
        val host = runCatching { URI(origin).host }.getOrNull() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
    }

    private fun HttpExchange.send(status: Int, json: String?) {
        if (json == null) {
            sendResponseHeaders(status, -1)
            return
        }
        val bytes = json.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun error(message: String) = """{"error":"${message.replace("\"", "'")}"}"""

    private companion object {
        const val PATH = "/mcp"
        const val SESSION_HEADER = "Mcp-Session-Id"
    }
}
