# PaymentSystem 사용법

## 시스템 구성

| 구성 요소 | 설명 |
|-----------|------|
| **Server** (`server-all.jar`) | Ktor HTTP 서버 + H2 DB — 거래 처리, 비밀번호 검증, 영업 상태 관리 |
| **Desktop** (`desktop/`) | Compose Desktop GUI — 카드 조회, 거래 화면, **관리자 패널** |
| **Arduino** (`rfid_bluetooth.ino`) | RFID 카드 리더 + IR 리모컨 입력 + LCD 표시 + 블루투스 |

> **데스크톱이 관리자다.** 모든 비밀번호/PIN 설정은 데스크톱 앱의 관리자 패널에서 한다.

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
- 데스크톱은 **관리자 권한** — 화면 상단의 **"관리자"** 버튼으로 바로 관리자 패널 진입 가능

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

### 금액 입력 모드 (`AMOUNT_INPUT`)

| 버튼 | 기능 |
|------|------|
| `0` ~ `9` | 숫자 입력 (최대 8자리) |
| `100+` | 00 추가 (100원 단위) |
| `200+` | 백스페이스 (한 자리 삭제) |
| `+` | 입금 실행 → PIN 입력으로 전환 |
| `-` | 출금 실행 → PIN 입력으로 전환 |
| `EQ` | 관리자 모드 진입 (마스터 비밀번호 필요) |

### PIN 입력 모드 (`PIN_ENTRY`)

| 버튼 | 기능 |
|------|------|
| `0` ~ `9` | PIN 숫자 입력 (LCD에 `*`로 표시, 최대 8자리) |
| `200+` | 백스페이스 (한 자리 삭제) |
| `+` 또는 `EQ` | PIN 제출 (서버 검증) |
| `-` | 취소 (IDLE로 복귀) |

### 타임아웃

- 카드 태그 후 15초 동안 입력 없으면 자동 취소
- PIN 입력 중 15초 동안 입력 없으면 자동 취소

---

## 4. 기본 사용 흐름

### 4.1 입출금 거래 (Arduino + 리모컨)

```
1. 카드 태그 → LCD에 "Card OK!" + UID 표시 + 환영 멜로디
2. 리모컨 숫자로 금액 입력 → LCD에 Amount: 5000
3. + (입금) 또는 - (출금) 버튼 → LCD에 "Enter Card PIN"
4. 리모컨 숫자로 PIN 입력 → LCD에 PIN: ****
5. + 또는 EQ 버튼 → 서버 검증
   - 성공: LCD에 "Authorized!"
   - PIN 불일치: LCD에 "Wrong PIN!"
   - 영업 중지: LCD에 "Business Closed"
6. 거래 완료 → IDLE 화면으로 복귀
```

### 4.2 관리자 모드 (두 가지 방법)

#### 방법 A — 데스크톱에서 직접 진입 (권장)

```
데스크톱 앱 상단 "관리자" 버튼 클릭 → 관리자 패널 즉시 열림
```

- 데스크톱은 관리자이므로 별도 인증 없이 진입 가능

#### 방법 B — Arduino EQ 버튼

```
1. EQ 버튼 → LCD에 "Master Password"
2. 리모컨 숫자로 마스터 비밀번호 입력 → LCD에 PIN: ****
3. + 버튼 → 서버 검증 → PC에 관리자 패널 표시
```

- 최초 마스터 비밀번호가 없으면 첫 입력값이 자동으로 마스터 비밀번호로 설정됨

---

## 5. 관리자 패널 기능

데스크톱 앱에서 **"관리자"** 버튼 클릭 시 표시:

```
┌─────────────────────────────┐
│  관리자 모드          [닫기] │
│  ┌ 영업 상태 ──────────────┐ │
│  │ 🟢 운영 중              │ │
│  └─────────────────────────┘ │
│  ┌ 영업 제어 ──────────────┐ │
│  │ [차단 종료 시간: HH:mm] │ │
│  │ [영업 종료] [영업 재개] │ │
│  └─────────────────────────┘ │
│  ┌ 마스터 비밀번호 변경 ───┐ │
│  │ [새 비밀번호] [변경]    │ │
│  └─────────────────────────┘ │
│  ┌ 카드 PIN 설정 ──────────┐ │
│  │ [카드 UID (자동 입력)]  │ │
│  │ [새 PIN]      [설정]   │ │
│  └─────────────────────────┘ │
│  [장비 제어]  [보정]        │
└─────────────────────────────┘
```

| 기능 | 설명 |
|------|------|
| **영업 상태** | 현재 영업 중 / 차단 상태 표시 |
| **영업 종료** | 지정 시간(HH:mm)까지 모든 입출금 차단, 종료 시각 이후 자동 재개 |
| **영업 재개** | 즉시 차단 해제 |
| **마스터 비밀번호 변경** | 관리자 모드 진입용 마스터 비밀번호 변경 |
| **카드 PIN 설정** | 관리자가 기존 카드 PIN을 변경할 때 사용 |
| **장비 제어** | (준비 중) |
| **보정** | (준비 중) |

> 카드 UID는 카드를 태그하면 자동으로 입력된다.

---

## 6. 최초 설정 흐름

### 처음부터 끝까지

```
1. 서버 실행 (start-server.bat)
2. 데스크톱 실행 (start-desktop.bat)
3. 데스크톱 "관리자" 버튼 클릭 → 관리자 패널 열림
4. "마스터 비밀번호 변경" 섹션에서 비밀번호 설정
5. 신규 카드를 태그하고 입금 또는 출금 금액 입력
6. 처음 입력한 카드 PIN이 해당 카드의 초기 PIN으로 영구 저장됨
7. 이후 거래부터 저장된 PIN과 일치해야 승인됨
8. PIN 변경이 필요한 경우 관리자 패널의 "카드 PIN 설정" 사용

> 카드 PIN과 거래 데이터는 `server/data/payment.mv.db`에 저장되며
> 서버를 종료하거나 다시 실행해도 유지된다.
```

### API로 설정 (대안)

```powershell
# 마스터 비밀번호
curl -X POST http://localhost:8080/set-master `
  -H "Content-Type: application/json" `
  -d '{"newPassword": "1234"}'

# 카드 PIN
curl -X POST http://localhost:8080/set-pin `
  -H "Content-Type: application/json" `
  -d '{"uuid": "<카드UID>", "newPin": "5678"}'

# 영업 상태 확인
curl http://localhost:8080/business-status
```

---

## 7. API 목록

### 거래

| Method | Path | 설명 | Body |
|--------|------|------|------|
| POST | `/register` | 카드 등록/조회 | `{"userName":"...", "uuid":"...", "credit":0}` |
| POST | `/buy` | 결제 (출금) | `{"uuid":"...", "credit":N}` |
| POST | `/add` | 입금 | `{"uuid":"...", "credit":N}` |
| POST | `/transaction` | 거래 처리 (위치 포함) | `{"uuid":"...", "userName":"...", "amount":N, "type":"DEPOSIT\|WITHDRAW"}` |

### 비밀번호 / PIN

| Method | Path | 설명 | Body |
|--------|------|------|------|
| POST | `/verify-pin` | 카드 PIN 검증 | `{"uuid":"...", "pin":"..."}` |
| POST | `/verify-master` | 마스터 비밀번호 검증 | `{"password":"..."}` |
| POST | `/set-master` | 마스터 비밀번호 설정 | `{"newPassword":"..."}` |
| POST | `/set-pin` | 카드 PIN 설정 | `{"uuid":"...", "newPin":"..."}` |

### 영업 관리

| Method | Path | 설명 | Body |
|--------|------|------|------|
| POST | `/lock-business` | 영업 차단 | `{"until":"2026-05-27T22:00"}` |
| GET | `/business-status` | 영업 상태 조회 | — |

### 조회

| Method | Path | 설명 | Body |
|--------|------|------|------|
| GET | `/` | 관리자 대시보드 (HTML) | — |
| GET | `/transactions` | 전체 거래 내역 | — |
| GET | `/transactions/{uuid}` | 카드별 거래 내역 | — |
| GET | `/admin/summary` | 통계 요약 | — |
| DELETE | `/card/{uuid}` | 카드 삭제 | — |
| GET | `/swagger` | Swagger UI | — |

---

## 8. 문제 해결

| 증상 | 확인 사항 |
|------|----------|
| "Card not authorized!" | 카드를 먼저 태그하세요 |
| "Wrong PIN!" | PIN 재입력 — 관리자 패널에서 카드 PIN 확인 후 재설정 |
| "No amount!" | + 또는 - 누르기 전에 금액을 입력하세요 |
| "No response from desktop" | 데스크톱 앱 실행 중인지 확인, USB 연결 확인 |
| "영업 종료됨" | 관리자 패널에서 "영업 재개" 클릭 |
| LCD 백라이트만 켜짐 | I2C 주소 확인 (0x27), 배선 확인 |
| RFID 인식 안 됨 | PN532 배선 확인, I2C 모드인지 확인 |
| 최초 마스터 비밀번호 모름 | 서버 DB 삭제 후 재시작 → 첫 EQ 입력이 자동 설정됨 |
