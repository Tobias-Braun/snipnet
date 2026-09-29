package app.snipnet.desktop.projects

import app.snipnet.desktop.upload.ProxyUploader
import app.snipnet.desktop.upload.UploadException
import app.snipnet.desktop.video.ProxyTranscoder
import app.snipnet.desktop.video.VideoEngine
import app.snipnet.desktop.video.VideoInfo
import app.snipnet.desktop.video.VideoPlayer
import app.snipnet.shared.api.ApiError
import app.snipnet.shared.api.SnipnetApi
import app.snipnet.shared.model.Court
import app.snipnet.shared.model.Point
import app.snipnet.shared.model.Roi
import app.snipnet.shared.model.UploadTarget
import app.snipnet.shared.store.Project
import app.snipnet.shared.store.ProjectStore
import app.snipnet.shared.store.openInMemoryDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportPipelineTest {
    private val tmp: Path = Files.createTempDirectory("snipnet-pipeline")
    private val original: Path = Files.write(tmp.resolve("match.mp4"), ByteArray(10))

    private val requests = CopyOnWriteArrayList<String>()
    private val createCount = AtomicInteger()
    private var videoStatus = "created"
    private var videoCourt: String = "null"
    private val jobs = CopyOnWriteArrayList<String>()
    private val analyzed = CopyOnWriteArrayList<Project>()

    /** The account the store acts as; tests change it to simulate logout and login. */
    @Volatile private var signedIn: String? = "u1"

    /** Makes `GET /v1/videos` answer 503, to show what the list looks like while the server is unreachable. */
    @Volatile private var videoListDown = false

    /** Number of upcoming `PUT /v1/videos/v1/court` calls that answer 503 before the court is accepted. */
    private val courtFailures = AtomicInteger()

    private val info = VideoInfo(60_000, 1920, 1080, 30.0, "h264", "aac", 48_000, 2)

    private val transcoder =
        object : ProxyTranscoder {
            var behavior: suspend (Path) -> Unit = { output -> Files.write(output, ByteArray(100)) }

            override suspend fun transcode(
                input: Path,
                output: Path,
                durationMs: Long,
                onProgress: (Double) -> Unit,
            ) {
                Files.createDirectories(output.parent)
                onProgress(0.5)
                behavior(output)
            }
        }

    private val uploader =
        object : ProxyUploader {
            var failures = 0
            val uploaded = AtomicInteger()

            override suspend fun upload(
                target: UploadTarget,
                file: Path,
                onProgress: (Double) -> Unit,
            ) {
                if (failures-- > 0) throw UploadException("storage down", retryable = true)
                uploaded.incrementAndGet()
            }
        }

    private val videoEngine =
        object : VideoEngine {
            override suspend fun probe(file: Path) = info

            override suspend fun open(file: Path): VideoPlayer = error("unused")

            override fun thumbnails(
                file: Path,
                count: Int,
                height: Int,
            ): Flow<app.snipnet.desktop.video.Thumbnail> = emptyFlow()

            override suspend fun waveform(
                file: Path,
                buckets: Int,
            ) = error("unused")
        }

    private fun videoJson(status: String = videoStatus) =
        """{"id":"v1","filename":"match.mp4","durationMs":60000,"width":1920,"height":1080,"fps":30,
        "proxySizeBytes":100,"status":"$status","court":$videoCourt,"createdAt":"2026-01-01T00:00:00Z",
        "updatedAt":"2026-01-01T00:00:00Z","latestJob":${if (status == "analyzing") {
            jobJson(
                "running",
                0.5,
            )
        } else {
            "null"
        }}}"""

    private fun jobJson(
        status: String,
        progress: Double,
        error: String? = null,
    ) = """{"id":"j1","videoId":"v1","status":"$status","progress":$progress,"modelVersion":null,
        "error":${error?.let { "\"$it\"" }},"attempts":1,"createdAt":"2026-01-01T00:00:00Z","startedAt":null,
        "finishedAt":null}"""

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val engine =
        MockEngine { request -> route(request) }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.route(request: HttpRequestData) =
        request.url.encodedPath.let { path ->
            requests += "${request.method.value} $path"
            when {
                path == "/v1/videos" && request.method == HttpMethod.Post -> {
                    createCount.incrementAndGet()
                    respond(
                        """{"video":${videoJson("created")},"upload":{"url":"http://storage/x","method":"PUT",
                        "headers":{"Content-Type":"video/mp4"},"expiresAt":"2099-01-01T00:00:00Z"}}""",
                        HttpStatusCode.Created,
                        json,
                    )
                }
                path == "/v1/videos" && videoListDown ->
                    respond(
                        """{"error":{"code":"unavailable","message":"down"}}""",
                        HttpStatusCode.ServiceUnavailable,
                        json,
                    )
                path == "/v1/videos" -> respond("""{"items":[${videoJson()}]}""", HttpStatusCode.OK, json)
                path == "/v1/videos/v1/upload-complete" -> {
                    videoStatus = "uploaded"
                    respond(videoJson(), HttpStatusCode.OK, json)
                }
                path == "/v1/videos/v1" && request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                path == "/v1/videos/v1" -> respond(videoJson(), HttpStatusCode.OK, json)
                else -> routeAnalysis(path)
            }
        }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.routeAnalysis(path: String) =
        when (path) {
            "/v1/videos/v1/court" ->
                if (courtFailures.getAndDecrement() > 0) {
                    respond(
                        """{"error":{"code":"unavailable","message":"court down"}}""",
                        HttpStatusCode.ServiceUnavailable,
                        json,
                    )
                } else {
                    videoCourt =
                        """{"roi":{"x":0.1,"y":0.1,"width":0.5,"height":0.5},"netPoint":{"x":0.3,"y":0.3}}"""
                    respond(videoJson(), HttpStatusCode.OK, json)
                }
            "/v1/videos/v1/analyze" -> {
                videoStatus = "analyzing"
                respond(jobJson("queued", 0.0), HttpStatusCode.Accepted, json)
            }
            "/v1/jobs/j1" -> {
                val next = jobs.removeAt(0)
                if (next == "succeeded") videoStatus = "analyzed"
                if (next == "failed") videoStatus = "failed"
                respond(jobResponse(next), HttpStatusCode.OK, json)
            }
            else -> respond("""{"error":{"code":"not_found","message":"no"}}""", HttpStatusCode.NotFound, json)
        }

    private fun jobResponse(name: String) =
        when (name) {
            "running" -> jobJson("running", 0.5)
            "succeeded" -> jobJson("succeeded", 1.0)
            else -> jobJson("failed", 0.5, error = "model crashed")
        }

    private val api = SnipnetApi(engine, "http://api.test")
    private var counter = 0
    private val store =
        ProjectStore(
            openInMemoryDatabase(),
            newId = { "p${++counter}" },
            now = { 1_000L },
            currentUserId = { signedIn },
        )
    private val pipeline =
        ImportPipeline(
            api = api,
            store = store,
            videoEngine = videoEngine,
            transcoder = transcoder,
            uploader = uploader,
            proxyDir = tmp.resolve("proxies"),
            onAnalyzed = { analyzed += it },
            dispatcher = Dispatchers.Default,
            pollIntervalMs = 5,
            maxPollIntervalMs = 20,
        )

    @AfterTest
    fun cleanUp() {
        pipeline.reset()
        tmp.toFile().deleteRecursively()
    }

    private suspend fun awaitRow(condition: (ProjectRow) -> Boolean): ProjectRow =
        withTimeout(10_000) { pipeline.rows.first { rows -> rows.any(condition) }.first(condition) }

    private fun court() = Court(Roi(0.1, 0.1, 0.5, 0.5), Point(0.3, 0.3))

    @Test
    fun unsupportedFilesAreRejectedAndCreateNoProject() {
        val text = Files.write(tmp.resolve("notes.txt"), ByteArray(1))
        val rejected = pipeline.import(listOf(text, tmp.resolve("missing.mp4")))
        assertEquals(2, rejected.size)
        assertTrue(pipeline.rows.value.isEmpty())
    }

    @Test
    fun importTranscodesUploadsAndEndsReadyForTheCourt() =
        runBlocking<Unit> {
            assertTrue(pipeline.import(listOf(original)).isEmpty())
            val row = awaitRow { it.status == ProjectStatus.READY }

            assertEquals("match.mp4", row.title)
            assertEquals(false, row.hasCourt)
            assertEquals(1, uploader.uploaded.get())
            val project = store.list().single()
            assertEquals(tmp.resolve("proxies/p1.mp4").toString(), project.proxyPath)
            assertEquals("v1", project.remoteVideoId)
            assertEquals(listOf("POST /v1/videos", "POST /v1/videos/v1/upload-complete"), requests.toList())
        }

    @Test
    fun aFailedUploadOffersRetryWhichKeepsTheProxyAndTheVideo() =
        runBlocking<Unit> {
            uploader.failures = 1
            pipeline.import(listOf(original))
            val failed = awaitRow { it.status == ProjectStatus.FAILED }
            assertEquals("storage down", failed.error)

            pipeline.retry(failed.project.id)
            awaitRow { it.status == ProjectStatus.READY }

            assertEquals(1, createCount.get())
            assertEquals(1, uploader.uploaded.get())
        }

    @Test
    fun cancellingATranscodeLeavesARetryableProject() =
        runBlocking<Unit> {
            transcoder.behavior = { awaitCancellation() }
            pipeline.import(listOf(original))
            val running = awaitRow { it.status == ProjectStatus.PROXY }
            assertTrue(running.cancellable)

            pipeline.cancel(running.project.id)
            val failed = awaitRow { it.status == ProjectStatus.FAILED }
            assertEquals("Cancelled.", failed.error)
            assertNull(store.list().single().proxyPath)

            transcoder.behavior = { output -> Files.write(output, ByteArray(100)) }
            pipeline.retry(failed.project.id)
            awaitRow { it.status == ProjectStatus.READY }
        }

    @Test
    fun analysisSendsTheLocalCourtPollsTheJobAndOpensTheEditor() =
        runBlocking<Unit> {
            pipeline.import(listOf(original))
            awaitRow { it.status == ProjectStatus.READY }
            store.setCourt("p1", court())
            jobs += listOf("running", "running", "succeeded")

            pipeline.startAnalysis("p1")
            val row = awaitRow { it.status == ProjectStatus.ANALYZED }

            assertEquals(true, row.hasCourt)
            assertTrue("PUT /v1/videos/v1/court" in requests)
            assertEquals(3, requests.count { it == "GET /v1/jobs/j1" })
            withTimeout(5_000) { while (analyzed.isEmpty()) kotlinx.coroutines.delay(10) }
            assertEquals("v1", analyzed.single().remoteVideoId)
        }

    @Test
    fun aCourtSavedBeforeTheUploadIsSentAfterUploadCompleteAndBeforeAnalyze() =
        runBlocking<Unit> {
            transcoder.behavior = { output ->
                store.setCourt("p1", court())
                Files.write(output, ByteArray(100))
            }
            pipeline.import(listOf(original))
            awaitRow { it.status == ProjectStatus.READY }

            // The analyze step would also send a missing court, so the court must be on the server before it starts.
            assertEquals(
                listOf("POST /v1/videos", "POST /v1/videos/v1/upload-complete", "PUT /v1/videos/v1/court"),
                requests.toList(),
            )

            jobs += listOf("succeeded")
            pipeline.startAnalysis("p1")
            awaitRow { it.status == ProjectStatus.ANALYZED }

            val order = requests.filter { it.startsWith("POST") || it.startsWith("PUT") }
            assertEquals(
                listOf(
                    "POST /v1/videos",
                    "POST /v1/videos/v1/upload-complete",
                    "PUT /v1/videos/v1/court",
                    "POST /v1/videos/v1/analyze",
                ),
                order,
            )
        }

    @Test
    fun aFailedCourtUploadAfterTheProxyIsRetriedWithoutUploadingTheProxyAgain() =
        runBlocking<Unit> {
            courtFailures.set(1)
            transcoder.behavior = { output ->
                store.setCourt("p1", court())
                Files.write(output, ByteArray(100))
            }
            pipeline.import(listOf(original))
            val failed = awaitRow { it.status == ProjectStatus.FAILED }
            assertNotNull(failed.error)

            pipeline.retry(failed.project.id)
            awaitRow { it.status == ProjectStatus.READY }

            assertEquals(1, createCount.get())
            assertEquals(1, uploader.uploaded.get())
            assertEquals(2, requests.count { it == "PUT /v1/videos/v1/court" })
            assertEquals("PUT /v1/videos/v1/court", requests.last())
        }

    @Test
    fun analysisWithoutACourtFailsWithAClearMessage() =
        runBlocking<Unit> {
            pipeline.import(listOf(original))
            awaitRow { it.status == ProjectStatus.READY }

            pipeline.startAnalysis("p1")
            val failed = awaitRow { it.status == ProjectStatus.FAILED }
            assertEquals("Select the court before analyzing.", failed.error)
            assertTrue("POST /v1/videos/v1/analyze" !in requests)
        }

    @Test
    fun aFailedJobShowsItsErrorAndRetryStartsANewAnalysis() =
        runBlocking<Unit> {
            pipeline.import(listOf(original))
            awaitRow { it.status == ProjectStatus.READY }
            store.setCourt("p1", court())
            jobs += listOf("failed")

            pipeline.startAnalysis("p1")
            val failed = awaitRow { it.status == ProjectStatus.FAILED }
            assertEquals("model crashed", failed.error)

            jobs += listOf("succeeded")
            pipeline.retry(failed.project.id)
            awaitRow { it.status == ProjectStatus.ANALYZED }
            assertEquals(2, requests.count { it == "POST /v1/videos/v1/analyze" })
        }

    @Test
    fun refreshResumesAnAnalysisThatWasRunningWhenTheAppClosed() =
        runBlocking<Unit> {
            store.create(original.toString(), proxyPath = original.toString(), remoteVideoId = "v1")
            videoStatus = "analyzing"
            jobs += listOf("succeeded")

            pipeline.refresh()
            awaitRow { it.status == ProjectStatus.ANALYZED }

            assertNotNull(requests.find { it == "GET /v1/jobs/j1" })
            assertTrue("POST /v1/videos/v1/analyze" !in requests)
            assertTrue(analyzed.isEmpty(), "a resumed analysis must not pull the user into the editor")
        }

    @Test
    fun aFailingRefreshAfterReLoginStillShowsTheUsersLocalProjects() =
        runBlocking<Unit> {
            val project = store.create(original.toString())
            signedIn = null
            pipeline.reset()
            assertTrue(pipeline.rows.value.isEmpty())

            signedIn = "u1"
            videoListDown = true
            assertFailsWith<ApiError> { pipeline.refresh() }

            assertEquals(listOf(project.id), pipeline.rows.value.map { it.project.id })
        }

    @Test
    fun pollIntervalGrowsWhileProgressStandsStillAndResetsWhenItMoves() {
        val base = 2_000L
        val max = 10_000L
        var interval = base
        val waits = mutableListOf<Long>()
        repeat(6) {
            interval = ImportPipeline.nextPollInterval(interval, base, max, progressMoved = false)
            waits += interval
        }
        assertEquals(listOf(3_000L, 4_500L, 6_750L, 10_000L, 10_000L, 10_000L), waits)
        assertEquals(base, ImportPipeline.nextPollInterval(10_000, base, max, progressMoved = true))
    }
}
