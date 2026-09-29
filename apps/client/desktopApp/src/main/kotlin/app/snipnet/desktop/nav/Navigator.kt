package app.snipnet.desktop.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Destinations of the app. Screens that work on a video carry its id so they can be restored from the stack. */
sealed interface Screen {
    data object Login : Screen

    data object Projects : Screen

    data class CourtSelection(
        val videoId: String,
    ) : Screen

    data class Editor(
        val videoId: String,
    ) : Screen
}

/**
 * A minimal back stack. The stack is never empty: [back] on the root is a no-op and [resetTo] replaces everything
 * (used after login and logout so "back" cannot return to a screen of the previous session).
 */
class Navigator(
    start: Screen,
) {
    private val mutableStack = MutableStateFlow(listOf(start))

    val stack: StateFlow<List<Screen>> = mutableStack.asStateFlow()

    val current: Screen get() = mutableStack.value.last()

    val canGoBack: Boolean get() = mutableStack.value.size > 1

    fun push(screen: Screen) {
        mutableStack.value = mutableStack.value + screen
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        mutableStack.value = mutableStack.value.dropLast(1)
        return true
    }

    fun resetTo(screen: Screen) {
        mutableStack.value = listOf(screen)
    }
}
