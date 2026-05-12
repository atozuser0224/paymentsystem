package org.gang.paymentsystem

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.coroutineContext

class DesktopSerialManager : DevicePlatform {

    private val _state = MutableStateFlow<DeviceUiState>(DeviceUiState.Disconnected)
    override val state: StateFlow<DeviceUiState> = _state

    private var serialPort: SerialPort? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun getAvailableDeviceNames(): List<String> {
        return SerialPort.getCommPorts().map { port ->
            "${port.systemPortName} - ${port.descriptivePortName}"
        }
    }

    override fun connect(deviceName: String) {
        if (_state.value is DeviceUiState.Connecting) return

        val portName = deviceName.split(" - ").firstOrNull()?.trim() ?: deviceName
        val port = SerialPort.getCommPort(portName)

        try {
            if (!port.openPort()) {
                _state.value = DeviceUiState.Error("포트를 찾을 수 없습니다: $portName")
                port.closePort()
                return
            }
            port.closePort()
            connectPort(port)
        } catch (e: Exception) {
            _state.value = DeviceUiState.Error("포트 접근 실패: ${e.message}")
        }
    }

    private fun connectPort(port: SerialPort) {
        _state.value = DeviceUiState.Connecting

        readJob?.cancel()
        serialPort?.closePort()

        scope.launch {
            try {
                port.setBaudRate(9600)
                port.setNumDataBits(8)
                port.setNumStopBits(SerialPort.ONE_STOP_BIT)
                port.setParity(SerialPort.NO_PARITY)
                port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 0)

                if (!port.openPort()) {
                    _state.value = DeviceUiState.Error("포트 열기 실패: ${port.systemPortName}")
                    return@launch
                }

                serialPort = port
                _state.value = DeviceUiState.Connected(port.descriptivePortName)

                readJob = launch { readLoop(port) }
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
            serialPort?.closePort()
        } catch (_: Exception) { }
        serialPort = null
        _state.value = DeviceUiState.Disconnected
    }

    override fun sendResponse(success: Boolean) {
        try {
            val msg = if (success) "OK\n" else "ERR\n"
            serialPort?.outputStream?.write(msg.toByteArray())
            serialPort?.outputStream?.flush()
        } catch (e: Exception) {
            _state.value = DeviceUiState.Error("전송 실패: ${e.message}")
        }
    }

    private suspend fun readLoop(port: SerialPort) {
        val inputStream = port.inputStream
        val buffer = StringBuilder()

        while (coroutineContext.isActive) {
            try {
                val byteBuf = ByteArray(256)
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
