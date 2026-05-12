package org.gang.paymentsystem

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val serialManager = DesktopSerialManager()

    Window(
        onCloseRequest = {
            serialManager.destroy()
            exitApplication()
        },
        title = "RFID 결제 단말기",
        state = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            size = DpSize(480.dp, 720.dp)
        )
    ) {
        App(devicePlatform = serialManager)
    }
}
