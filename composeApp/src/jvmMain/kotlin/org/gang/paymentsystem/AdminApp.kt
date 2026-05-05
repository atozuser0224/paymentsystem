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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface

@Composable
fun AdminApp() {
    val scope = rememberCoroutineScope()
    val api = remember { PaymentApi() }

    var summary by remember { mutableStateOf(AdminSummary()) }
    var allTransactions by remember { mutableStateOf<List<TransactionDTO>>(emptyList()) }
    var filterUuid by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var lastRefresh by remember { mutableStateOf("") }
    val localIp by remember { mutableStateOf(getLocalIpAddresses()) }

    // Auto-refresh every 10 seconds
    LaunchedEffect(Unit) {
        while (true) {
            try {
                summary = api.getAdminSummary()
                allTransactions = api.getTransactions(null)
                lastRefresh = java.time.LocalTime.now().toString().take(8)
            } catch (e: Exception) {
                statusMessage = "서버 연결 실패: ${e.message}"
            }
            delay(10_000)
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1565C0),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFBBDEFB),
            secondary = Color(0xFF00897B),
            background = Color(0xFFF5F5F5),
            surface = Color.White,
            surfaceVariant = Color(0xFFF0F0F0),
            onBackground = Color(0xFF212121),
            onSurface = Color(0xFF212121),
            error = Color(0xFFD32F2F),
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ── Sidebar ──
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "RFID\n관리자",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        lastRefresh,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // ── Main Content ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "RFID 결제 시스템 관리자",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "실시간 거래 모니터링 및 카드 관리",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            summary = api.getAdminSummary()
                                            allTransactions = api.getTransactions(null)
                                            lastRefresh = java.time.LocalTime.now().toString().take(8)
                                            statusMessage = "새로고침 완료"
                                        } catch (e: Exception) {
                                            statusMessage = "오류: ${e.message}"
                                        }
                                    }
                                }
                            ) { Text("새로고침") }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // ── Summary Cards ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SummaryCard("등록된 카드", "${summary.totalCards}", Color(0xFF1565C0), Modifier.weight(1f))
                        SummaryCard("총 거래 건수", "${summary.totalTransactions}", Color(0xFF00897B), Modifier.weight(1f))
                        SummaryCard("총 입금액", "₩${formatAmount(summary.totalDeposits)}", Color(0xFF2E7D32), Modifier.weight(1f))
                        SummaryCard("총 출금액", "₩${formatAmount(summary.totalWithdrawals)}", Color(0xFFC62828), Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(20.dp))

                    // ── Transaction Table ──
                    Card(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "거래 내역",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = filterUuid,
                                        onValueChange = { filterUuid = it },
                                        label = { Text("카드 UUID 필터") },
                                        modifier = Modifier.width(200.dp),
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { filterUuid = "" }) {
                                        Text("초기화")
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            if (statusMessage.isNotEmpty()) {
                                Text(
                                    statusMessage,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(4.dp))
                            }

                            // Table header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                TableHeader("시간", Modifier.weight(0.18f))
                                TableHeader("사용자", Modifier.weight(0.12f))
                                TableHeader("UID", Modifier.weight(0.18f))
                                TableHeader("금액", Modifier.weight(0.12f))
                                TableHeader("유형", Modifier.weight(0.1f))
                                TableHeader("위치", Modifier.weight(0.3f))
                            }

                            Spacer(Modifier.height(4.dp))

                            val filteredTx = if (filterUuid.isBlank()) {
                                allTransactions
                            } else {
                                allTransactions.filter {
                                    it.uuid.contains(filterUuid, ignoreCase = true)
                                }
                            }

                            if (filteredTx.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(200.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (allTransactions.isEmpty()) "거래 내역이 없습니다"
                                        else "필터와 일치하는 거래가 없습니다",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn {
                                    items(filteredTx) { tx ->
                                        TransactionRow(tx)
                                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    // Status bar
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "마지막 갱신: $lastRefresh | 10초마다 자동 갱신",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "서버: $localIp",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun TableHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer
    )
}

@Composable
private fun TransactionRow(tx: TransactionDTO) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            tx.timestamp.takeLast(12),
            modifier = Modifier.weight(0.18f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            tx.userName,
            modifier = Modifier.weight(0.12f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            tx.uuid,
            modifier = Modifier.weight(0.18f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "₩${formatAmount(tx.amount)}",
            modifier = Modifier.weight(0.12f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        // Type badge
        Box(
            modifier = Modifier.weight(0.1f)
        ) {
            val (label, color) = if (tx.type == "DEPOSIT") {
                "입금" to Color(0xFF2E7D32)
            } else {
                "출금" to Color(0xFFC62828)
            }
            Text(
                label,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        // Location
        Text(
            if (tx.locationName.isNotEmpty()) tx.locationName
            else if (tx.latitude != 0.0) "${"%.4f".format(tx.latitude)}, ${"%.4f".format(tx.longitude)}"
            else "-",
            modifier = Modifier.weight(0.3f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun getLocalIpAddresses(): String {
    return try {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
            .map { it.hostAddress }
            .joinToString(", ")
            .ifEmpty { "localhost" }
    } catch (_: Exception) {
        "localhost"
    }
}
