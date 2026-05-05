package org.gang.paymentsystem

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "RFID 결제 시스템 - 관리자 대시보드",
        state = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            size = DpSize(1200.dp, 800.dp)
        )
    ) {
        AdminApp()
    }
}
