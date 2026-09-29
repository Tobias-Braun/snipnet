package app.snipnet.desktop

import app.snipnet.desktop.auth.AuthMode
import app.snipnet.desktop.auth.AuthStateHolder
import app.snipnet.desktop.auth.Session
import app.snipnet.desktop.auth.TokenStore
import app.snipnet.shared.api.SnipnetApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SETTLE_TIMEOUT_MS = 5_000L
private const val POLL_MS = 10L

private const val USER_JSON =
    """{"id":"u1","email":"a@b.de","trainingConsent":false,"createdAt":"2026-01-01T00:00:00Z"}"""

class TokenStoreTest {
    private fun store() = TokenStore(Files.createTempDirectory("snipnet-token").resolve("nested/token"))

    @Test
    fun missingTokenLoadsAsNull() {
        assertNull(store().load())
    }

    @Test
    fun savedTokenIsReadBackAndCanBeCleared() {
        val store = store()
        store.save("jwt-1")
        assertEquals("jwt-1", store.load())
        store.save("jwt-2")
        assertEquals("jwt-2", store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun tokenFileIsReadableByOwnerOnly() {
        val file = Files.createTempDirectory("snipnet-token").resolve("token")
        TokenStore(file).save("secret")
        // Overwriting must keep the restriction as well, not only the first creation.
        TokenStore(file).save("secret-2")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionAndAuthTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Env(
        val tokenStore: TokenStore,
        val api: SnipnetApi,
        val session: Session,
        var loggedOut: Boolean = false,
    )

    private fun env(
        stored: String? = null,
        handler: (path: String) -> Pair<HttpStatusCode, String>,
    ): Env {
        val tokenStore = TokenStore(Files.createTempDirectory("snipnet-session").resolve("token"))
        stored?.let(tokenStore::save)
        val api =
            SnipnetApi(
                MockEngine { request ->
                    val (status, body) = handler(request.url.encodedPath)
                    respond(body, status, json)
                },
            )
        lateinit var env: Env
        val session = Session(api, tokenStore, onLoggedOut = { env.loggedOut = true })
        env = Env(tokenStore, api, session)
        return env
    }

    private val authOk = HttpStatusCode.OK to """{"token":"jwt","user":$USER_JSON}"""

    private fun error(
        status: HttpStatusCode,
        code: String,
    ) = status to """{"error":{"code":"$code","message":"server says no"}}"""

    private fun TestScope.holder(
        env: Env,
        onAuthenticated: () -> Unit = {},
    ) = AuthStateHolder(env.session, onAuthenticated, StandardTestDispatcher(testScheduler))

    /**
     * Runs the holder's queued coroutines and waits until its request has finished. The Ktor mock engine completes
     * on its own dispatcher, so the test scheduler alone cannot know when the response has arrived; the wait polls
     * in real time (bounded) and lets the test dispatcher process the continuation after each poll.
     */
    private suspend fun TestScope.settle(holder: AuthStateHolder) {
        withContext(Dispatchers.Default) {
            withTimeout(SETTLE_TIMEOUT_MS) {
                do {
                    testScheduler.advanceUntilIdle()
                    delay(POLL_MS)
                } while (holder.state.value.submitting)
            }
        }
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun loginStoresTokenAndUser() =
        runTest {
            val env = env { authOk }
            env.session.login("a@b.de", "password1")
            assertEquals("jwt", env.api.token)
            assertEquals("jwt", env.tokenStore.load())
            assertEquals(
                "a@b.de",
                env.session.user.value
                    ?.email,
            )
        }

    @Test
    fun logoutClearsTokenUserAndLocalData() =
        runTest {
            val env = env { authOk }
            env.session.login("a@b.de", "password1")
            env.session.logout()
            assertNull(env.api.token)
            assertNull(env.tokenStore.load())
            assertNull(env.session.user.value)
            assertTrue(env.loggedOut)
        }

    @Test
    fun restoreResumesSessionWithStoredToken() =
        runTest {
            val env = env(stored = "old") { HttpStatusCode.OK to USER_JSON }
            assertTrue(env.session.restore())
            assertEquals("old", env.api.token)
            assertNotNull(env.session.user.value)
        }

    @Test
    fun restoreWithoutTokenDoesNothing() =
        runTest {
            assertFalse(env { error(HttpStatusCode.InternalServerError, "internal") }.session.restore())
        }

    @Test
    fun restoreDropsARejectedToken() =
        runTest {
            val env = env(stored = "expired") { error(HttpStatusCode.Unauthorized, "unauthorized") }
            assertFalse(env.session.restore())
            assertNull(env.tokenStore.load())
            assertNull(env.api.token)
        }

    @Test
    fun restoreKeepsTheTokenWhenTheServerIsDown() =
        runTest {
            val env = env(stored = "still-good") { error(HttpStatusCode.BadGateway, "internal") }
            assertFalse(env.session.restore())
            assertEquals("still-good", env.tokenStore.load())
            assertNull(env.session.user.value)
        }

    @Test
    fun invalidInputIsRejectedWithoutARequest() =
        runTest {
            var requests = 0
            val env = env { requests++.let { authOk } }
            val holder = holder(env)
            holder.setEmail("not-an-email")
            holder.submit()
            settle(holder)
            assertEquals("Enter a valid email address.", holder.state.value.emailError)
            assertEquals("Enter your password.", holder.state.value.passwordError)
            assertEquals(0, requests)
        }

    @Test
    fun registrationRequiresEightCharacterPasswords() =
        runTest {
            val env = env { authOk }
            val holder = holder(env)
            holder.setMode(AuthMode.REGISTER)
            holder.setEmail("a@b.de")
            holder.setPassword("short")
            holder.submit()
            settle(holder)
            assertEquals("Use at least 8 characters.", holder.state.value.passwordError)
            assertNull(env.session.user.value)
        }

    @Test
    fun loginWithShortPasswordIsLeftToTheServer() {
        assertNull(AuthStateHolder.validatePassword("short", AuthMode.LOGIN))
    }

    @Test
    fun successfulSubmitAuthenticatesAndClearsThePassword() =
        runTest {
            var authenticated = false
            val env = env { authOk }
            val holder = holder(env) { authenticated = true }
            holder.setEmail(" a@b.de ")
            holder.setPassword("password1")
            holder.submit()
            settle(holder)
            assertTrue(authenticated)
            assertEquals("", holder.state.value.password)
            assertFalse(holder.state.value.submitting)
            assertEquals(
                "a@b.de",
                env.session.user.value
                    ?.email,
            )
        }

    @Test
    fun wrongCredentialsShowAReadableError() =
        runTest {
            var authenticated = false
            val env = env { error(HttpStatusCode.Unauthorized, "unauthorized") }
            val holder = holder(env) { authenticated = true }
            holder.setEmail("a@b.de")
            holder.setPassword("password1")
            holder.submit()
            settle(holder)
            assertFalse(authenticated)
            assertEquals("Incorrect email or password.", holder.state.value.error)
            assertFalse(holder.state.value.submitting)
        }

    @Test
    fun duplicateRegistrationShowsAConflictMessage() =
        runTest {
            val env = env { error(HttpStatusCode.Conflict, "conflict") }
            val holder = holder(env)
            holder.setMode(AuthMode.REGISTER)
            holder.setEmail("a@b.de")
            holder.setPassword("password1")
            holder.submit()
            settle(holder)
            assertEquals("An account with this email already exists.", holder.state.value.error)
        }

    @Test
    fun editingAFieldClearsTheError() =
        runTest {
            val env = env { error(HttpStatusCode.Unauthorized, "unauthorized") }
            val holder = holder(env)
            holder.setEmail("a@b.de")
            holder.setPassword("password1")
            holder.submit()
            settle(holder)
            holder.setPassword("password2")
            assertNull(holder.state.value.error)
        }
}
