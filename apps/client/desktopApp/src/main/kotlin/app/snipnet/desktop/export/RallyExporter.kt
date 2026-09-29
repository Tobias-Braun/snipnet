package app.snipnet.desktop.export

import app.snipnet.desktop.video.FfmpegProxyTranscoder
import app.snipnet.desktop.video.ffmpegPath
import app.snipnet.shared.editing.TimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Writes the rallies of a video to disk in the format of the request. */
interface RallyExporter {
    /**
     * Exports [request] and returns the files written. [onProgress] gets a fraction in 0..1 from a background
     * thread, so it must be cheap and thread-safe. Cancelling the coroutine stops ffmpeg; in every failure case
     * no file of this run remains.
     */
    suspend fun export(
        request: ExportRequest,
        onProgress: (Double) -> Unit,
    ): List<Path>
}

/**
 * The real exporter: cuts the original with the bundled ffmpeg executable and writes the project files itself.
 *
 * Video modes cut every range into its own file first (frame accurate when re-encoding); the concatenated video
 * is then made by joining these parts with the concat demuxer and copying, so a long list of rallies never needs a
 * huge filter graph. Existing files with the same name are replaced.
 */
class FfmpegRallyExporter(
    private val ffmpeg: () -> Path = ::ffmpegPath,
) : RallyExporter {
    override suspend fun export(
        request: ExportRequest,
        onProgress: (Double) -> Unit,
    ): List<Path> {
        if (request.ranges.isEmpty()) throw ExportException("There is nothing to export: no segments are selected.")
        return withContext(Dispatchers.IO) {
            Files.createDirectories(request.folder)
            val stem =
                request.source.fileName
                    .toString()
                    .substringBeforeLast('.')
            val written = mutableListOf<Path>()
            var finished = false
            try {
                when (request.mode) {
                    ExportMode.Fcpxml -> written.add(writeFcpxml(request, stem))
                    ExportMode.Edl -> written.add(writeEdl(request, stem))
                    ExportMode.PerRally -> exportPerRally(request, stem, written, onProgress)
                    ExportMode.SingleVideo -> exportSingle(request, stem, written, onProgress)
                }
                onProgress(1.0)
                finished = true
                written.toList()
            } finally {
                if (!finished) written.forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun writeFcpxml(
        request: ExportRequest,
        stem: String,
    ): Path {
        val uri =
            request.source
                .toAbsolutePath()
                .toUri()
                .toString()
        return request.folder
            .resolve("$stem.fcpxml")
            .also { Files.writeString(it, ProjectFiles.fcpxml(stem, uri, request.info, request.ranges)) }
    }

    private fun writeEdl(
        request: ExportRequest,
        stem: String,
    ): Path {
        val edl = ProjectFiles.edl(stem, request.source.fileName.toString(), request.info, request.ranges)
        return request.folder.resolve("$stem.edl").also { Files.writeString(it, edl) }
    }

    private suspend fun exportPerRally(
        request: ExportRequest,
        stem: String,
        written: MutableList<Path>,
        onProgress: (Double) -> Unit,
    ) {
        val extension = FfmpegCommands.extensionFor(request.source, request.quality)
        val progress = PartProgress(request.ranges.sumOf { it.endMs - it.startMs }, onProgress)
        request.ranges.forEachIndexed { index, range ->
            // Only the number goes through format, since a stem like "final 100%" is not a valid format string.
            val target = request.folder.resolve("$stem-rally-${"%03d".format(index + 1)}.$extension")
            written.add(target)
            cutTo(request, target, range, progress)
        }
    }

    private suspend fun exportSingle(
        request: ExportRequest,
        stem: String,
        written: MutableList<Path>,
        onProgress: (Double) -> Unit,
    ) {
        val extension = FfmpegCommands.extensionFor(request.source, request.quality)
        val target = request.folder.resolve("$stem-rallies.$extension")
        written.add(target)
        val progress = PartProgress(request.ranges.sumOf { it.endMs - it.startMs }, onProgress)
        if (request.ranges.size == 1) {
            cutTo(request, target, request.ranges.single(), progress)
            return
        }
        val workDir = Files.createTempDirectory(request.folder, ".snipnet-export-")
        try {
            val parts =
                request.ranges.mapIndexed { index, range ->
                    workDir.resolve("part-%04d.$extension".format(index)).also { cutTo(request, it, range, progress) }
                }
            val list = workDir.resolve("parts.txt")
            Files.writeString(list, FfmpegCommands.concatList(parts.map { it.toAbsolutePath() }))
            FfmpegProcess.run(FfmpegCommands.concat(ffmpeg(), list, target), 0) {}
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    private suspend fun cutTo(
        request: ExportRequest,
        target: Path,
        range: TimeRange,
        progress: PartProgress,
    ) {
        val length = range.endMs - range.startMs
        FfmpegProcess.run(
            FfmpegCommands.cut(ffmpeg(), request.source, target, range, request.quality),
            length,
        ) { progress.report(it, length) }
        progress.complete(length)
    }

    /** Turns the per-part fractions into one overall fraction, weighting every part by the media time it covers. */
    private class PartProgress(
        private val totalMs: Long,
        private val sink: (Double) -> Unit,
    ) {
        @Volatile
        private var doneMs = 0L

        fun report(
            fraction: Double,
            partMs: Long,
        ) = sink(((doneMs + fraction * partMs) / totalMs).coerceIn(0.0, MAX_RUNNING))

        fun complete(partMs: Long) {
            doneMs += partMs
        }

        private companion object {
            /** Full progress is only reported once the whole export, including its last step, has finished. */
            const val MAX_RUNNING = 0.99
        }
    }
}

/** Runs one ffmpeg process to completion with progress, error capture and kill-on-cancel. */
internal object FfmpegProcess {
    private const val ERROR_TAIL_CHARS = 1000

    /**
     * Blocks on ffmpeg's `-progress` stream, which is the only reader of stdout; stderr is drained concurrently so
     * a chatty encoder can never fill the pipe and stall. A watcher coroutine kills the process when the caller is
     * cancelled, which also unblocks the stdout read so the cancellation is noticed promptly.
     *
     * @param durationMs length of the media being written, to scale the progress lines; 0 disables progress.
     */
    suspend fun run(
        command: List<String>,
        durationMs: Long,
        onProgress: (Double) -> Unit,
    ) = coroutineScope {
        val process = ProcessBuilder(command).start()
        val killer =
            launch {
                try {
                    awaitCancellation()
                } finally {
                    process.destroyForcibly()
                }
            }
        try {
            val errors =
                async {
                    process.errorStream
                        .bufferedReader()
                        .readText()
                        .trim()
                        .takeLast(ERROR_TAIL_CHARS)
                }
            process.inputStream.bufferedReader().forEachLine { line ->
                FfmpegProxyTranscoder.parseProgress(line, durationMs)?.let(onProgress)
            }
            val exit = process.waitFor()
            ensureActive()
            if (exit != 0) throw ExportException("ffmpeg exited with code $exit: ${errors.await()}")
        } finally {
            killer.cancel()
            // Waiting for the exit makes the caller's cleanup deterministic: a dying ffmpeg can no longer create or
            // hold its output file open (which blocks deletion on Windows) once this returns.
            process.destroyForcibly().waitFor()
        }
    }
}
