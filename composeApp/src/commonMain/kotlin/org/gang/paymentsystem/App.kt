package org.gang.paymentsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun App(
    devicePlatform: DevicePlatform? = null,
    locationPlatform: LocationPlatform? = null
) {
    val scope = rememberCoroutineScope()
    val api = remember { PaymentApi() }

    // ── State ──
    var deviceList by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(api.baseUrl) }
    var uuidInput by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("카드를 태그해주세요") }
    var resultSuccess by remember { mutableStateOf(true) }
    var currentBalance by remember { mutableStateOf<Long?>(null) }
    var currentLocation by remember { mutableStateOf<LocationData?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val deviceState by (devicePlatform?.state?.collectAsState()
        ?: remember { mutableStateOf(DeviceUiState.Disconnected) })

    // ── Auto-fetch location on connect ──
    LaunchedEffect(deviceState) {
        if (deviceState is DeviceUiState.Connected && currentLocation == null) {
            try {
                currentLocation = locationPlatform?.getCurrentLocation()
            } catch (_: Exception) { }
        }
    }

    // ── Handle RFID card reads from Arduino ──
    LaunchedEffect(deviceState) {
        if (deviceState is DeviceUiState.CardRead) {
            val uid = (deviceState as DeviceUiState.CardRead).uid
            uuidInput = uid
            isProcessing = true
            try {
                val res = api.registerOrFetchCard("", uid, 0L)
                if (res is CardDTO) {
                    userName = res.userName
                    currentBalance = res.credit
                    resultText = "${res.userName}님, 잔액: ₩${formatAmount(res.credit)}"
                    resultSuccess = true
                } else {
                    currentBalance = 0L
                    resultText = "신규 카드 등록됨"
                    resultSuccess = true
                }
            } catch (e: Exception) {
                resultText = "카드 조회 실패"
                resultSuccess = false
            } finally {
                isProcessing = false
            }
        }
    }

    // ── Handle IR remote transactions from Arduino (PN532 + IR) ──
    LaunchedEffect(deviceState) {
        if (deviceState is DeviceUiState.TransactionRead) {
            val tx = deviceState as DeviceUiState.TransactionRead
            uuidInput = tx.uid
            amountText = tx.amount.toString()
            isProcessing = true
            try {
                val res = api.registerOrFetchCard("", tx.uid, 0L)
                val name: String
                val balance: Long
                if (res is CardDTO) {
                    name = res.userName
                    balance = res.credit
                    userName = name
                    currentBalance = balance
                } else {
                    name = "사용자"
                    balance = 0L
                    currentBalance = balance
                }
                executeTransaction(
                    scope, api, devicePlatform,
                    tx.uid, name, tx.amount.toString(),
                    currentLocation, tx.type, balance,
                    { r, s -> resultText = r; resultSuccess = s },
                    { b -> currentBalance = b },
                    { p -> isProcessing = p }
                )
            } catch (e: Exception) {
                resultText = "거래 실패: ${e.message}"
                resultSuccess = false
                devicePlatform?.sendResponse(false)
                isProcessing = false
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4CAF50),
            onPrimary = Color.White,
            primaryContainer = Color(0xFF1B5E20),
            secondary = Color(0xFF2196F3),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            surfaceVariant = Color(0xFF2C2C2C),
            onBackground = Color.White,
            onSurface = Color.White,
            error = Color(0xFFEF5350),
            onError = Color.White,
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Top Status Bar ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "RFID 결제 단말기",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Settings button
                        TextButton(
                            onClick = { showSettingsDialog = true },
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text("⚙", fontSize = 18.sp)
                        }

                        // Bluetooth status indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    when (deviceState) {
                                        is DeviceUiState.Connected,
                                        is DeviceUiState.CardRead -> Color(0xFF4CAF50)
                                        is DeviceUiState.Connecting -> Color(0xFFFFC107)
                                        else -> Color(0xFFF44336)
                                    }
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (deviceState) {
                                is DeviceUiState.Connected -> "연결됨"
                                is DeviceUiState.CardRead -> "연결됨"
                                is DeviceUiState.Connecting -> "연결중..."
                                else -> "미연결"
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    } // end settings+bluetooth row
                }

                Spacer(Modifier.height(16.dp))

                // ── Card Display Card ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uuidInput.isNotBlank())
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (uuidInput.isBlank()) {
                            Text(
                                "카드를 태그해주세요",
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Arduino RFID 리더에\n카드를 태그하면 자동으로 인식됩니다",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Text(
                                "카드 인식됨",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                uuidInput,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            if (userName.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    userName,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (currentBalance != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "잔액",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "₩${formatAmount(currentBalance!!)}",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Amount Input ──
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() } },
                    label = { Text("금액 입력") },
                    placeholder = { Text("0") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isProcessing,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(Modifier.height(12.dp))

                // ── Payment Buttons ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Payment (Withdraw) button
                    Button(
                        onClick = {
                            executeTransaction(
                                scope, api, devicePlatform,
                                uuidInput, userName, amountText, currentLocation,
                                "WITHDRAW", currentBalance,
                                { r, s -> resultText = r; resultSuccess = s },
                                { b -> currentBalance = b },
                                { p -> isProcessing = p }
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp),
                        enabled = canTransact(uuidInput, amountText, deviceState, isProcessing),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("결제", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("출금", fontSize = 12.sp)
                        }
                    }

                    // Deposit button
                    Button(
                        onClick = {
                            executeTransaction(
                                scope, api, devicePlatform,
                                uuidInput, userName, amountText, currentLocation,
                                "DEPOSIT", currentBalance,
                                { r, s -> resultText = r; resultSuccess = s },
                                { b -> currentBalance = b },
                                { p -> isProcessing = p }
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp),
                        enabled = canTransact(uuidInput, amountText, deviceState, isProcessing),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1565C0)
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("입금", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("충전", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Quick amount buttons ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("1000", "5000", "10000", "50000").forEach { quickAmount ->
                        OutlinedButton(
                            onClick = { amountText = quickAmount },
                            modifier = Modifier.weight(1f),
                            enabled = !isProcessing,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "₩${formatAmount(quickAmount.toLong())}",
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Location Bar ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📍", fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    if (currentLocation != null) {
                        val lat = (currentLocation!!.latitude * 10000).toLong() / 10000.0
                        val lng = (currentLocation!!.longitude * 10000).toLong() / 10000.0
                        Text(
                            "위도: $lat, 경도: $lng",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "위치 정보 없음 (버튼을 눌러 갱신)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    currentLocation = locationPlatform?.getCurrentLocation()
                                } catch (_: Exception) { }
                            }
                        },
                        enabled = locationPlatform != null && !isProcessing
                    ) {
                        Text("GPS 갱신", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.weight(1f))

                // ── Result Banner ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (resultSuccess)
                            Color(0xFF1B5E20)
                        else
                            Color(0xFFB71C1C)
                    )
                ) {
                    Text(
                        resultText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }

                // ── Bottom Controls ──
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    OutlinedButton(
                        onClick = {
                            if (deviceState is DeviceUiState.Connected ||
                                deviceState is DeviceUiState.CardRead
                            ) {
                                devicePlatform?.disconnect()
                            } else {
                                devicePlatform?.getAvailableDeviceNames()?.let {
                                    deviceList = it
                                    showDeviceDialog = true
                                }
                            }
                        },
                        enabled = devicePlatform != null && !isProcessing
                    ) {
                        Text(
                            if (deviceState is DeviceUiState.Connected) "연결 해제"
                            else "기기 연결"
                        )
                    }
                }

                if (isProcessing) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    // ── Device Selection Dialog ──
    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = { Text("기기 연결") },
            text = {
                if (deviceList.isEmpty()) {
                    Text("연결 가능한 기기가 없습니다.\n안드로이드: HC-05/HC-06/HM-10을 페어링해주세요.\n데스크탑: 아두이노를 USB로 연결해주세요.")
                } else {
                    Column {
                        deviceList.forEach { device ->
                            TextButton(
                                onClick = {
                                    showDeviceDialog = false
                                    devicePlatform?.connect(device)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(device) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceDialog = false }) { Text("취소") }
            }
        )
    }

    // ── Server Settings Dialog ──
    if (showSettingsDialog) {
        var editedUrl by remember { mutableStateOf(serverUrl) }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("서버 주소 설정") },
            text = {
                Column {
                    Text(
                        "안드로이드 에뮬레이터: http://10.0.2.2:8080\n" +
                        "실제 기기: http://노트북IP:8080\n" +
                        "(노트북 IP는 ipconfig로 확인)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editedUrl,
                        onValueChange = { editedUrl = it },
                        label = { Text("서버 URL") },
                        placeholder = { Text("http://192.168.x.x:8080") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    api.baseUrl = editedUrl
                    serverUrl = editedUrl
                    showSettingsDialog = false
                }) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("취소") }
            }
        )
    }
}

// ── Helpers ──

private fun canTransact(
    uuid: String,
    amount: String,
    deviceState: DeviceUiState,
    isProcessing: Boolean
): Boolean {
    return uuid.isNotBlank() &&
            amount.isNotBlank() &&
            (amount.toLongOrNull() ?: 0) > 0 &&
            (deviceState is DeviceUiState.Connected || deviceState is DeviceUiState.CardRead) &&
            !isProcessing
}

private fun executeTransaction(
    scope: kotlinx.coroutines.CoroutineScope,
    api: PaymentApi,
    bt: DevicePlatform?,
    uuid: String,
    userName: String,
    amountText: String,
    location: LocationData?,
    type: String,
    currentBalance: Long?,
    setResult: (String, Boolean) -> Unit,
    setBalance: (Long?) -> Unit,
    setProcessing: (Boolean) -> Unit
) {
    val amount = amountText.toLongOrNull()
    if (amount == null || amount <= 0) {
        setResult("올바른 금액을 입력하세요", false)
        return
    }
    val typeLabel = if (type == "WITHDRAW") "결제" else "입금"

    scope.launch {
        setProcessing(true)
        try {
            val req = TransactionRequest(
                uuid = uuid,
                userName = userName.ifEmpty { "사용자" },
                amount = amount,
                type = type,
                latitude = location?.latitude ?: 0.0,
                longitude = location?.longitude ?: 0.0,
                locationName = ""
            )
            val tx = api.sendTransaction(req)
            val newBalance = if (type == "WITHDRAW")
                (currentBalance ?: 0) - amount
            else
                (currentBalance ?: 0) + amount
            setBalance(newBalance)
            setResult("$typeLabel 완료! ₩${formatAmount(amount)} | 잔액: ₩${formatAmount(newBalance)}", true)
            bt?.sendResponse(true)
        } catch (e: Exception) {
            setResult("$typeLabel 실패: ${e.message}", false)
            bt?.sendResponse(false)
        } finally {
            setProcessing(false)
        }
    }
}

