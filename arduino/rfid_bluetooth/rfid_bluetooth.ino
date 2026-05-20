#include <Arduino.h>


#include "PinDefinitionsAndMore.h" // Define macros for input and output pin etc. Sets FLASHEND and RAMSIZE and evaluates value of SEND_PWM_BY_TIMER.
#include <IRremote.hpp> // include the library
#include <Wire.h>                  // 추가
#include <LiquidCrystal_I2C.h>     // 추가
#include <Adafruit_PN532.h>        // 추가: PN532 라이브러리
#include <SoftwareSerial.h>        // 추가: 블루투스 통신용

LiquidCrystal_I2C lcd(0x27, 16, 2);  // 추가
String amount = "";                   // 추가: 입력된 금액 저장
const int MAX_DIGITS = 8;             // 추가: 최대 8자리 제한
bool cardAuthorized = false;          // 추가: 카드 인증 상태
unsigned long lastInputTime = 0;      // 추가: 마지막 입력 시간
const unsigned long TIMEOUT_MS = 15000;  // 추가: 15초 타임아웃
String currentCardUid = "";           // 추가: 현재 인증된 카드의 UID

// 추가: PN532 객체 (I2C 모드)
#define PN532_IRQ   (7)
#define PN532_RESET (4)   // 핀3은 BT_TX와 충돌 → 핀4로 변경
Adafruit_PN532 nfc(PN532_IRQ, PN532_RESET);

// 추가: HM-10 BLE 블루투스 (참조 코드와 동일)
#define BT_TX 3   // HM-10 TX -> 아두이노 RX
#define BT_RX 2   // HM-10 RX -> 아두이노 TX
SoftwareSerial bluetooth(BT_TX, BT_RX);

// 추가: 상태 LED (참조 코드와 동일)
#define LED_GREEN 6   // 성공 (PN532 RESET이 7번이라 6번으로 변경)
#define LED_RED 8     // 에러

// 추가: 부저 핀 (IR과 충돌 안 하도록 A0 사용)
#define BUZZER_PIN A0

// 추가: 숫자별 주파수 (Hz) - 도레미파솔라시도레미
const int digitTones[10] = {
    262,  // 0 = 도(C4)
    294,  // 1 = 레(D4)
    330,  // 2 = 미(E4)
    349,  // 3 = 파(F4)
    392,  // 4 = 솔(G4)
    440,  // 5 = 라(A4)
    494,  // 6 = 시(B4)
    523,  // 7 = 높은 도(C5)
    587,  // 8 = 높은 레(D5)
    659   // 9 = 높은 미(E5)
};

// 추가: 카드 쿨다운 (참조 코드에서 가져옴)
String lastCardUid = "";
unsigned long lastCardTime = 0;
const unsigned long CARD_COOLDOWN = 3000;

// 추가: tone() 대신 사용하는 함수 (IRremote와 충돌 안 함)
void playTone(int frequency, int duration) {
    long period = 1000000L / frequency;   // 마이크로초 단위 주기
    long cycles = (long)frequency * duration / 1000;
    for (long i = 0; i < cycles; i++) {
        digitalWrite(BUZZER_PIN, HIGH);
        delayMicroseconds(period / 2);
        digitalWrite(BUZZER_PIN, LOW);
        delayMicroseconds(period / 2);
    }
}

// 추가: 카드 인식 환영 멜로디 (도-미-솔)
void playCardWelcome() {
    playTone(523, 150);   // 도
    delay(20);
    playTone(659, 150);   // 미
    delay(20);
    playTone(784, 250);   // 솔
}

// 추가: UID를 16진수 문자열로 변환 (참조 코드 함수)
String getUidString(uint8_t* uid, uint8_t uidLength) {
    String uidStr = "";
    for (uint8_t i = 0; i < uidLength; i++) {
        if (uid[i] < 0x10) uidStr += "0";
        uidStr += String(uid[i], HEX);
    }
    uidStr.toUpperCase();
    return uidStr;
}

// 추가: LED 깜빡이기 (참조 코드 함수)
void flashLed(int pin, int times, int delayMs) {
    for (int i = 0; i < times; i++) {
        digitalWrite(pin, HIGH);
        delay(delayMs);
        digitalWrite(pin, LOW);
        delay(delayMs);
    }
}

// 블루투스 응답 대기 (참조 코드 함수)
String waitForResponse(unsigned long timeoutMs) {
    unsigned long start = millis();
    String response = "";

    while (millis() - start < timeoutMs) {
        while (bluetooth.available()) {
            char c = bluetooth.read();
            if (c == '\n' || c == '\r') {
                if (response.length() > 0) return response;
            } else {
                response += c;
            }
        }
        delay(10);
    }
    return response;
}

// USB Serial 응답 대기 (데스크톱 앱과 통신)
String waitForSerialResponse(unsigned long timeoutMs) {
    unsigned long start = millis();
    String response = "";

    while (millis() - start < timeoutMs) {
        while (Serial.available()) {
            char c = Serial.read();
            if (c == '\n' || c == '\r') {
                if (response.length() > 0) return response;
            } else {
                response += c;
            }
        }
        delay(10);
    }
    return response;
}

// 추가: LCD 화면 갱신 함수
void updateLCD() {
    lcd.clear();
    if (!cardAuthorized) {
        // 카드 미인증: 카드 대기 화면
        lcd.setCursor(0, 0);
        lcd.print("Tap your card");
        lcd.setCursor(0, 1);
        lcd.print("to start...");
    } else {
        // 카드 인증됨: 금액 입력 화면
        lcd.setCursor(0, 0);
        lcd.print("Enter amount");
        lcd.setCursor(0, 1);
        lcd.print("Amount: ");
        lcd.print(amount);
    }
}

// 추가: 카드 감지 시 인증 처리
void onCardDetected(uint8_t* uid, uint8_t uidLength) {
    currentCardUid = getUidString(uid, uidLength);
    
    Serial.print(F("RFID:"));
    Serial.println(currentCardUid);
    
    // 추가: 카드 인식 환영 멜로디 재생!
    playCardWelcome();
    
    // 인증 메시지 표시
    lcd.clear();
    lcd.setCursor(0, 0);
    lcd.print("Card OK!");
    lcd.setCursor(0, 1);
    lcd.print(currentCardUid.substring(0, 8));   // UID 일부 표시
    delay(1500);
    
    // 인증 상태로 전환
    cardAuthorized = true;
    amount = "";
    lastInputTime = millis();
    updateLCD();
}

// 추가: 타임아웃 처리 함수
void handleTimeout() {
    Serial.println(F("Timeout! Please tap card again"));
    flashLed(LED_RED, 2, 200);
    
    lcd.clear();
    lcd.setCursor(0, 0);
    lcd.print("Timeout!");
    lcd.setCursor(0, 1);
    lcd.print("Tap card again");
    delay(2000);
    cardAuthorized = false;
    amount = "";
    currentCardUid = "";
    updateLCD();
}

// 추가: 거래 처리 (입금/출금 통합)
void handleTransaction(const String& type) {
    // 금액 미입력 체크
    if (amount.length() == 0) {
        flashLed(LED_RED, 2, 200);
        lcd.clear();
        lcd.setCursor(0, 0);
        lcd.print("No amount!");
        lcd.setCursor(0, 1);
        lcd.print("Enter first");
        delay(1500);
        updateLCD();
        return;
    }
    
    long amountValue = amount.toInt();
    
    Serial.print(F("Sending: "));
    Serial.print(currentCardUid);
    Serial.print(F(", "));
    Serial.print(amountValue);
    Serial.print(F(", "));
    Serial.println(type);
    
    // 처리 중 메시지
    lcd.clear();
    lcd.setCursor(0, 0);
    lcd.print("Processing...");
    lcd.setCursor(0, 1);
    lcd.print(type);
    
    // USB Serial로 거래 정보 전송 (데스크톱 앱이 USB로 연결됨)
    Serial.print(F("TRANS:"));
    Serial.print(currentCardUid);
    Serial.print(F(","));
    Serial.print(amountValue);
    Serial.print(F(","));
    Serial.println(type);

    // 데스크톱 응답 대기 (5초, USB Serial)
    String response = waitForSerialResponse(5000);
    
    lcd.clear();
    if (response.startsWith("OK")) {
        // 성공
        flashLed(LED_GREEN, 3, 150);
        Serial.println(F("Transaction OK"));
        
        lcd.setCursor(0, 0);
        if (type == "DEPOSIT") {
            lcd.print("Deposit OK!");
            lcd.setCursor(0, 1);
            lcd.print("+");
        } else {
            lcd.print("Withdraw OK!");
            lcd.setCursor(0, 1);
            lcd.print("-");
        }
        lcd.print(amount);
    } else if (response.startsWith("ERR")) {
        // 에러
        flashLed(LED_RED, 5, 200);
        Serial.print(F("Transaction Error: "));
        Serial.println(response);
        
        lcd.setCursor(0, 0);
        lcd.print("Error!");
        lcd.setCursor(0, 1);
        // 에러 메시지가 있으면 표시 (16자 제한)
        if (response.length() > 4) {
            String errMsg = response.substring(4);
            if (errMsg.length() > 16) errMsg = errMsg.substring(0, 16);
            lcd.print(errMsg);
        } else {
            lcd.print("Try again");
        }
    } else {
        // 응답 없음
        flashLed(LED_RED, 2, 300);
        Serial.println(F("No response from desktop"));
        
        lcd.setCursor(0, 0);
        lcd.print("No response");
        lcd.setCursor(0, 1);
        lcd.print("Check app");
    }
    delay(2500);
    
    // 로그아웃
    cardAuthorized = false;
    amount = "";
    currentCardUid = "";
    lastCardUid = "";   // 다음 카드는 즉시 인식 가능
    updateLCD();
}

void setup() {
    Serial.begin(115200);

    // LED 핀 설정 (추가)
    pinMode(LED_GREEN, OUTPUT);
    pinMode(LED_RED, OUTPUT);
    pinMode(BUZZER_PIN, OUTPUT);   // 추가: 부저 핀
    
    // 시작 시 LED 깜빡임 (참조 코드 동작)
    digitalWrite(LED_GREEN, HIGH);
    digitalWrite(LED_RED, HIGH);
    delay(500);
    digitalWrite(LED_GREEN, LOW);
    digitalWrite(LED_RED, LOW);

    // LCD 초기화 (추가)
    lcd.init();
    lcd.backlight();
    updateLCD();

    // 추가: PN532 초기화
    nfc.begin();
    uint32_t versiondata = nfc.getFirmwareVersion();
    if (!versiondata) {
        Serial.println(F("PN532 not found! Check wiring."));
        lcd.clear();
        lcd.setCursor(0, 0);
        lcd.print("PN532 not found");
        lcd.setCursor(0, 1);
        lcd.print("Check wiring");
        // while(1) 제거: 멈추지 않고 LED로 에러 표시만 하고 계속 진행
        flashLed(LED_RED, 5, 200);
    }
    Serial.print(F("PN532 Firmware: "));
    Serial.println(versiondata, HEX);
    nfc.SAMConfig();
    Serial.println(F("PN532 ready"));
    
    // 추가: 블루투스 초기화 (HM-10)
    bluetooth.begin(9600);
    bluetooth.println(F("READY"));
    Serial.println(F("Bluetooth ready"));

    Serial.println(F("RFID Payment System Ready (PN532 + HM-10 + IR)"));

    // Just to know which program is running on my Arduino
    Serial.println(F("START " __FILE__ " from " __DATE__ "\r\nUsing library version " VERSION_IRREMOTE));

    // Start the receiver and if not 3. parameter specified, take LED_BUILTIN pin from the internal boards definition as default feedback LED
    IrReceiver.begin(IR_RECEIVE_PIN, ENABLE_LED_FEEDBACK);

    Serial.print(F("Ready to receive IR signals of protocols: "));
    printActiveIRProtocols(&Serial); // Requires additional 318 bytes program memory
    Serial.println(F("at pin " STR(IR_RECEIVE_PIN)));
}

void loop() {

    // 추가: 카드 미인증 상태일 때만 카드 감지 (쿨다운 적용)
    if (!cardAuthorized) {
        uint8_t uid[7] = { 0 };
        uint8_t uidLength;
        if (nfc.readPassiveTargetID(PN532_MIFARE_ISO14443A, uid, &uidLength, 50)) {
            String uidStr = getUidString(uid, uidLength);
            
            // 같은 카드 쿨다운 체크 (3초 내 중복 방지)
            unsigned long now = millis();
            if (uidStr == lastCardUid && (now - lastCardTime) < CARD_COOLDOWN) {
                // 쿨다운 중이면 무시
            } else {
                lastCardUid = uidStr;
                lastCardTime = now;
                onCardDetected(uid, uidLength);
            }
        }
    }
    
    // 추가: 인증 상태에서 15초 타임아웃 체크
    if (cardAuthorized && (millis() - lastInputTime > TIMEOUT_MS)) {
        handleTimeout();
    }
    
    // 추가: 블루투스에서 받은 메시지를 시리얼로 출력 (디버깅용)
    if (bluetooth.available()) {
        Serial.write(bluetooth.read());
    }

    if (IrReceiver.decode()) {

        if (IrReceiver.decodedIRData.protocol == UNKNOWN) {
            Serial.println(F("Received noise or an unknown (or not yet enabled) protocol"));
            // We have an unknown protocol here, print extended info
            IrReceiver.printIRResultRawFormatted(&Serial, true);

            IrReceiver.resume(); // Do it here, to preserve raw data for printing with printIRResultRawFormatted()
        } else {
            IrReceiver.resume(); // Early enable receiving of the next IR frame

            IrReceiver.printIRResultShort(&Serial);   // Requires additional 1436 bytes program memory
            IrReceiver.printIRSendUsage(&Serial);     // Calls printIRResultShort() and other functions, if protocol is UNKNOWN
        }
        Serial.println();

        if (IrReceiver.decodedIRData.flags & IRDATA_FLAGS_IS_REPEAT) {
            Serial.println(F("Repeat received. Here you can repeat the same action as before."));
        } else {
            // 추가: 카드 인증 안 됐으면 모든 버튼 무시
            if (!cardAuthorized) {
                Serial.println(F("Card not authorized!"));
                flashLed(LED_RED, 1, 100);   // 짧게 빨강 깜빡임
                return;
            }
            
            // 추가: 입력이 들어왔으니 타이머 리셋
            lastInputTime = millis();
            
            if (IrReceiver.decodedIRData.command == 0x16) {
                Serial.println(0);
                if (amount.length() < MAX_DIGITS) { amount += "0"; updateLCD(); playTone(digitTones[0], 100); }   // 변경
            } 
            else if (IrReceiver.decodedIRData.command == 0xC) {
                Serial.println(1);
                if (amount.length() < MAX_DIGITS) { amount += "1"; updateLCD(); playTone(digitTones[1], 100); }   // 변경
            }
            else if (IrReceiver.decodedIRData.command == 0x18) {
                Serial.println(2);
                if (amount.length() < MAX_DIGITS) { amount += "2"; updateLCD(); playTone(digitTones[2], 100); }   // 변경
            }
            else if (IrReceiver.decodedIRData.command == 0x5E) {
                Serial.println(3);
                if (amount.length() < MAX_DIGITS) { amount += "3"; updateLCD(); playTone(digitTones[3], 100); }   // 변경
            }
            else if (IrReceiver.decodedIRData.command == 0x8) {
                Serial.println(4);
                if (amount.length() < MAX_DIGITS) { amount += "4"; updateLCD(); playTone(digitTones[4], 100); }   // 변경
            }
            else if (IrReceiver.decodedIRData.command == 0x1C) {
                Serial.println(5);
                if (amount.length() < MAX_DIGITS) { amount += "5"; updateLCD(); playTone(digitTones[5], 100); }   // 변경
            }
            else if (IrReceiver.decodedIRData.command == 0x5A) {
                Serial.println(6);
                if (amount.length() < MAX_DIGITS) { amount += "6"; updateLCD(); playTone(digitTones[6], 100); }   // 변경
            }
            else if (IrReceiver.decodedIRData.command == 0x42) {
                Serial.println(7);
                if (amount.length() < MAX_DIGITS) { amount += "7"; updateLCD(); playTone(digitTones[7], 100); }   // 변경
            }
            else if (IrReceiver.decodedIRData.command == 0x52) {
                Serial.println(8);
                if (amount.length() < MAX_DIGITS) { amount += "8"; updateLCD(); playTone(digitTones[8], 100); }   // 변경
            }
            else if (IrReceiver.decodedIRData.command == 0x4A) {
                Serial.println(9);
                if (amount.length() < MAX_DIGITS) { amount += "9"; updateLCD(); playTone(digitTones[9], 100); }   // 변경
            }
            else if (IrReceiver.decodedIRData.command == 0x19) {
                Serial.println("100+");
                if (amount.length() <= MAX_DIGITS - 2) { amount += "00"; updateLCD(); }   // 변경: 00은 2자리라 -2
            }
            else if (IrReceiver.decodedIRData.command == 0xD) {
                Serial.println("200+");
                if (amount.length() > 0) {           // 추가: 백스페이스
                    amount.remove(amount.length() - 1);
                    updateLCD();
                }
            }
            else if (IrReceiver.decodedIRData.command == 0x7) {
                Serial.println("-");
                handleTransaction("WITHDRAW");   // 변경: - 버튼으로 출금
            }
            else if (IrReceiver.decodedIRData.command == 0x15) {
                Serial.println("+");
                handleTransaction("DEPOSIT");    // 변경: + 버튼으로 입금
            }
            else if (IrReceiver.decodedIRData.command == 0x9) {
                Serial.println("EQ");
                // EQ는 비워둠 (필요시 기능 추가)
            }
        }
    }
}