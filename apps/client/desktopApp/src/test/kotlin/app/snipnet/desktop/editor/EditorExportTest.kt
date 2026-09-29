package app.snipnet.desktop.editor

import app.snipnet.desktop.export.ExportException
import app.snipnet.desktop.export.ExportMode
import app.snipnet.desktop.export.ExportRequest
import app.snipnet.desktop.export.RallyExporter
import app.snipnet.shared.editing.TimeRange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The export dialog's state handling with a scripted exporter; the real ffmpeg run is covered by RallyExporterTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorExportTest {
    private class ScriptedExporter(
        private val action: suspend (ExportRequest, (Double) -> Unit) -> List<Path>,
    ) : RallyExporter {
        var request: ExportRequest? = null

        override suspend fun export(
            request: ExportRequest,
            onProgress: (Double) -> Unit,
        ): List<Path> {
            this.request = request
            return action(request, onProgress)
        }
    }

    private fun holder(exporter: RallyExporter): EditorStateHolder {
        val store = newStore()
        store.create("/videos/match.mp4", remoteVideoId = "remote-1")
        return EditorStateHolder(
            "p1",
            store,
            FakeEngine(),
            newQueue(store),
            loadSets = { listOf(prediction(threeRallies)) },
            dispatcher = UnconfinedTestDispatcher(),
            exporter = exporter,
        )
    }

    @Test
    fun theDialogDefaultsToTheFolderOfTheOriginal() {
        val holder = holder(ScriptedExporter { _, _ -> emptyList() })
        holder.openExport()
        assertTrue(holder.state.value.export.open)
        assertEquals(
            Path.of("/videos").toAbsolutePath(),
            holder.state.value.export.options.folder,
        )
    }

    @Test
    fun exportsTheAcceptedSegmentsOfTheOriginalFile() {
        val exporter =
            ScriptedExporter { _, onProgress ->
                onProgress(0.5)
                listOf(Path.of("/videos/match-rallies.mp4"))
            }
        val holder = holder(exporter)
        holder.toggleAccept(
            holder.state.value.segments[1]
                .id,
        )
        holder.openExport()
        holder.startExport()

        val request = assertNotNull(exporter.request)
        assertEquals(Path.of("/videos/match.mp4"), request.source)
        assertEquals(listOf(TimeRange(5_000, 15_000), TimeRange(40_000, 50_000)), request.ranges)
        assertEquals(ExportMode.SingleVideo, request.mode)
        val export = holder.state.value.export
        assertFalse(export.running)
        assertEquals(1.0, export.progress)
        assertEquals(listOf(Path.of("/videos/match-rallies.mp4")), export.result)
    }

    @Test
    fun rejectedSegmentsAreIncludedOnRequest() {
        val exporter = ScriptedExporter { _, _ -> emptyList() }
        val holder = holder(exporter)
        holder.toggleAccept(
            holder.state.value.segments[1]
                .id,
        )
        holder.openExport()
        holder.setExportOptions { it.copy(includeRejected = true, mode = ExportMode.Edl) }
        holder.startExport()

        assertEquals(3, exporter.request?.ranges?.size)
        assertEquals(ExportMode.Edl, exporter.request?.mode)
    }

    @Test
    fun aFailureIsShownAndTheDialogStaysOpen() {
        val holder = holder(ScriptedExporter { _, _ -> throw ExportException("disk full") })
        holder.openExport()
        holder.startExport()

        val export = holder.state.value.export
        assertEquals("disk full", export.error)
        assertFalse(export.running)
        assertTrue(export.open)
        assertNull(export.result)
    }

    @Test
    fun cancellingStopsTheRunningExport() {
        val holder = holder(ScriptedExporter { _, _ -> CompletableDeferred<List<Path>>().await() })
        holder.openExport()
        holder.startExport()
        assertTrue(holder.state.value.export.running)

        holder.cancelExport()

        val export = holder.state.value.export
        assertFalse(export.running)
        assertNull(export.error)
        assertNull(export.result)
    }

    @Test
    fun optionsAreLockedWhileRunning() {
        val holder = holder(ScriptedExporter { _, _ -> CompletableDeferred<List<Path>>().await() })
        holder.openExport()
        holder.startExport()
        holder.setExportOptions { it.copy(mode = ExportMode.PerRally) }
        assertEquals(ExportMode.SingleVideo, holder.state.value.export.options.mode)
        holder.closeExport()
    }
}
