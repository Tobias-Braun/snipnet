package app.snipnet.desktop.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The transcode ended with a non-zero exit code; [message] carries the tail of ffmpeg's error output. */
class TranscodeException(
    message: String,
) : IOException(message)

/** Creates the upload proxy (`docs/api.md`, "Proxy format") from an original video file. */
interface ProxyTranscoder {
    /**
     * Writes the proxy of [input] to [output] and reports progress as a fraction in 0..1. [onProgress] is called from
     * a background thread, so it must be cheap and thread-safe. [durationMs] is the probed length
     * of [input] and only scales the progress. Cancelling the coroutine kills ffmpeg; in every failure case no file
     * remains at [output].
     */
    suspend fun transcode(
        input: Path,
        output: Path,
        durationMs: Long,
        onProgress: (Double) -> Unit,
    )
}

/**
 * Runs the bundled ffmpeg executable with the reference command of the API contract. The encode goes to a `.part`
 * sibling of the output and is renamed on success, so a crash or cancellation never leaves a half-written file that
 * a later run would take for a finished proxy.
 */
class FfmpegProxyTranscoder(
    private val ffmpeg: () -> Path = ::ffmpegPath,
) : ProxyTranscoder {
    override suspend fun transcode(
        input: Path,
        output: Path,
        durationMs: Long,
        onProgress: (Double) -> Unit,
    ) {
        val part = output.resolveSibling(output.fileName.toString() + ".part")
        withContext(Dispatchers.IO) {
            Files.createDirectories(output.parent)
            try {
                runFfmpeg(command(ffmpeg(), input, part), durationMs, onProgress)
                Files.move(part, output, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(part)
            }
        }
    }

    /**
     * Blocks on ffmpeg's `-progress` stream, which is the only reader of stdout; stderr is drained concurrently so a
     * chatty encoder can never fill the pipe and stall. A watcher coroutine kills the process when the caller is
     * cancelled, which also unblocks the stdout read so the cancellation is noticed promptly.
     */
    private suspend fun runFfmpeg(
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
                    cancellationAware {
                        process.errorStream
                            .bufferedReader()
                            .readText()
                            .trim()
                            .takeLast(ERROR_TAIL_CHARS)
                    }
                }
            cancellationAware {
                process.inputStream.bufferedReader().forEachLine { line ->
                    parseProgress(line, durationMs)?.let(onProgress)
                }
            }
            val exit = process.waitFor()
            ensureActive()
            if (exit != 0) throw TranscodeException("ffmpeg exited with code $exit: ${errors.await()}")
            onProgress(1.0)
        } finally {
            killer.cancel()
            process.destroyForcibly()
        }
    }

    /**
     * Runs a blocking read of the ffmpeg pipes. When the caller is cancelled the watcher kills the process, and on
     * Linux that can close the pipe under a read that is still blocked, so the read fails with an [IOException]
     * instead of seeing EOF. That failure is only a side effect of the cancellation, so [ensureActive] reports it as
     * the CancellationException. An [IOException] while the coroutine is still active is a real error and is
     * rethrown unchanged.
     */
    private suspend fun <T> cancellationAware(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        }

    companion object {
        private const val ERROR_TAIL_CHARS = 1000

        /** Longest side of the proxy in pixels (480p for landscape footage). */
        const val MAX_SIDE = 854

        /** Constant frame rate of the proxy. */
        const val FPS = 15

        /** The reference command of `docs/api.md`, plus machine-readable progress on stdout. */
        fun command(
            ffmpeg: Path,
            input: Path,
            output: Path,
        ): List<String> =
            listOf(
                ffmpeg.toString(),
                "-y",
                "-nostdin",
                "-loglevel",
                "error",
                "-progress",
                "pipe:1",
                "-nostats",
                "-i",
                input.toString(),
                "-vf",
                "scale='if(gt(iw,ih),min($MAX_SIDE,iw),-2)':'if(gt(iw,ih),-2,min($MAX_SIDE,ih))',fps=$FPS",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "28",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-ac",
                "1",
                "-ar",
                "16000",
                "-b:a",
                "64k",
                "-movflags",
                "+faststart",
                "-f",
                "mp4",
                output.toString(),
            )

        /**
         * Interprets one line of ffmpeg's `-progress` output. `out_time_us` is the media time written so far; it is
         * capped just below 1.0 because the encode is only finished once ffmpeg has exited successfully. Other keys,
         * and the `N/A` that ffmpeg prints before the first frame, yield null.
         */
        fun parseProgress(
            line: String,
            durationMs: Long,
        ): Double? {
            if (durationMs <= 0 || !line.startsWith("out_time_us=")) return null
            val micros = line.substringAfter('=').trim().toLongOrNull() ?: return null
            return (micros / 1000.0 / durationMs).coerceIn(0.0, MAX_RUNNING_PROGRESS)
        }

        private const val MAX_RUNNING_PROGRESS = 0.99
    }
}
