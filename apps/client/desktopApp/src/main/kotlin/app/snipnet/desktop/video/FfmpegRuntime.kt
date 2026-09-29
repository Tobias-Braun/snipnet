package app.snipnet.desktop.video

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.Loader
import java.nio.file.Path

/**
 * One-time setup of the bundled FFmpeg natives. Everything that touches JavaCV must go through [ensureLoaded]
 * first (the engine does), because the native flavour has to be selected before JavaCPP resolves its libraries.
 */
object FfmpegRuntime {
    /**
     * The build ships the `-gpl` FFmpeg natives (x264/x265 for proxy transcode and export). JavaCPP only looks for
     * them when told the platform extension, otherwise it searches for the plain classifier and fails to load.
     */
    private const val PLATFORM_EXTENSION = "-gpl"

    @Volatile
    private var loaded = false

    /** Selects the native flavour and quiets FFmpeg's stderr logging down to real errors. Safe to call repeatedly. */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        if (System.getProperty("org.bytedeco.javacpp.platform.extension") == null) {
            System.setProperty("org.bytedeco.javacpp.platform.extension", PLATFORM_EXTENSION)
        }
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        loaded = true
    }

    /**
     * Absolute path of the `ffmpeg` executable bundled in the `ffmpeg-platform` natives. JavaCPP extracts it to its
     * cache directory on first use, so the returned file can be handed to `ProcessBuilder` by the transcode and
     * export features.
     */
    fun ffmpegPath(): Path {
        ensureLoaded()
        return Path.of(Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java))
    }
}

/** Convenience for callers that only need the executable, for example the proxy transcode. */
fun ffmpegPath(): Path = FfmpegRuntime.ffmpegPath()
