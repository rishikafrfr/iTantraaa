/*
 * iTantra reference receiver — ESP32 (Arduino framework, open-source only)
 *
 * Receives iTantra wire-protocol frames over Bluetooth Serial (SPP), verifies
 * the CRC-16/CCITT-FALSE, ACKs ACK_REQ frames, prints the text to Serial (and
 * an SSD1306 OLED if attached), and pulses a buzzer GPIO for ALERT frames.
 *
 * This proves the "embedded device" half of SIH26173 and documents the frame
 * format for third parties. Wire protocol spec: docs/PROTOCOL.md
 *
 * Libraries: BluetoothSerial (bundled with the Arduino ESP32 core). The OLED
 * path is optional and commented out to keep this sketch dependency-free.
 */

#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled! Run `idf.py menuconfig` in the ESP-IDF build.
#endif

BluetoothSerial SerialBT;

// ---- wire protocol constants (must match the Android app / docs/PROTOCOL.md) ----
static const uint8_t  MAGIC0        = 0x49;  // 'I'
static const uint8_t  MAGIC1        = 0x54;  // 'T'
static const uint8_t  VERSION       = 1;
static const size_t   HEADER_LEN    = 12;
static const size_t   CRC_LEN       = 2;

static const uint8_t TYPE_HELLO = 0x01;
static const uint8_t TYPE_TEXT  = 0x02;
static const uint8_t TYPE_ACK   = 0x03;
static const uint8_t TYPE_PING  = 0x04;
static const uint8_t TYPE_PONG  = 0x05;
static const uint8_t TYPE_BYE   = 0x07;

static const uint8_t FLAG_ALERT   = 0x01;
static const uint8_t FLAG_ACK_REQ = 0x02;

static const uint8_t LANG_NAMES[10][4] = {
    "hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en"
};

static const int8_t BUZZER_PIN = 25;  // set to -1 if no buzzer is wired

// ---- frame reassembly state ----
uint8_t  buf[600];
size_t   bufLen = 0;

uint16_t crc16(const uint8_t *data, size_t len) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= ((uint16_t)data[i]) << 8;
        for (uint8_t b = 0; b < 8; b++) {
            crc = (crc & 0x8000) ? ((crc << 1) ^ 0x1021) : (crc << 1);
        }
    }
    return crc;
}

void sendAck(uint32_t msgId) {
    uint8_t f[HEADER_LEN + CRC_LEN] = {0};
    f[0] = MAGIC0; f[1] = MAGIC1; f[2] = VERSION; f[3] = TYPE_ACK;
    f[6] = (msgId >> 24) & 0xFF; f[7] = (msgId >> 16) & 0xFF;
    f[8] = (msgId >> 8) & 0xFF;  f[9] = msgId & 0xFF;
    uint16_t crc = crc16(f, HEADER_LEN);
    f[HEADER_LEN] = crc >> 8; f[HEADER_LEN + 1] = crc & 0xFF;
    SerialBT.write(f, sizeof(f));
}

void sendHello() {
    const char *json = "{\"name\":\"ESP32-receiver\",\"langs\":[]}";
    size_t n = strlen(json);
    uint8_t f[HEADER_LEN + 96 + CRC_LEN] = {0};
    f[0] = MAGIC0; f[1] = MAGIC1; f[2] = VERSION; f[3] = TYPE_HELLO;
    f[10] = (n >> 8) & 0xFF; f[11] = n & 0xFF;
    memcpy(f + HEADER_LEN, json, n);
    uint16_t crc = crc16(f, HEADER_LEN + n);
    f[HEADER_LEN + n] = crc >> 8; f[HEADER_LEN + n + 1] = crc & 0xFF;
    SerialBT.write(f, HEADER_LEN + n + CRC_LEN);
}

void handleFrame(const uint8_t *f, size_t total) {
    uint8_t  type   = f[3];
    uint8_t  flags  = f[4];
    uint8_t  langId = f[5];
    uint32_t msgId  = ((uint32_t)f[6] << 24) | ((uint32_t)f[7] << 16) |
                      ((uint32_t)f[8] << 8) | f[9];
    uint16_t plen   = ((uint16_t)f[10] << 8) | f[11];
    const char *lang = (langId < 10) ? (const char *)LANG_NAMES[langId] : "??";

    if (flags & FLAG_ACK_REQ) sendAck(msgId);

    switch (type) {
        case TYPE_TEXT: {
            char text[512];
            size_t copy = plen < sizeof(text) - 1 ? plen : sizeof(text) - 1;
            memcpy(text, f + HEADER_LEN, copy);
            text[copy] = 0;
            Serial.printf("[%s#%u]%s %s\n", lang, msgId,
                          (flags & FLAG_ALERT) ? " ALERT:" : ":", text);
            if (flags & FLAG_ALERT && BUZZER_PIN >= 0) {
                for (int i = 0; i < 3; i++) {
                    tone(BUZZER_PIN, 880, 120); delay(150);
                    tone(BUZZER_PIN, 1320, 120); delay(180);
                }
                noTone(BUZZER_PIN);
            }
            break;
        }
        case TYPE_PING: {
            // echo as PONG (payload passthrough keeps the clock-sync math happy)
            uint8_t pong[600];
            memcpy(pong, f, total);
            pong[3] = TYPE_PONG;
            uint16_t crc = crc16(pong, total - CRC_LEN);
            pong[total - 2] = crc >> 8; pong[total - 1] = crc & 0xFF;
            SerialBT.write(pong, total);
            break;
        }
        case TYPE_HELLO: Serial.println("[peer hello]"); break;
        case TYPE_BYE:   Serial.println("[peer bye]");   break;
        default: break;
    }
}

// Feed one byte into the parser; resyncs on the magic bytes after corruption.
void feed(uint8_t b) {
    if (bufLen < sizeof(buf)) buf[bufLen++] = b;

    for (;;) {
        // hunt for magic
        while (bufLen >= 2 && (buf[0] != MAGIC0 || buf[1] != MAGIC1)) {
            memmove(buf, buf + 1, bufLen - 1);
            bufLen--;
        }
        if (bufLen < HEADER_LEN) return;

        uint16_t plen  = ((uint16_t)buf[10] << 8) | buf[11];
        uint8_t  timing = (buf[4] & 0x08) ? 16 : 0;
        size_t   total = HEADER_LEN + plen + timing + CRC_LEN;
        if (total > sizeof(buf)) { memmove(buf, buf + 1, bufLen - 1); bufLen--; continue; }
        if (bufLen < total) return;

        uint16_t want = ((uint16_t)buf[total - 2] << 8) | buf[total - 1];
        if (crc16(buf, total - CRC_LEN) == want) {
            handleFrame(buf, total);
            memmove(buf, buf + total, bufLen - total);
            bufLen -= total;
        } else {
            // CRC bad: drop the header and resync
            memmove(buf, buf + 2, bufLen - 2);
            bufLen -= 2;
        }
    }
}

void setup() {
    Serial.begin(115200);
    if (BUZZER_PIN >= 0) pinMode(BUZZER_PIN, OUTPUT);
    SerialBT.begin("itantra-esp32");   // Bluetooth device name
    Serial.println("iTantra ESP32 receiver ready, pairing name: itantra-esp32");
}

void loop() {
    while (SerialBT.available()) feed(SerialBT.read());
    delay(2);
}
