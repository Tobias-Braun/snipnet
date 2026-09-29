package app.snipnet.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.snipnet.desktop.court.CourtGeometry
import app.snipnet.desktop.court.CourtSelectionState
import app.snipnet.desktop.court.CourtSelectionStateHolder
import app.snipnet.desktop.court.FrameBox
import app.snipnet.desktop.court.RoiCorner
import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.nav.Screen
import app.snipnet.desktop.theme.SnipnetTheme
import app.snipnet.shared.model.Point
import kotlin.math.roundToInt

/** What a drag on the frame started on, decided once at the start of the gesture. */
private sealed interface DragTarget {
    data object Net : DragTarget

    data object MoveRoi : DragTarget

    data class Corner(
        val corner: RoiCorner,
    ) : DragTarget
}

private const val HANDLE_RADIUS_PX = 14.0
private const val SLIDER_STEPS_MS = 1000f

/**
 * Lets the user mark the court: pick a representative frame with the scrubber, click the net, then move and resize
 * the ROI rectangle. [Screen.CourtSelection.videoId] is the local project id.
 */
@Composable
fun CourtSelectionScreen(
    container: AppContainer,
    videoId: String,
) {
    val holder =
        remember(videoId) {
            container.courtSelectionStateHolder(videoId, onSaved = { container.navigator.push(Screen.Editor(videoId)) })
        }
    DisposableEffect(holder) { onDispose { holder.close() } }
    val state by holder.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mark your court", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Click the net, then adjust the box: mark your court so other courts are ignored.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                state.loading -> CircularProgressIndicator()
                state.loadError != null ->
                    Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                else -> CourtCanvas(state, holder)
            }
        }
        if (state.durationMs > 0) {
            Slider(
                value = state.positionMs.toFloat(),
                onValueChange = { holder.scrubTo(it.toLong(), exact = false) },
                onValueChangeFinished = { holder.scrubTo(state.positionMs, exact = true) },
                valueRange = 0f..maxOf(state.durationMs.toFloat(), SLIDER_STEPS_MS),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { container.navigator.back() }) { Text("Back") }
            OutlinedButton(onClick = holder::resetRoi, enabled = state.netPoint != null) { Text("Reset box") }
            Button(onClick = holder::save, enabled = state.canSave) {
                Text(if (state.saving) "Saving..." else "Save and continue")
            }
        }
    }
}

/**
 * The frame with the net marker and ROI rectangle drawn on top. All geometry goes through [FrameBox], so the frame
 * may be letterboxed inside the available area while stored coordinates stay relative to the frame.
 */
@Composable
private fun CourtCanvas(
    state: CourtSelectionState,
    holder: CourtSelectionStateHolder,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val frame = state.frame
    val box =
        remember(canvasSize, frame?.width, frame?.height) {
            FrameBox.fit(
                canvasSize.width.toDouble(),
                canvasSize.height.toDouble(),
                frame?.width?.toDouble() ?: 0.0,
                frame?.height?.toDouble() ?: 0.0,
            )
        }
    val currentState by rememberUpdatedState(state)
    val currentBox by rememberUpdatedState(box)
    val accent = MaterialTheme.colorScheme.primary
    val netColor = SnipnetTheme.editor.playhead
    var dragTarget by remember { mutableStateOf<DragTarget?>(null) }

    Canvas(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { canvasSize = it }
                .pointerInput(holder) {
                    detectTapGestures { offset ->
                        val b = currentBox
                        if (b.contains(offset.x.toDouble(), offset.y.toDouble())) {
                            holder.setNetPoint(b.toNormalized(offset.x.toDouble(), offset.y.toDouble()))
                        }
                    }
                }.pointerInput(holder) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val b = currentBox
                            dragTarget =
                                if (b.width > 0) {
                                    hitTest(currentState, b, b.toNormalized(offset.x.toDouble(), offset.y.toDouble()))
                                } else {
                                    null
                                }
                        },
                        onDragEnd = { dragTarget = null },
                        onDragCancel = { dragTarget = null },
                    ) { change, dragAmount ->
                        val b = currentBox
                        val roi = currentState.roi
                        if (b.width <= 0) return@detectDragGestures
                        val pointer = b.toNormalized(change.position.x.toDouble(), change.position.y.toDouble())
                        when (val target = dragTarget) {
                            DragTarget.Net -> holder.setNetPoint(pointer)
                            DragTarget.MoveRoi ->
                                if (roi != null) {
                                    holder.setRoi(
                                        CourtGeometry.move(
                                            roi,
                                            dragAmount.x / b.width,
                                            dragAmount.y / b.height,
                                        ),
                                    )
                                }
                            is DragTarget.Corner ->
                                if (roi != null) holder.setRoi(CourtGeometry.resize(roi, target.corner, pointer))
                            null -> Unit
                        }
                        change.consume()
                    }
                },
    ) {
        if (frame != null && box.width > 0) {
            drawImage(
                frame,
                dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
                dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()),
            )
        }
        if (box.width <= 0) return@Canvas
        state.roi?.let { roi ->
            val topLeft = Offset(box.toPixelX(roi.x).toFloat(), box.toPixelY(roi.y).toFloat())
            val size = Size((roi.width * box.width).toFloat(), (roi.height * box.height).toFloat())
            drawRect(accent.copy(alpha = 0.15f), topLeft, size)
            drawRect(accent, topLeft, size, style = Stroke(width = 2f))
            RoiCorner.entries.forEach { corner ->
                val c = CourtGeometry.cornerPoint(roi, corner)
                drawCircle(
                    accent,
                    radius = 7f,
                    center = Offset(box.toPixelX(c.x).toFloat(), box.toPixelY(c.y).toFloat()),
                )
            }
        }
        state.netPoint?.let { net ->
            val center = Offset(box.toPixelX(net.x).toFloat(), box.toPixelY(net.y).toFloat())
            drawCircle(Color.Black, radius = 11f, center = center)
            drawCircle(netColor, radius = 8f, center = center)
        }
    }
}

/**
 * Decides what a drag starting at [start] grabs: the net marker first, then an ROI corner, then the ROI body.
 * Grab radii are constant in pixels, so they are converted to normalized units per axis.
 */
private fun hitTest(
    state: CourtSelectionState,
    box: FrameBox,
    start: Point,
): DragTarget? {
    val radiusX = HANDLE_RADIUS_PX / box.width
    val radiusY = HANDLE_RADIUS_PX / box.height
    state.netPoint?.let { net ->
        if (kotlin.math.abs(net.x - start.x) <= radiusX && kotlin.math.abs(net.y - start.y) <= radiusY) {
            return DragTarget.Net
        }
    }
    val roi = state.roi ?: return null
    CourtGeometry.cornerAt(roi, start, radiusX, radiusY)?.let { return DragTarget.Corner(it) }
    return if (CourtGeometry.contains(roi, start)) DragTarget.MoveRoi else null
}
