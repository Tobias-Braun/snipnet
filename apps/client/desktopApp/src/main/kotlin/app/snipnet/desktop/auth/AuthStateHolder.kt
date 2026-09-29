package app.snipnet.desktop.auth

import app.snipnet.desktop.state.StateHolder
import app.snipnet.shared.api.ApiError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class AuthMode { LOGIN, REGISTER }

/**
 * @property emailError validation message for the email field, shown only after a submit attempt.
 * @property passwordError validation message for the password field.
 * @property error failure of the last request (wrong credentials, server unreachable...).
 */
data class AuthState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val error: String? = null,
)

/**
 * State of the combined login/register screen. Input is validated locally first, mirroring the contract (a
 * plausible email; password of at least [MIN_PASSWORD_LENGTH] characters when registering); anything the server
 * rejects is translated into a readable message by [describe].
 *
 * @param onAuthenticated called on the state-holder's dispatcher after a successful login or registration.
 */
class AuthStateHolder(
    private val session: Session,
    private val onAuthenticated: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder<AuthState>(AuthState(), dispatcher) {
    fun setEmail(value: String) = update { it.copy(email = value, emailError = null, error = null) }

    fun setPassword(value: String) = update { it.copy(password = value, passwordError = null, error = null) }

    fun setMode(mode: AuthMode) = update { it.copy(mode = mode, emailError = null, passwordError = null, error = null) }

    fun submit() {
        val current = state.value
        if (current.submitting) return
        val email = current.email.trim()
        val emailError = validateEmail(email)
        val passwordError = validatePassword(current.password, current.mode)
        if (emailError != null || passwordError != null) {
            update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        update { it.copy(submitting = true, error = null) }
        scope.launch {
            try {
                when (current.mode) {
                    AuthMode.LOGIN -> session.login(email, current.password)
                    AuthMode.REGISTER -> session.register(email, current.password)
                }
                update { it.copy(submitting = false, password = "") }
                onAuthenticated()
            } catch (e: ApiError) {
                update { it.copy(submitting = false, error = describe(e, current.mode)) }
            }
        }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8

        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun validateEmail(email: String): String? =
            when {
                email.isEmpty() -> "Enter your email address."
                !EMAIL.matches(email) -> "Enter a valid email address."
                else -> null
            }

        fun validatePassword(
            password: String,
            mode: AuthMode,
        ): String? =
            when {
                password.isEmpty() -> "Enter your password."
                mode == AuthMode.REGISTER && password.length < MIN_PASSWORD_LENGTH ->
                    "Use at least $MIN_PASSWORD_LENGTH characters."
                else -> null
            }

        /** User-facing text for an API failure; server messages are only shown where they are meant for people. */
        fun describe(
            error: ApiError,
            mode: AuthMode,
        ): String =
            when (error) {
                is ApiError.Unauthorized -> "Incorrect email or password."
                is ApiError.Conflict ->
                    if (mode ==
                        AuthMode.REGISTER
                    ) {
                        "An account with this email already exists."
                    } else {
                        error.message.orEmpty()
                    }
                is ApiError.Validation -> error.message.orEmpty()
                is ApiError.RateLimited -> "Too many attempts. Please wait a moment and try again."
                is ApiError.Network -> "Cannot reach the server. Check your connection and try again."
                is ApiError.Server -> "The server had a problem. Please try again later."
                else -> "Something went wrong (${error.code})."
            }
    }
}
