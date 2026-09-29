package app.snipnet.desktop.window

import app.snipnet.shared.model.SnipnetJson
import kotlinx.serialization.Serializable
import java.awt.Rectangle
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
     * Guards against a corrupt file producing an unusable window: tiny or non-finite sizes fall back to defaults and
     * an incomplete or non-finite position is dropped. Positions on detached monitors are handled by [visibleOn].
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

    /**
     * Drops the saved position when the window's title bar would not be reachable on any of [screens] (for example
     * after the monitor it was on has been unplugged), so the OS places the window instead of opening it off-screen.
     * [screens] are the screen bounds in the same logical coordinates as [x] and [y].
     */
    fun visibleOn(screens: List<Rectangle>): WindowSettings {
        if (x == null || y == null) return this
        val titleBar = Rectangle(x.toInt(), y.toInt(), width.toInt(), TITLE_BAR_HEIGHT)
        val reachable =
            screens.any { screen ->
                val overlap = screen.intersection(titleBar)
                !overlap.isEmpty && overlap.width >= MIN_VISIBLE_TITLE_BAR
            }
        return if (reachable) this else copy(x = null, y = null)
    }

    /**
     * The settings to persist for the window's current geometry. While maximized the window reports the maximized
     * bounds, so the floating size and position are kept from this (the last non-maximized) state and only the
     * flag changes; that way un-maximizing after a restart returns to the size the user chose.
     */
    fun withGeometry(
        width: Float,
        height: Float,
        x: Float?,
        y: Float?,
        maximized: Boolean,
    ): WindowSettings =
        if (maximized) copy(maximized = true) else WindowSettings(width, height, x, y, maximized = false)

    companion object {
        const val MIN_SIZE = 400f
        private const val TITLE_BAR_HEIGHT = 32
        private const val MIN_VISIBLE_TITLE_BAR = 64
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
