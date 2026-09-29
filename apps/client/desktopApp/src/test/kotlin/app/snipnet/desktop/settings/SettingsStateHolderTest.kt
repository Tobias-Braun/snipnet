package app.snipnet.desktop.settings

import app.snipnet.desktop.auth.Session
import app.snipnet.desktop.auth.TokenStore
import app.snipnet.shared.api.SnipnetApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStateHolderTest {
    private val requests = mutableListOf<Pair<HttpMethod, String>>()
    private var failing = false

    private fun user(consent: Boolean) =
        """{"id":"u1","email":"a@b.de","trainingConsent":$consent,"createdAt":"2026-01-01T00:00:00Z"}"""

    private fun session(): Session {
        val api =
            SnipnetApi(
                MockEngine { request ->
                    val body = String(request.body.toByteArray())
                    requests += request.method to body
                    val json = headersOf(HttpHeaders.ContentType, "application/json")
                    when {
                        request.url.encodedPath == "/v1/auth/login" ->
                            respond("""{"token":"jwt","user":${user(false)}}""", HttpStatusCode.OK, json)
                        failing ->
                            respond(
                                """{"error":{"code":"internal","message":"boom"}}""",
                                HttpStatusCode.InternalServerError,
                                json,
                            )
                        else -> respond(user(body.contains("true")), HttpStatusCode.OK, json)
                    }
                },
            )
        return Session(api, TokenStore(Files.createTempDirectory("snipnet-settings").resolve("token")))
    }

    private fun holderFor(session: Session): SettingsStateHolder {
        runBlocking { session.login("a@b.de", "password1") }
        return SettingsStateHolder(session, UnconfinedTestDispatcher())
    }

    private fun SettingsStateHolder.awaitIdle() =
        runBlocking { withTimeout(5_000) { while (state.value.saving) delay(10) } }

    @Test
    fun startsWithTheConsentOfTheSignedInUser() {
        assertFalse(holderFor(session()).state.value.trainingConsent)
    }

    @Test
    fun togglingSendsThePatchAndAdoptsTheStoredUser() {
        val session = session()
        val holder = holderFor(session)

        holder.setTrainingConsent(true)
        holder.awaitIdle()

        assertTrue(holder.state.value.trainingConsent)
        assertTrue(session.user.value?.trainingConsent == true)
        assertEquals(HttpMethod.Patch, requests.last().first)
        assertTrue(requests.last().second.contains("\"trainingConsent\":true"))
        assertNull(holder.state.value.error)
    }

    @Test
    fun aFailedChangeRestoresTheStoredValueAndShowsTheError() {
        val holder = holderFor(session())
        failing = true

        holder.setTrainingConsent(true)
        holder.awaitIdle()

        assertFalse(holder.state.value.trainingConsent)
        assertNotNull(holder.state.value.error)
    }
}
