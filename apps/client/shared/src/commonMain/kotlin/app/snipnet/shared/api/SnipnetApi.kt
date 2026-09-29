package app.snipnet.shared.api

import app.snipnet.shared.model.ApiErrorBody
import app.snipnet.shared.model.AuthResponse
import app.snipnet.shared.model.Court
import app.snipnet.shared.model.CreateVideoRequest
import app.snipnet.shared.model.CreateVideoResponse
import app.snipnet.shared.model.EditOp
import app.snipnet.shared.model.Job
import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SegmentSet
import app.snipnet.shared.model.SnipnetJson
import app.snipnet.shared.model.TrainingExportItem
import app.snipnet.shared.model.User
import app.snipnet.shared.model.Video
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Typed client for every public endpoint of `docs/api.md`. All suspend functions either return the decoded
 * response or throw an [ApiError]; nothing else escapes except coroutine cancellation.
 *
 * Authentication: [token] is sent as `Authorization: Bearer` on every `/v1` call except health, register, login and
 * waitlist, which the contract leaves open. It is a plain property so the session layer can set it after login and
 * clear it on logout without rebuilding the client.
 *
 * @param baseUrl server root without a trailing slash requirement, for example `http://localhost:3000`.
 * @param engine HTTP engine; tests pass a Ktor `MockEngine`.
 */
class SnipnetApi(
    engine: HttpClientEngine,
    baseUrl: String = DEFAULT_BASE_URL,
    @kotlin.concurrent.Volatile var token: String? = null,
) : AutoCloseable {
    private val baseUrl = baseUrl.trimEnd('/')

    private val client =
        HttpClient(engine) {
            // Non-2xx responses are mapped by hand into ApiError, so the default exception must not fire first.
            expectSuccess = false
            install(ContentNegotiation) { json(SnipnetJson) }
        }

    suspend fun health(): HealthResponse = send(HttpMethod.Get, "/v1/health", auth = Auth.NONE)

    suspend fun register(
        email: String,
        password: String,
    ): AuthResponse = send(HttpMethod.Post, "/v1/auth/register", credentials(email, password), Auth.NONE)

    suspend fun login(
        email: String,
        password: String,
    ): AuthResponse = send(HttpMethod.Post, "/v1/auth/login", credentials(email, password), Auth.NONE)

    suspend fun me(): User = send(HttpMethod.Get, "/v1/me")

    suspend fun updateMe(trainingConsent: Boolean): User =
        send(HttpMethod.Patch, "/v1/me", buildJsonObject { put("trainingConsent", trainingConsent) })

    suspend fun createVideo(request: CreateVideoRequest): CreateVideoResponse =
        send(HttpMethod.Post, "/v1/videos", SnipnetJson.encodeToJsonElement(CreateVideoRequest.serializer(), request))

    suspend fun listVideos(): List<Video> = send<VideoList>(HttpMethod.Get, "/v1/videos").items

    suspend fun getVideo(id: String): Video = send(HttpMethod.Get, "/v1/videos/$id")

    suspend fun deleteVideo(id: String) = sendNoContent(HttpMethod.Delete, "/v1/videos/$id")

    suspend fun uploadComplete(id: String): Video = send(HttpMethod.Post, "/v1/videos/$id/upload-complete")

    suspend fun putCourt(
        id: String,
        court: Court,
    ): Video = send(HttpMethod.Put, "/v1/videos/$id/court", SnipnetJson.encodeToJsonElement(Court.serializer(), court))

    suspend fun analyze(id: String): Job = send(HttpMethod.Post, "/v1/videos/$id/analyze", JsonObject(emptyMap()))

    suspend fun getJob(id: String): Job = send(HttpMethod.Get, "/v1/jobs/$id")

    suspend fun listSegmentSets(videoId: String): List<SegmentSet> =
        send<SegmentSetList>(HttpMethod.Get, "/v1/videos/$videoId/segment-sets").items

    suspend fun getSegmentSet(id: String): SegmentSet = send(HttpMethod.Get, "/v1/segment-sets/$id")

    suspend fun createSegmentSet(
        videoId: String,
        parentSetId: String?,
        segments: List<Segment>,
        editLog: List<EditOp>,
        isFinal: Boolean,
    ): SegmentSet =
        send(
            HttpMethod.Post,
            "/v1/videos/$videoId/segment-sets",
            buildJsonObject {
                put("parentSetId", parentSetId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("segments", SnipnetJson.encodeToJsonElement(ListSerializer(Segment.serializer()), segments))
                put("editLog", SnipnetJson.encodeToJsonElement(ListSerializer(EditOp.serializer()), editLog))
                put("isFinal", isFinal)
            },
        )

    /** Joins the public waitlist; idempotent server-side. [source] is an optional free-form origin tag. */
    suspend fun joinWaitlist(
        email: String,
        source: String? = null,
    ) {
        sendNoContent(
            HttpMethod.Post,
            "/v1/waitlist",
            buildJsonObject {
                put("email", email)
                if (source != null) put("source", source)
            },
            Auth.NONE,
        )
    }

    /**
     * Streams the admin training export, which is NDJSON with one [TrainingExportItem] per line. Authenticated with
     * the separate [adminToken] rather than the user token. [since] is an ISO-8601 timestamp.
     */
    suspend fun trainingExport(
        adminToken: String,
        since: String? = null,
    ): List<TrainingExportItem> {
        val response =
            execute(HttpMethod.Get, "/v1/admin/training-export", null, Auth.NONE) {
                bearerAuth(adminToken)
                if (since != null) parameter("since", since)
            }
        return decode(response.status.value) {
            response
                .bodyAsText()
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { SnipnetJson.decodeFromString(TrainingExportItem.serializer(), it) }
                .toList()
        }
    }

    override fun close() = client.close()

    private fun credentials(
        email: String,
        password: String,
    ) = buildJsonObject {
        put("email", email)
        put("password", password)
    }

    private suspend inline fun <reified T> send(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
        auth: Auth = Auth.USER,
    ): T {
        val response = execute(method, path, body, auth)
        return decode(response.status.value) { SnipnetJson.decodeFromString(serializerOf<T>(), response.bodyAsText()) }
    }

    private suspend fun sendNoContent(
        method: HttpMethod,
        path: String,
        body: JsonElement? = null,
        auth: Auth = Auth.USER,
    ) {
        execute(method, path, body, auth)
    }

    /**
     * Performs the request and returns only successful responses. Transport failures become [ApiError.Network] and
     * non-2xx responses are mapped through [toApiError].
     */
    private suspend fun execute(
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        auth: Auth,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val response =
            try {
                client.request(baseUrl + path) {
                    this.method = method
                    if (auth == Auth.USER) token?.let { bearerAuth(it) }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    configure()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw ApiError.Network(e)
            }
        if (!response.status.isSuccess()) throw toApiError(response)
        return response
    }

    private suspend fun toApiError(response: HttpResponse): ApiError {
        val status = response.status.value
        val detail =
            runCatching { SnipnetJson.decodeFromString(ApiErrorBody.serializer(), response.bodyAsText()).error }
                .getOrNull()
        val code =
            detail?.code ?: response.status.description
                .lowercase()
                .replace(' ', '_')
        val message = detail?.message ?: "Request failed with status $status."
        return when (status) {
            400 -> ApiError.Validation(code, message)
            401 -> ApiError.Unauthorized(code, message)
            403 -> ApiError.Forbidden(code, message)
            404 -> ApiError.NotFound(code, message)
            409 -> ApiError.Conflict(code, message)
            429 -> ApiError.RateLimited(code, message, response.headers[HttpHeaders.RetryAfter]?.toLongOrNull())
            in 500..599 -> ApiError.Server(status, code, message)
            else -> ApiError.Unexpected(status, code, message)
        }
    }

    private inline fun <T> decode(
        status: Int,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: SerializationException) {
            throw ApiError.MalformedResponse(status, e)
        } catch (e: IllegalArgumentException) {
            throw ApiError.MalformedResponse(status, e)
        }

    private enum class Auth { USER, NONE }

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:3000"
    }
}

/** Response of `GET /v1/health`. */
@kotlinx.serialization.Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)

@kotlinx.serialization.Serializable
private data class VideoList(
    val items: List<Video>,
)

@kotlinx.serialization.Serializable
private data class SegmentSetList(
    val items: List<SegmentSet>,
)

private inline fun <reified T> serializerOf(): KSerializer<T> = serializer()
