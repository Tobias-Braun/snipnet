package app.snipnet.desktop

import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.editor.FakeEngine
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
import kotlin.test.assertFalse

private const val DRAIN_TIMEOUT_MS = 5_000L

/** [AppContainer.close] must stop the save-queue loop and release the database so nothing outlives the container. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalPathApi::class)
class AppContainerCloseTest {
    private val dataDir = Files.createTempDirectory("snipnet-close")
    private val dispatcher = StandardTestDispatcher()
    private val engine = MockEngine { respond("{}") }

    private fun container() =
        AppContainer(
            dataDir,
            engine = engine,
            baseUrl = "http://snipnet.test",
            videoEngineOverride = FakeEngine(),
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
