package org.gang.paymentsystem

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StubBluetoothPlatform : BluetoothPlatform {
    private val _state = MutableStateFlow<BluetoothUiState>(BluetoothUiState.Disconnected)
    override val state: StateFlow<BluetoothUiState> = _state

    override fun getPairedDeviceNames(): List<String> {
        _state.value = BluetoothUiState.Error("Bluetooth는 Android에서만 지원됩니다.")
        return emptyList()
    }

    override fun connect(deviceName: String) {
        _state.value = BluetoothUiState.Error("Bluetooth는 Android에서만 지원됩니다.")
    }

    override fun disconnect() {
        _state.value = BluetoothUiState.Disconnected
    }

    override fun sendResponse(success: Boolean) { }
}

class StubLocationPlatform : LocationPlatform {
    override suspend fun getCurrentLocation(): LocationData? = null
}
