package app.snipnet.desktop.editor

import androidx.compose.ui.graphics.ImageBitmap
import app.snipnet.desktop.video.Thumbnail
import app.snipnet.desktop.video.VideoEngine
import app.snipnet.desktop.video.VideoInfo
import app.snipnet.desktop.video.VideoPlayer
import app.snipnet.desktop.video.Waveform
import app.snipnet.shared.model.ScoreCurve
import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SegmentSet
import app.snipnet.shared.model.SegmentSetKind
import app.snipnet.shared.store.ProjectStore
import app.snipnet.shared.store.openInMemoryDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path

const val TEST_DURATION_MS = 60_000L

/** A player that records what the editor asks of it and lets tests push positions like the real clock would. */
class FakePlayer : VideoPlayer {
    override val info =
        VideoInfo(TEST_DURATION_MS, 1920, 1080, 25.0, "h264", "aac", 44100, 2)
    override val frames: SharedFlow<ImageBitmap> = MutableSharedFlow(replay = 1)
    override val position = MutableStateFlow(0L)
    override val isPlaying = MutableStateFlow(false)
    override val rate = MutableStateFlow(1.0)

    val seeks = mutableListOf<Pair<Long, Boolean>>()
    var closed = false

    override fun play() {
        isPlaying.value = true
    }

    override fun pause() {
        isPlaying.value = false
    }

    override fun seek(
        positionMs: Long,
        exact: Boolean,
    ) {
        seeks += positionMs to exact
    }

    override fun setRate(rate: Double) {
        this.rate.value = rate
    }

    override fun close() {
        closed = true
    }
}

class FakeEngine(
    val player: FakePlayer = FakePlayer(),
) : VideoEngine {
    override suspend fun probe(file: Path) = player.info

    override suspend fun open(file: Path): VideoPlayer = player

    override fun thumbnails(
        file: Path,
        count: Int,
        height: Int,
    ): Flow<Thumbnail> = emptyFlow()

    override suspend fun waveform(
        file: Path,
        buckets: Int,
    ) = Waveform(TEST_DURATION_MS, FloatArray(buckets) { (it % 10) / 10f })
}

fun newStore(): ProjectStore = ProjectStore(openInMemoryDatabase(), newId = { "p1" }, now = { 1L })

fun prediction(
    segments: List<Segment>,
    scores: ScoreCurve? = null,
) = SegmentSet(
    id = "s1",
    videoId = "remote-1",
    kind = SegmentSetKind.PREDICTION,
    parentSetId = null,
    jobId = null,
    modelVersion = "heuristic-v0.1",
    segments = segments,
    scores = scores,
    editLog = null,
    isFinal = false,
    createdAt = "2026-01-01T00:00:00Z",
)

/** Three rallies with dead time between them: 5-15 s, 20-30 s and 40-50 s. */
val threeRallies = listOf(Segment(5_000, 15_000), Segment(20_000, 30_000), Segment(40_000, 50_000))
