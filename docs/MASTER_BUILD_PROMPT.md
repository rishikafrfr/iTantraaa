# iTantra — Offline Voice-over-Text Communicator (SIH26173, ISRO)
## Master Build Prompt

---

## 1. Your role and mission

You are a senior Android engineer with deep experience in on-device speech ML (ONNX Runtime / sherpa-onnx / TFLite), real-time audio on Android, and Bluetooth / Wi-Fi peer-to-peer networking. You will build, end to end, a production-quality Android application called **iTantra** for the Smart India Hackathon problem SIH26173 set by ISRO (Department of Space).

**The core idea:** voice audio is too heavy for low-bitrate links, but in alert and distress situations voice matters more than text because it works for everyone, literate or not. So iTantra never transmits audio. The sending phone runs speech-to-text (STT) fully offline, sends only the recognised text over Wi-Fi or Bluetooth, and the receiving phone converts that text back into speech with offline text-to-speech (TTS). The result behaves like a voice radio, but uses a few hundred bytes per sentence instead of kilobytes per second.

You will work in phases (Section 12). At the end of every phase the project must compile, run on a real device, and meet that phase's acceptance criteria before you move on.

---

## 2. Hard rules (non-negotiable)

1. **Open-source only.** Every library and every model must be under an OSI-approved or open model licence. Proprietary or commercial voice SDKs are prohibited. This explicitly bans: `android.speech.SpeechRecognizer`, `android.speech.tts.TextToSpeech` system engines (Google TTS), Google Cloud Speech, Google Play Services / GMS of any kind (including **Nearby Connections**), Firebase, ML Kit, Picovoice, Vosk-commercial variants, and any API that phones home.
2. **Fully offline at runtime.** After language packs are installed, the app must work with no internet (airplane mode + Wi-Fi/Bluetooth only). No analytics, no crash reporting that uses the network.
3. **Must run smoothly on low- and mid-range phones.** Design for a 3 GB RAM, 4-to-8-core Cortex-A53/A55 class device on Android 8+. Support `arm64-v8a` and `armeabi-v7a`.
4. **Do not invent APIs.** Before writing code against sherpa-onnx (or any native library), read the actual Kotlin API files and Android example apps in the version you pin (e.g. the repo's `kotlin-api/` sources and `android/SherpaOnnxVadAsr`, `android/SherpaOnnxTts` examples). If this spec disagrees with the real library, follow the library and tell me what you changed.
5. **Complete files, not fragments.** When you create or modify a file, output the whole file. No `// TODO: implement`, no stub functions on the critical path (capture → VAD → STT → transport → TTS → playback). If something genuinely cannot be finished in a phase, say so explicitly in your phase report.
6. **Licence ledger.** Maintain `docs/LICENSES.md` listing every dependency and every model with its licence and source URL. Flag any model with a non-commercial (NC) licence in red text in that file so the team can decide whether it is acceptable for the hackathon.
7. **Every model is swappable.** No model name, path, or language assumption may be hard-coded outside the model registry (Section 7.9). Swapping a language's STT or TTS model must require only a new pack manifest, not code changes.

---

## 3. Judging criteria you are optimising for

| Weight | Criterion | What it means for engineering |
|---|---|---|
| 40% | **Accuracy** | Low Word Error Rate (WER) for STT across all 10 languages; natural, intelligible TTS. Biggest weight: model choice and text normalisation matter most. |
| 20% | **Efficiency** | Small APK and model packs, low RAM and flash footprint, **low CPU while idle-listening**. |
| 20% | **Latency** | Speech→text completion delay, text→audio playback delay, Real-Time Factor (RTF), and the **end-to-end delta** from a sentence ending on phone A to audio starting on phone B. |
| 20% | Not published (typically social impact / usability) | Accessibility for non-literate users, emergency use, localised UI, robustness. |

The app itself must be able to **measure and export** every metric above (Section 7.11), so the team can put real numbers in the presentation.

---

## 4. Functional requirements

### 4.1 Languages (all 10 required)
| Code | Language | Script |
|---|---|---|
| `hi` | Hindi | Devanagari |
| `gu` | Gujarati | Gujarati |
| `mr` | Marathi | Devanagari |
| `kn` | Kannada | Kannada |
| `ml` | Malayalam | Malayalam |
| `ta` | Tamil | Tamil |
| `te` | Telugu | Telugu |
| `or` | Odia | Odia |
| `bn` | Bengali | Bengali |
| `en` | English (Indian-accented) | Latin |

### 4.2 STT side (sender)
- Continuously capture microphone audio, detect speech with a VAD, and detect pauses / end of sentence.
- On end-of-sentence, finalise the transcript and **immediately** stream the text to the connected peer (another phone running iTantra, or an embedded device).
- Show a live caption while the user speaks and a history of sent messages.
- The sender marks each message as NORMAL or ALERT (big red ALERT toggle; see 7.8 for optional keyword auto-alert).

### 4.3 TTS side (receiver)
- On receiving text, synthesise intelligible speech in the message's language and play it, saving it as a replayable voice note in the history.
- **ALERT messages play at maximum volume and cannot be interrupted** (details in 7.8).
- Normal messages are queued and played in order.

### 4.4 Roles and modes
- **Roles:** `SPEAKER` (STT only), `LISTENER` (TTS only), `DUPLEX` (both at once, for "phone" behaviour).
- **Push-to-talk ON → walkie-talkie mode:** user holds a large button to talk; releasing it force-finalises the sentence (no waiting for VAD silence). Half-duplex.
- **Push-to-talk OFF → phone mode:** hands-free, VAD segments speech automatically. In `DUPLEX`, the mic is gated (muted) while TTS is playing, and `VOICE_COMMUNICATION` audio source + `AcousticEchoCanceler` (when available) are used to stop the phone transcribing its own speaker.

### 4.5 Official validation scenario (must work flawlessly)
Two phones with iTantra: phone A in STT mode, phone B in TTS mode, connected over Wi-Fi **or** Bluetooth. With push-to-talk it behaves like a walkie-talkie; with push-to-talk off, like a phone call. Build a dedicated **"Demo Mode"** screen that guides judges through exactly this scenario and shows live latency numbers on both phones.

---

## 5. System architecture

```
 SENDER PHONE                                                   RECEIVER PHONE
 ┌──────────────────────────────────────────────┐              ┌────────────────────────────────────────────┐
 │ AudioRecord 16 kHz mono PCM16                 │              │ Transport RX ─► Frame decoder ─► Dedupe/ACK │
 │     │                                         │              │                         │                  │
 │     ▼                                         │   TEXT       │                         ▼                  │
 │ Energy gate ─► Silero VAD ─► Segment buffer   │   frames     │           Priority queue (ALERT first)     │
 │                    │   (pause / PTT release)  │ ───────────► │                         │                  │
 │                    ▼                          │ Wi-Fi TCP /  │                         ▼                  │
 │ STT engine (offline, per-language pack)       │ Wi-Fi Direct │ Text normaliser ─► TTS engine (per-lang)  │
 │     │                                         │ BT RFCOMM /  │                         │ (chunked synth)  │
 │     ▼                                         │ BLE NUS      │                         ▼                  │
 │ Text post-processor ─► Frame encoder ─► TX    │              │ AudioTrack streaming playback + voice note │
 └──────────────────────────────────────────────┘              └────────────────────────────────────────────┘
          ▲                                                                   ▲
          └────────── PING/PONG clock sync + latency tracing on both ends ───┘
```

Key design decisions:
- **VAD-segmented offline recognition** is the primary STT strategy: VAD cuts one utterance, the offline recogniser decodes it in one shot. This fits the "form the sentence after pause, then send" requirement and allows the most accurate non-streaming Indic models. For live captions, re-decode the growing segment every ~1 s on a background thread (throttled, cancellable). Where a genuine streaming model exists for a language, support it through the same interface.
- **One language loaded at a time** per direction (one STT model + one TTS model resident), loaded lazily and released on language switch, to protect RAM on low-end devices.
- **Models live outside the APK** in app-specific storage as "language packs", so the base APK stays small.

---

## 6. Tech stack (pin exact versions in `gradle/libs.versions.toml`)

- **Language:** Kotlin (latest stable), coroutines + Flow. No Java in new code.
- **UI:** Jetpack Compose + Material 3, single-activity, Navigation Compose.
- **Architecture:** MVVM + unidirectional data flow; Hilt for DI.
- **Persistence:** Room (message history, benchmark runs), DataStore (settings).
- **Speech runtime:** **sherpa-onnx** Android build (Apache-2.0) — provides Silero VAD, offline and streaming ASR (NeMo CTC, transducer, Whisper, Zipformer, etc.), and offline TTS (VITS / Piper, Matcha, Kokoro). Pin one release; vendor the matching `.so` files for both ABIs and the matching Kotlin API sources. This is the "similar TinyML framework" allowed by the problem statement (ONNX Runtime underneath).
- **Fallback runtime (only if a chosen model cannot run in sherpa-onnx):** `onnxruntime-android` or LiteRT (TFLite). If you add a second ONNX Runtime, resolve duplicate `libonnxruntime.so` with a documented `packaging { jniLibs { pickFirsts } }` rule and prove both engines still load.
- **Networking:** plain `java.net` sockets for TCP, `android.net.nsd.NsdManager` for discovery, `WifiP2pManager` for Wi-Fi Direct, `BluetoothSocket` (RFCOMM) and `BluetoothGatt` (BLE). All AOSP — no GMS.
- **Build:** Gradle Kotlin DSL, AGP latest stable, `minSdk 26`, `targetSdk` latest stable, R8 full mode, resource shrinking, ABI splits (`arm64-v8a`, `armeabi-v7a`) plus a universal debug build.
- **Tooling:** Python 3.11 scripts under `tools/` for model export, quantisation, pack building, and WER evaluation (`jiwer`, `onnx`, `onnxruntime`, `soundfile`).

---

## 7. Component specifications

### 7.1 Audio capture (`:core:audio`)
- `AudioRecord` at 16 000 Hz, mono, `ENCODING_PCM_16BIT`, source `VOICE_RECOGNITION` (SPEAKER role) or `VOICE_COMMUNICATION` (DUPLEX). Fall back to 48 kHz capture + high-quality downsampling if a device rejects 16 kHz.
- Read in 20–32 ms frames on a dedicated high-priority thread (`THREAD_PRIORITY_URGENT_AUDIO`) into a lock-free ring buffer; convert to `FloatArray` in [-1, 1] only when feeding models.
- Enable `NoiseSuppressor` / `AutomaticGainControl` when available, toggleable in settings (they can hurt WER on some devices, so the benchmark screen must be able to compare on vs off).
- Pre-roll: keep the last 300 ms before VAD onset so the first syllable is never clipped.

### 7.2 VAD and end-of-sentence detection (`:core:vad`)
- **Two-stage gating for low idle CPU:** a cheap RMS/energy gate runs on every frame; Silero VAD (via sherpa-onnx `Vad`) runs only when energy exceeds an adaptive noise floor. When the room is silent, CPU should be near zero.
- Tunable parameters in settings (with sensible defaults): speech threshold (≈0.5), min speech duration (≈0.25 s), **min silence duration for end-of-sentence (default 0.5 s, range 0.3–1.2 s)**, max utterance length (≈15 s, then force-split at the lowest-energy point).
- PTT mode: VAD still trims leading/trailing silence, but releasing the button finalises instantly.
- Emit a timestamp `tSpeechEnd` (monotonic clock) at the moment end-of-speech is decided; this is the start point of the latency chain.

### 7.3 STT engine layer (`:core:stt`)
Define:
```kotlin
interface SttEngine : AutoCloseable {
    val languageCode: String
    suspend fun load(pack: SttPackManifest)
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): SttResult   // final, one-shot
    fun transcribePartial(samples: FloatArray): String                        // cheap, for captions
}
data class SttResult(val text: String, val decodeMs: Long, val audioMs: Long) { val rtf get() = decodeMs.toFloat() / audioMs }
```
Implementations:
- `SherpaOfflineEngine` — wraps sherpa-onnx `OfflineRecognizer`; must support at least NeMo CTC and Whisper model types, selected by the pack manifest.
- `SherpaStreamingEngine` — wraps `OnlineRecognizer` for languages that have a streaming model.
- Threads: default `numThreads = min(4, bigCores)`; configurable; decode on a single dedicated dispatcher so decodes never overlap.

**Candidate models to evaluate (Phase 0 spike decides the winners — do not assume):**
1. **AI4Bharat IndicConformer** — community INT8 ONNX exports of the multilingual model exist, and per-language NeMo-CTC exports in sherpa-onnx format are appearing (e.g. Malayalam). Preferred direction: **one per-language CTC model per pack** if accuracy holds; the full multilingual model is likely too large for 3 GB phones.
2. **sherpa-onnx Omnilingual ASR 300M CTC (INT8)** — a single model covering all 10 languages; check WER vs size trade-off.
3. **Per-language fine-tuned Whisper tiny/small** (INT8, sherpa-onnx format) for languages where they exist; note Whisper's decoder makes RTF worse and coverage of Odia/Gujarati/Marathi/Bengali is patchy.
4. **English:** an Indian-English fine-tuned model (Zipformer or NeMo) vs a general English model — measure on Indian-accented speech.

If a good ONNX export does not exist for a language, write the export + INT8 dynamic quantisation script in `tools/export/` (NeMo → ONNX → `onnxruntime.quantization.quantize_dynamic`) and document the exact commands.

### 7.4 Text post-processing (`:core:nlp`)
- Unicode NFC normalisation, whitespace cleanup, removal of CTC artefacts (repeated tokens, `<unk>`).
- Sentence terminal punctuation per script (`।` for hi/mr/bn/or, `.` for others) since CTC models output no punctuation.
- **TTS-side normaliser (big accuracy lever):** numbers, dates, times, and common abbreviations → spoken words in each of the 10 languages (write a rule-based `NumberToWords` per language with unit tests), strip characters the TTS vocabulary does not contain (use the model's `tokens.txt` to decide), split long text into clauses at punctuation for chunked synthesis.
- Keep all rules data-driven (JSON per language) so native speakers on the team can correct them without touching code.

### 7.5 Wire protocol (`:core:protocol`)
Compact binary framing, designed so an ESP32 can parse it in ~100 lines of C. All integers big-endian.

| Offset | Size | Field |
|---|---|---|
| 0 | 2 | Magic `0x49 0x54` ("IT") |
| 2 | 1 | Version = 1 |
| 3 | 1 | Type: `0x01 HELLO`, `0x02 TEXT`, `0x03 ACK`, `0x04 PING`, `0x05 PONG`, `0x06 PARTIAL`, `0x07 BYE` |
| 4 | 1 | Flags: bit0 `ALERT`, bit1 `ACK_REQ`, bit2 `FINAL`, bit3 `HAS_TIMING` |
| 5 | 1 | Language id (0–9, table in 4.1 order) |
| 6 | 4 | Message id (uint32, per-sender incrementing) |
| 10 | 2 | Payload length N (uint16) |
| 12 | N | Payload (UTF-8 text for TEXT/PARTIAL; small JSON for HELLO; timestamps for PING/PONG) |
| 12+N | 16 (optional) | If `HAS_TIMING`: `tSpeechEnd` and `tSend` as int64 sender-monotonic µs |
| end | 2 | CRC-16/CCITT-FALSE over everything before it |

Rules:
- Stream transports (TCP, RFCOMM): parser resynchronises on magic after a CRC failure.
- BLE: fragment frames to `MTU − 3` with a 1-byte fragment header (bit7 = last fragment, bits0–6 = index).
- Receiver ACKs every `ACK_REQ` frame; sender retransmits after 800 ms, max 3 times; receiver dedupes by (sender id, message id).
- Messages composed while disconnected are queued and flushed on reconnect (ALERTs first).
- `HELLO` exchanges device name, app version, role, and supported languages; the receiver warns in the UI if it lacks the TTS pack for an incoming language.
- `PARTIAL` (live caption streaming) is optional and off by default to save bandwidth.
- Write exhaustive unit tests for the codec (round-trip, corruption, fragmentation, resync).

### 7.6 Transports (`:core:transport`)
```kotlin
interface Transport {
    val state: StateFlow<LinkState>          // Disconnected, Discovering, Connecting, Connected(peer), Error(reason)
    val incoming: Flow<Frame>
    suspend fun send(frame: Frame)
    suspend fun start(config: TransportConfig)
    suspend fun stop()
}
```
Implement, in this priority order:
1. **Wi-Fi LAN / hotspot (TCP):** one phone is host (`ServerSocket`), advertised via `NsdManager` as `_itantra._tcp`; the other discovers and connects. Also allow manual IP entry and a UDP broadcast discovery fallback (NSD is flaky on some OEM ROMs). Works when one phone runs a mobile hotspot with **no internet**.
2. **Bluetooth Classic RFCOMM:** SPP UUID `00001101-0000-1000-8000-00805F9B34FB`, host/client roles, pairing flow. This is also the path for ESP32 (classic) / HC-05 style embedded devices.
3. **Wi-Fi Direct (`WifiP2pManager`):** router-less Wi-Fi; after group formation, reuse the TCP transport over the P2P interface.
4. **BLE GATT using Nordic UART Service UUIDs** (`6E400001-B5A3-F393-E0A9-E50E24DCCA9E` family) for BLE-only embedded devices.

Common: `TCP_NODELAY` on sockets, heartbeat PING every 2 s, auto-reconnect with backoff, and a clear connection screen showing link type, peer name, RSSI where available, and measured round-trip time.

### 7.7 Receiver pipeline and TTS (`:core:tts`)
```kotlin
interface TtsEngine : AutoCloseable {
    val languageCode: String
    suspend fun load(pack: TtsPackManifest)
    fun synthesize(text: String, speed: Float = 1.0f, onChunk: (FloatArray) -> Unit): TtsStats  // streams audio chunks
    val sampleRate: Int
}
```
- Primary implementation: `SherpaOfflineTtsEngine` wrapping sherpa-onnx `OfflineTts` (VITS / Matcha / Kokoro types via manifest).
- **Latency tactics:** keep the current language's TTS model warm (run a one-word synthesis at load time); synthesise clause-by-clause and start `AudioTrack` (streaming mode, `PERFORMANCE_MODE_LOW_LATENCY`) as soon as the first clause is ready; pre-load the TTS model for the peer's language as soon as `HELLO` arrives.
- Save every played message as a 16 kHz mono Opus or WAV voice note (open-source encoder only) linked to its history row, with a replay button.
- Receiver queue: ALERT preempts NORMAL (a NORMAL message being spoken is paused and resumed afterwards); NORMAL is FIFO.

**Candidate TTS models to evaluate in Phase 0 (verify language coverage and licence for each):**
1. **AI4Bharat Indic-TTS** (FastPitch + HiFi-GAN, per language; covers all 10 including Indian English) — may need ONNX export via `tools/export/`; check whether it can be converted to a sherpa-onnx-supported format before writing a custom runner.
2. **AI4Bharat VITS "Rasa"** multilingual Indic model — an ONNX export for sherpa-onnx exists but needs a sherpa-onnx build with the extra emotion input; confirm which of our 10 languages it covers.
3. **Meta MMS-TTS** per-language VITS (covers all 10) — **licence is CC-BY-NC 4.0; flag it**, use only if the team accepts that.
4. **Piper / Kokoro** voices where they exist (e.g. Hindi, English).

Evaluate each candidate for: intelligibility (round-trip CER: TTS output → our own STT), RTF on the low-end reference phone, first-chunk latency, pack size, and a quick 5-listener naturalness score (1–5).

### 7.8 Alert policy
When an ALERT frame arrives:
- Request `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`; play with `AudioAttributes.USAGE_ALARM` / `CONTENT_TYPE_SPEECH`.
- Save the current alarm-stream volume, set it to max via `AudioManager.setStreamVolume`, restore it afterwards.
- If Do-Not-Disturb is on and the user has granted notification-policy access, play through it; request that access during onboarding with a clear explanation.
- **No stop, skip, or pause control** while an alert plays; ignore focus-loss callbacks and volume-key presses (intercept in the activity and restore volume if changed); hardware back / home must not stop it (playback lives in the foreground service).
- Pre-roll a short attention tone (generated in code, not a copyrighted sound), vibrate with a distinct pattern, and show a full-screen-intent notification with the alert text in large type.
- Optionally replay the alert once (setting, default on).
- Optional sender feature: **keyword auto-alert** — if the transcript contains any word from a per-language editable list (e.g. "help", "bachao", "SOS", "fire"), prompt the sender to send as ALERT with one tap.

### 7.9 Language pack manager (`:core:models`)
- Each pack is a folder with `manifest.json`:
```json
{
  "id": "hi-stt-indicconformer-ctc-int8",
  "lang": "hi",
  "kind": "stt",
  "engine": "sherpa-offline",
  "modelType": "nemo_ctc",
  "files": { "model": "model.int8.onnx", "tokens": "tokens.txt" },
  "sampleRate": 16000,
  "sizeBytes": 0,
  "sha256": { "model.int8.onnx": "..." },
  "license": "…",
  "source": "https://…",
  "version": "1"
}
```
- Install paths: (a) one-time download from a GitHub Releases URL while online; (b) **sideload from a zip** on local storage / USB OTG via the Storage Access Framework (important for demo day and for a truly offline field deployment); (c) **phone-to-phone pack transfer** over the existing Wi-Fi link.
- Verify SHA-256 before activation; show pack sizes and total storage used; allow deletion.
- Bundle nothing large in the APK. Only the Silero VAD model (≈2 MB) ships inside the APK.
- Never load models from `assets/` with compression; always from file paths in app storage.

### 7.10 Background service and lifecycle
- A single foreground service owns capture, VAD, engines, transports, and playback, with `foregroundServiceType="microphone|connectedDevice"` and the matching permissions for Android 14+.
- Partial wake lock only while connected; prompt the user to exempt iTantra from battery optimisation (with OEM-specific guidance text for Xiaomi, Realme, Vivo, Oppo, Samsung).
- The receiver must keep working with the screen off and the app in the background.
- Handle permission denial gracefully: `RECORD_AUDIO`, `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` (API 31+), `NEARBY_WIFI_DEVICES` (API 33+), location (older APIs for BT/Wi-Fi Direct scanning), `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `ACCESS_NOTIFICATION_POLICY`.

### 7.11 Metrics, clock sync, and benchmarking (`:core:metrics`)
This module is how the team wins the Latency and Efficiency scores. Build it properly.
- **Latency tracing:** record monotonic timestamps for `tSpeechEnd`, `tSttDone`, `tSend` (sender) and `tRecv`, `tTtsFirstChunk`, `tAudioStart` (receiver; use `AudioTrack.getTimestamp` for true playback start).
- **Clock sync:** NTP-style PING/PONG over the active link; keep the offset from the sample with the lowest RTT over a sliding window of 20 pings. Report the uncertainty (±RTT/2) next to every end-to-end number.
- Derived metrics per message: speech→text delay, STT RTF, network transit, text→first-audio delay, TTS RTF, and **end-to-end delta** (`tAudioStart` on B − `tSpeechEnd` on A, offset-corrected).
- **Resource metrics:** app PSS / RSS via `Debug.getMemoryInfo`, peak native heap during decode, CPU% of the process (from `/proc/self/stat` deltas) during idle-listening, active-decode, and idle-connected states; model load time.
- **Benchmark screen:** run a fixed test set (WAV + reference transcripts per language, loaded from a sideloaded folder) through the STT engine and compute WER/CER on-device; run a fixed sentence list through TTS and report RTF and first-chunk latency; export everything as CSV + JSON to Downloads.
- **Live overlay** in Demo Mode showing the last message's end-to-end delta on both phones.

### 7.12 UI/UX (Compose)
Screens: Onboarding & permissions → Home (role, mode, language, link status) → Connect (Wi-Fi host/join, Bluetooth pair/connect, Wi-Fi Direct, BLE device) → Talk (huge PTT button, live caption, ALERT toggle, sent history) → Listen (incoming voice notes, alert banner, replay) → Language Packs → Benchmark → Demo Mode → Settings.
- Designed for **non-literate and stressed users**: very large touch targets (≥ 64 dp for primary actions), icons + colour + sound for every state (listening, sending, sent, received, alert), haptic confirmation on send.
- UI strings localised into all 10 languages (`values-hi`, `values-gu`, …), plus optional spoken UI prompts using the app's own TTS.
- Full TalkBack labels, dynamic type, high-contrast theme, dark mode, works one-handed.
- Clear, honest error states ("Bluetooth disconnected — retrying", "Tamil voice pack not installed on the other phone").

### 7.13 Embedded device reference (`firmware/esp32/`)
Provide a minimal, well-commented reference sketch (Arduino framework, open-source only) for an ESP32 that connects over Bluetooth Classic SPP or BLE NUS, parses iTantra frames (magic, CRC), sends ACKs, and prints text to Serial / an optional SSD1306 OLED, with ALERT frames triggering a buzzer GPIO. This proves the "embedded device" half of the requirement and documents the protocol for third parties.

---

## 8. Performance budgets (targets; the benchmark screen must report actuals)

| Metric | Low-end reference (3 GB RAM, Android 8–11) | Mid-range reference (6 GB, Snapdragon 6xx / Dimensity 6xxx class) |
|---|---|---|
| Base APK (per ABI) | ≤ 25 MB | ≤ 25 MB |
| One STT pack | aim ≤ 150 MB | aim ≤ 150 MB |
| One TTS pack | aim ≤ 80 MB | aim ≤ 80 MB |
| Peak app RAM (STT + TTS loaded) | ≤ 450 MB | ≤ 450 MB |
| CPU while idle-listening in silence | ≤ 5% of one core | ≤ 3% |
| STT RTF | ≤ 0.5 | ≤ 0.25 |
| Speech-end → text ready (5 s utterance) | ≤ 1.5 s | ≤ 0.8 s |
| Text received → first audio | ≤ 600 ms | ≤ 350 ms |
| End-to-end delta over Wi-Fi | ≤ 2.2 s | ≤ 1.3 s |

If a budget cannot be met with the chosen model, report it and propose the trade-off (smaller model vs WER) with numbers, rather than silently missing it.

---

## 9. Repository layout

```
itantra/
├── app/                          # Activity, navigation, DI graph, screens
├── core/
│   ├── audio/                    # capture, ring buffer, AudioTrack player, focus, alert player
│   ├── vad/                      # energy gate + Silero wrapper, segmenter
│   ├── stt/                      # SttEngine + sherpa implementations
│   ├── tts/                      # TtsEngine + sherpa implementations, chunked playback
│   ├── nlp/                      # normalisers, number-to-words per language, sentence splitter
│   ├── protocol/                 # frame codec, CRC, fragmentation
│   ├── transport/                # TCP+NSD, Wi-Fi Direct, RFCOMM, BLE NUS
│   ├── models/                   # pack manifests, registry, installer, sha256 verify
│   ├── metrics/                  # tracing, clock sync, resource sampling, benchmark runner
│   └── service/                  # foreground service orchestrating the pipeline
├── firmware/esp32/               # reference receiver sketch
├── tools/
│   ├── export/                   # NeMo/HF → ONNX, INT8 quantisation scripts
│   ├── packs/                    # build pack zips + manifest + sha256
│   └── eval/                     # desktop WER/CER eval with jiwer, TTS round-trip CER
├── docs/
│   ├── ARCHITECTURE.md
│   ├── PROTOCOL.md
│   ├── MODELS.md                 # chosen models, sizes, WER/RTF tables, why chosen
│   ├── BENCHMARKS.md             # device results
│   ├── LICENSES.md
│   └── DEMO_SCRIPT.md            # step-by-step judge demo
└── README.md
```

---

## 10. Evaluation data (for WER and TTS checks)
- Use public, openly licensed test sets that cover all 10 languages, such as **Google FLEURS** test splits and **AI4Bharat Kathbath / IndicVoices** test sets (English: FLEURS `en_us` plus an Indian-English set such as Svarah). Confirm licences and record them in `docs/LICENSES.md`.
- `tools/eval/` computes WER and CER per language on desktop (same ONNX models, same normalisation) so on-device and desktop numbers can be cross-checked.
- Apply identical text normalisation to hypotheses and references before scoring (strip punctuation, NFC, number normalisation) and document it.
- Also record a small **in-house noisy test set** (10 sentences × 10 languages, outdoor/fan/crowd noise, 2–3 speakers) because real distress audio is noisy.

---

## 11. Testing
- Unit tests: protocol codec, CRC, fragmentation, number-to-words (≥ 20 cases per language), sentence splitter, pack manifest validation, clock-offset estimator.
- Instrumented tests: engine load/unload cycles without leaks, 100-message soak test between two emulators over TCP, ALERT preemption ordering.
- Manual test matrix in `docs/DEMO_SCRIPT.md`: each language × {Wi-Fi, Bluetooth} × {PTT on, PTT off}; screen-off receiving; app killed and restarted; link drop and recovery; airplane mode with Bluetooth on.
- Leak and jank: LeakCanary in debug builds only; no audio underruns logged during 10-minute continuous sessions.

---

## 12. Phased delivery plan

Work strictly in this order. At the end of each phase, give me a **phase report** (Section 13) and wait for my go-ahead.

**Phase 0 — Model spike (desktop + device).**
Set up `tools/`. For Hindi, Tamil, Odia, and English first (then the rest), download or export the candidate STT and TTS models, quantise to INT8 where applicable, run them in sherpa-onnx on desktop, and measure WER/CER, RTF, size, and peak RAM. Produce `docs/MODELS.md` with a comparison table and a recommended model per language per direction. Deliverable: pack zips for Hindi + English.
*Accept when:* recommendations are backed by numbers, every chosen model has a verified licence.

**Phase 1 — Skeleton + single-phone loop.**
Multi-module project, DI, navigation, pack manager with sideload install, audio capture, VAD segmentation, STT → on-screen text, and TTS of typed text, for Hindi and English.
*Accept when:* on a real phone in airplane mode, speaking a Hindi sentence produces correct text after the pause, and typed Hindi text is spoken intelligibly.

**Phase 2 — Two-phone walkie-talkie over Wi-Fi.**
Protocol codec + TCP/NSD transport + receiver queue + streaming playback + message history with voice notes. PTT mode.
*Accept when:* the official validation scenario works over a hotspot with no internet; end-to-end delta is shown on screen.

**Phase 3 — Phone mode, alerts, background, Bluetooth.**
Continuous VAD mode, DUPLEX with mic gating/echo control, full alert policy, foreground service, screen-off receiving, Bluetooth RFCOMM transport, reconnection and offline queueing.
*Accept when:* the validation scenario works over Bluetooth too; an ALERT plays at max volume and cannot be stopped from the UI, volume keys, or back/home.

**Phase 4 — All 10 languages.**
Remaining packs, per-language normalisers and number-to-words, language switching with lazy load/unload, HELLO-based pack mismatch warnings, localised UI strings.
*Accept when:* every language passes a spoken round-trip test on two phones, and WER per language is recorded.

**Phase 5 — Metrics and benchmark.**
Clock sync, full latency tracing, resource sampling, on-device benchmark runner, CSV/JSON export, Demo Mode overlay.
*Accept when:* `docs/BENCHMARKS.md` is filled from real exports on at least one low-end and one mid-range phone.

**Phase 6 — Optimisation.**
Profile and hit the budgets in Section 8: thread counts, INT8 everywhere, energy pre-gate tuning, warm-up, clause-chunked TTS, model load time, APK size (R8, ABI splits). Add Wi-Fi Direct and BLE NUS transports, and the ESP32 reference sketch.
*Accept when:* budgets are met or the gap is explained with measured trade-offs.

**Phase 7 — Polish and submission assets.**
Accessibility pass, keyword auto-alert, onboarding, OEM battery guidance, README with screenshots, `DEMO_SCRIPT.md`, final licence ledger, signed release APKs per ABI.

---

## 13. How to respond in every phase

1. **Plan first:** list the files you will create/modify and any decision points. If a requirement here is ambiguous or technically impossible on Android, stop and ask one precise question instead of guessing.
2. **Then code:** complete files with their full paths, in dependency order (build files first).
3. **Then verify:** exact commands to build, install, and test (`./gradlew`, `adb`, Python scripts), and what the tester should see on screen.
4. **Phase report:** what works, what does not, measured numbers, known risks, and what the next phase needs from me (devices, recordings, decisions).

Quality bar: readable, idiomatic Kotlin; no blocking I/O on the main thread; all native resources released in `close()`; structured logging with a debug-only verbose tag; no secrets, no network calls outside the explicit pack download.

Begin with **Phase 0**. Start by stating the sherpa-onnx version you will pin and why, then the list of candidate models you will benchmark for Hindi, Tamil, Odia, and English with their licences and download sources.