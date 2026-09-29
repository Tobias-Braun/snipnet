package app.snipnet.desktop.editor

import app.snipnet.shared.api.ApiError
import app.snipnet.shared.model.Segment
import app.snipnet.shared.store.PendingSave
import app.snipnet.shared.store.ProjectStore
import app.snipnet.shared.store.openInMemoryDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A queued save must never go out under the token of an account that does not own its project. */
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun theRetryLoopSendsNothingWhileSignedOutAndResumesOnLogin() =
        runTest {
            queueSave()
            signedIn = null
            // A refused token counts as temporary, so any request made while signed out would show up as a failure.
            server.failure = ApiError.Unauthorized("unauthorized", "No session")
            newQueue(store, server).start(backgroundScope, intervalMs = 1_000)

            advanceTimeBy(5_500)
            assertTrue(server.uploads.isEmpty())
            assertEquals(0, server.attempts)
            assertNotNull(store.get("p1")?.pendingSave)

            signedIn = "u1"
            server.failure = null
            advanceTimeBy(1_000)

            assertEquals(1, server.uploads.size)
            assertEquals(null, store.get("p1")?.pendingSave)
        }

    @Test
    fun theRetryLoopLeavesTheSaveOfAnotherAccountQueued() =
        runTest {
            queueSave()
            signedIn = "u2"
            newQueue(store, server).start(backgroundScope, intervalMs = 1_000)

            advanceTimeBy(3_500)

            assertTrue(server.uploads.isEmpty())
            assertEquals(0, server.attempts)
            assertNotNull(store.get("p1")?.pendingSave)
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
