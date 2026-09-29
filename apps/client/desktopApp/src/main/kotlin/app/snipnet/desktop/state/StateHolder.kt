package app.snipnet.desktop.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel-like base class: a screen's whole UI state lives in one immutable [State] value exposed as a
 * [StateFlow], and all work runs in [scope], which is cancelled by [close] when the screen leaves the back stack.
 *
 * The scope uses a [SupervisorJob] so one failed child coroutine does not cancel the others, and defaults to the
 * Swing/main dispatcher so state updates are safe to read from composables. Tests pass a test dispatcher instead.
 */
abstract class StateHolder<State>(
    initial: State,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(initial)

    val state: StateFlow<State> = mutableState.asStateFlow()

    protected val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Atomically derives the next state from the current one. */
    protected fun update(transform: (State) -> State) = mutableState.update(transform)

    override fun close() = scope.cancel()
}
