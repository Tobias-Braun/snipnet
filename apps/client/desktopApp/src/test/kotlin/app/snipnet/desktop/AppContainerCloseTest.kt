package app.snipnet.desktop

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.editor.FakeEngine
import app.snipnet.shared.store.closableDatabaseOn
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

private const val DRAIN_TIMEOUT_MS = 5_000L

/** Forwards everything to [delegate] and counts [close] calls, so a test can see the connection being released. */
private class CloseCountingDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    var closeCalls = 0
        private set

    override fun close() {
        closeCalls++
        delegate.close()
    }
}

/** [AppContainer.close] must stop the save-queue loop and release the database so nothing outlives the container. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class AppContainerCloseTest {
    private val dataDir = Files.createTempDirectory("snipnet-close")
    private val dispatcher = StandardTestDispatcher()
    private val engine = MockEngine { respond("{}") }

    /** The driver of the real file database the container opened; null until the container opens it. */
    private var driver: CloseCountingDriver? = null

    private fun container() =
        AppContainer(
            dataDir,
            engine = engine,
            baseUrl = "http://snipnet.test",
            videoEngineOverride = FakeEngine(),
            openDatabase = { file ->
                val counting = CloseCountingDriver(JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}"))
                driver = counting
                closableDatabaseOn(counting)
            },
        )

    @AfterTest
    fun cleanUp() {
        Dispatchers.resetMain()
        dataDir.deleteRecursively()
    }

    @Test
    fun closeCancelsTheSaveQueueLoop() {
        Dispatchers.setMain(dispatcher)
        val container = container()
        container.startBackgroundWork()
        dispatcher.scheduler.runCurrent()

        container.close()

        // Draining the scheduler only returns once no task is left. A loop that survived close() re-arms its delay
        // forever, so the drain runs on a helper thread that is abandoned after a timeout instead of hanging the build.
        val drain = thread(isDaemon = true) { dispatcher.scheduler.advanceUntilIdle() }
        drain.join(DRAIN_TIMEOUT_MS)
        assertFalse(drain.isAlive, "the save queue loop should be cancelled by close()")
    }

    /**
     * On macOS and Linux an open SQLite file can be deleted anyway, so a leaked connection is caught by watching the
     * driver of the real file database. ClosableDatabaseTest in the shared module checks that closing a
     * [closableDatabaseOn] handle, which the production opener returns too, reaches its driver.
     */
    @Test
    fun closeClosesTheDatabaseDriver() {
        val container = container()
        container.projectStore.list()
        val opened = assertNotNull(driver, "listing projects should open the database")
        assertEquals(0, opened.closeCalls, "the database must stay open until close()")

        container.close()

        assertEquals(1, opened.closeCalls, "close() should close the database driver")
    }

    /** Only a Windows file lock makes this deletion fail while the connection leaks; see [closeClosesTheDatabaseDriver]. */
    @Test
    fun closeReleasesTheDatabaseSoTheDataDirCanBeDeleted() {
        val container = container()
        container.projectStore.list()

        container.close()

        dataDir.deleteRecursively()
        assertFalse(dataDir.exists())
    }

    @Test
    fun closeWorksWithoutTheStoreEverOpenedAndTwice() {
        val container = container()

        container.close()
        container.close()
    }

    /**
     * Ktor does not close an engine that was handed to `HttpClient(engine)` when the client closes, so the container
     * has to close it itself; a CIO engine would otherwise keep its selector and dispatcher threads alive.
     */
    @Test
    fun closeClosesTheHttpEngine() {
        val container = container()

        container.close()

        assertFalse(engine.coroutineContext[Job]!!.isActive, "the HTTP engine should be closed by close()")
    }
}
