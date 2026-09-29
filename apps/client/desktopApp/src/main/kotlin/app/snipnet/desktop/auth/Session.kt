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
 * @param onLoggedOut runs on logout so other per-account data (the local project list) can be dropped.
 */
class Session(
    private val api: SnipnetApi,
    private val tokenStore: TokenStore,
    private val onLoggedOut: () -> Unit = {},
) {
    private val mutableUser = MutableStateFlow<User?>(null)

    val user: StateFlow<User?> = mutableUser.asStateFlow()

    /**
     * Resumes a session from the stored token. Returns true when the server still accepts it. An expired or revoked
     * token is deleted; when the server is merely unreachable the token is kept so the next start can retry, but the
     * user is treated as signed out for now.
     */
    suspend fun restore(): Boolean {
        val stored = tokenStore.load() ?: return false
        api.token = stored
        return try {
            mutableUser.value = api.me()
            true
        } catch (e: ApiError.Unauthorized) {
            discardToken()
            false
        } catch (e: ApiError) {
            api.token = null
            false
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

    fun logout() {
        discardToken()
        mutableUser.value = null
        onLoggedOut()
    }

    private fun adopt(auth: app.snipnet.shared.model.AuthResponse) {
        api.token = auth.token
        tokenStore.save(auth.token)
        mutableUser.value = auth.user
    }

    private fun discardToken() {
        api.token = null
        tokenStore.clear()
    }
}
