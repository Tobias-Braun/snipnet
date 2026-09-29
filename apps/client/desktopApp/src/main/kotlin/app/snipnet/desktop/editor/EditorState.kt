package app.snipnet.desktop.editor

import androidx.compose.ui.graphics.ImageBitmap
import app.snipnet.desktop.video.VideoInfo
import app.snipnet.desktop.video.Waveform
import app.snipnet.shared.editing.EditHistory
import app.snipnet.shared.editing.EditSegment
import app.snipnet.shared.editing.Timeline
import app.snipnet.shared.editing.Viewport
import app.snipnet.shared.model.ScoreCurve

/** Which side of a segment a trim drag moves. */
enum class SegmentEdge { Start, End }

/**
 * The whole UI state of the editor.
 *
 * @property history the segments with undo/redo; null until the project and video are loaded.
 * @property info facts of the loaded video (duration, frame rate).
 * @property frame the picture currently shown in the preview.
 * @property ralliesOnly whether playback skips everything outside the accepted segments.
 * @property markInMs in mark set with I, the start of a segment to add with Enter.
 * @property markOutMs out mark set with O.
 * @property viewport zoom and scroll of the timeline; only meaningful once [viewportWidthPx] is known.
 * @property viewportWidthPx pixel width of the timeline view, reported by the UI.
 * @property scores rally probability of the model, drawn as heat strip.
 * @property waveform audio peaks, null while loading or when the video has no audio.
 * @property thumbnails decoded timeline thumbnails by index, evenly spread over the video in [thumbnailCount] slices.
 * @property snapGuideMs the time a trim drag is currently snapped to, drawn as a guide line.
 * @property saveError why the draft could not be stored locally; editing continues in memory.
 */
data class EditorState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val history: EditHistory? = null,
    val info: VideoInfo? = null,
    val frame: ImageBitmap? = null,
    val isPlaying: Boolean = false,
    val rate: Double = 1.0,
    val ralliesOnly: Boolean = false,
    val markInMs: Long? = null,
    val markOutMs: Long? = null,
    val viewport: Viewport = Viewport(pxPerMs = 0.05),
    val viewportWidthPx: Double = 0.0,
    val scores: ScoreCurve? = null,
    val waveform: Waveform? = null,
    val thumbnails: Map<Int, ImageBitmap> = emptyMap(),
    val thumbnailCount: Int = 0,
    val snapGuideMs: Long? = null,
    val saveError: String? = null,
) {
    val timeline: Timeline? get() = history?.timeline

    val segments: List<EditSegment> get() = history?.timeline?.segments ?: emptyList()

    val playheadMs: Long get() = history?.timeline?.playheadMs ?: 0

    val durationMs: Long get() = history?.timeline?.durationMs ?: 0

    val canUndo: Boolean get() = history?.canUndo == true

    val canRedo: Boolean get() = history?.canRedo == true
}
