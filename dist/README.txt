# RFID 결제 시스템 - 배포 패키지

## 구성 요소

### 1. 서버 실행 (server/server.jar)
```
java -jar server/server.jar
```
서버가 localhost:8080 에서 시작됩니다.
관리자 대시보드: 브라우저에서 http://localhost:8080 접속

### 2. 데스크탑 결제 단말기

**방법 A - JAR 실행 (권장)**
```
java -jar desktop/RFID-Payment-Terminal.jar
```

**방법 B - 설치**
desktop/RFID-Payment-Terminal-Setup.msi 실행하여 설치

### 3. 아두이노

**준비물**
- Arduino Uno
- PN532 NFC 모듈 (I2C 모드)
- HM-10 블루투스 모듈
- I2C LCD 1602/2004 (주소 0x27)
- IR 리모컨 수신기
- 부저 (패시브)
- LED (초록, 빨강)

**업로드**
Arduino IDE에서 arduino/rfid_bluetooth.ino 열어서 업로드

**핀 연결**
| 아두이노 | 연결 |
|----------|------|
| A4 | PN532 SDA + LCD SDA |
| A5 | PN532 SCL + LCD SCL |
| A0 | 부저 |
| 2 | IR 수신 + HM-10 TX |
| 3 | PN532 RESET + HM-10 RX |
| 6 | 초록 LED |
| 7 | PN532 IRQ |
| 8 | 빨간 LED |

## 실행 순서
1. 서버 먼저 실행 (java -jar server.jar)
2. 데스크탑 단말기 실행
3. 단말기에서 [기기 연결] → COM 포트 선택 (USB: COM9, 블루투스: COM10)
4. 연결되면 카드 태그하여 사용
