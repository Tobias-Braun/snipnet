package app.snipnet.desktop.court

import androidx.compose.ui.graphics.ImageBitmap
import app.snipnet.desktop.video.Thumbnail
import app.snipnet.desktop.video.VideoEngine
import app.snipnet.desktop.video.VideoInfo
import app.snipnet.desktop.video.VideoPlayer
import app.snipnet.desktop.video.Waveform
import app.snipnet.shared.api.ApiError
import app.snipnet.shared.model.Court
import app.snipnet.shared.model.CourtSuggestion
import app.snipnet.shared.model.Point
import app.snipnet.shared.model.Roi
import app.snipnet.shared.store.ProjectStore
import app.snipnet.shared.store.openInMemoryDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePlayer : VideoPlayer {
    override val info = VideoInfo(100_000, 1920, 1080, 30.0, "h264", null, 0, 0)
    override val frames: SharedFlow<ImageBitmap> = MutableSharedFlow()
    override val position: StateFlow<Long> = MutableStateFlow(0)
    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
    override val rate: StateFlow<Double> = MutableStateFlow(1.0)
    val seeks = mutableListOf<Pair<Long, Boolean>>()
    var closed = false

    override fun play() = Unit

    override fun pause() = Unit

    override fun seek(
        positionMs: Long,
        exact: Boolean,
    ) {
        seeks += positionMs to exact
    }

    override fun setRate(rate: Double) = Unit

    override fun close() {
        closed = true
    }
}

private class FakeEngine(
    val player: FakePlayer = FakePlayer(),
) : VideoEngine {
    var opened: Path? = null

    override suspend fun probe(file: Path) = player.info

    override suspend fun open(file: Path): VideoPlayer {
        opened = file
        return player
    }

    override fun thumbnails(
        file: Path,
        count: Int,
        height: Int,
    ): Flow<Thumbnail> = emptyFlow()

    override suspend fun waveform(
        file: Path,
        buckets: Int,
    ) = Waveform(0, FloatArray(0))
}

@OptIn(ExperimentalCoroutinesApi::class)
class CourtSelectionStateHolderTest {
    private val store = ProjectStore(openInMemoryDatabase(), newId = { "p1" }, now = { 1L }, currentUserId = { "u1" })
    private val engine = FakeEngine()
    private val uploads = mutableListOf<Pair<String, Court>>()
    private var uploadError: ApiError? = null
    private var saved = 0
    private var suggestion: CourtSuggestion? = null
    private var suggestionError: ApiError? = null
    private val detectedCourt = Court(Roi(0.2, 0.15, 0.6, 0.7), Point(0.5, 0.5))

    private fun TestScope.holder(projectId: String = "p1") =
        CourtSelectionStateHolder(
            projectId,
            store,
            engine,
            uploadCourt = { id, court ->
                uploadError?.let { throw it }
                uploads += id to court
            },
            onSaved = { saved++ },
            loadSuggestion = {
                suggestionError?.let { throw it }
                suggestion
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    @Test
    fun unknownProjectReportsALoadError() =
        runTest {
            val holder = holder("missing")
            advanceUntilIdle()
            assertNotNull(holder.state.value.loadError)
            assertTrue(!holder.state.value.loading)
        }

    @Test
    fun opensTheOriginalWhenThereIsNoProxyAndShowsAFrameNearTheStart() =
        runTest {
            store.create("/videos/match.mp4")
            val holder = holder()
            advanceUntilIdle()
            assertEquals(Path.of("/videos/match.mp4"), engine.opened)
            assertEquals(100_000, holder.state.value.durationMs)
            assertEquals(listOf(10_000L to true), engine.player.seeks)
        }

    @Test
    fun scrubbingClampsToTheVideoAndSeeksTheGivenMode() =
        runTest {
            store.create("/videos/match.mp4")
            val holder = holder()
            advanceUntilIdle()
            holder.scrubTo(250_000, exact = false)
            assertEquals(100_000, holder.state.value.positionMs)
            assertEquals(100_000L to false, engine.player.seeks.last())
        }

    @Test
    fun clickingTheNetCreatesTheDefaultRoiAroundIt() =
        runTest {
            store.create("/videos/match.mp4")
            val holder = holder()
            advanceUntilIdle()
            holder.setNetPoint(Point(0.5, 0.5))
            assertEquals(CourtGeometry.defaultRoi(Point(0.5, 0.5)), holder.state.value.roi)
            assertTrue(holder.state.value.canSave)
        }

    @Test
    fun aConfidentSuggestionPrefillsTheCourtAndCanBeSaved() =
        runTest {
            suggestion = CourtSuggestion(detectedCourt, 0.9)
            val project = store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            val holder = holder(project.id)
            advanceUntilIdle()

            assertEquals(detectedCourt.netPoint, holder.state.value.netPoint)
            assertEquals(detectedCourt.roi, holder.state.value.roi)
            assertTrue(holder.state.value.prefilledFromDetection)
            assertTrue(holder.state.value.canSave)

            holder.setRoi(Roi(0.1, 0.1, 0.5, 0.5))
            assertFalse(holder.state.value.prefilledFromDetection)
        }

    @Test
    fun aLowConfidenceSuggestionIsNotApplied() =
        runTest {
            suggestion = CourtSuggestion(detectedCourt, 0.4)
            val project = store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            val holder = holder(project.id)
            advanceUntilIdle()

            assertNull(holder.state.value.netPoint)
            assertNull(holder.state.value.roi)
        }

    @Test
    fun aSavedCourtIsNeverOverwrittenBySuggestion() =
        runTest {
            suggestion = CourtSuggestion(detectedCourt, 0.9)
            val project = store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            val own = Court(Roi(0.1, 0.1, 0.3, 0.3), Point(0.25, 0.25))
            store.setCourt(project.id, own)
            val holder = holder(project.id)
            advanceUntilIdle()

            assertEquals(own.roi, holder.state.value.roi)
            assertFalse(holder.state.value.prefilledFromDetection)
        }

    @Test
    fun aFailingSuggestionRequestLeavesTheManualFlowIntact() =
        runTest {
            suggestionError = ApiError.Network(RuntimeException("offline"))
            val project = store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            val holder = holder(project.id)
            advanceUntilIdle()

            assertNull(holder.state.value.loadError)
            assertNull(holder.state.value.netPoint)
        }

    @Test
    fun anAdjustedRoiSurvivesLaterNetClicksUntilReset() =
        runTest {
            store.create("/videos/match.mp4")
            val holder = holder()
            advanceUntilIdle()
            holder.setNetPoint(Point(0.5, 0.5))
            val custom = Roi(0.1, 0.1, 0.3, 0.3)
            holder.setRoi(custom)
            holder.setNetPoint(Point(0.4, 0.4))
            assertEquals(custom, holder.state.value.roi)

            holder.resetRoi()
            assertEquals(CourtGeometry.defaultRoi(Point(0.4, 0.4)), holder.state.value.roi)
        }

    @Test
    fun savePersistsLocallyAndUploadsToTheRemoteVideo() =
        runTest {
            val project = store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            val holder = holder(project.id)
            advanceUntilIdle()
            holder.setNetPoint(Point(0.5, 0.5))
            holder.save()
            advanceUntilIdle()

            val court = Court(CourtGeometry.defaultRoi(Point(0.5, 0.5)), Point(0.5, 0.5))
            assertEquals(court, store.get(project.id)?.court)
            assertEquals(listOf("remote-1" to court), uploads)
            assertEquals(1, saved)
        }

    @Test
    fun saveWithoutARemoteVideoStaysLocal() =
        runTest {
            val project = store.create("/videos/match.mp4")
            val holder = holder(project.id)
            advanceUntilIdle()
            holder.setNetPoint(Point(0.3, 0.6))
            holder.save()
            advanceUntilIdle()

            assertNotNull(store.get(project.id)?.court)
            assertTrue(uploads.isEmpty())
            assertEquals(1, saved)
        }

    @Test
    fun rejectedUploadKeepsTheLocalCourtAndStaysOnTheScreen() =
        runTest {
            val project = store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            uploadError = ApiError.Validation("validation_error", "roi out of range")
            val holder = holder(project.id)
            advanceUntilIdle()
            holder.setNetPoint(Point(0.5, 0.5))
            holder.save()
            advanceUntilIdle()

            assertNotNull(store.get(project.id)?.court)
            assertEquals(0, saved)
            assertTrue(
                holder.state.value.saveError
                    .orEmpty()
                    .contains("roi out of range"),
            )
            assertTrue(!holder.state.value.saving)
        }

    @Test
    fun unreachableServerIsReportedAsSuchAndNotAsARejection() =
        runTest {
            val project = store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            uploadError = ApiError.Network(java.io.IOException("connection refused"))
            val holder = holder(project.id)
            advanceUntilIdle()
            holder.setNetPoint(Point(0.5, 0.5))
            holder.save()
            advanceUntilIdle()

            val error =
                holder.state.value.saveError
                    .orEmpty()
            assertTrue(error.contains("could not be reached"), error)
            assertNotNull(store.get(project.id)?.court)
            assertEquals(0, saved)
        }

    @Test
    fun saveWithoutANetPointDoesNothing() =
        runTest {
            val project = store.create("/videos/match.mp4")
            val holder = holder(project.id)
            advanceUntilIdle()
            holder.save()
            advanceUntilIdle()
            assertNull(store.get(project.id)?.court)
            assertEquals(0, saved)
        }

    @Test
    fun existingCourtIsPrefilledAndClosingReleasesThePlayer() =
        runTest {
            val court = Court(Roi(0.1, 0.2, 0.5, 0.6), Point(0.4, 0.5))
            val project = store.create("/videos/match.mp4")
            store.setCourt(project.id, court)
            val holder = holder(project.id)
            advanceUntilIdle()
            assertEquals(court.netPoint, holder.state.value.netPoint)
            assertEquals(court.roi, holder.state.value.roi)

            holder.close()
            assertTrue(engine.player.closed)
        }
}
