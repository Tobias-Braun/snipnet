package app.snipnet.shared.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsTest {
    private val rally = Segment(startMs = 1_000, endMs = 9_500, confidence = 0.87)

    private val job =
        Job(
            id = "9b0f3a5e-1f3f-4a58-9d3c-0d6a4b5a1c11",
            videoId = "1c2d3e4f-0000-4000-8000-000000000001",
            status = JobStatus.RUNNING,
            progress = 0.4,
            modelVersion = null,
            error = null,
            attempts = 1,
            createdAt = "2026-01-01T10:00:00Z",
            startedAt = "2026-01-01T10:01:00Z",
            finishedAt = null,
        )

    private val video =
        Video(
            id = "1c2d3e4f-0000-4000-8000-000000000001",
            filename = "match.mp4",
            durationMs = 3_600_000,
            width = 854,
            height = 480,
            fps = 15.0,
            proxySizeBytes = 64_000_000,
            status = VideoStatus.ANALYZING,
            court = Court(Roi(0.1, 0.2, 0.5, 0.6), Point(0.4, 0.5)),
            createdAt = "2026-01-01T09:00:00Z",
            updatedAt = "2026-01-01T10:01:00Z",
            latestJob = job,
        )

    private val segmentSet =
        SegmentSet(
            id = "5a5a5a5a-0000-4000-8000-000000000002",
            videoId = video.id,
            kind = SegmentSetKind.USER,
            parentSetId = "5a5a5a5a-0000-4000-8000-000000000001",
            jobId = null,
            modelVersion = "heuristic-v0.1",
            segments = listOf(rally),
            scores = ScoreCurve(hz = 2.0, values = listOf(0.1, 0.9)),
            editLog =
                listOf(
                    EditOp(
                        op = EditOpKind.TRIM,
                        atMs = 1_767_000_000_000,
                        before = listOf(rally),
                        after = listOf(rally.copy(endMs = 9_000)),
                    ),
                ),
            isFinal = true,
            createdAt = "2026-01-01T11:00:00Z",
        )

    private fun encodeObject(value: Any): JsonObject =
        when (value) {
            is SegmentSet -> SnipnetJson.encodeToJsonElement(SegmentSet.serializer(), value)
            is Segment -> SnipnetJson.encodeToJsonElement(Segment.serializer(), value)
            is Job -> SnipnetJson.encodeToJsonElement(Job.serializer(), value)
            else -> error("unsupported ${value::class}")
        } as JsonObject

    @Test
    fun everyContractTypeSurvivesARoundTrip() {
        val user = User("u1", "a@b.de", trainingConsent = true, createdAt = "2026-01-01T00:00:00Z")
        val auth = AuthResponse("jwt", user)

        assertEquals(
            user,
            SnipnetJson.decodeFromString(User.serializer(), SnipnetJson.encodeToString(User.serializer(), user)),
        )
        assertEquals(
            video,
            SnipnetJson.decodeFromString(Video.serializer(), SnipnetJson.encodeToString(Video.serializer(), video)),
        )
        assertEquals(
            job,
            SnipnetJson.decodeFromString(Job.serializer(), SnipnetJson.encodeToString(Job.serializer(), job)),
        )
        assertEquals(
            segmentSet,
            SnipnetJson.decodeFromString(
                SegmentSet.serializer(),
                SnipnetJson.encodeToString(SegmentSet.serializer(), segmentSet),
            ),
        )
        assertEquals(
            auth,
            SnipnetJson.decodeFromString(
                AuthResponse.serializer(),
                SnipnetJson.encodeToString(AuthResponse.serializer(), auth),
            ),
        )
    }

    @Test
    fun decodesTheContractJsonShapeAndIgnoresUnknownFields() {
        val json =
            """
            {"id":"v1","filename":"a.mp4","durationMs":1000,"width":854,"height":480,"fps":15,
             "proxySizeBytes":10,"status":"analyzed","court":null,
             "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","latestJob":null,
             "someFutureField":42}
            """.trimIndent()
        val decoded = SnipnetJson.decodeFromString(Video.serializer(), json)
        assertEquals(VideoStatus.ANALYZED, decoded.status)
        assertNull(decoded.court)
        assertNull(decoded.latestJob)
    }

    @Test
    fun enumsUseTheLowercaseWireNames() {
        assertEquals("user", encodeObject(segmentSet)["kind"]?.jsonPrimitive?.content)
        assertEquals("rally", encodeObject(rally)["label"]?.jsonPrimitive?.content)
    }

    @Test
    fun nullableFieldsAreWrittenExplicitly() {
        val obj = encodeObject(job)
        assertTrue(obj.containsKey("error"))
        assertTrue(obj.containsKey("finishedAt"))
    }

    @Test
    fun errorEnvelopeDecodes() {
        val body =
            SnipnetJson.decodeFromString(
                ApiErrorBody.serializer(),
                """{"error":{"code":"not_found","message":"nope"}}""",
            )
        assertEquals("not_found", body.error.code)
    }
}
