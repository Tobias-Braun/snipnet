package app.snipnet.desktop

import app.snipnet.desktop.nav.Navigator
import app.snipnet.desktop.nav.Screen
import app.snipnet.desktop.state.StateHolder
import app.snipnet.desktop.window.WindowSettings
import app.snipnet.desktop.window.WindowSettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.awt.Rectangle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigatorTest {
    @Test
    fun pushAndBackWalkTheStack() {
        val navigator = Navigator(Screen.Login)
        navigator.push(Screen.Projects)
        navigator.push(Screen.CourtSelection("v1"))
        assertEquals(Screen.CourtSelection("v1"), navigator.current)

        assertTrue(navigator.back())
        assertEquals(Screen.Projects, navigator.current)
    }

    @Test
    fun backOnTheRootIsANoOp() {
        val navigator = Navigator(Screen.Login)
        assertFalse(navigator.back())
        assertEquals(listOf<Screen>(Screen.Login), navigator.stack.value)
    }

    @Test
    fun resetReplacesTheWholeStack() {
        val navigator = Navigator(Screen.Login)
        navigator.push(Screen.Projects)
        navigator.push(Screen.Editor("v1"))
        navigator.resetTo(Screen.Login)
        assertEquals(listOf<Screen>(Screen.Login), navigator.stack.value)
        assertFalse(navigator.canGoBack)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StateHolderTest {
    private class CounterHolder(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) : StateHolder<Int>(0, dispatcher) {
        fun incrementLater() {
            scope.launch { update { it + 1 } }
        }

        val active get() = scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive
    }

    @Test
    fun coroutinesUpdateTheExposedState() =
        runTest {
            val holder = CounterHolder(StandardTestDispatcher(testScheduler))
            holder.incrementLater()
            holder.incrementLater()
            advanceUntilIdle()
            assertEquals(2, holder.state.value)
        }

    @Test
    fun closeCancelsTheScope() =
        runTest {
            val holder = CounterHolder(StandardTestDispatcher(testScheduler))
            holder.incrementLater()
            holder.close()
            advanceUntilIdle()
            assertEquals(0, holder.state.value)
            assertFalse(holder.active)
        }
}

class WindowSettingsStoreTest {
    private fun store() = WindowSettingsStore(Files.createTempDirectory("snipnet-test").resolve("nested/window.json"))

    @Test
    fun missingFileYieldsDefaults() {
        assertEquals(WindowSettings.DEFAULT, store().load())
    }

    @Test
    fun savedSettingsAreRestored() {
        val store = store()
        val settings = WindowSettings(width = 900f, height = 700f, x = 40f, y = 60f, maximized = true)
        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test
    fun corruptFileYieldsDefaults() {
        val dir = Files.createTempDirectory("snipnet-test")
        val file = dir.resolve("window.json")
        Files.writeString(file, "{not json")
        assertEquals(WindowSettings.DEFAULT, WindowSettingsStore(file).load())
    }

    @Test
    fun implausibleSizeIsReplacedByDefaults() {
        val sanitized = WindowSettings(width = 10f, height = 5f, x = 1f, y = null).sanitized()
        assertEquals(WindowSettings.DEFAULT.width, sanitized.width)
        assertEquals(WindowSettings.DEFAULT.height, sanitized.height)
        assertEquals(null, sanitized.x)
    }

    @Test
    fun positionOnAnAttachedScreenIsKept() {
        val screens = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(1920, 0, 2560, 1440))
        val onSecond = WindowSettings(width = 900f, height = 700f, x = 2000f, y = 100f)
        assertEquals(onSecond, onSecond.visibleOn(screens))
    }

    @Test
    fun positionOnADetachedScreenIsDropped() {
        val screens = listOf(Rectangle(0, 0, 1920, 1080))
        val settings = WindowSettings(width = 900f, height = 700f, x = 2000f, y = 100f, maximized = true)
        assertEquals(settings.copy(x = null, y = null), settings.visibleOn(screens))
    }

    @Test
    fun titleBarBarelyOnScreenIsTreatedAsUnreachable() {
        val screens = listOf(Rectangle(0, 0, 1920, 1080))
        assertEquals(null, WindowSettings(width = 900f, height = 700f, x = 1900f, y = 100f).visibleOn(screens).x)
        assertEquals(null, WindowSettings(width = 900f, height = 700f, x = 100f, y = -500f).visibleOn(screens).y)
    }

    @Test
    fun maximizingKeepsTheFloatingGeometry() {
        val floating = WindowSettings(width = 900f, height = 700f, x = 40f, y = 60f)
        val maximized = floating.withGeometry(width = 2560f, height = 1440f, x = 0f, y = 0f, maximized = true)
        assertEquals(floating.copy(maximized = true), maximized)

        val restored = maximized.withGeometry(width = 1000f, height = 750f, x = 50f, y = 70f, maximized = false)
        assertEquals(WindowSettings(width = 1000f, height = 750f, x = 50f, y = 70f, maximized = false), restored)
    }
}
