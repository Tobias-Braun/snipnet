package app.snipnet.desktop.di

import app.snipnet.desktop.auth.AuthStateHolder
import app.snipnet.desktop.auth.Session
import app.snipnet.desktop.auth.TokenStore
import app.snipnet.desktop.court.CourtSelectionStateHolder
import app.snipnet.desktop.editor.EditorStateHolder
import app.snipnet.desktop.editor.SaveQueue
import app.snipnet.desktop.nav.Navigator
import app.snipnet.desktop.nav.Screen
import app.snipnet.desktop.projects.ImportPipeline
import app.snipnet.desktop.projects.ProjectsStateHolder
import app.snipnet.desktop.settings.SettingsStateHolder
import app.snipnet.desktop.upload.HttpProxyUploader
import app.snipnet.desktop.video.FfmpegProxyTranscoder
import app.snipnet.desktop.video.JavaCvVideoEngine
import app.snipnet.desktop.video.VideoEngine
import app.snipnet.desktop.window.WindowSettingsStore
import app.snipnet.shared.api.SnipnetApi
import app.snipnet.shared.store.ProjectStore
import app.snipnet.shared.store.openClosableDatabase
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Manual dependency container, created once in `main` and handed to the UI. Long-lived services are `val`s (or
 * `by lazy` when expensive); screen state holders are created by factory functions so each screen instance gets a
 * fresh one. Features add their services here as they land (video engine).
 *
 * @param engine HTTP engine of the API client; tests inject a mock engine.
 * @param baseUrl API root; defaults to `SNIPNET_API_URL` or the local development server.
 * @param videoEngineOverride replaces the JavaCV engine, so UI tests can run the screens without decoding real video.
 */
class AppContainer(
    dataDir: Path = defaultDataDir(),
    private val engine: HttpClientEngine = CIO.create(),
    baseUrl: String = defaultBaseUrl(),
    videoEngineOverride: VideoEngine? = null,
) : AutoCloseable {
    val windowSettingsStore = WindowSettingsStore(dataDir.resolve("window.json"))

    val videoEngine: VideoEngine by lazy {
        videoEngineOverride ?: JavaCvVideoEngine(dataDir.resolve("cache").resolve("media"))
    }

    val navigator = Navigator(start = Screen.Login)

    val api = SnipnetApi(engine, baseUrl)

    /** Kept separate from [projectStore] so [close] releases the connection only when it was ever opened. */
    private val database = lazy { openClosableDatabase(dataDir.resolve("snipnet.db")) }

    val projectStore: ProjectStore by lazy {
        ProjectStore(
            database.value.database,
            newId = { UUID.randomUUID().toString() },
            now = System::currentTimeMillis,
            currentUserId = { session.user.value?.id },
        ).also { store ->
            // Projects from before user_id existed belong to nobody; drop them and their derived proxy files once.
            store.purgeOwnerless().forEach { project -> project.proxyPath?.let(::deleteProxyFile) }
        }
    }

    /** Where [ImportPipeline] writes the proxies it transcodes, one `<project id>.mp4` per project. */
    private val proxyDir: Path = dataDir.resolve("proxies").toAbsolutePath().normalize()

    /**
     * Deletes the proxy at [proxyPath] of a purged project, but only when it lies inside [proxyDir]. The purge runs
     * unattended at startup, so a path that points anywhere else (a hand-edited database, a proxy path that happened
     * to be the original video) is left alone rather than risking the user's own footage.
     */
    private fun deleteProxyFile(proxyPath: String) {
        runCatching {
            val file = Path.of(proxyPath).toAbsolutePath().normalize()
            if (file.startsWith(proxyDir)) Files.deleteIfExists(file)
        }
    }

    /**
     * Import, upload and analysis run here for the whole app lifetime. A finished analysis opens the editor, but only
     * when the user is still looking at the projects list, so it never yanks them out of another screen.
     */
    private val pipelineLazy =
        lazy {
            ImportPipeline(
                api = api,
                store = projectStore,
                videoEngine = videoEngine,
                transcoder = FfmpegProxyTranscoder(),
                uploader = HttpProxyUploader(engine),
                proxyDir = proxyDir,
                currentUserId = { session.user.value?.id },
                onAnalyzed = { project ->
                    if (navigator.current == Screen.Projects) navigator.push(Screen.Editor(project.id))
                },
            )
        }

    val pipeline: ImportPipeline by pipelineLazy

    /**
     * Uploads saved corrections. [startBackgroundWork] runs its loop, which retries the saves of projects without an
     * open editor, so a save queued while offline still goes out after the editor was closed.
     */
    val saveQueue: SaveQueue by lazy {
        SaveQueue(projectStore, loadSets = api::listSegmentSets) { remoteVideoId, save ->
            api.createSegmentSet(remoteVideoId, save.parentSetId, save.segments, save.editLog, save.isFinal)
        }
    }

    /** Scope of the work started by [startBackgroundWork]; cancelled by [close]. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Starts the background work that must run for the whole app lifetime, called once from the root composable. */
    fun startBackgroundWork() {
        saveQueue.start(backgroundScope)
    }

    /**
     * Stops everything the container started: the save-queue loop, a running import or upload, the HTTP client, the
     * shared [engine] and the database connection. Called when the window closes and by tests, which would otherwise
     * leave the loop running and the database file open for the rest of the JVM. Safe to call twice; the container
     * must not be used afterwards.
     *
     * The engine is closed explicitly because Ktor's `HttpClient(engine)` does not manage an engine instance it was
     * handed, so closing [api] alone would leave a CIO engine's selector and dispatcher threads running. The container
     * owns the engine either way: the uploader shares it and nothing else outlives the container.
     */
    override fun close() {
        backgroundScope.cancel()
        if (pipelineLazy.isInitialized()) pipeline.reset()
        api.close()
        engine.close()
        if (database.isInitialized()) database.value.close()
    }

    val session =
        Session(
            api,
            TokenStore(dataDir.resolve("token")),
            onLoggedOut = { pipeline.reset() },
            onSessionExpired = { navigator.resetTo(Screen.Login) },
        )

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
            saveQueue,
            loadSets = { remoteVideoId -> api.listSegmentSets(remoteVideoId) },
        )

    /** Backs the settings dialog; the dialog closes it when it leaves the composition. */
    fun settingsStateHolder() = SettingsStateHolder(session)

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
