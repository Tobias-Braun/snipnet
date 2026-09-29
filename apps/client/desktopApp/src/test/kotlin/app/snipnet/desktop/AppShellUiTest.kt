package app.snipnet.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.editor.FakeEngine
import app.snipnet.desktop.nav.Screen
import app.snipnet.desktop.ui.App
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

private const val SCREEN_TIMEOUT_MS = 10_000L

/**
 * Renders the whole app shell (`App` with a real [AppContainer]) and clicks through login, projects, court selection
 * and the editor, asserting the title of each screen. Only the network (a mock engine) and the video decoder (a fake
 * engine) are replaced, so navigation, state holders and composables are the production ones. Compose's test runtime
 * renders offscreen with the software renderer, so no display server is needed on CI.
 */
@OptIn(ExperimentalTestApi::class)
class AppShellUiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val user =
        """{"id":"u1","email":"anna@example.com","trainingConsent":false,"createdAt":"2026-01-01T00:00:00Z"}"""

    private fun video(status: String) =
        """{"id":"remote-1","filename":"match.mp4","durationMs":60000,"width":1920,"height":1080,"fps":25.0,""" +
            """"proxySizeBytes":1000,"status":"$status","court":null,"createdAt":"2026-01-01T00:00:00Z",""" +
            """"updatedAt":"2026-01-01T00:00:00Z","latestJob":null}"""

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntilExactlyOneExists(hasText(text), timeoutMillis = SCREEN_TIMEOUT_MS)

    @Test
    fun clicksThroughLoginProjectsCourtSelectionAndEditor() =
        runDesktopComposeUiTest {
            // Set by the test thread and read on the mock engine's request thread, hence atomic.
            val videoStatus = AtomicReference("uploaded")
            val http =
                MockEngine { request ->
                    val body =
                        when (request.url.encodedPath) {
                            "/v1/auth/login" -> """{"token":"jwt-1","user":$user}"""
                            "/v1/videos" -> """{"items":[${video(videoStatus.get())}]}"""
                            "/v1/videos/remote-1/segment-sets" -> """{"items":[]}"""
                            else -> null
                        }
                    if (body == null) {
                        val error = """{"error":{"code":"not_found","message":"no route"}}"""
                        respond(error, HttpStatusCode.NotFound, json)
                    } else {
                        respond(body, HttpStatusCode.OK, json)
                    }
                }
            val container =
                AppContainer(
                    Files.createTempDirectory("snipnet-shell"),
                    engine = http,
                    baseUrl = "http://snipnet.test",
                    videoEngineOverride = FakeEngine(),
                )
            val project = container.projectStore.create("/videos/match.mp4", remoteVideoId = "remote-1")

            setContent { App(container) }

            awaitText("New here? Create an account")
            onNodeWithText("Email").performTextInput("anna@example.com")
            onNodeWithText("Password").performTextInput("correct horse battery")
            // The headline and the submit button both read "Sign in"; the button comes second in the tree.
            onAllNodesWithText("Sign in")[1].performClick()

            awaitText("Projects")
            awaitText("Signed in as anna@example.com")
            awaitText("Select court")
            onNodeWithText("match.mp4").assertIsDisplayed()

            onNodeWithText("Select court").performClick()
            awaitText("Mark your court")
            assertEquals(Screen.CourtSelection(project.id), container.navigator.current)

            onNodeWithText("Back").performClick()
            awaitText("Projects")

            videoStatus.set("analyzed")
            onNodeWithText("Refresh").performClick()
            awaitText("Open")
            onNodeWithText("Open").performClick()
            waitUntilExactlyOneExists(hasTestTag("editor"), timeoutMillis = SCREEN_TIMEOUT_MS)
            awaitText("Export")
            assertEquals(Screen.Editor(project.id), container.navigator.current)

            onNodeWithText("Back").performClick()
            awaitText("Projects")
            assertEquals(Screen.Projects, container.navigator.current)
        }
}
