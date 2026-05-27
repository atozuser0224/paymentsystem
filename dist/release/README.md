# PaymentSystem 사용법

## 시스템 구성

| 구성 요소 | 설명 |
|-----------|------|
| **Server** (`server-all.jar`) | Ktor HTTP 서버 + H2 DB — 거래 처리, 비밀번호 검증, 영업 상태 관리 |
| **Desktop** (`org.gang.paymentsystem.exe`) | Compose Desktop GUI — 카드 조회, 거래 화면, 관리자 패널 |
| **Arduino** (`rfid_bluetooth.ino`) | RFID 카드 리더 + IR 리모컨 입력 + LCD 표시 + 블루투스 |

---

## 1. 시작하기

### 서버 실행

```bat
start-server.bat
```

- 포트: `8080`
- DB 파일: `server/data/payment.mv.db` (자동 생성, 영구 저장)
- 서버가 켜져 있어야 데스크톱 앱과 거래가 동작함

### 데스크톱 앱 실행

```bat
start-desktop.bat
```

- 서버가 먼저 실행 중이어야 함
- USB로 Arduino가 연결되어 있어야 Serial 통신 가능

### Arduino 업로드

1. `arduino/rfid_bluetooth.ino` 를 Arduino IDE로 열기
2. 필요 라이브러리 설치:
   - `IRremote` (IR 리모컨 수신)
   - `LiquidCrystal_I2C` (LCD 디스플레이)
   - `Adafruit_PN532` (RFID/NFC 리더)
   - `SoftwareSerial` (블루투스 모듈)
3. 보드에 업로드 후 USB 연결

---

## 2. 하드웨어 연결

| 부품 | Arduino 핀 | 비고 |
|------|-----------|------|
| LCD I2C | SDA(A4), SCL(A5) | 0x27 주소, 16x2 |
| PN532 RFID | IRQ=7, RESET=4 | I2C 모드 |
| HM-10 BLE | TX=3, RX=2 | SoftwareSerial |
| IR 수신기 | IR_RECEIVE_PIN | PinDefinitionsAndMore.h 참조 |
| 초록 LED | 6 | 거래 성공 표시 |
| 빨간 LED | 8 | 에러/취소 표시 |
| 부저 | A0 | 버튼음 + 카드 인식음 |

---

## 3. 리모컨 버튼

### 금액 입력 모드

| 버튼 | 기능 |
|------|------|
| `0` ~ `9` | 숫자 입력 (최대 8자리) |
| `100+` | 00 추가 (100원 단위) |
| `200+` | 백스페이스 (한 자리 삭제) |
| `+` | 입금 실행 → PIN 입력으로 전환 |
| `-` | 출금 실행 → PIN 입력으로 전환 |
| `EQ` | 관리자 모드 진입 |

### PIN 입력 모드

| 버튼 | 기능 |
|------|------|
| `0` ~ `9` | PIN 숫자 입력 (LCD에 `*`로 표시, 최대 8자리) |
| `200+` | 백스페이스 (한 자리 삭제) |
| `+` | PIN 제출 (서버 검증) |
| `-` | 취소 (IDLE로 복귀) |

### 타임아웃

- 카드 태그 후 15초 동안 입력 없으면 자동 취소
- PIN 입력 중 15초 동안 입력 없으면 자동 취소

---

## 4. 기본 사용 흐름

### 입출금 거래

```
1. 카드 태그 → LCD에 "Card OK!" + UID 표시 + 환영 멜로디
2. 리모컨 숫자로 금액 입력 → LCD에 Amount: 5000
3. + (입금) 또는 - (출금) 버튼 → LCD에 "Enter Card PIN"
4. 리모컨 숫자로 PIN 입력 → LCD에 PIN: ****
5. + 버튼 → 서버 검증 → LCD에 "Authorized!" 또는 "Wrong PIN!"
6. 거래 완료 → IDLE 화면으로 복귀
```

### 관리자 모드

```
1. EQ 버튼 → LCD에 "Master Password"
2. 리모컨 숫자로 마스터 비밀번호 입력 → LCD에 PIN: ****
3. + 버튼 → 서버 검증 → PC에 관리자 패널 표시
```

### 관리자 패널 기능

| 기능 | 설명 |
|------|------|
| **마스터 비밀번호 변경** | 관리자 모드 진입 비밀번호 변경 |
| **카드 PIN 설정** | 카드 UID 지정 → 새 PIN 설정 (카드 태그 시 UID 자동 입력) |
| **영업 종료** | 지정 시간까지 모든 입출금 차단 (HH:mm 형식) |
| **영업 재개** | 차단 해제 |
| **장비 제어** | (준비 중) |
| **보정** | (준비 중) |

---

## 5. 최초 설정

> 모든 설정은 데스크톱 앱의 **관리자 패널**에서 가능하다. 아래는 API 직접 호출 방식.

### 마스터 비밀번호 설정 (API)

```powershell
curl -X POST http://localhost:8080/set-master `
  -H "Content-Type: application/json" `
  -d '{"newPassword": "1234"}'
```

### 카드 PIN 설정 (API)

```powershell
curl -X POST http://localhost:8080/set-pin `
  -H "Content-Type: application/json" `
  -d '{"uuid": "<카드UID>", "newPin": "5678"}'
```

> 카드 UID는 카드를 태그하면 LCD와 PC 화면에 표시됨

### 설정 확인 (API)

```powershell
# 비밀번호 검증 테스트
curl -X POST http://localhost:8080/verify-master `
  -H "Content-Type: application/json" `
  -d '{"password": "1234"}'

# 영업 상태 확인
curl http://localhost:8080/business-status
```

---

## 6. API 목록

| Method | Path | 설명 | Body |
|--------|------|------|------|
| POST | `/verify-pin` | 카드 PIN 검증 | `{"uuid":"...", "pin":"..."}` |
| POST | `/verify-master` | 마스터 비밀번호 검증 | `{"password":"..."}` |
| POST | `/set-master` | 마스터 비밀번호 설정 | `{"newPassword":"..."}` |
| POST | `/set-pin` | 카드 PIN 설정 | `{"uuid":"...", "newPin":"..."}` |
| POST | `/lock-business` | 영업 차단 | `{"until":"2026-05-27T22:00"}` |
| GET | `/business-status` | 영업 상태 조회 | — |

---

## 7. 문제 해결

| 증상 | 확인 사항 |
|------|----------|
| "Card not authorized!" | 카드를 먼저 태그하세요 |
| "Wrong PIN!" | PIN 재입력 — 서버에 설정된 PIN과 일치 확인 |
| "No amount!" | + 또는 - 누르기 전에 금액을 입력하세요 |
| "No response from desktop" | 데스크톱 앱이 실행 중인지 확인, USB 연결 확인 |
| "영업 종료됨" | 관리자 패널에서 영업 재개하거나 서버 재시작 |
| LCD 백라이트만 켜짐 | I2C 주소 확인 (0x27), 배선 확인 |
| RFID 인식 안 됨 | PN532 배선 확인, I2C 모드인지 확인 |
