package app.snipnet.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun windowTitleWithoutProjectIsTheProductName() {
        assertEquals("Snipnet", AppInfo.windowTitle())
        assertEquals("Snipnet", AppInfo.windowTitle("   "))
    }

    @Test
    fun windowTitleWithProjectPrefixesTheProjectName() {
        assertEquals("Club finals – Snipnet", AppInfo.windowTitle(" Club finals "))
    }
}
