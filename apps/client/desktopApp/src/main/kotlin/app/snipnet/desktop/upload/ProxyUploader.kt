package app.snipnet.desktop.upload

import app.snipnet.shared.model.UploadTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The storage did not accept the proxy. [retryable] is false for answers a repeat cannot change (for example the
 * `403` of an expired or mismatching presigned URL), which the caller has to resolve with a new upload target.
 */
class UploadException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Sends the proxy file to the presigned URL of `POST /v1/videos` (`docs/api.md`, "Proxy upload"). */
interface ProxyUploader {
    /**
     * Uploads [file] as the request body with exactly the headers of [target] and a `Content-Length` of the file
     * size, reporting the fraction sent in 0..1 through [onProgress] (from a background thread). Transient failures
     * are retried; the progress starts over with each attempt. Throws [UploadException] when giving up.
     */
    suspend fun upload(
        target: UploadTarget,
        file: Path,
        onProgress: (Double) -> Unit,
    )
}

/**
 * Ktor implementation streaming the file from disk, so a multi-hundred-megabyte proxy is never held in memory.
 *
 * @param backoffMs waits between attempts; its size is the number of retries after the first attempt.
 */
class HttpProxyUploader(
    engine: HttpClientEngine,
    private val backoffMs: List<Long> = listOf(1_000, 3_000, 8_000),
) : ProxyUploader {
    /**
     * The engine is shared with the API client, and its engine-wide request timeout (15 s for CIO) would abort the
     * upload of any realistic proxy. The whole request is therefore unbounded here; a stalled connection is still
     * detected by the socket timeout, which surfaces as a retryable failure.
     */
    private val client =
        HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }

    override suspend fun upload(
        target: UploadTarget,
        file: Path,
        onProgress: (Double) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            try {
                send(target, file, onProgress)
                return
            } catch (e: UploadException) {
                if (!e.retryable || attempt >= backoffMs.size) throw e
            }
            delay(backoffMs[attempt++])
        }
    }

    private suspend fun send(
        target: UploadTarget,
        file: Path,
        onProgress: (Double) -> Unit,
    ) {
        val size = Files.size(file)
        onProgress(0.0)
        val status =
            try {
                client
                    .request(target.url) {
                        method = HttpMethod.parse(target.method)
                        // Content-Type and Content-Length are set on the body: Ktor rejects them as plain headers.
                        target.headers.filterKeys { !it.isManaged() }.forEach { (name, value) ->
                            headers.append(name, value)
                        }
                        val type = target.headers.entries.firstOrNull { it.key.equals(HttpHeaders.ContentType, true) }
                        setBody(FileBody(file, size, type?.let { ContentType.parse(it.value) }, onProgress))
                    }.status
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw UploadException(
                    "The upload could not reach the storage: ${e.message}",
                    retryable = true,
                    cause = e,
                )
            }
        val code = status.value
        if (code !in 200..299) {
            throw UploadException(
                "The storage rejected the upload with status $code.",
                retryable = code >= 500 || code == 408 || code == 429,
            )
        }
        onProgress(1.0)
    }

    private fun String.isManaged() =
        equals(HttpHeaders.ContentType, ignoreCase = true) || equals(HttpHeaders.ContentLength, ignoreCase = true)

    /** Re-opens the file on every attempt, since a retry needs the stream from the start. */
    private class FileBody(
        private val file: Path,
        private val size: Long,
        override val contentType: ContentType?,
        private val onProgress: (Double) -> Unit,
    ) : OutgoingContent.ReadChannelContent() {
        override val contentLength: Long = size

        override fun readFrom(): ByteReadChannel {
            val counting =
                object : FilterInputStream(Files.newInputStream(file)) {
                    private var sent = 0L

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int =
                        super.read(b, off, len).also {
                            if (it > 0) {
                                sent += it
                                if (size > 0) onProgress((sent.toDouble() / size).coerceAtMost(MAX_RUNNING_PROGRESS))
                            }
                        }
                } as InputStream
            return counting.toByteReadChannel()
        }
    }

    private companion object {
        /** The upload only counts as done once the storage has answered, so the body alone never reaches 1.0. */
        const val MAX_RUNNING_PROGRESS = 0.99

        /** No byte sent or received for this long means the connection is stuck. */
        const val SOCKET_TIMEOUT_MS = 60_000L
    }
}
