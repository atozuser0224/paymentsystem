package org.gang.paymentsystem

import kotlinx.coroutines.flow.StateFlow

sealed class DeviceUiState {
    data object Disconnected : DeviceUiState()
    data object Connecting : DeviceUiState()
    data class Connected(val deviceName: String) : DeviceUiState()
    data class CardRead(val uid: String) : DeviceUiState()
    data class TransactionRead(val uid: String, val amount: Long, val type: String) : DeviceUiState()
    data class Error(val message: String) : DeviceUiState()
    data class PasswordInput(
        val mode: String,    // "MASTER", "WITHDRAW", "DEPOSIT"
        val uid: String,     // 카드 UID (MASTER일 때 "")
        val amount: String,  // 거래 금액 (MASTER일 때 "")
        val pin: String      // 입력된 PIN
    ) : DeviceUiState()
}

interface DevicePlatform {
    val state: StateFlow<DeviceUiState>
    fun getAvailableDeviceNames(): List<String>
    fun connect(deviceName: String)
    fun disconnect()
    fun sendResponse(success: Boolean)
}

interface LocationPlatform {
    suspend fun getCurrentLocation(): LocationData?
}
