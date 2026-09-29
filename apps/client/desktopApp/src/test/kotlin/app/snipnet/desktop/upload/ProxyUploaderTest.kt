package app.snipnet.desktop.upload

import app.snipnet.shared.model.UploadTarget
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs against a real local HTTP server on a random port, so headers, content length and the body are checked. */
class ProxyUploaderTest {
    private class Received(
        val method: String,
        val contentType: String?,
        val contentLength: String?,
        val extra: String?,
        val body: ByteArray,
    )

    private val received = CopyOnWriteArrayList<Received>()
    private val statuses = CopyOnWriteArrayList<Int>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/proxy") { exchange ->
                val body = exchange.requestBody.readAllBytes()
                val headers = exchange.requestHeaders
                received +=
                    Received(
                        exchange.requestMethod,
                        headers.getFirst("Content-Type"),
                        headers.getFirst("Content-Length"),
                        headers.getFirst("X-Amz-Meta"),
                        body,
                    )
                val status = if (statuses.isEmpty()) 200 else statuses.removeAt(0)
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }
    private val tmp: Path = Files.createTempDirectory("snipnet-upload")
    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private val file: Path = Files.write(tmp.resolve("proxy.mp4"), payload)
    private val uploader = HttpProxyUploader(CIO.create(), backoffMs = listOf(1, 1))

    private val target =
        UploadTarget(
            url = "http://127.0.0.1:${server.address.port}/proxy",
            method = "PUT",
            headers = mapOf("Content-Type" to "video/mp4", "X-Amz-Meta" to "yes"),
            expiresAt = "2099-01-01T00:00:00Z",
        )

    @AfterTest
    fun cleanUp() {
        server.stop(0)
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun sendsTheFileWithExactHeadersAndReportsProgress() =
        runBlocking {
            val progress = CopyOnWriteArrayList<Double>()
            uploader.upload(target, file) { progress += it }

            val request = received.single()
            assertEquals("PUT", request.method)
            assertEquals("video/mp4", request.contentType)
            assertEquals(payload.size.toString(), request.contentLength)
            assertEquals("yes", request.extra)
            assertContentEquals(payload, request.body)
            assertEquals(1.0, progress.last(), 0.0)
        }

    @Test
    fun retriesTransientServerErrors() =
        runBlocking {
            statuses += listOf(503, 500)
            uploader.upload(target, file) {}
            assertEquals(3, received.size)
            received.forEach { assertContentEquals(payload, it.body) }
        }

    @Test
    fun givesUpAfterTheConfiguredRetries() {
        statuses += listOf(500, 500, 500, 500)
        val error = assertFailsWith<UploadException> { runBlocking { uploader.upload(target, file) {} } }
        assertTrue(error.retryable)
        assertEquals(3, received.size)
    }

    @Test
    fun doesNotRetryARejection() {
        statuses += listOf(403)
        val error = assertFailsWith<UploadException> { runBlocking { uploader.upload(target, file) {} } }
        assertFalse(error.retryable)
        assertEquals(1, received.size)
    }

    /**
     * The shared CIO engine caps every request at its `requestTimeout` (15 s by default), which a proxy of a few
     * hundred megabytes easily exceeds. The upload must lift that cap, so a slow storage here outlasts a tiny one.
     */
    @Test
    fun anUploadMayTakeLongerThanTheEngineRequestTimeout() =
        runBlocking {
            server.createContext("/slow") { exchange ->
                exchange.requestBody.readAllBytes()
                Thread.sleep(1_500)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            val shortTimeoutEngine = CIO.create { requestTimeout = 500 }
            val slowUploader = HttpProxyUploader(shortTimeoutEngine, backoffMs = emptyList())
            slowUploader.upload(target.copy(url = target.url.replace("/proxy", "/slow")), file) {}
        }

    @Test
    fun anUnreachableStorageIsARetryableFailure() {
        server.stop(0)
        val error = assertFailsWith<UploadException> { runBlocking { uploader.upload(target, file) {} } }
        assertTrue(error.retryable)
    }
}
