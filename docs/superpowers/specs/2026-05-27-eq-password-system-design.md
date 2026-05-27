# EQ 버튼 비밀번호 시스템 설계

## Goal
RFID 결제 단말기에 EQ 버튼(IR 리모컨 0x9)을 누르면 비밀번호 인증을 거쳐 관리자 모드에 진입하는 시스템. 카드 거래 시에도 카드별 PIN 인증 추가. DB 영구 저장으로 전환.

## Architecture

### 전체 흐름

```
리모컨 → IR → Arduino (LCD에 * 표시, PIN 취합)
  → Serial("PASSWD:mode,uid,pin")
  → DesktopSerialManager (파싱 → PasswordInput state)
  → App (state 감지 → API 호출 → 서버 검증)
  → 결과에 따라 AdminPanel 표시 or 거래 실행 or 에러
```

### Arduino 상태 머신

```
IDLE ──(EQ 버튼)──▶ PIN_ENTRY(mode=MASTER) ──(+)──▶ PASSWD:MASTER,,pin ▶ IDLE

IDLE ──(카드 태그)──▶ AMOUNT_INPUT ──(+/-)──▶ PIN_ENTRY(mode=WITHDRAW/DEPOSIT)
    ──(+)──▶ PASSWD:mode,uid,pin ▶ IDLE

PIN_ENTRY 상태:
  - 0~9: pin += digit, LCD에 * 표시
  - 백스페이스(0xD): 마지막 자리 삭제
  - 100+(0x19): 무시
  - +: PIN 제출 → Serial 전송
  - -: 취소 → IDLE 복귀
  - 15초 타임아웃 → IDLE 복귀
```

## Components

### 1. Arduino (`rfid_bluetooth.ino`)

| 변경 | 내용 |
|------|------|
| `enterPinMode(mode)` | PIN 입력 모드 진입, LCD 표시 |
| `handlePinDigit(char)` | 숫자 입력, * 마스킹 |
| `handlePinBackspace()` | 백스페이스 |
| `submitPin()` | `PASSWD:mode,uid,pin` Serial 출력 |
| EQ 버튼 핸들러 | `enterPinMode("MASTER")` 호출 |
| + 버튼 핸들러 | AMOUNT_INPUT 모드에서 PIN_ENTRY로 전환 |

### 2. Serial 프로토콜

새로운 프리픽스: `PASSWD:<mode>,<uid>,<pin>`

- `mode`: MASTER, WITHDRAW, DEPOSIT
- `uid`: 카드 UID (MASTER는 빈 값)
- `pin`: 입력된 PIN (평문, 서버에서 BCrypt 검증)

### 3. DeviceUiState 추가 (`PlatformServices.kt`)

```kotlin
data class PasswordInput(
    val mode: String,
    val uid: String,
    val pin: String
) : DeviceUiState()
```

### 4. DesktopSerialManager (`DesktopSerialManager.kt`)

readLoop에 `PASSWD:` 라인 파싱 추가 → `PasswordInput` 상태 emit

### 5. Server — DB 영구화 (`Databases.kt`)

```kotlin
// H2 file-based (기존 in-memory에서 변경)
url = "jdbc:h2:file:./data/payment;DB_CLOSE_DELAY=-1"
```

### 6. Server — 새 테이블 (`CardService.kt`)

```kotlin
object CardPins : Table() {
    val uuid = varchar("uuid", length = 100) // BCrypt 해시된 UUID
    val pinHash = varchar("pinHash", length = 200)
}

object SystemConfig : Table() {
    val key = varchar("key", length = 50)     // "master_password", "business_locked_until"
    val value = varchar("value", length = 500)
}
```

### 7. Server — 새 API 엔드포인트

| Method | Path | 설명 |
|--------|------|------|
| POST | `/verify-pin` | `{uuid, pin}` → `{ok: true/false}` |
| POST | `/verify-master` | `{password}` → `{ok: true/false}` |
| POST | `/set-master` | `{newPassword}` → `{ok: true/false}` |
| POST | `/set-pin` | `{uuid, newPin}` → `{ok: true/false}` |
| POST | `/lock-business` | `{until: "2026-05-27T22:00"}` → `{ok: true}` |
| GET | `/business-status` | `{locked: bool, until: string}` |

### 8. Client API (`PaymentApi.kt`)

```kotlin
suspend fun verifyPin(uuid: String, pin: String): Boolean
suspend fun verifyMasterPassword(password: String): Boolean
suspend fun setMasterPassword(newPassword: String): Boolean
suspend fun lockBusiness(until: String): Boolean
suspend fun getBusinessStatus(): BusinessStatus
```

### 9. App UI (`App.kt`)

- `LaunchedEffect(deviceState)`에 `PasswordInput` 처리 추가
- `AdminPanel` Compose: 영업 종료/재개, 장비 제어, 보정 기능
- 관리자 모드 진입 시 `showAdminPanel = true`

### Admin Panel 레이아웃

```
┌─────────────────────────────┐
│  관리자 모드                │
│  [영업 종료 (방범 모드)]     │
│  [영업 재개]                │
│  [장비 제어]                │
│  [보정 (거래 조정)]         │
│  영업 상태: 🟢 운영 중       │
│  [닫기]                     │
└─────────────────────────────┘
```

## Data Flow

### 거래 + PIN 인증

```
1. 카드 태그 → RFID → Arduino → Serial("RFID:uid") → PC → CardRead state
2. App: 카드 조회 → 카드 정보 표시
3. 리모컨 숫자 → Arduino: amount += digit, LCD 표시
4. + 또는 - → Arduino: enterPinMode("DEPOSIT"/"WITHDRAW")
5. 리모컨 숫자 → Arduino: pin += digit, LCD * 표시
6. + → Arduino: Serial("PASSWD:DEPOSIT,uid,1234") → PC
7. App: POST /verify-pin → true
8. App: POST /transaction → 거래 실행 → OK → Arduino
9. Arduino: LCD "Authorized!" → IDLE
```

### 마스터 비밀번호 + 관리자 모드

```
1. EQ 버튼 → Arduino: enterPinMode("MASTER")
2. 리모컨 숫자 → Arduino: pin += digit, LCD * 표시
3. + → Arduino: Serial("PASSWD:MASTER,,1234") → PC
4. App: POST /verify-master → true
5. App: showAdminPanel = true
6. Arduino: LCD "Authorized!" → IDLE
```

## Error Handling

- Wrong PIN → LCD "Wrong PIN!", PC resultText "비밀번호 오류", sendResponse(false)
- Server down → resultText "인증 실패", sendResponse(false)
- Timeout (15s) → Arduino IDLE 복귀
- PIN 최대 8자리 제한

## 영업 차단 (Business Lock)

- 관리자가 "영업 종료" 설정 → SystemConfig에 `business_locked_until` 저장
- 거래 처리 전 `getBusinessStatus()` 확인 → locked면 거래 거부 + "영업 종료됨" 메시지
- 관리자가 "영업 재개" → `business_locked_until` 삭제

## 파일 변경 목록

| 파일 | 변경 |
|------|------|
| `rfid_bluetooth.ino` | PIN_ENTRY 모드, PASSWD 출력, EQ 연결 |
| `Databases.kt` | H2 file, 새 라우트 (/verify-pin 등) |
| `CardService.kt` | CardPins, SystemConfig 테이블 |
| `PlatformServices.kt` | PasswordInput state |
| `DesktopSerialManager.kt` | PASSWD 파싱 |
| `PaymentApi.kt` | verifyPin, verifyMaster 등 |
| `App.kt` | PasswordInput 처리, AdminPanel |
