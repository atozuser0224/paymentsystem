package org.gang.paymentsystem

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

class BluetoothManager : DevicePlatform {
    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val _state = MutableStateFlow<DeviceUiState>(DeviceUiState.Disconnected)
    override val state: StateFlow<DeviceUiState> = _state

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun getAvailableDeviceNames(): List<String> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices
            .filter { it.name?.contains("HC-0", ignoreCase = true) == true
                    || it.name?.contains("BT", ignoreCase = true) == true
                    || it.name?.contains("HM-", ignoreCase = true) == true }
            .map { it.name ?: it.address }
            .toList()
    }

    override fun connect(deviceName: String) {
        if (_state.value is DeviceUiState.Connecting) return

        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device = adapter?.bondedDevices?.find {
            (it.name ?: it.address) == deviceName
        } ?: run {
            _state.value = DeviceUiState.Error("기기를 찾을 수 없습니다: $deviceName")
            return
        }
        connectDevice(device)
    }

    private fun connectDevice(device: BluetoothDevice) {
        _state.value = DeviceUiState.Connecting

        readJob?.cancel()
        socket?.close()

        scope.launch {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                adapter?.cancelDiscovery()

                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                outputStream = socket?.outputStream

                _state.value = DeviceUiState.Connected(device.name ?: "Unknown")

                readJob = launch { readLoop(socket!!.inputStream) }
            } catch (e: Exception) {
                _state.value = DeviceUiState.Error("연결 실패: ${e.message}")
                disconnect()
            }
        }
    }

    override fun disconnect() {
        readJob?.cancel()
        readJob = null
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) { }
        socket = null
        outputStream = null
        _state.value = DeviceUiState.Disconnected
    }

    override fun sendResponse(success: Boolean, reason: String?) {
        try {
            val msg = if (success) "OK\n" else "${reason ?: "ERR"}\n"
            outputStream?.write(msg.toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {
            _state.value = DeviceUiState.Error("전송 실패: ${e.message}")
        }
    }

    private suspend fun readLoop(inputStream: InputStream) {
        val buffer = StringBuilder()
        val byteBuf = ByteArray(256)

        while (coroutineContext.isActive) {
            try {
                val bytesRead = inputStream.read(byteBuf)
                if (bytesRead == -1) break

                val chunk = String(byteBuf, 0, bytesRead)
                buffer.append(chunk)

                while (true) {
                    val newlineIdx = buffer.indexOfAny(charArrayOf('\n', '\r'))
                    if (newlineIdx == -1) break

                    val line = buffer.substring(0, newlineIdx).trim()
                    buffer.delete(0, newlineIdx + 1)

                    if (line.startsWith("RFID:")) {
                        val uid = line.removePrefix("RFID:").trim()
                        if (uid.isNotEmpty()) {
                            _state.value = DeviceUiState.CardRead(uid)
                        }
                    } else if (line.startsWith("TRANS:")) {
                        val parts = line.removePrefix("TRANS:").split(",")
                        if (parts.size >= 3) {
                            val uid = parts[0].trim()
                            val amount = parts[1].toLongOrNull()
                            val type = parts[2].trim()
                            if (uid.isNotEmpty() && amount != null && type.isNotEmpty()) {
                                _state.value = DeviceUiState.TransactionRead(uid, amount, type)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    _state.value = DeviceUiState.Error("연결 끊김: ${e.message}")
                }
                break
            }
        }

        disconnect()
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
