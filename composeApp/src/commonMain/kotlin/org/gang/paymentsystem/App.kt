package org.gang.paymentsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────
// Main App
// ─────────────────────────────────────────────

@Composable
fun App(
    devicePlatform: DevicePlatform? = null,
    locationPlatform: LocationPlatform? = null,
    allowDirectAdminAccess: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val api = remember { PaymentApi() }

    // ── Shared State ──
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
    var transactions by remember { mutableStateOf<List<TransactionDTO>>(emptyList()) }
    var logFilter by remember { mutableStateOf(true) } // true = 전체, false = 현재카드
    var narrowTab by remember { mutableStateOf(0) }    // 0 = 결제, 1 = 로그
    var showAdminPanel by remember { mutableStateOf(false) }

    val deviceState by (devicePlatform?.state?.collectAsState()
        ?: remember { mutableStateOf(DeviceUiState.Disconnected) })

    // ── Auto-fetch location on connect ──
    LaunchedEffect(deviceState) {
        if (deviceState is DeviceUiState.Connected && currentLocation == null) {
            try { currentLocation = locationPlatform?.getCurrentLocation() } catch (_: Exception) {}
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
                // 카드 태그 시 거래 로그 새로고침
                refreshTransactions(api, transactions, { transactions = it })
            } catch (e: Exception) {
                resultText = "카드 조회 실패"
                resultSuccess = false
            } finally {
                isProcessing = false
            }
        }
    }

    // ── Handle IR remote transactions from Arduino ──
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
                    { p -> isProcessing = p },
                    { refreshTransactions(api, transactions, { transactions = it }) }
                )
            } catch (e: Exception) {
                resultText = "거래 실패: ${e.message}"
                resultSuccess = false
                devicePlatform?.sendResponse(false)
                isProcessing = false
            }
        }
    }

    // ── Handle Password Input from Arduino ──
    LaunchedEffect(deviceState) {
        if (deviceState is DeviceUiState.PasswordInput) {
            val pw = deviceState as DeviceUiState.PasswordInput
            isProcessing = true
            try {
                when (pw.mode) {
                    "MASTER" -> {
                        val ok = api.verifyMasterPassword(pw.pin)
                        if (ok) {
                            showAdminPanel = true
                            resultText = "관리자 모드"
                            resultSuccess = true
                            devicePlatform?.sendResponse(true)
                        } else {
                            resultText = "마스터 비밀번호 오류"
                            resultSuccess = false
                            devicePlatform?.sendResponse(false)
                        }
                    }
                    "WITHDRAW", "DEPOSIT" -> {
                        val ok = api.verifyPin(pw.uid, pw.pin)
                        if (ok) {
                            val amount = pw.amount.toLongOrNull() ?: 0L
                            if (amount <= 0) {
                                resultText = "올바른 금액을 입력하세요"
                                resultSuccess = false
                                devicePlatform?.sendResponse(false)
                            } else {
                                // Check business lock
                                val status = api.getBusinessStatus()
                                if (status.locked) {
                                    resultText = "영업 종료됨 (${status.until ?: ""})"
                                    resultSuccess = false
                                    devicePlatform?.sendResponse(false)
                                } else {
                                    // Fetch card info
                                    val res = api.registerOrFetchCard("", pw.uid, 0L)
                                    if (res is CardDTO) {
                                        userName = res.userName
                                        currentBalance = res.credit
                                    }
                                    executeTransaction(
                                        scope, api, devicePlatform,
                                        pw.uid, userName, pw.amount, currentLocation,
                                        pw.mode, currentBalance,
                                        { r, s -> resultText = r; resultSuccess = s },
                                        { b -> currentBalance = b },
                                        { p -> isProcessing = p },
                                        { refreshTransactions(api, transactions) { transactions = it } }
                                    )
                                    return@LaunchedEffect
                                }
                            }
                        } else {
                            resultText = "카드 PIN 오류"
                            resultSuccess = false
                            devicePlatform?.sendResponse(false)
                        }
                    }
                }
            } catch (e: Exception) {
                resultText = "인증 실패: ${e.message}"
                resultSuccess = false
                devicePlatform?.sendResponse(false)
            } finally {
                isProcessing = false
            }
        }
    }

    // ── Initial transaction log fetch ──
    LaunchedEffect(Unit) {
        refreshTransactions(api, transactions, { transactions = it })
    }

    // ── Theme ──
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
            // Read window width for responsive layout
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isWide = maxWidth >= 800.dp

                if (isWide) {
                    WideLayout(
                        devicePlatform = devicePlatform,
                        locationPlatform = locationPlatform,
                        api = api,
                        deviceState = deviceState,
                        deviceList = deviceList,
                        showDeviceDialog = showDeviceDialog,
                        showSettingsDialog = showSettingsDialog,
                        serverUrl = serverUrl,
                        uuidInput = uuidInput,
                        userName = userName,
                        amountText = amountText,
                        resultText = resultText,
                        resultSuccess = resultSuccess,
                        currentBalance = currentBalance,
                        currentLocation = currentLocation,
                        isProcessing = isProcessing,
                        transactions = transactions,
                        logFilter = logFilter,
                        showAdminPanel = showAdminPanel,
                        allowDirectAdminAccess = allowDirectAdminAccess,
                        onShowDeviceDialogChange = { showDeviceDialog = it },
                        onShowSettingsDialogChange = { showSettingsDialog = it },
                        onShowAdminPanelChange = { showAdminPanel = it },
                        onServerUrlChange = { serverUrl = it; api.baseUrl = it },
                        onUuidInputChange = { uuidInput = it },
                        onUserNameChange = { userName = it },
                        onAmountTextChange = { amountText = it },
                        onResultChange = { r, s -> resultText = r; resultSuccess = s },
                        onBalanceChange = { currentBalance = it },
                        onLocationChange = { currentLocation = it },
                        onProcessingChange = { isProcessing = it },
                        onTransactionsChange = { transactions = it },
                        onLogFilterChange = { logFilter = it },
                        onDeviceListChange = { deviceList = it },
                        onRefreshLogs = {
                            scope.launch { refreshTransactions(api, transactions) { transactions = it } }
                        }
                    )
                } else {
                    NarrowLayout(
                        devicePlatform = devicePlatform,
                        locationPlatform = locationPlatform,
                        api = api,
                        deviceState = deviceState,
                        deviceList = deviceList,
                        showDeviceDialog = showDeviceDialog,
                        showSettingsDialog = showSettingsDialog,
                        serverUrl = serverUrl,
                        uuidInput = uuidInput,
                        userName = userName,
                        amountText = amountText,
                        resultText = resultText,
                        resultSuccess = resultSuccess,
                        currentBalance = currentBalance,
                        currentLocation = currentLocation,
                        isProcessing = isProcessing,
                        transactions = transactions,
                        logFilter = logFilter,
                        showAdminPanel = showAdminPanel,
                        allowDirectAdminAccess = allowDirectAdminAccess,
                        narrowTab = narrowTab,
                        onNarrowTabChange = { narrowTab = it },
                        onShowDeviceDialogChange = { showDeviceDialog = it },
                        onShowSettingsDialogChange = { showSettingsDialog = it },
                        onShowAdminPanelChange = { showAdminPanel = it },
                        onServerUrlChange = { serverUrl = it; api.baseUrl = it },
                        onUuidInputChange = { uuidInput = it },
                        onUserNameChange = { userName = it },
                        onAmountTextChange = { amountText = it },
                        onResultChange = { r, s -> resultText = r; resultSuccess = s },
                        onBalanceChange = { currentBalance = it },
                        onLocationChange = { currentLocation = it },
                        onProcessingChange = { isProcessing = it },
                        onTransactionsChange = { transactions = it },
                        onLogFilterChange = { logFilter = it },
                        onDeviceListChange = { deviceList = it },
                        onRefreshLogs = {
                            scope.launch { refreshTransactions(api, transactions) { transactions = it } }
                        }
                    )
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
                    Text("연결 가능한 기기가 없습니다.\n데스크탑: 아두이노를 USB로 연결해주세요.")
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
            confirmButton = { TextButton(onClick = { showDeviceDialog = false }) { Text("취소") } }
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
                        "안드로이드 에뮬레이터: http://10.0.2.2:8080\n실제 기기: http://노트북IP:8080",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editedUrl,
                        onValueChange = { editedUrl = it },
                        label = { Text("서버 URL") },
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
            dismissButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("취소") } }
        )
    }
}

// ─────────────────────────────────────────────
// Wide Layout (>= 800dp): Left-Right Split
// ─────────────────────────────────────────────

@Composable
private fun WideLayout(
    devicePlatform: DevicePlatform?,
    locationPlatform: LocationPlatform?,
    api: PaymentApi,
    deviceState: DeviceUiState,
    deviceList: List<String>,
    showDeviceDialog: Boolean,
    showSettingsDialog: Boolean,
    serverUrl: String,
    uuidInput: String,
    userName: String,
    amountText: String,
    resultText: String,
    resultSuccess: Boolean,
    currentBalance: Long?,
    currentLocation: LocationData?,
    isProcessing: Boolean,
    transactions: List<TransactionDTO>,
    logFilter: Boolean,
    showAdminPanel: Boolean,
    allowDirectAdminAccess: Boolean,
    onShowDeviceDialogChange: (Boolean) -> Unit,
    onShowSettingsDialogChange: (Boolean) -> Unit,
    onShowAdminPanelChange: (Boolean) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onUuidInputChange: (String) -> Unit,
    onUserNameChange: (String) -> Unit,
    onAmountTextChange: (String) -> Unit,
    onResultChange: (String, Boolean) -> Unit,
    onBalanceChange: (Long?) -> Unit,
    onLocationChange: (LocationData?) -> Unit,
    onProcessingChange: (Boolean) -> Unit,
    onTransactionsChange: (List<TransactionDTO>) -> Unit,
    onLogFilterChange: (Boolean) -> Unit,
    onDeviceListChange: (List<String>) -> Unit,
    onRefreshLogs: () -> Unit
) {
    if (showAdminPanel) {
        AdminPanel(
            api = api,
            isWide = true,
            uuidInput = uuidInput,
            onClose = { onShowAdminPanelChange(false) },
            onResult = { r, s -> onResultChange(r, s) }
        )
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left: Payment Panel
            PaymentPanel(
                devicePlatform = devicePlatform,
                locationPlatform = locationPlatform,
                api = api,
                deviceState = deviceState,
                deviceList = deviceList,
                uuidInput = uuidInput,
                userName = userName,
                amountText = amountText,
                resultText = resultText,
                resultSuccess = resultSuccess,
                currentBalance = currentBalance,
                currentLocation = currentLocation,
                isProcessing = isProcessing,
                allowDirectAdminAccess = allowDirectAdminAccess,
                modifier = Modifier.weight(0.5f),
                onShowDeviceDialogChange = onShowDeviceDialogChange,
                onShowSettingsDialogChange = onShowSettingsDialogChange,
                onShowAdminPanelChange = onShowAdminPanelChange,
                onUuidInputChange = onUuidInputChange,
                onAmountTextChange = onAmountTextChange,
                onResultChange = onResultChange,
                onBalanceChange = onBalanceChange,
                onLocationChange = onLocationChange,
                onProcessingChange = onProcessingChange,
                onDeviceListChange = onDeviceListChange,
                onRefreshLogs = onRefreshLogs
            )

            // Divider
            VerticalDivider(modifier = Modifier.fillMaxHeight(), color = Color(0xFF333333))

            // Right: Transaction Log Panel
            TransactionLogPanel(
                uuidInput = uuidInput,
                transactions = transactions,
                logFilter = logFilter,
                modifier = Modifier.weight(0.5f),
                onLogFilterChange = onLogFilterChange,
                onRefreshLogs = onRefreshLogs
            )
        }
    }
}

// ─────────────────────────────────────────────
// Narrow Layout (< 800dp): Tab-based
// ─────────────────────────────────────────────

@Composable
private fun NarrowLayout(
    devicePlatform: DevicePlatform?,
    locationPlatform: LocationPlatform?,
    api: PaymentApi,
    deviceState: DeviceUiState,
    deviceList: List<String>,
    showDeviceDialog: Boolean,
    showSettingsDialog: Boolean,
    serverUrl: String,
    uuidInput: String,
    userName: String,
    amountText: String,
    resultText: String,
    resultSuccess: Boolean,
    currentBalance: Long?,
    currentLocation: LocationData?,
    isProcessing: Boolean,
    transactions: List<TransactionDTO>,
    logFilter: Boolean,
    narrowTab: Int,
    showAdminPanel: Boolean,
    allowDirectAdminAccess: Boolean,
    onNarrowTabChange: (Int) -> Unit,
    onShowDeviceDialogChange: (Boolean) -> Unit,
    onShowSettingsDialogChange: (Boolean) -> Unit,
    onShowAdminPanelChange: (Boolean) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onUuidInputChange: (String) -> Unit,
    onUserNameChange: (String) -> Unit,
    onAmountTextChange: (String) -> Unit,
    onResultChange: (String, Boolean) -> Unit,
    onBalanceChange: (Long?) -> Unit,
    onLocationChange: (LocationData?) -> Unit,
    onProcessingChange: (Boolean) -> Unit,
    onTransactionsChange: (List<TransactionDTO>) -> Unit,
    onLogFilterChange: (Boolean) -> Unit,
    onDeviceListChange: (List<String>) -> Unit,
    onRefreshLogs: () -> Unit
) {
    if (showAdminPanel) {
        AdminPanel(
            api = api,
            isWide = false,
            uuidInput = uuidInput,
            onClose = { onShowAdminPanelChange(false) },
            onResult = { r, s -> onResultChange(r, s) }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tab Row
            TabRow(
                selectedTabIndex = narrowTab,
                containerColor = Color(0xFF1A1A1A),
                contentColor = Color(0xFF4CAF50)
            ) {
                Tab(selected = narrowTab == 0, onClick = { onNarrowTabChange(0) }) {
                    Text("결제", modifier = Modifier.padding(12.dp), fontSize = 14.sp)
                }
                Tab(selected = narrowTab == 1, onClick = { onNarrowTabChange(1) }) {
                    Text("거래 로그", modifier = Modifier.padding(12.dp), fontSize = 14.sp)
                }
            }

            when (narrowTab) {
                0 -> PaymentPanel(
                    devicePlatform = devicePlatform,
                    locationPlatform = locationPlatform,
                    api = api,
                    deviceState = deviceState,
                    deviceList = deviceList,
                    uuidInput = uuidInput,
                    userName = userName,
                    amountText = amountText,
                    resultText = resultText,
                    resultSuccess = resultSuccess,
                    currentBalance = currentBalance,
                    currentLocation = currentLocation,
                    isProcessing = isProcessing,
                    allowDirectAdminAccess = allowDirectAdminAccess,
                    modifier = Modifier.fillMaxSize(),
                    onShowDeviceDialogChange = onShowDeviceDialogChange,
                    onShowSettingsDialogChange = onShowSettingsDialogChange,
                    onShowAdminPanelChange = onShowAdminPanelChange,
                    onUuidInputChange = onUuidInputChange,
                    onAmountTextChange = onAmountTextChange,
                    onResultChange = onResultChange,
                    onBalanceChange = onBalanceChange,
                    onLocationChange = onLocationChange,
                    onProcessingChange = onProcessingChange,
                    onDeviceListChange = onDeviceListChange,
                    onRefreshLogs = onRefreshLogs
                )
                1 -> TransactionLogPanel(
                    uuidInput = uuidInput,
                    transactions = transactions,
                    logFilter = logFilter,
                    modifier = Modifier.fillMaxSize(),
                    onLogFilterChange = onLogFilterChange,
                    onRefreshLogs = onRefreshLogs
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// Payment Panel (Left / Tab 1)
// ─────────────────────────────────────────────

@Composable
private fun PaymentPanel(
    devicePlatform: DevicePlatform?,
    locationPlatform: LocationPlatform?,
    api: PaymentApi,
    deviceState: DeviceUiState,
    deviceList: List<String>,
    uuidInput: String,
    userName: String,
    amountText: String,
    resultText: String,
    resultSuccess: Boolean,
    currentBalance: Long?,
    currentLocation: LocationData?,
    isProcessing: Boolean,
    allowDirectAdminAccess: Boolean,
    modifier: Modifier = Modifier,
    onShowDeviceDialogChange: (Boolean) -> Unit,
    onShowSettingsDialogChange: (Boolean) -> Unit,
    onShowAdminPanelChange: (Boolean) -> Unit,
    onUuidInputChange: (String) -> Unit,
    onAmountTextChange: (String) -> Unit,
    onResultChange: (String, Boolean) -> Unit,
    onBalanceChange: (Long?) -> Unit,
    onLocationChange: (LocationData?) -> Unit,
    onProcessingChange: (Boolean) -> Unit,
    onDeviceListChange: (List<String>) -> Unit,
    onRefreshLogs: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.padding(16.dp).fillMaxSize(),
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
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (allowDirectAdminAccess) {
                    TextButton(
                        onClick = { onShowAdminPanelChange(true) },
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("관리자", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { onShowSettingsDialogChange(true) }, contentPadding = PaddingValues(4.dp)) {
                    Text("⚙", fontSize = 16.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(10.dp).clip(CircleShape).background(
                            when (deviceState) {
                                is DeviceUiState.Connected, is DeviceUiState.CardRead -> Color(0xFF4CAF50)
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
                            is DeviceUiState.Error -> (deviceState as DeviceUiState.Error).message
                            else -> "미연결"
                        },
                        fontSize = 12.sp,
                        color = if (deviceState is DeviceUiState.Error) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Scrollable Content ──
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Card Display Card ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (uuidInput.isNotBlank()) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (uuidInput.isBlank()) {
                        Text("카드를 태그해주세요", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Arduino RFID 리더에\n카드를 태그하면 자동 인식",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                        )
                    } else {
                        Text("카드 인식됨", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(uuidInput, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        if (userName.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(userName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (currentBalance != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("잔액", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "₩${formatAmount(currentBalance!!)}",
                                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Amount Input ──
            OutlinedTextField(
                value = amountText,
                onValueChange = { onAmountTextChange(it.filter { c -> c.isDigit() }) },
                label = { Text("금액 입력") },
                placeholder = { Text("0") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isProcessing,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(Modifier.height(10.dp))

            // ── Payment Buttons ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        executeTransaction(
                            scope, api, devicePlatform,
                            uuidInput, userName, amountText, currentLocation,
                            "WITHDRAW", currentBalance,
                            { r, s -> onResultChange(r, s) },
                            { onBalanceChange(it) },
                            { onProcessingChange(it) },
                            onRefreshLogs
                        )
                    },
                    modifier = Modifier.weight(1f).height(64.dp),
                    enabled = canTransact(uuidInput, amountText, deviceState, isProcessing),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("결제", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("출금", fontSize = 11.sp)
                    }
                }
                Button(
                    onClick = {
                        executeTransaction(
                            scope, api, devicePlatform,
                            uuidInput, userName, amountText, currentLocation,
                            "DEPOSIT", currentBalance,
                            { r, s -> onResultChange(r, s) },
                            { onBalanceChange(it) },
                            { onProcessingChange(it) },
                            onRefreshLogs
                        )
                    },
                    modifier = Modifier.weight(1f).height(64.dp),
                    enabled = canTransact(uuidInput, amountText, deviceState, isProcessing),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("입금", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("충전", fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Quick amount buttons ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("1000", "5000", "10000", "50000").forEach { qa ->
                    OutlinedButton(
                        onClick = { onAmountTextChange(qa) },
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("₩${formatAmount(qa.toLong())}", fontSize = 11.sp) }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Location Bar ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📍", fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                if (currentLocation != null) {
                    val lat = (currentLocation!!.latitude * 10000).toLong() / 10000.0
                    val lng = (currentLocation!!.longitude * 10000).toLong() / 10000.0
                    Text("위도: $lat, 경도: $lng", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("위치 정보 없음", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        scope.launch {
                            try { onLocationChange(locationPlatform?.getCurrentLocation()) } catch (_: Exception) {}
                        }
                    },
                    enabled = locationPlatform != null && !isProcessing
                ) { Text("GPS 갱신", fontSize = 11.sp) }
            }

            Spacer(Modifier.height(8.dp))

            // ── Result Banner ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (resultSuccess) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                )
            ) {
                Text(
                    resultText,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                )
            }
        }

        // ── Progress ──
        if (isProcessing) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(8.dp))

        // ── Connect Button ──
        OutlinedButton(
            onClick = {
                if (deviceState is DeviceUiState.Connected || deviceState is DeviceUiState.CardRead) {
                    devicePlatform?.disconnect()
                } else {
                    devicePlatform?.getAvailableDeviceNames()?.let {
                        onDeviceListChange(it)
                        onShowDeviceDialogChange(true)
                    }
                }
            },
            enabled = devicePlatform != null && !isProcessing
        ) {
            Text(if (deviceState is DeviceUiState.Connected) "연결 해제" else "기기 연결")
        }
    }
}

// ─────────────────────────────────────────────
// Transaction Log Panel (Right / Tab 2)
// ─────────────────────────────────────────────

@Composable
private fun TransactionLogPanel(
    uuidInput: String,
    transactions: List<TransactionDTO>,
    logFilter: Boolean,
    modifier: Modifier = Modifier,
    onLogFilterChange: (Boolean) -> Unit,
    onRefreshLogs: () -> Unit
) {
    Column(modifier = modifier.padding(16.dp).fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("거래 로그", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            TextButton(onClick = onRefreshLogs, contentPadding = PaddingValues(4.dp)) {
                Text("새로고침", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Filter Tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = logFilter,
                onClick = { onLogFilterChange(true) },
                label = { Text("전체", fontSize = 12.sp) }
            )
            FilterChip(
                selected = !logFilter,
                onClick = { onLogFilterChange(false) },
                label = { Text("현재 카드", fontSize = 12.sp) }
            )
        }

        Spacer(Modifier.height(8.dp))

        // Filtered transactions
        val filtered = if (logFilter) transactions
        else transactions.filter { uuidInput.isNotBlank() && it.uuid == uuidInput }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (transactions.isEmpty()) "거래 내역이 없습니다" else "해당 카드의 거래 내역이 없습니다",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filtered) { tx -> TransactionItem(tx = tx, isHighlighted = tx.uuid == uuidInput && uuidInput.isNotBlank()) }
            }
        }
    }
}

// ─────────────────────────────────────────────
// Transaction Item
// ─────────────────────────────────────────────

@Composable
private fun TransactionItem(tx: TransactionDTO, isHighlighted: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) Color(0xFF1B3A1B) else Color(0xFF252525)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (tx.type == "DEPOSIT") "입금" else "출금",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (tx.type == "DEPOSIT") Color(0xFF4CAF50) else Color(0xFFEF5350)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tx.userName,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row {
                    Text(
                        tx.uuid.take(16),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF888888),
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tx.timestamp.takeLast(8),
                        fontSize = 10.sp,
                        color = Color(0xFF888888)
                    )
                }
            }
            Text(
                "₩${formatAmount(tx.amount)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (tx.type == "DEPOSIT") Color(0xFF4CAF50) else Color(0xFFEF5350)
            )
        }
    }
}

// ─────────────────────────────────────────────
// Shared Helpers
// ─────────────────────────────────────────────

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
    setProcessing: (Boolean) -> Unit,
    onDone: () -> Unit
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
            val newBalance = if (type == "WITHDRAW") (currentBalance ?: 0) - amount
            else (currentBalance ?: 0) + amount
            setBalance(newBalance)
            setResult("$typeLabel 완료! ₩${formatAmount(amount)} | 잔액: ₩${formatAmount(newBalance)}", true)
            bt?.sendResponse(true)
            onDone()
        } catch (e: Exception) {
            setResult("$typeLabel 실패: ${e.message}", false)
            bt?.sendResponse(false)
            onDone()
        } finally {
            setProcessing(false)
        }
    }
}

// ─────────────────────────────────────────────
// Admin Panel
// ─────────────────────────────────────────────

@Composable
private fun AdminPanel(
    api: PaymentApi,
    isWide: Boolean,
    uuidInput: String,
    onClose: () -> Unit,
    onResult: (String, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var businessStatus by remember { mutableStateOf<BusinessStatusResponse?>(null) }
    var lockUntil by remember { mutableStateOf("") }
    var newMasterPassword by remember { mutableStateOf("") }
    var masterPasswordResult by remember { mutableStateOf<String?>(null) }
    var cardUidInput by remember { mutableStateOf(uuidInput) }
    var newCardPin by remember { mutableStateOf("") }
    var cardPinResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try { businessStatus = api.getBusinessStatus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "관리자 모드",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = onClose) {
                Text("닫기", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Business Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (businessStatus?.locked == true)
                    Color(0xFFB71C1C) else Color(0xFF1B5E20)
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (businessStatus?.locked == true) "영업 종료" else "운영 중",
                    fontSize = 16.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                if (businessStatus?.until != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "~ ${businessStatus!!.until}",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Business Lock Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("영업 제어", fontSize = 16.sp, fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = lockUntil,
                    onValueChange = { lockUntil = it },
                    label = { Text("차단 종료 시간 (예: 22:00)") },
                    placeholder = { Text("HH:mm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (lockUntil.isNotBlank()) {
                                    val until = lockUntil + ":00"
                                    try {
                                        api.lockBusiness(until)
                                        onResult("영업 종료 설정됨 (~${lockUntil})", true)
                                        businessStatus = api.getBusinessStatus()
                                    } catch (e: Exception) {
                                        onResult("실패: ${e.message}", false)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("영업 종료")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                try {
                                    api.lockBusiness("")
                                    onResult("영업 재개됨", true)
                                    businessStatus = api.getBusinessStatus()
                                } catch (e: Exception) {
                                    onResult("실패: ${e.message}", false)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("영업 재개")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Master Password Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("마스터 비밀번호 변경", fontSize = 16.sp, fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newMasterPassword,
                        onValueChange = { newMasterPassword = it },
                        label = { Text("새 비밀번호") },
                        placeholder = { Text("숫자 4~8자리") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                if (newMasterPassword.isNotBlank()) {
                                    try {
                                        api.setMasterPassword(newMasterPassword)
                                        masterPasswordResult = "마스터 비밀번호 변경 완료"
                                        newMasterPassword = ""
                                    } catch (e: Exception) {
                                        masterPasswordResult = "실패: ${e.message}"
                                    }
                                }
                            }
                        },
                        enabled = newMasterPassword.isNotBlank()
                    ) {
                        Text("변경")
                    }
                }

                if (masterPasswordResult != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        masterPasswordResult!!,
                        fontSize = 12.sp,
                        color = if (masterPasswordResult!!.startsWith("실패")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Card PIN Management Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("카드 PIN 설정", fontSize = 16.sp, fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = cardUidInput,
                    onValueChange = { cardUidInput = it },
                    label = { Text("카드 UID") },
                    placeholder = { Text("카드 태그 시 자동 입력") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newCardPin,
                        onValueChange = { newCardPin = it.filter { c -> c.isDigit() } },
                        label = { Text("새 PIN") },
                        placeholder = { Text("숫자 4~8자리") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                if (cardUidInput.isNotBlank() && newCardPin.isNotBlank()) {
                                    try {
                                        api.setCardPin(cardUidInput, newCardPin)
                                        cardPinResult = "PIN 설정 완료"
                                        newCardPin = ""
                                    } catch (e: Exception) {
                                        cardPinResult = "실패: ${e.message}"
                                    }
                                }
                            }
                        },
                        enabled = cardUidInput.isNotBlank() && newCardPin.isNotBlank()
                    ) {
                        Text("설정")
                    }
                }

                if (cardPinResult != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        cardPinResult!!,
                        fontSize = 12.sp,
                        color = if (cardPinResult!!.startsWith("실패")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Placeholder buttons for future features
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onResult("장비 제어 기능은 준비 중입니다", true) },
                modifier = Modifier.weight(1f),
                enabled = false
            ) {
                Text("장비 제어", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { onResult("보정 기능은 준비 중입니다", true) },
                modifier = Modifier.weight(1f),
                enabled = false
            ) {
                Text("보정", fontSize = 13.sp)
            }
        }
    }
}

private fun refreshTransactions(
    api: PaymentApi,
    current: List<TransactionDTO>,
    onUpdate: (List<TransactionDTO>) -> Unit
) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        try {
            val txns = api.getTransactions()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onUpdate(txns)
            }
        } catch (_: Exception) {}
    }
}
