package app.snipnet.desktop.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.snipnet.desktop.theme.SnipnetTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compose UI tests of the editor screen against a fake video engine. The window is 1200 px wide at density 1, so the
 * 60 s test video shows at 20 px per second: the three rallies (5-15 s, 20-30 s, 40-50 s) sit at x 100-300, 400-600
 * and 800-1000, and the rows of the timeline are at y 0-22 (ruler), 22-126 (media rows) and 126-166 (segment track).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class EditorUiTest {
    private val rulerY = 10f
    private val trackY = 146f

    private class Fixture(
        val holder: EditorStateHolder,
        val player: FakePlayer,
    )

    private fun ComposeUiTest.open(): Fixture {
        val store = newStore()
        store.create("/videos/match.mp4", remoteVideoId = "remote-1")
        val engine = FakeEngine()
        val holder =
            EditorStateHolder(
                "p1",
                store,
                engine,
                loadPrediction = { prediction(threeRallies) },
                now = { 1L },
                dispatcher = UnconfinedTestDispatcher(),
            )
        setContent { SnipnetTheme { EditorContent(holder, onBack = {}) } }
        waitForIdle()
        return Fixture(holder, engine.player)
    }

    private fun ComposeUiTest.press(
        key: Key,
        withCtrl: Boolean = false,
    ) {
        onNodeWithTag("editor").performKeyInput {
            if (withCtrl) keyDown(Key.CtrlLeft)
            pressKey(key)
            if (withCtrl) keyUp(Key.CtrlLeft)
        }
        waitForIdle()
    }

    private fun Fixture.segmentCount() = holder.state.value.segments.size

    @Test
    fun showsTheTimelineTransportAndSegmentList() =
        runDesktopComposeUiTest(1200, 800) {
            open()
            onNodeWithTag("timeline").assertIsDisplayed()
            onNodeWithText("Segments (3)").assertIsDisplayed()
            onNodeWithText("Play").assertIsDisplayed()
            onNodeWithTag("timecode").assertIsDisplayed()
        }

    @Test
    fun spaceStartsAndPausesPlayback() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            press(Key.Spacebar)
            assertTrue(fixture.player.isPlaying.value)
            onNodeWithText("Pause").assertIsDisplayed()
            press(Key.Spacebar)
            assertFalse(fixture.player.isPlaying.value)
        }

    @Test
    fun sSplitsAtThePlayheadAndCtrlZUndoesIt() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            fixture.holder.seek(10_000)
            waitForIdle()
            press(Key.S)
            assertEquals(4, fixture.segmentCount())
            onNodeWithText("Segments (4)").assertIsDisplayed()

            press(Key.Z, withCtrl = true)
            assertEquals(3, fixture.segmentCount())
        }

    @Test
    fun deleteRemovesTheSelectedSegment() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            onNodeWithTag("timeline").performMouseInput { click(Offset(500f, trackY)) }
            waitForIdle()
            press(Key.Delete)
            assertEquals(
                listOf(5_000L, 40_000L),
                fixture.holder.state.value.segments
                    .map { it.startMs },
            )
        }

    @Test
    fun markInAndOutThenEnterAddsASegment() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            fixture.holder.seek(32_000)
            press(Key.I)
            fixture.holder.seek(36_000)
            press(Key.O)
            press(Key.Enter)
            assertEquals(4, fixture.segmentCount())
        }

    @Test
    fun nAndPJumpBetweenRallies() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            press(Key.N)
            press(Key.N)
            assertEquals(20_000L, fixture.holder.state.value.playheadMs)
            onNodeWithText("0:20.000 / 1:00.000").assertIsDisplayed()
            press(Key.P)
            assertEquals(5_000L, fixture.holder.state.value.playheadMs)
        }

    @Test
    fun rTogglesRalliesOnlyPreview() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            press(Key.R)
            assertTrue(fixture.holder.state.value.ralliesOnly)
            press(Key.R)
            assertFalse(fixture.holder.state.value.ralliesOnly)
        }

    @Test
    fun clickingTheRulerSeeksThere() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            onNodeWithTag("timeline").performMouseInput { click(Offset(500f, rulerY)) }
            waitForIdle()
            assertEquals(25_000L, fixture.holder.state.value.playheadMs)
            assertEquals(25_000L to true, fixture.player.seeks.last())
        }

    @Test
    fun clickingASegmentSelectsItWithoutMovingThePlayhead() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            onNodeWithTag("timeline").performMouseInput { click(Offset(500f, trackY)) }
            waitForIdle()
            val second = fixture.holder.state.value.segments[1]
            assertEquals(
                setOf(second.id),
                fixture.holder.state.value.timeline!!
                    .selection,
            )
            assertEquals(0L, fixture.holder.state.value.playheadMs)
        }

    @Test
    fun clickingEmptyTrackSeeksAndClearsTheSelection() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            onNodeWithTag("timeline").performMouseInput { click(Offset(500f, trackY)) }
            onNodeWithTag("timeline").performMouseInput { click(Offset(350f, trackY)) }
            waitForIdle()
            assertTrue(
                fixture.holder.state.value.timeline!!
                    .selection
                    .isEmpty(),
            )
            assertEquals(17_500L, fixture.holder.state.value.playheadMs)
        }

    @Test
    fun draggingASegmentEdgeTrimsIt() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            onNodeWithTag("timeline").performMouseInput {
                moveTo(Offset(300f, trackY))
                press()
                moveTo(Offset(320f, trackY))
                moveTo(Offset(340f, trackY))
                release()
            }
            waitForIdle()
            assertEquals(
                17_000L,
                fixture.holder.state.value.segments[0]
                    .endMs,
            )
            press(Key.Z, withCtrl = true)
            assertEquals(
                15_000L,
                fixture.holder.state.value.segments[0]
                    .endMs,
            )
        }

    @Test
    fun draggingAnEdgeSnapsToTheNeighbouringSegment() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            onNodeWithTag("timeline").performMouseInput {
                moveTo(Offset(300f, trackY))
                press()
                moveTo(Offset(396f, trackY))
                release()
            }
            waitForIdle()
            assertEquals(
                20_000L,
                fixture.holder.state.value.segments[0]
                    .endMs,
            )
        }

    @Test
    fun ctrlWheelZoomsAroundTheCursor() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            val before = fixture.holder.state.value.viewport
            // The test input state keeps modifier keys pressed across injections, so ctrl stays down for the wheel.
            onNodeWithTag("editor").performKeyInput { keyDown(Key.CtrlLeft) }
            onNodeWithTag("timeline").performMouseInput {
                moveTo(Offset(600f, trackY))
                scroll(-3f)
            }
            onNodeWithTag("editor").performKeyInput { keyUp(Key.CtrlLeft) }
            waitForIdle()
            val after = fixture.holder.state.value.viewport
            assertTrue(after.pxPerMs > before.pxPerMs * 1.5, "zoomed in: ${after.pxPerMs} vs ${before.pxPerMs}")
            assertTrue(kotlin.math.abs(after.pxToTime(600.0) - before.pxToTime(600.0)) <= 10)
        }

    @Test
    fun draggingTheEmptyTrackScrollsWhenZoomedIn() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            fixture.holder.zoomAround(0.0, 4.0)
            waitForIdle()
            val before = fixture.holder.state.value.viewport.scrollMs
            onNodeWithTag("timeline").performMouseInput {
                moveTo(Offset(700f, trackY))
                press()
                moveTo(Offset(600f, trackY))
                moveTo(Offset(500f, trackY))
                release()
            }
            waitForIdle()
            assertTrue(fixture.holder.state.value.viewport.scrollMs > before)
        }

    @Test
    fun clickingASegmentRowJumpsToIt() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            val third = fixture.holder.state.value.segments[2]
            onNodeWithTag("segment-row-${third.id}").performClick()
            waitForIdle()
            assertEquals(40_000L, fixture.holder.state.value.playheadMs)
            assertEquals(
                setOf(third.id),
                fixture.holder.state.value.timeline!!
                    .selection,
            )
        }

    @Test
    fun theAcceptCheckboxRejectsASegment() =
        runDesktopComposeUiTest(1200, 800) {
            val fixture = open()
            val first = fixture.holder.state.value.segments[0]
            onNodeWithTag("segment-accept-${first.id}").performClick()
            waitForIdle()
            assertFalse(
                fixture.holder.state.value.segments[0]
                    .accepted,
            )
            onNodeWithText("Rejected").assertIsDisplayed()
        }
}
