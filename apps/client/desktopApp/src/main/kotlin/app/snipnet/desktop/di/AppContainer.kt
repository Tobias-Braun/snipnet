package app.snipnet.desktop.di

import app.snipnet.desktop.auth.AuthStateHolder
import app.snipnet.desktop.auth.Session
import app.snipnet.desktop.auth.TokenStore
import app.snipnet.desktop.nav.Navigator
import app.snipnet.desktop.nav.Screen
import app.snipnet.desktop.window.WindowSettingsStore
import app.snipnet.shared.api.SnipnetApi
import app.snipnet.shared.store.ProjectStore
import app.snipnet.shared.store.openDatabase
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import java.nio.file.Path
import java.util.UUID

/**
 * Manual dependency container, created once in `main` and handed to the UI. Long-lived services are `val`s (or
 * `by lazy` when expensive); screen state holders are created by factory functions so each screen instance gets a
 * fresh one. Features add their services here as they land (video engine).
 *
 * @param engine HTTP engine of the API client; tests inject a mock engine.
 * @param baseUrl API root; defaults to `SNIPNET_API_URL` or the local development server.
 */
class AppContainer(
    dataDir: Path = defaultDataDir(),
    engine: HttpClientEngine = CIO.create(),
    baseUrl: String = defaultBaseUrl(),
) {
    val windowSettingsStore = WindowSettingsStore(dataDir.resolve("window.json"))

    val navigator = Navigator(start = Screen.Login)

    val api = SnipnetApi(engine, baseUrl)

    val projectStore: ProjectStore by lazy {
        ProjectStore(
            openDatabase(dataDir.resolve("snipnet.db")),
            newId = { UUID.randomUUID().toString() },
            now = System::currentTimeMillis,
        )
    }

    val session = Session(api, TokenStore(dataDir.resolve("token")), onLoggedOut = { projectStore.clear() })

    /** Backs the login/register screen; the caller closes it when the screen leaves the composition. */
    fun authStateHolder() = AuthStateHolder(session, onAuthenticated = { navigator.resetTo(Screen.Projects) })

    /** Ends the session and returns to the login screen with an empty back stack. */
    fun logout() {
        session.logout()
        navigator.resetTo(Screen.Login)
    }

    companion object {
        /** Per-user application data directory, overridable with `SNIPNET_DATA_DIR` for tests and portable installs. */
        fun defaultDataDir(): Path =
            System.getenv("SNIPNET_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".snipnet")

        /** API root from `SNIPNET_API_URL`, falling back to the contract's local default. */
        fun defaultBaseUrl(): String =
            System.getenv("SNIPNET_API_URL")?.takeIf { it.isNotBlank() } ?: SnipnetApi.DEFAULT_BASE_URL
    }
}
