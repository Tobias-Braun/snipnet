package app.snipnet.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.snipnet.shared.AppInfo

fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = AppInfo.windowTitle()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(AppInfo.NAME)
            }
        }
    }
