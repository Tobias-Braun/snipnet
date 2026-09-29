package app.snipnet.desktop

import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.editor.FakeEngine
import app.snipnet.shared.store.openClosableDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Projects from before the user_id migration are purged when the container first opens the store. The row and its
 * proxy under the data dir go; a proxy path outside the proxies folder must never be deleted, since the purge runs
 * without the user asking for it.
 */
@OptIn(ExperimentalPathApi::class)
class AppContainerPurgeTest {
    private val dataDir = Files.createTempDirectory("snipnet-purge")
    private val outside = Files.createTempDirectory("snipnet-purge-outside")

    @AfterTest
    fun cleanUp() {
        dataDir.deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun openingTheStoreDeletesOwnerlessProjectsAndOnlyTheirProxiesInsideTheDataDir() {
        val proxy = Files.createDirectories(dataDir.resolve("proxies")).resolve("old.mp4")
        Files.writeString(proxy, "proxy")
        val original = Files.writeString(outside.resolve("match.mp4"), "original footage")
        openClosableDatabase(dataDir.resolve("snipnet.db")).use { db ->
            val queries = db.database.projectQueries
            queries.insert("old", null, "/old.mp4", proxy.toString(), null, null, null, 1L, 1L)
            queries.insert("odd", null, original.toString(), original.toString(), null, null, null, 1L, 1L)
        }

        val container =
            AppContainer(
                dataDir,
                engine = MockEngine { respond("{}") },
                baseUrl = "http://snipnet.test",
                videoEngineOverride = FakeEngine(),
            )
        try {
            assertNull(container.projectStore.get("old"))
            assertNull(container.projectStore.get("odd"))
            assertFalse(proxy.exists(), "the proxy of a purged project should be deleted")
            assertTrue(original.exists(), "a proxy path outside the proxies folder must be left alone")
        } finally {
            container.close()
        }
    }
}
