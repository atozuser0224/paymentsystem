package org.gang.paymentsystem

import kotlinx.coroutines.flow.StateFlow

sealed class BluetoothUiState {
    data object Disconnected : BluetoothUiState()
    data object Connecting : BluetoothUiState()
    data class Connected(val deviceName: String) : BluetoothUiState()
    data class CardRead(val uid: String) : BluetoothUiState()
    data class Error(val message: String) : BluetoothUiState()
}

interface BluetoothPlatform {
    val state: StateFlow<BluetoothUiState>
    fun getPairedDeviceNames(): List<String>
    fun connect(deviceName: String)
    fun disconnect()
    fun sendResponse(success: Boolean)
}

interface LocationPlatform {
    suspend fun getCurrentLocation(): LocationData?
}
