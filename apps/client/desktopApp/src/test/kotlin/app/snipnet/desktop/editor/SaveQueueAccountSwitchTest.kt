package app.snipnet.desktop.editor

import app.snipnet.shared.model.Segment
import app.snipnet.shared.store.PendingSave
import app.snipnet.shared.store.ProjectStore
import app.snipnet.shared.store.openInMemoryDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A queued save must never go out under the token of an account that does not own its project. */
class SaveQueueAccountSwitchTest {
    private var signedIn: String? = "u1"
    private val store =
        ProjectStore(openInMemoryDatabase(), newId = { "p1" }, now = { 1L }, currentUserId = { signedIn })
    private val server = FakeSegmentSetServer()
    private val save = PendingSave("set-1", listOf(Segment(0, 10_000)), emptyList(), isFinal = false)

    private fun queueSave() {
        store.create("/videos/match.mp4", remoteVideoId = "remote-1")
        store.setPendingSave("p1", save)
    }

    @Test
    fun aSaveOfAnotherAccountIsNotSentAndStaysQueued() =
        runTest {
            queueSave()
            signedIn = "u2"

            assertEquals(FlushResult.Idle, newQueue(store, server).flush("p1"))

            assertTrue(server.uploads.isEmpty())
            assertNotNull(store.get("p1")?.pendingSave)
        }

    @Test
    fun aSaveIsNotSentWhenNobodyIsSignedIn() =
        runTest {
            queueSave()
            signedIn = null

            assertEquals(FlushResult.Idle, newQueue(store, server).flush("p1"))

            assertTrue(server.uploads.isEmpty())
        }

    @Test
    fun aSaveGoesOutOnceItsOwnerSignsInAgain() =
        runTest {
            queueSave()
            val queue = newQueue(store, server)
            signedIn = "u2"
            queue.flush("p1")

            signedIn = "u1"

            assertTrue(queue.flush("p1") is FlushResult.Saved)
            assertEquals(1, server.uploads.size)
        }

    @Test
    fun theAccountChangingWhileTheParentSetIsLookedUpStopsTheUpload() =
        runTest {
            store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            store.setPendingSave("p1", save.copy(parentSetId = null))
            val queue =
                SaveQueue(
                    store,
                    loadSets = {
                        signedIn = "u2"
                        server.sets
                    },
                    upload = server::upload,
                )

            assertEquals(FlushResult.Idle, queue.flush("p1"))

            assertTrue(server.uploads.isEmpty())
            assertNotNull(store.get("p1")?.pendingSave)
        }
}
