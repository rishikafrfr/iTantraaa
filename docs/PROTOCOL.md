# iTantra Wire Protocol v1 (PROTOCOL.md)

Compact binary framing for text-over-low-bitrate links. All integers **big-endian**. Designed so an ESP32 can parse it in ~100 lines of C (see `firmware/esp32/`).

## Frame layout

| Offset | Size | Field |
|---|---|---|
| 0 | 2 | Magic `0x49 0x54` ("IT") |
| 2 | 1 | Version = 1 |
| 3 | 1 | Type (below) |
| 4 | 1 | Flags (below) |
| 5 | 1 | Language id (0–9) |
| 6 | 4 | Message id (uint32, per-sender incrementing) |
| 10 | 2 | Payload length N (uint16) |
| 12 | N | Payload |
| 12+N | 16 (optional) | `tSpeechEnd`, `tSend` (int64 µs, sender monotonic clock) — present iff FLAG_HAS_TIMING |
| end | 2 | CRC-16/CCITT-FALSE over all preceding bytes |

## Types

| Value | Name | Payload |
|---|---|---|
| 0x01 | HELLO | small JSON: `{"name":…, "langs":["hi","en",…]}` (supported TTS languages) |
| 0x02 | TEXT | UTF-8 sentence |
| 0x03 | ACK | empty (msgId identifies the acked frame) |
| 0x04 | PING | 8 bytes: t0 (sender clock µs) |
| 0x05 | PONG | 16 bytes: t0, t1 (responder clock µs at receive) |
| 0x06 | PARTIAL | UTF-8 partial caption (off by default) |
| 0x07 | BYE | empty |

## Flags

bit0 ALERT · bit1 ACK_REQ · bit2 FINAL · bit3 HAS_TIMING

## Rules

- Stream transports (TCP, BT RFCOMM): parsers resynchronise by hunting magic bytes after any CRC failure.
- BLE (NUS): fragment to MTU−3 chunks with a 1-byte header — bit7 = last fragment, bits0–6 = index.
- Receiver ACKs every ACK_REQ frame; sender retransmits after 800 ms, max 3 attempts; receiver dedupes by msg id.
- Clock sync (NTP-style): offset = ((t1−t0)+(t2−t3))/2, rtt = t3−t0; keep the lowest-RTT sample of a 20-sample window; report ±minRtt/2 as uncertainty.
- Language id order is frozen: `hi gu mr kn ml ta te or bn en`.

Reference implementations: `app/src/main/java/isro/itantra/protocol/Wire.kt` (Kotlin), `firmware/esp32/` (C++).
