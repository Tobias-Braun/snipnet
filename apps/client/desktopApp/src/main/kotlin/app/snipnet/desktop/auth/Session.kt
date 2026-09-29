package app.snipnet.desktop.auth

import app.snipnet.shared.api.ApiError
import app.snipnet.shared.api.SnipnetApi
import app.snipnet.shared.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The signed-in user of this installation. It owns the token lifecycle: after login or registration the token is
 * given to [api] and written to [tokenStore]; [logout] removes both. The UI observes [user] to know whether a
 * session exists.
 *
 * A 401 on any later authenticated call means the token expired or was revoked while the app was running. The
 * session hooks [SnipnetApi.onUnauthorized] and then behaves like a logout that also publishes [notice], which the
 * login screen shows as "session expired".
 *
 * @param onLoggedOut runs on logout so in-flight per-account work (such as running imports) can be stopped. Local projects
 * are not dropped: they are scoped by user id and stay on disk for the next login of the same account.
 * @param onSessionExpired runs after the server rejected the token of a signed-in session, following [onLoggedOut];
 * the app uses it to return to the login screen.
 */
class Session(
    private val api: SnipnetApi,
    private val tokenStore: TokenStore,
    private val onLoggedOut: () -> Unit = {},
    private val onSessionExpired: () -> Unit = {},
) {
    private val mutableUser = MutableStateFlow<User?>(null)
    private val mutableNotice = MutableStateFlow<String?>(null)

    @kotlin.concurrent.Volatile
    private var restoring = false

    init {
        api.onUnauthorized = ::expire
    }

    /** Message for the login screen after the session ended without the user asking for it, null otherwise. */
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    val user: StateFlow<User?> = mutableUser.asStateFlow()

    /**
     * Resumes a session from the stored token. Returns true when the server still accepts it. An expired or revoked
     * token is deleted; when the server is merely unreachable the token is kept so the next start can retry, but the
     * user is treated as signed out for now.
     */
    suspend fun restore(): Boolean {
        val stored = tokenStore.load() ?: return false
        api.token = stored
        // restore() handles a rejected token itself and must not report it as an expired session.
        restoring = true
        return try {
            mutableUser.value = api.me()
            true
        } catch (e: ApiError.Unauthorized) {
            discardToken()
            false
        } catch (e: ApiError) {
            api.token = null
            false
        } finally {
            restoring = false
        }
    }

    suspend fun login(
        email: String,
        password: String,
    ) = adopt(api.login(email, password))

    suspend fun register(
        email: String,
        password: String,
    ) = adopt(api.register(email, password))

    /** Stores the training consent on the server (`PATCH /v1/me`) and adopts the user it returns. */
    suspend fun setTrainingConsent(consent: Boolean) {
        mutableUser.value = api.updateMe(consent)
    }

    fun logout() {
        mutableNotice.value = null
        discardToken()
        mutableUser.value = null
        onLoggedOut()
    }

    private fun expire() {
        if (restoring || mutableUser.value == null) return
        logout()
        mutableNotice.value = SESSION_EXPIRED_MESSAGE
        onSessionExpired()
    }

    /**
     * Persists the token first so that a failing write (full disk, unwritable data dir) leaves the session untouched
     * instead of half signed in; the caller sees the IOException and can report it.
     */
    private fun adopt(auth: app.snipnet.shared.model.AuthResponse) {
        tokenStore.save(auth.token)
        api.token = auth.token
        mutableNotice.value = null
        mutableUser.value = auth.user
    }

    private fun discardToken() {
        api.token = null
        tokenStore.clear()
    }

    companion object {
        const val SESSION_EXPIRED_MESSAGE = "Your session has expired. Please sign in again."
    }
}
