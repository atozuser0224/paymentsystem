/*
 * RFID Payment System - Arduino Sketch
 *
 * Wiring:
 *   MFRC522 RFID Reader (SPI):
 *     SDA (SS)  -> Pin 10
 *     SCK       -> Pin 13
 *     MOSI      -> Pin 11
 *     MISO      -> Pin 12
 *     RST       -> Pin 9
 *     VCC       -> 3.3V
 *     GND       -> GND
 *
 *   HC-05 Bluetooth Module:
 *     TX        -> Pin 2 (SoftwareSerial RX)
 *     RX        -> Pin 3 (SoftwareSerial TX)
 *     VCC       -> 5V
 *     GND       -> GND
 *
 *   Status LEDs:
 *     Green LED -> Pin 7 (success)
 *     Red LED   -> Pin 8 (error)
 */

#include <SPI.h>
#include <MFRC522.h>
#include <SoftwareSerial.h>

#define SS_PIN 10
#define RST_PIN 9
#define BT_TX 3
#define BT_RX 2
#define LED_GREEN 7
#define LED_RED 8

MFRC522 mfrc522(SS_PIN, RST_PIN);
SoftwareSerial bluetooth(BT_TX, BT_RX);

String lastCardUid = "";
unsigned long lastCardTime = 0;
const unsigned long CARD_COOLDOWN = 3000;  // 3 seconds between same card reads

void setup() {
  Serial.begin(9600);
  while (!Serial);

  SPI.begin();
  mfrc522.PCD_Init();
  mfrc522.PCD_SetAntennaGain(MFRC522::RxGain_max);

  bluetooth.begin(9600);

  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_RED, OUTPUT);

  // Startup blink
  digitalWrite(LED_GREEN, HIGH);
  digitalWrite(LED_RED, HIGH);
  delay(500);
  digitalWrite(LED_GREEN, LOW);
  digitalWrite(LED_RED, LOW);

  Serial.println(F("RFID Payment System Ready"));
  bluetooth.println(F("READY"));
}

void loop() {
  // Check for new RFID card
  if (mfrc522.PICC_IsNewCardPresent() && mfrc522.PICC_ReadCardSerial()) {
    String uid = getUidString();

    // Cooldown check to avoid duplicate reads
    unsigned long now = millis();
    if (uid == lastCardUid && (now - lastCardTime) < CARD_COOLDOWN) {
      mfrc522.PICC_HaltA();
      return;
    }
    lastCardUid = uid;
    lastCardTime = now;

    Serial.print(F("Card detected: "));
    Serial.println(uid);

    // Send UID over Bluetooth
    bluetooth.print(F("RFID:"));
    bluetooth.println(uid);

    // Wait for response from Android (with timeout)
    String response = waitForResponse(5000);

    if (response.startsWith("OK")) {
      flashLed(LED_GREEN, 3, 150);
      Serial.println(F("Transaction OK"));
    } else if (response.startsWith("ERR")) {
      flashLed(LED_RED, 5, 200);
      Serial.print(F("Transaction Error: "));
      Serial.println(response);
    } else {
      // Timeout or unknown response
      flashLed(LED_RED, 2, 300);
      Serial.println(F("No response from Android"));
    }

    mfrc522.PICC_HaltA();
    mfrc522.PCD_StopCrypto1();
  }

  // Also echo any Bluetooth data to Serial for debugging
  if (bluetooth.available()) {
    Serial.write(bluetooth.read());
  }
}

String getUidString() {
  String uid = "";
  for (byte i = 0; i < mfrc522.uid.size; i++) {
    if (mfrc522.uid.uidByte[i] < 0x10) {
      uid += "0";
    }
    uid += String(mfrc522.uid.uidByte[i], HEX);
  }
  uid.toUpperCase();
  return uid;
}

String waitForResponse(unsigned long timeoutMs) {
  unsigned long start = millis();
  String response = "";

  while (millis() - start < timeoutMs) {
    while (bluetooth.available()) {
      char c = bluetooth.read();
      if (c == '\n' || c == '\r') {
        if (response.length() > 0) {
          return response;
        }
      } else {
        response += c;
      }
    }
    delay(10);
  }

  return response;
}

void flashLed(int pin, int times, int delayMs) {
  for (int i = 0; i < times; i++) {
    digitalWrite(pin, HIGH);
    delay(delayMs);
    digitalWrite(pin, LOW);
    delay(delayMs);
  }
}
