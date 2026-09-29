package app.snipnet.desktop.di

import app.snipnet.desktop.auth.AuthStateHolder
import app.snipnet.desktop.auth.Session
import app.snipnet.desktop.auth.TokenStore
import app.snipnet.desktop.court.CourtSelectionStateHolder
import app.snipnet.desktop.editor.EditorStateHolder
import app.snipnet.desktop.nav.Navigator
import app.snipnet.desktop.nav.Screen
import app.snipnet.desktop.projects.ImportPipeline
import app.snipnet.desktop.projects.ProjectsStateHolder
import app.snipnet.desktop.upload.HttpProxyUploader
import app.snipnet.desktop.video.FfmpegProxyTranscoder
import app.snipnet.desktop.video.JavaCvVideoEngine
import app.snipnet.desktop.video.VideoEngine
import app.snipnet.desktop.window.WindowSettingsStore
import app.snipnet.shared.api.SnipnetApi
import app.snipnet.shared.model.SegmentSetKind
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
    private val engine: HttpClientEngine = CIO.create(),
    baseUrl: String = defaultBaseUrl(),
) {
    val windowSettingsStore = WindowSettingsStore(dataDir.resolve("window.json"))

    val videoEngine: VideoEngine by lazy { JavaCvVideoEngine(dataDir.resolve("cache").resolve("media")) }

    val navigator = Navigator(start = Screen.Login)

    val api = SnipnetApi(engine, baseUrl)

    val projectStore: ProjectStore by lazy {
        ProjectStore(
            openDatabase(dataDir.resolve("snipnet.db")),
            newId = { UUID.randomUUID().toString() },
            now = System::currentTimeMillis,
        )
    }

    /**
     * Import, upload and analysis run here for the whole app lifetime. A finished analysis opens the editor, but only
     * when the user is still looking at the projects list, so it never yanks them out of another screen.
     */
    val pipeline: ImportPipeline by lazy {
        ImportPipeline(
            api = api,
            store = projectStore,
            videoEngine = videoEngine,
            transcoder = FfmpegProxyTranscoder(),
            uploader = HttpProxyUploader(engine),
            proxyDir = dataDir.resolve("proxies"),
            onAnalyzed = { project ->
                if (navigator.current == Screen.Projects) navigator.push(Screen.Editor(project.id))
            },
        )
    }

    val session =
        Session(api, TokenStore(dataDir.resolve("token")), onLoggedOut = {
            pipeline.reset()
            projectStore.clear()
        })

    /** Backs the projects screen; the caller closes it when the screen leaves the composition. */
    fun projectsStateHolder() = ProjectsStateHolder(pipeline)

    /** Backs the login/register screen; the caller closes it when the screen leaves the composition. */
    fun authStateHolder() = AuthStateHolder(session, onAuthenticated = { navigator.resetTo(Screen.Projects) })

    /** Backs the court selection of the local project [projectId]; the caller closes it when the screen is left. */
    fun courtSelectionStateHolder(
        projectId: String,
        onSaved: () -> Unit,
    ) = CourtSelectionStateHolder(
        projectId,
        projectStore,
        videoEngine,
        uploadCourt = { remoteVideoId, court -> api.putCourt(remoteVideoId, court) },
        onSaved = onSaved,
        loadSuggestion = { remoteVideoId -> api.getVideo(remoteVideoId).courtSuggestion },
    )

    /**
     * Backs the editor of the local project [projectId]; the caller closes it when the screen is left. The newest
     * prediction of the video (segments and score curve) is loaded from the API when the project has been uploaded.
     */
    fun editorStateHolder(projectId: String) =
        EditorStateHolder(
            projectId,
            projectStore,
            videoEngine,
            loadPrediction = { remoteVideoId ->
                api.listSegmentSets(remoteVideoId).lastOrNull { it.kind == SegmentSetKind.PREDICTION }
            },
        )

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
