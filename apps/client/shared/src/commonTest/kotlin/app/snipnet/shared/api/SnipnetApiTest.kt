package app.snipnet.shared.api

import app.snipnet.shared.model.Court
import app.snipnet.shared.model.CreateVideoRequest
import app.snipnet.shared.model.EditOp
import app.snipnet.shared.model.EditOpKind
import app.snipnet.shared.model.Point
import app.snipnet.shared.model.Roi
import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SegmentSetKind
import app.snipnet.shared.model.VideoStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnipnetApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val userJson =
        """{"id":"u1","email":"a@b.de","trainingConsent":false,"createdAt":"2026-01-01T00:00:00Z"}"""

    private val jobJson =
        """{"id":"j1","videoId":"v1","status":"queued","progress":0,"modelVersion":null,"error":null,
            "attempts":0,"createdAt":"2026-01-01T00:00:00Z","startedAt":null,"finishedAt":null}"""

    private val videoJson =
        """{"id":"v1","filename":"m.mp4","durationMs":1000,"width":854,"height":480,"fps":15,"proxySizeBytes":10,
            "status":"uploaded","court":null,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
            "latestJob":null}"""

    private val segmentSetJson =
        """{"id":"s1","videoId":"v1","kind":"user","parentSetId":null,"jobId":null,"modelVersion":null,
            "segments":[{"startMs":0,"endMs":500,"label":"rally","confidence":null}],"scores":null,"editLog":[],
            "isFinal":true,"createdAt":"2026-01-01T00:00:00Z"}"""

    private class Recorder {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val last get() = requests.last()
        val lastBody get() = bodies.last()
    }

    private fun api(
        recorder: Recorder = Recorder(),
        token: String? = "tok",
        respond: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = SnipnetApi(
        MockEngine { request ->
            recorder.requests += request
            recorder.bodies += request.body.toByteArray().decodeToString()
            respond(request)
        },
        baseUrl = "http://api.test/",
        token = token,
    )

    private fun MockRequestHandleScope.ok(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, jsonHeaders)

    private fun bodyOf(recorder: Recorder): JsonObject = Json.parseToJsonElement(recorder.lastBody).jsonObject

    @Test
    fun healthNeedsNoAuth() =
        runTest {
            val rec = Recorder()
            val result = api(rec) { ok("""{"status":"ok","version":"1.2.3"}""") }.health()
            assertEquals("1.2.3", result.version)
            assertEquals("http://api.test/v1/health", rec.last.url.toString())
            assertNull(rec.last.headers[HttpHeaders.Authorization])
        }

    @Test
    fun registerPostsCredentialsWithoutBearer() =
        runTest {
            val rec = Recorder()
            val auth =
                api(rec) { ok("""{"token":"jwt","user":$userJson}""", HttpStatusCode.Created) }
                    .register("a@b.de", "password1")
            assertEquals("jwt", auth.token)
            assertEquals(HttpMethod.Post, rec.last.method)
            assertEquals("/v1/auth/register", rec.last.url.encodedPath)
            assertNull(rec.last.headers[HttpHeaders.Authorization])
            assertEquals("a@b.de", bodyOf(rec)["email"]?.jsonPrimitive?.content)
            assertEquals("password1", bodyOf(rec)["password"]?.jsonPrimitive?.content)
        }

    @Test
    fun loginPostsCredentials() =
        runTest {
            val rec = Recorder()
            val auth = api(rec) { ok("""{"token":"jwt","user":$userJson}""") }.login("a@b.de", "pw")
            assertEquals("u1", auth.user.id)
            assertEquals("/v1/auth/login", rec.last.url.encodedPath)
        }

    @Test
    fun meSendsBearerToken() =
        runTest {
            val rec = Recorder()
            val user = api(rec) { ok(userJson) }.me()
            assertEquals("a@b.de", user.email)
            assertEquals("Bearer tok", rec.last.headers[HttpHeaders.Authorization])
            assertEquals(HttpMethod.Get, rec.last.method)
        }

    @Test
    fun tokenChangesApplyToLaterRequests() =
        runTest {
            val rec = Recorder()
            val client = api(rec, token = null) { ok(userJson) }
            client.me()
            assertNull(rec.last.headers[HttpHeaders.Authorization])
            client.token = "new"
            client.me()
            assertEquals("Bearer new", rec.last.headers[HttpHeaders.Authorization])
        }

    @Test
    fun updateMePatchesConsent() =
        runTest {
            val rec = Recorder()
            api(rec) { ok(userJson.replace("false", "true")) }.updateMe(true).also { assertTrue(it.trainingConsent) }
            assertEquals(HttpMethod.Patch, rec.last.method)
            assertEquals("/v1/me", rec.last.url.encodedPath)
            assertTrue(bodyOf(rec)["trainingConsent"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun createVideoReturnsUploadTarget() =
        runTest {
            val rec = Recorder()
            val response =
                api(rec) {
                    ok(
                        """{"video":$videoJson,"upload":{"url":"http://s3/x","method":"PUT",
                            "headers":{"Content-Length":"10"},"expiresAt":"2026-01-01T01:00:00Z"}}""",
                        HttpStatusCode.Created,
                    )
                }.createVideo(CreateVideoRequest("m.mp4", 1000, 854, 480, 15.0, 10))
            assertEquals("http://s3/x", response.upload.url)
            assertEquals("10", response.upload.headers["Content-Length"])
            assertEquals("/v1/videos", rec.last.url.encodedPath)
            assertEquals("m.mp4", bodyOf(rec)["filename"]!!.jsonPrimitive.content)
            assertEquals(10L, bodyOf(rec)["proxySizeBytes"]!!.jsonPrimitive.content.toLong())
        }

    @Test
    fun listVideosUnwrapsItems() =
        runTest {
            val rec = Recorder()
            val videos = api(rec) { ok("""{"items":[$videoJson]}""") }.listVideos()
            assertEquals(listOf("v1"), videos.map { it.id })
            assertEquals(HttpMethod.Get, rec.last.method)
            assertEquals("/v1/videos", rec.last.url.encodedPath)
        }

    @Test
    fun getVideo() =
        runTest {
            val rec = Recorder()
            val video = api(rec) { ok(videoJson) }.getVideo("v1")
            assertEquals(VideoStatus.UPLOADED, video.status)
            assertEquals("/v1/videos/v1", rec.last.url.encodedPath)
        }

    @Test
    fun deleteVideoAcceptsNoContent() =
        runTest {
            val rec = Recorder()
            api(rec) { respond("", HttpStatusCode.NoContent) }.deleteVideo("v1")
            assertEquals(HttpMethod.Delete, rec.last.method)
            assertEquals("/v1/videos/v1", rec.last.url.encodedPath)
        }

    @Test
    fun uploadCompletePostsWithoutBody() =
        runTest {
            val rec = Recorder()
            api(rec) { ok(videoJson) }.uploadComplete("v1")
            assertEquals(HttpMethod.Post, rec.last.method)
            assertEquals("/v1/videos/v1/upload-complete", rec.last.url.encodedPath)
        }

    @Test
    fun putCourtSendsCourtBody() =
        runTest {
            val rec = Recorder()
            val court = Court(Roi(0.1, 0.2, 0.5, 0.6), Point(0.4, 0.5))
            api(rec) { ok(videoJson) }.putCourt("v1", court)
            assertEquals(HttpMethod.Put, rec.last.method)
            assertEquals("/v1/videos/v1/court", rec.last.url.encodedPath)
            val body = bodyOf(rec)
            assertEquals(
                0.5,
                body["roi"]!!
                    .jsonObject["width"]!!
                    .jsonPrimitive.content
                    .toDouble(),
            )
            assertEquals(
                0.4,
                body["netPoint"]!!
                    .jsonObject["x"]!!
                    .jsonPrimitive.content
                    .toDouble(),
            )
        }

    @Test
    fun analyzeSendsEmptyObject() =
        runTest {
            val rec = Recorder()
            val job = api(rec) { ok(jobJson, HttpStatusCode.Accepted) }.analyze("v1")
            assertEquals("j1", job.id)
            assertEquals(HttpMethod.Post, rec.last.method)
            assertEquals("/v1/videos/v1/analyze", rec.last.url.encodedPath)
            assertEquals(JsonObject(emptyMap()), bodyOf(rec))
        }

    @Test
    fun getJob() =
        runTest {
            val rec = Recorder()
            assertEquals("v1", api(rec) { ok(jobJson) }.getJob("j1").videoId)
            assertEquals("/v1/jobs/j1", rec.last.url.encodedPath)
        }

    @Test
    fun listSegmentSetsUnwrapsItems() =
        runTest {
            val rec = Recorder()
            val sets = api(rec) { ok("""{"items":[$segmentSetJson]}""") }.listSegmentSets("v1")
            assertEquals(SegmentSetKind.USER, sets.single().kind)
            assertEquals("/v1/videos/v1/segment-sets", rec.last.url.encodedPath)
        }

    @Test
    fun getSegmentSet() =
        runTest {
            val rec = Recorder()
            assertEquals("s1", api(rec) { ok(segmentSetJson) }.getSegmentSet("s1").id)
            assertEquals("/v1/segment-sets/s1", rec.last.url.encodedPath)
        }

    @Test
    fun createSegmentSetSendsSegmentsAndEditLog() =
        runTest {
            val rec = Recorder()
            val segment = Segment(0, 500)
            val op = EditOp(EditOpKind.ADD, 1_767_000_000_000, emptyList(), listOf(segment))
            api(rec) { ok(segmentSetJson, HttpStatusCode.Created) }
                .createSegmentSet(
                    "v1",
                    parentSetId = null,
                    segments = listOf(segment),
                    editLog = listOf(op),
                    isFinal = true,
                )
            assertEquals("/v1/videos/v1/segment-sets", rec.last.url.encodedPath)
            val body = bodyOf(rec)
            assertEquals(JsonNull, body["parentSetId"])
            assertEquals(1, body["segments"]!!.jsonArray.size)
            assertEquals(
                "add",
                body["editLog"]!!
                    .jsonArray
                    .single()
                    .jsonObject["op"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(body["isFinal"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun waitlistIsUnauthenticatedAndOmitsMissingSource() =
        runTest {
            val rec = Recorder()
            api(rec) { ok("{}", HttpStatusCode.Accepted) }.joinWaitlist("x@y.de")
            assertNull(rec.last.headers[HttpHeaders.Authorization])
            assertEquals("/v1/waitlist", rec.last.url.encodedPath)
            assertTrue("source" !in bodyOf(rec))

            api(rec) { ok("{}", HttpStatusCode.Accepted) }.joinWaitlist("x@y.de", "landing")
            assertEquals("landing", bodyOf(rec)["source"]!!.jsonPrimitive.content)
        }

    @Test
    fun trainingExportParsesNdjsonWithAdminToken() =
        runTest {
            val rec = Recorder()
            val line =
                """{"video":${videoJson.replace(
                    "\n",
                    "",
                )},"proxyUrl":"http://s3/p","prediction":${segmentSetJson.replace(
                    "\n",
                    "",
                )},"final":${segmentSetJson.replace("\n", "")}}"""
            val items =
                api(
                    rec,
                ) {
                    respond(
                        "$line\n$line\n",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/x-ndjson"),
                    )
                }.trainingExport("admin", since = "2026-01-01T00:00:00Z")
            assertEquals(2, items.size)
            assertEquals("Bearer admin", rec.last.headers[HttpHeaders.Authorization])
            assertEquals("2026-01-01T00:00:00Z", rec.last.url.parameters["since"])
        }

    @Test
    fun httpStatusesMapToSealedErrors() =
        runTest {
            suspend fun errorFor(status: HttpStatusCode): ApiError =
                assertFailsWith<ApiError> {
                    api { respond("""{"error":{"code":"some_code","message":"Boom"}}""", status, jsonHeaders) }.me()
                }

            assertIs<ApiError.Validation>(errorFor(HttpStatusCode.BadRequest))
            assertIs<ApiError.Unauthorized>(errorFor(HttpStatusCode.Unauthorized))
            assertIs<ApiError.Forbidden>(errorFor(HttpStatusCode.Forbidden))
            assertIs<ApiError.NotFound>(errorFor(HttpStatusCode.NotFound))
            assertIs<ApiError.Conflict>(errorFor(HttpStatusCode.Conflict))
            assertIs<ApiError.RateLimited>(errorFor(HttpStatusCode.TooManyRequests))
            assertIs<ApiError.Server>(errorFor(HttpStatusCode.InternalServerError))
            assertIs<ApiError.Unexpected>(errorFor(HttpStatusCode.PaymentRequired))

            val error = errorFor(HttpStatusCode.Conflict)
            assertEquals("some_code", error.code)
            assertEquals("Boom", error.message)
            assertEquals(409, error.status)
        }

    @Test
    fun rateLimitedCarriesRetryAfter() =
        runTest {
            val error =
                assertFailsWith<ApiError.RateLimited> {
                    api {
                        respond(
                            """{"error":{"code":"rate_limited","message":"slow down"}}""",
                            HttpStatusCode.TooManyRequests,
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                HttpHeaders.RetryAfter to listOf("30"),
                            ),
                        )
                    }.me()
                }
            assertEquals(30L, error.retryAfterSeconds)
        }

    @Test
    fun errorWithoutJsonBodyStillMaps() =
        runTest {
            val error = assertFailsWith<ApiError.Server> { api { respond("<html>", HttpStatusCode.BadGateway) }.me() }
            assertEquals(502, error.status)
            assertEquals("bad_gateway", error.code)
        }

    @Test
    fun transportFailureBecomesNetworkError() =
        runTest {
            val error =
                assertFailsWith<ApiError.Network> {
                    SnipnetApi(MockEngine { throw IOException("connection refused") }).me()
                }
            assertNull(error.status)
            assertEquals("connection refused", error.message)
        }

    @Test
    fun undecodableSuccessBodyBecomesMalformedResponse() =
        runTest {
            val error = assertFailsWith<ApiError.MalformedResponse> { api { ok("""{"unexpected":true}""") }.me() }
            assertEquals(200, error.status)
        }

    @Test
    fun defaultBaseUrlIsLocalhost() =
        runTest {
            val rec = Recorder()
            SnipnetApi(
                MockEngine {
                    rec.requests += it
                    respond("""{"status":"ok","version":"1"}""", HttpStatusCode.OK, jsonHeaders)
                },
            ).health()
            assertEquals("http://localhost:3000/v1/health", rec.last.url.toString())
        }
}
