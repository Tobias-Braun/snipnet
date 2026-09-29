package app.snipnet.desktop.window

import app.snipnet.shared.model.SnipnetJson
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Window geometry restored on the next start. Sizes and positions are in dp; a null position means "let the OS pick". */
@Serializable
data class WindowSettings(
    val width: Float = 1280f,
    val height: Float = 800f,
    val x: Float? = null,
    val y: Float? = null,
    val maximized: Boolean = false,
) {
    /**
     * Guards against a corrupt or stale file (for example from a monitor that is no longer attached) producing an
     * unusable window: tiny or non-finite sizes fall back to defaults and the position is dropped.
     */
    fun sanitized(): WindowSettings {
        val usableSize = width.isFinite() && height.isFinite() && width >= MIN_SIZE && height >= MIN_SIZE
        val usablePosition = x != null && y != null && x.isFinite() && y.isFinite()
        return copy(
            width = if (usableSize) width else DEFAULT.width,
            height = if (usableSize) height else DEFAULT.height,
            x = if (usablePosition) x else null,
            y = if (usablePosition) y else null,
        )
    }

    companion object {
        const val MIN_SIZE = 400f
        val DEFAULT = WindowSettings()
    }
}

/** Persists [WindowSettings] as JSON at [file]; every failure degrades to defaults because this is only a convenience. */
class WindowSettingsStore(
    private val file: Path,
) {
    fun load(): WindowSettings =
        runCatching { SnipnetJson.decodeFromString(WindowSettings.serializer(), Files.readString(file)).sanitized() }
            .getOrDefault(WindowSettings.DEFAULT)

    fun save(settings: WindowSettings) {
        runCatching {
            Files.createDirectories(file.toAbsolutePath().parent)
            // Write to a sibling and move so a crash mid-write never leaves a truncated settings file.
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, SnipnetJson.encodeToString(WindowSettings.serializer(), settings))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
