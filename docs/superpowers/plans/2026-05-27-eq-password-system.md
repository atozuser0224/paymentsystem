# EQ 버튼 비밀번호 시스템 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** EQ 버튼(IR 0x9)으로 마스터 비밀번호 인증 후 관리자 모드 진입, 카드 거래 시 PIN 인증, H2 DB 파일 기반 영구 저장.

**Architecture:** Arduino가 LCD로 PIN 입력을 처리하고 `PASSWD:mode,uid,amount,pin`을 Serial 전송. PC DesktopSerialManager가 파싱해 DeviceUiState.PasswordInput을 emit. App이 API로 서버 검증 후 거래 실행 또는 관리자 패널 표시. 서버는 H2 file DB로 영구 저장 + BCrypt 비밀번호 검증.

**Tech Stack:** Kotlin Multiplatform Compose, Ktor Client/Server, Exposed ORM, H2 Database, jSerialComm, Arduino C++

---

### Task 1: Server — DB 영구화 + 새 테이블 생성

**Files:**
- Modify: `server/src/main/kotlin/org/gang/paymentsystem/Databases.kt:20`
- Modify: `server/src/main/kotlin/org/gang/paymentsystem/CardService.kt:63-91`

- [ ] **Step 1: H2 인메모리를 파일 기반으로 변경**

`Databases.kt:20` 한 줄 변경:

```kotlin
// 변경 전
url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",

// 변경 후
url = "jdbc:h2:file:./data/payment;DB_CLOSE_DELAY=-1",
```

- [ ] **Step 2: CardService.kt에 CardPins, SystemConfig 테이블 추가**

`CardService.kt`의 `Transactions` object 아래에 추가:

```kotlin
object CardPins : Table("card_pins") {
    val id = integer("id").autoIncrement()
    val uuidHash = varchar("uuid_hash", length = 100)
    val pinHash = varchar("pin_hash", length = 200)
    override val primaryKey = PrimaryKey(id)
}

object SystemConfig : Table("system_config") {
    val key = varchar("key", length = 50)
    val value = varchar("value", length = 500)
    override val primaryKey = PrimaryKey(key)
}
```

- [ ] **Step 3: init 블록에 새 테이블 추가**

`CardService.kt`의 `init` 블록:

```kotlin
init {
    transaction(database) {
        addLogger(StdOutSqlLogger)
        SchemaUtils.create(Cards, Transactions, CardPins, SystemConfig)
    }
}
```

- [ ] **Step 4: CardService에 PIN/Config 관련 메서드 추가**

`CardService.kt`의 `getAdminSummary()` 아래에 추가:

```kotlin
suspend fun setCardPin(rawUuid: String, pin: String) = dbQuery {
    val uuidHash = Cards.selectAll()
        .firstOrNull { BCrypt.checkpw(rawUuid, it[Cards.uuid]) }
        ?.let { it[Cards.uuid] } ?: return@dbQuery
    val pinHash = BCrypt.hashpw(pin, BCrypt.gensalt())
    CardPins.insert {
        it[CardPins.uuidHash] = uuidHash
        it[CardPins.pinHash] = pinHash
    }
}

suspend fun verifyCardPin(rawUuid: String, pin: String): Boolean = dbQuery {
    val row = Cards.selectAll()
        .firstOrNull { BCrypt.checkpw(rawUuid, it[Cards.uuid]) }
        ?: return@dbQuery false
    CardPins.selectAll()
        .firstOrNull { it[CardPins.uuidHash] == row[Cards.uuid] }
        ?.let { BCrypt.checkpw(pin, it[CardPins.pinHash]) }
        ?: false
}

suspend fun setConfig(key: String, value: String) = dbQuery {
    SystemConfig.upsert {
        it[SystemConfig.key] = key
        it[SystemConfig.value] = value
    }
}

suspend fun getConfig(key: String): String? = dbQuery {
    SystemConfig.selectAll()
        .firstOrNull { it[SystemConfig.key] == key }
        ?.let { it[SystemConfig.value] }
}

suspend fun deleteConfig(key: String) = dbQuery {
    SystemConfig.deleteWhere { SystemConfig.key eq key }
}
```

- [ ] **Step 5: 서버 빌드 확인**

Run: `./gradlew server:build`

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/org/gang/paymentsystem/Databases.kt server/src/main/kotlin/org/gang/paymentsystem/CardService.kt
git commit -m "feat: H2 file-based DB + CardPins/SystemConfig 테이블 추가"
```

---

### Task 2: Server — 비밀번호 API 엔드포인트 추가

**Files:**
- Modify: `server/src/main/kotlin/org/gang/paymentsystem/Databases.kt` (라우팅 추가)
- Create: `server/src/main/kotlin/org/gang/paymentsystem/PasswordService.kt`

- [ ] **Step 1: PasswordService.kt 생성**

`server/src/main/kotlin/org/gang/paymentsystem/PasswordService.kt`:

```kotlin
package org.gang.paymentsystem

import kotlinx.serialization.Serializable

@Serializable
data class VerifyPinRequest(val uuid: String, val pin: String)

@Serializable
data class VerifyMasterRequest(val password: String)

@Serializable
data class SetMasterRequest(val newPassword: String)

@Serializable
data class SetPinRequest(val uuid: String, val newPin: String)

@Serializable
data class LockBusinessRequest(val until: String)

@Serializable
data class VerifyResponse(val ok: Boolean)

@Serializable
data class BusinessStatusResponse(val locked: Boolean, val until: String?)

class PasswordService(private val cardService: CardService) {

    suspend fun verifyPin(uuid: String, pin: String): Boolean =
        cardService.verifyCardPin(uuid, pin)

    suspend fun verifyMasterPassword(password: String): Boolean {
        val hash = cardService.getConfig("master_password") ?: return false
        return org.mindrot.jbcrypt.BCrypt.checkpw(password, hash)
    }

    suspend fun setMasterPassword(newPassword: String) {
        val hash = org.mindrot.jbcrypt.BCrypt.hashpw(newPassword, org.mindrot.jbcrypt.BCrypt.gensalt())
        cardService.setConfig("master_password", hash)
    }

    suspend fun setCardPin(uuid: String, newPin: String) {
        cardService.setCardPin(uuid, newPin)
    }

    suspend fun lockBusiness(until: String) {
        cardService.setConfig("business_locked_until", until)
    }

    suspend fun unlockBusiness() {
        cardService.deleteConfig("business_locked_until")
    }

    suspend fun getBusinessStatus(): BusinessStatusResponse {
        val until = cardService.getConfig("business_locked_until")
        if (until != null) {
            try {
                val untilTime = java.time.LocalDateTime.parse(until)
                if (java.time.LocalDateTime.now().isBefore(untilTime)) {
                    return BusinessStatusResponse(locked = true, until = until)
                }
            } catch (_: Exception) {}
            cardService.deleteConfig("business_locked_until")
        }
        return BusinessStatusResponse(locked = false, until = null)
    }
}
```

- [ ] **Step 2: Databases.kt에 PasswordService 인스턴스 생성**

`Databases.kt`의 `configureDatabases()` 함수 내, `val historyService = ...` 아래에 추가:

```kotlin
val passwordService = PasswordService(cardService)
```

- [ ] **Step 3: Databases.kt에 라우트 추가**

`Databases.kt`의 routing 블록 마지막 (`delete("/card/{uuid}")` 아래)에 추가:

```kotlin
// ── PIN Verification ──
post("/verify-pin") {
    val req = call.receive<VerifyPinRequest>()
    if (!isValidNfcFormat(req.uuid)) {
        call.respond(HttpStatusCode.BadRequest, VerifyResponse(false))
        return@post
    }
    val ok = passwordService.verifyPin(req.uuid, req.pin)
    call.respond(HttpStatusCode.OK, VerifyResponse(ok))
}

post("/verify-master") {
    val req = call.receive<VerifyMasterRequest>()
    val ok = passwordService.verifyMasterPassword(req.password)
    call.respond(HttpStatusCode.OK, VerifyResponse(ok))
}

post("/set-master") {
    val req = call.receive<SetMasterRequest>()
    passwordService.setMasterPassword(req.newPassword)
    call.respond(HttpStatusCode.OK, VerifyResponse(true))
}

post("/set-pin") {
    val req = call.receive<SetPinRequest>()
    if (!isValidNfcFormat(req.uuid)) {
        call.respond(HttpStatusCode.BadRequest, VerifyResponse(false))
        return@post
    }
    passwordService.setCardPin(req.uuid, req.newPin)
    call.respond(HttpStatusCode.OK, VerifyResponse(true))
}

post("/lock-business") {
    val req = call.receive<LockBusinessRequest>()
    passwordService.lockBusiness(req.until)
    call.respond(HttpStatusCode.OK, VerifyResponse(true))
}

get("/business-status") {
    val status = passwordService.getBusinessStatus()
    call.respond(HttpStatusCode.OK, status)
}
```

- [ ] **Step 4: 서버 빌드 확인**

Run: `./gradlew server:build`

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/org/gang/paymentsystem/PasswordService.kt server/src/main/kotlin/org/gang/paymentsystem/Databases.kt
git commit -m "feat: 비밀번호 검증 API 엔드포인트 추가"
```

---

### Task 3: Shared — Model 타입 + PasswordInput 상태 추가

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/org/gang/paymentsystem/PlatformServices.kt:5-12`
- Modify: `composeApp/src/commonMain/kotlin/org/gang/paymentsystem/Model.kt`

- [ ] **Step 1: PlatformServices.kt에 PasswordInput 상태 추가**

`DeviceUiState` sealed class에 추가:

```kotlin
data class PasswordInput(
    val mode: String,    // "MASTER", "WITHDRAW", "DEPOSIT"
    val uid: String,     // 카드 UID (MASTER일 때 "")
    val amount: String,  // 거래 금액 (MASTER일 때 "")
    val pin: String      // 입력된 PIN
) : DeviceUiState()
```

- [ ] **Step 2: Model.kt에 API 요청/응답 타입 추가**

```kotlin
@Serializable
data class VerifyPinRequest(val uuid: String, val pin: String)

@Serializable
data class VerifyMasterRequest(val password: String)

@Serializable
data class SetMasterRequest(val newPassword: String)

@Serializable
data class SetPinRequest(val uuid: String, val newPin: String)

@Serializable
data class LockBusinessRequest(val until: String)

@Serializable
data class VerifyResponse(val ok: Boolean)

@Serializable
data class BusinessStatusResponse(val locked: Boolean, val until: String? = null)
```

- [ ] **Step 3: 빌드 확인**

Run: `./gradlew composeApp:compileKotlinJvm`

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/org/gang/paymentsystem/PlatformServices.kt composeApp/src/commonMain/kotlin/org/gang/paymentsystem/Model.kt
git commit -m "feat: PasswordInput 상태 + API 모델 타입 추가"
```

---

### Task 4: Client — PaymentApi 비밀번호 메서드 추가

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/org/gang/paymentsystem/PaymentApi.kt`

- [ ] **Step 1: PaymentApi.kt에 비밀번호 검증 메서드 추가**

`PaymentApi` 클래스의 `close()` 메서드 위에 추가:

```kotlin
suspend fun verifyPin(uuid: String, pin: String): Boolean {
    val response = client.post("$baseUrl/verify-pin") {
        contentType(ContentType.Application.Json)
        setBody(VerifyPinRequest(uuid, pin))
    }
    return if (response.status == HttpStatusCode.OK) {
        response.body<VerifyResponse>().ok
    } else false
}

suspend fun verifyMasterPassword(password: String): Boolean {
    val response = client.post("$baseUrl/verify-master") {
        contentType(ContentType.Application.Json)
        setBody(VerifyMasterRequest(password))
    }
    return if (response.status == HttpStatusCode.OK) {
        response.body<VerifyResponse>().ok
    } else false
}

suspend fun setMasterPassword(newPassword: String): Boolean {
    val response = client.post("$baseUrl/set-master") {
        contentType(ContentType.Application.Json)
        setBody(SetMasterRequest(newPassword))
    }
    return response.status == HttpStatusCode.OK
}

suspend fun setCardPin(uuid: String, newPin: String): Boolean {
    val response = client.post("$baseUrl/set-pin") {
        contentType(ContentType.Application.Json)
        setBody(SetPinRequest(uuid, newPin))
    }
    return response.status == HttpStatusCode.OK
}

suspend fun lockBusiness(until: String): Boolean {
    val response = client.post("$baseUrl/lock-business") {
        contentType(ContentType.Application.Json)
        setBody(LockBusinessRequest(until))
    }
    return response.status == HttpStatusCode.OK
}

suspend fun getBusinessStatus(): BusinessStatusResponse {
    val response = client.get("$baseUrl/business-status")
    return if (response.status == HttpStatusCode.OK) {
        response.body<BusinessStatusResponse>()
    } else BusinessStatusResponse(false, null)
}
```

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew composeApp:compileKotlinJvm`

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/org/gang/paymentsystem/PaymentApi.kt
git commit -m "feat: PaymentApi 비밀번호 검증/설정 메서드 추가"
```

---

### Task 5: Desktop — PASSWD Serial 파싱

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/org/gang/paymentsystem/DesktopSerialManager.kt:143-160`

- [ ] **Step 1: readLoop에 PASSWD 파싱 추가**

`DesktopSerialManager.kt`의 readLoop 내에서 `} else if (line.startsWith("TRANS:")) {` 블록 아래에 추가:

```kotlin
} else if (line.startsWith("PASSWD:")) {
    val parts = line.removePrefix("PASSWD:").split(",")
    if (parts.size >= 4) {
        val mode = parts[0].trim()
        val uid = parts[1].trim()
        val amount = parts[2].trim()
        val pin = parts[3].trim()
        log("비밀번호 수신: mode=$mode uid=$uid amount=$amount")
        _state.value = DeviceUiState.PasswordInput(mode, uid, amount, pin)
    }
}
```

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew composeApp:compileKotlinJvm`

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/org/gang/paymentsystem/DesktopSerialManager.kt
git commit -m "feat: PASSWD 시리얼 메시지 파싱 추가"
```

---

### Task 6: App UI — PasswordInput 처리 + AdminPanel

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/org/gang/paymentsystem/App.kt` (여러 위치)

- [ ] **Step 1: App state에 showAdminPanel 변수 추가**

`App()` 함수의 state 변수들 아래에 추가 (line 48 근처):

```kotlin
var showAdminPanel by remember { mutableStateOf(false) }
```

- [ ] **Step 2: PasswordInput LaunchedEffect 추가**

두 번째 `LaunchedEffect(deviceState)` (TransactionRead 처리) 아래에 추가:

```kotlin
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
                        // PIN 검증 성공 → 거래 실행
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
                                return@LaunchedEffect  // executeTransaction가 isProcessing=false 처리
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
```

- [ ] **Step 3: AdminPanel Composable 추가**

파일 끝에 `refreshTransactions` 함수 위에 추가:

```kotlin
// ─────────────────────────────────────────────
// Admin Panel
// ─────────────────────────────────────────────

@Composable
private fun AdminPanel(
    api: PaymentApi,
    isWide: Boolean,
    onClose: () -> Unit,
    onResult: (String, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var businessStatus by remember { mutableStateOf<BusinessStatusResponse?>(null) }
    var lockUntil by remember { mutableStateOf("") }

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
                    if (businessStatus?.locked == true) "🔒 영업 종료" else "🟢 운영 중",
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
                                    val today = java.time.LocalDate.now().toString()
                                    val until = "$today" + "T" + lockUntil + ":00"
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
                                    api.lockBusiness("") // 빈 값으로 잠금 해제
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
```

- [ ] **Step 4: WideLayout/NarrowLayout에 showAdminPanel 분기 추가**

`WideLayout`과 `NarrowLayout`에서 `showAdminPanel`이 true일 때 AdminPanel을 표시하도록 추가. 현재 PaymentPanel과 TransactionLogPanel을 감싸는 부분에 추가.

`WideLayout`의 `Row`를 `Box`로 감싸고:

```kotlin
if (showAdminPanel) {
    AdminPanel(
        api = api,
        isWide = true,
        onClose = { showAdminPanel = false },
        onResult = { r, s -> resultText = r; resultSuccess = s }
    )
} else {
    Row(modifier = Modifier.fillMaxSize()) {
        // ... 기존 PaymentPanel + TransactionLogPanel
    }
}
```

같은 방식으로 `NarrowLayout`도 `showAdminPanel`일 때 AdminPanel을 표시.

- [ ] **Step 5: WideLayout/NarrowLayout에 showAdminPanel 파라미터 전달**

두 함수의 시그니처에 `showAdminPanel: Boolean` 파라미터 추가.

- [ ] **Step 6: 빌드 확인**

Run: `./gradlew composeApp:compileKotlinJvm`

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/org/gang/paymentsystem/App.kt
git commit -m "feat: PasswordInput 처리 + AdminPanel UI 추가"
```

---

### Task 7: Arduino — PIN_ENTRY 모드 + EQ 연결

**Files:**
- Modify: `arduino/rfid_bluetooth/rfid_bluetooth.ino`

- [ ] **Step 1: 전역 변수 추가**

`currentCardUid` 변수 아래 (line 17 근처)에 추가:

```cpp
String pinInput = "";
String pinMode = "";
```

- [ ] **Step 2: PIN 모드 함수 추가**

`handleTimeout()` 함수 아래에 추가:

```cpp
void enterPinMode(const String& mode) {
    pinMode = mode;
    pinInput = "";
    lastInputTime = millis();
    lcd.clear();
    lcd.setCursor(0, 0);
    if (mode == "MASTER") {
        lcd.print("Master Password");
    } else {
        lcd.print("Enter Card PIN");
    }
    lcd.setCursor(0, 1);
    lcd.print("PIN: ");
}

void handlePinDigit(char digit) {
    if (pinInput.length() >= 8) return;
    pinInput += digit;
    lastInputTime = millis();
    lcd.setCursor(5, 1);
    for (unsigned int i = 0; i < pinInput.length(); i++) {
        lcd.print("*");
    }
    lcd.print("           ");
    playTone(digitTones[digit - '0'], 80);
}

void handlePinBackspace() {
    if (pinInput.length() > 0) {
        pinInput.remove(pinInput.length() - 1);
        lcd.setCursor(5, 1);
        for (unsigned int i = 0; i < pinInput.length(); i++) {
            lcd.print("*");
        }
        lcd.print("            ");
    }
}

void submitPin() {
    Serial.print("PASSWD:");
    Serial.print(pinMode);
    Serial.print(",");
    if (pinMode != "MASTER") {
        Serial.print(currentCardUid);
    }
    Serial.print(",");
    if (pinMode != "MASTER") {
        Serial.print(amount);
    }
    Serial.print(",");
    Serial.println(pinInput);

    String response = waitForSerialResponse(5000);

    if (response.startsWith("OK")) {
        flashLed(LED_GREEN, 3, 150);
        lcd.clear();
        lcd.setCursor(0, 0);
        lcd.print("Authorized!");
        delay(1500);
        // PC가 거래도 함께 처리하므로 Arduino는 IDLE로 복귀만 하면 됨
    } else {
        flashLed(LED_RED, 3, 200);
        lcd.clear();
        lcd.setCursor(0, 0);
        lcd.print("Wrong PIN!");
        delay(1500);
    }

    pinMode = "";
    pinInput = "";
    cardAuthorized = false;
    currentCardUid = "";
    amount = "";
    updateLCD();
}
```

- [ ] **Step 3: EQ 버튼 핸들러 변경**

`rfid_bluetooth.ino:467-470`, `else if (IrReceiver.decodedIRData.command == 0x9)`:

```cpp
else if (IrReceiver.decodedIRData.command == 0x9) {
    Serial.println("EQ");
    enterPinMode("MASTER");
}
```

- [ ] **Step 4: +/- 버튼 핸들러 변경 — PIN 모드로 전환**

`handleTransaction`을 바로 호출하지 않고 `enterPinMode`를 호출하도록 변경.

`else if (IrReceiver.decodedIRData.command == 0x7)` (WITHDRAW, line 459-462):

```cpp
else if (IrReceiver.decodedIRData.command == 0x7) {
    Serial.println("-");
    if (amount.length() == 0) {
        flashLed(LED_RED, 2, 200);
        lcd.clear(); lcd.setCursor(0, 0); lcd.print("No amount!");
        lcd.setCursor(0, 1); lcd.print("Enter first");
        delay(1500);
        updateLCD();
    } else {
        enterPinMode("WITHDRAW");
    }
}
```

`else if (IrReceiver.decodedIRData.command == 0x15)` (DEPOSIT, line 463-466):

```cpp
else if (IrReceiver.decodedIRData.command == 0x15) {
    Serial.println("+");
    if (amount.length() == 0) {
        flashLed(LED_RED, 2, 200);
        lcd.clear(); lcd.setCursor(0, 0); lcd.print("No amount!");
        lcd.setCursor(0, 1); lcd.print("Enter first");
        delay(1500);
        updateLCD();
    } else {
        enterPinMode("DEPOSIT");
    }
}
```

- [ ] **Step 5: PIN_ENTRY 모드에서 버튼 처리**

`loop()` 함수에서 카드 미인증 체크 (`if (!cardAuthorized)`) 와 숫자 버튼 처리 사이에 PIN_ENTRY 모드 분기 추가.

PIN_ENTRY 모드일 때는 IR 버튼을 PIN 입력으로 처리. `// 추가: 카드 인증 안 됐으면 모든 버튼 무시` 블록을 수정:

```cpp
// 추가: PIN 입력 모드 체크
if (pinMode.length() > 0) {
    if (IrReceiver.decodedIRData.command == 0x16) { // 0
        handlePinDigit('0');
    } else if (IrReceiver.decodedIRData.command == 0xC) { // 1
        handlePinDigit('1');
    } else if (IrReceiver.decodedIRData.command == 0x18) { // 2
        handlePinDigit('2');
    } else if (IrReceiver.decodedIRData.command == 0x5E) { // 3
        handlePinDigit('3');
    } else if (IrReceiver.decodedIRData.command == 0x8) { // 4
        handlePinDigit('4');
    } else if (IrReceiver.decodedIRData.command == 0x1C) { // 5
        handlePinDigit('5');
    } else if (IrReceiver.decodedIRData.command == 0x5A) { // 6
        handlePinDigit('6');
    } else if (IrReceiver.decodedIRData.command == 0x42) { // 7
        handlePinDigit('7');
    } else if (IrReceiver.decodedIRData.command == 0x52) { // 8
        handlePinDigit('8');
    } else if (IrReceiver.decodedIRData.command == 0x4A) { // 9
        handlePinDigit('9');
    } else if (IrReceiver.decodedIRData.command == 0xD) { // backspace
        handlePinBackspace();
    } else if (IrReceiver.decodedIRData.command == 0x15) { // + (submit)
        if (pinInput.length() > 0) {
            submitPin();
        }
    } else if (IrReceiver.decodedIRData.command == 0x7) { // - (cancel)
        flashLed(LED_RED, 1, 200);
        pinMode = "";
        pinInput = "";
        cardAuthorized = false;
        currentCardUid = "";
        amount = "";
        updateLCD();
    }
    return;
}

// 기존: 카드 인증 안 됐으면 모든 버튼 무시
if (!cardAuthorized) {
    ...
}
```

- [ ] **Step 6: Commit**

```bash
git add arduino/rfid_bluetooth/rfid_bluetooth.ino
git commit -m "feat: Arduino PIN_ENTRY 모드 + EQ 버튼 연결"
```

---

### Task 8: End-to-End 검증

**Files:** 없음 (수동 검증)

- [ ] **Step 1: 서버 실행**

Run: `./gradlew server:run`

Expected: `jdbc:h2:file:./data/payment` 파일 생성 확인, 서버 정상 기동

- [ ] **Step 2: 마스터 비밀번호 초기 설정**

Run: `curl -X POST http://localhost:8080/set-master -H "Content-Type: application/json" -d '{"newPassword":"1234"}'`

Expected: `{"ok":true}`

- [ ] **Step 3: 마스터 비밀번호 검증 테스트**

Run: `curl -X POST http://localhost:8080/verify-master -H "Content-Type: application/json" -d '{"password":"1234"}'`

Expected: `{"ok":true}`

Run: `curl -X POST http://localhost:8080/verify-master -H "Content-Type: application/json" -d '{"password":"wrong"}'`

Expected: `{"ok":false}`

- [ ] **Step 4: 데이터 영구성 확인**

서버 재시작 후 마스터 비밀번호 검증 → `1234`로 로그인 가능해야 함

- [ ] **Step 5: 전체 Gradle 빌드**

Run: `./gradlew build`

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: E2E 검증 완료 및 종속성 정리"
```
