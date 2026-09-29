package app.snipnet.desktop.di

import app.snipnet.desktop.nav.Navigator
import app.snipnet.desktop.nav.Screen
import app.snipnet.desktop.window.WindowSettingsStore
import java.nio.file.Path

/**
 * Manual dependency container, created once in `main` and handed to the UI. Long-lived services are `val`s (or
 * `by lazy` when expensive); screen state holders are created by factory functions so each screen instance gets a
 * fresh one. Features add their services here as they land (API client, local store, video engine).
 */
class AppContainer(
    dataDir: Path = defaultDataDir(),
) {
    val windowSettingsStore = WindowSettingsStore(dataDir.resolve("window.json"))

    val navigator = Navigator(start = Screen.Login)

    companion object {
        /** Per-user application data directory, overridable with `SNIPNET_DATA_DIR` for tests and portable installs. */
        fun defaultDataDir(): Path =
            System.getenv("SNIPNET_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".snipnet")
    }
}
