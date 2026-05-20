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

    private val logFile by lazy {
        val dir = java.io.File(System.getProperty("user.home"), "PaymentSystem")
        dir.mkdirs()
        java.io.File(dir, "serial-log.txt")
    }

    private fun log(msg: String) {
        val ts = java.time.LocalDateTime.now().toString().replace("T", " ").substringBeforeLast(".")
        val line = "[$ts] $msg"
        try {
            logFile.appendText(line + "\n")
        } catch (_: Exception) { }
        println(line)
    }

    override fun getAvailableDeviceNames(): List<String> {
        return SerialPort.getCommPorts().map { port ->
            "${port.systemPortName} - ${port.descriptivePortName}"
        }
    }

    override fun connect(deviceName: String) {
        if (_state.value is DeviceUiState.Connecting) return

        val portName = deviceName.split(" - ").firstOrNull()?.trim() ?: deviceName
        val port = SerialPort.getCommPort(portName)
        log("연결 시도: $portName")

        connectPort(port)
    }

    private fun connectPort(port: SerialPort) {
        _state.value = DeviceUiState.Connecting

        readJob?.cancel()
        serialPort?.closePort()

        scope.launch {
            try {
                if (!port.openPort()) {
                    val err = "포트 열기 실패: ${port.systemPortName}"
                    log(err)
                    _state.value = DeviceUiState.Error(err)
                    return@launch
                }

                log("포트 열림: ${port.systemPortName}")

                port.setBaudRate(115200)
                port.setNumDataBits(8)
                port.setNumStopBits(SerialPort.ONE_STOP_BIT)
                port.setParity(SerialPort.NO_PARITY)
                port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0)

                log("아두이노 안정화 대기 2초...")
                delay(2000)

                serialPort = port
                log("연결 완료: ${port.descriptivePortName}")
                _state.value = DeviceUiState.Connected(port.descriptivePortName)

                readJob = launch { readLoop(port) }
            } catch (e: Exception) {
                val err = "연결 실패: ${e.message}"
                log("ERROR: $err")
                _state.value = DeviceUiState.Error(err)
                disconnect()
            }
        }
    }

    override fun disconnect() {
        log("연결 해제")
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
            log("응답 전송: ${msg.trim()}")
        } catch (e: Exception) {
            val err = "전송 실패: ${e.message}"
            log("ERROR: $err")
            _state.value = DeviceUiState.Error(err)
        }
    }

    private suspend fun readLoop(port: SerialPort) {
        val inputStream = port.inputStream
        val buffer = StringBuilder()
        log("읽기 루프 시작 (논블로킹)")

        while (coroutineContext.isActive) {
            try {
                val byteBuf = ByteArray(256)
                val bytesRead = inputStream.read(byteBuf)
                if (bytesRead <= 0) {
                    // 데이터 없음 → 잠시 대기 후 재시도
                    delay(100)
                    continue
                }

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
                            log("카드 읽음: $uid")
                            _state.value = DeviceUiState.CardRead(uid)
                        }
                    } else if (line.startsWith("TRANS:")) {
                        val parts = line.removePrefix("TRANS:").split(",")
                        if (parts.size >= 3) {
                            val uid = parts[0].trim()
                            val amount = parts[1].toLongOrNull()
                            val type = parts[2].trim()
                            if (uid.isNotEmpty() && amount != null && type.isNotEmpty()) {
                                log("거래 읽음: uid=$uid amount=$amount type=$type")
                                _state.value = DeviceUiState.TransactionRead(uid, amount, type)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    val errMsg = "연결 끊김: ${e.message} (${e.javaClass.simpleName})"
                    log("ERROR: $errMsg")
                    _state.value = DeviceUiState.Error(errMsg)
                }
                break
            }
        }

        disconnect()
    }

    fun destroy() {
        log("매니저 종료")
        disconnect()
        scope.cancel()
    }
}
