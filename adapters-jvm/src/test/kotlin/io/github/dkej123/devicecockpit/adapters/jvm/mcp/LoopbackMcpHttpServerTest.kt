package io.github.dkej123.devicecockpit.adapters.jvm.mcp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class LoopbackMcpHttpServerTest {

    private val received = mutableListOf<Pair<String, String?>>()
    private val ended = mutableListOf<String>()
    private val handler = object : McpHttpHandler {
        override suspend fun handle(body: String, sessionId: String?): McpHttpReply {
            received += body to sessionId
            return if (body.contains("notification")) McpHttpReply(null) else McpHttpReply("""{"ok":true}""", sessionId = "s1")
        }

        override fun endSession(sessionId: String) {
            ended += sessionId
        }
    }
    private var token = "atk_secret"
    private val server = LoopbackMcpHttpServer(handler, { token }, maxBodyBytes = 1024)
    private val client = HttpClient.newHttpClient()

    @AfterEach
    fun stop() = server.stop()

    private fun port() = (server.start(0) as McpServerStart.Listening).port

    private fun request(port: Int, method: String = "POST", body: String = "{}", vararg headers: Pair<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
            .method(method, if (method == "POST") HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private val auth = "Authorization" to "Bearer atk_secret"

    @Test
    fun `posts JSON-RPC to the handler and returns its reply with the session id`() {
        val port = port()

        val response = request(port, "POST", """{"id":1}""", auth, "Mcp-Session-Id" to "prev")

        response.statusCode() shouldBe 200
        response.body() shouldBe """{"ok":true}"""
        response.headers().firstValue("Mcp-Session-Id").get() shouldBe "s1"
        response.headers().firstValue("Content-Type").get() shouldBe "application/json"
        received.single() shouldBe ("""{"id":1}""" to "prev")
    }

    @Test
    fun `notifications are accepted without a body`() {
        request(port(), "POST", """{"method":"notification"}""", auth).statusCode() shouldBe 202
    }

    @Test
    fun `a missing or wrong token is 401, and the token is read on every request`() {
        val port = port()
        request(port).statusCode() shouldBe 401
        request(port, "POST", "{}", "Authorization" to "Bearer nope").statusCode() shouldBe 401
        token = "atk_new"
        request(port, "POST", "{}", auth).statusCode() shouldBe 401
        received shouldBe emptyList()
    }

    @Test
    fun `browser origins other than localhost are refused`() {
        val port = port()
        request(port, "POST", "{}", auth, "Origin" to "https://evil.example").statusCode() shouldBe 403
        request(port, "POST", "{}", auth, "Origin" to "http://localhost:3000").statusCode() shouldBe 200
    }

    @Test
    fun `GET has no event stream, DELETE ends the session, big bodies are refused`() {
        val port = port()
        request(port, "GET", "", auth).statusCode() shouldBe 405
        request(port, "DELETE", "", auth, "Mcp-Session-Id" to "s1").statusCode() shouldBe 200
        ended shouldBe listOf("s1")
        request(port, "POST", "x".repeat(2048), auth).statusCode() shouldBe 413
    }

    @Test
    fun `a busy port is reported instead of moving elsewhere`() {
        ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress()).use { taken ->
            server.start(taken.localPort).shouldBeInstanceOf<McpServerStart.Failed>().reason shouldBe
                "Port ${taken.localPort} is in use by another program"
        }
    }

    @Test
    fun `stopping closes the port`() {
        val port = port()
        server.stop()
        server.port shouldBe null
        ServerSocket(port, 0, java.net.InetAddress.getLoopbackAddress()).close() // free again
    }
}
