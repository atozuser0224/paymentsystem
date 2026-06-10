package org.gang.paymentsystem

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StubDevicePlatform : DevicePlatform {
    private val _state = MutableStateFlow<DeviceUiState>(DeviceUiState.Disconnected)
    override val state: StateFlow<DeviceUiState> = _state

    override fun getAvailableDeviceNames(): List<String> {
        _state.value = DeviceUiState.Error("이 플랫폼에서는 기기 연결을 지원하지 않습니다.")
        return emptyList()
    }

    override fun connect(deviceName: String) {
        _state.value = DeviceUiState.Error("이 플랫폼에서는 기기 연결을 지원하지 않습니다.")
    }

    override fun disconnect() {
        _state.value = DeviceUiState.Disconnected
    }

    override fun sendResponse(success: Boolean, reason: String?) { }
}

class StubLocationPlatform : LocationPlatform {
    override suspend fun getCurrentLocation(): LocationData? = null
}
