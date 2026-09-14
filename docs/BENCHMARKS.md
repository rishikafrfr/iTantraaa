# Benchmarks (BENCHMARKS.md)

## Emulator verification — 2026-09-10 (Phase 0.5)

Device: **Android 15 x86_64 emulator** (Pixel 5 AVD, 4 GB RAM, google_apis image, `emu64xa:15/AE3A.240806.043`) on Intel 28-core host.
Runtime: sherpa-onnx **1.13.8** Android AAR (all-ABIs), CPU provider, STT 4 threads / TTS 2 threads.
Source: `tools/android/results/device_verification.json` (raw instrumented-test export), produced by `app/src/androidTest/.../ModelVerificationTest.kt` via `tools/android/run_verification.sh`.

⚠️ **These are emulator numbers on a desktop-class CPU — they prove integration and correctness, not phone performance.** ARM-phone numbers (low/mid-range references) are the Phase 5 deliverable.

| Component | Load | Decode/Synth | RTF | First clause | RSS (PSS) | Output correctness |
|---|---|---|---|---|---|---|
| Omnilingual STT — English | 683 ms | 255 ms / 3.8 s audio | 0.066 | — | 534 MB (after decode) | ✅ perfect transcript |
| Omnilingual STT — Hindi | (same session) | 327 ms / 5.1 s audio | 0.064 | — | — | ✅ correct Devanagari |
| Whisper tiny INT8 — English | — | 168 ms / 3.8 s | ~0.04 | — | — | ✅ (repetition at tail_paddings=300 — tune) |
| Silero VAD (5.1 s Hindi) | — | 15 ms | 0.003 | — | — | ✅ 1 speech segment |
| Piper TTS en (lessac) | 566 ms | 195 ms / 5.5 s audio | 0.035 | **48 ms** | 201 MB | ✅ wav saved & pulled |
| MMS TTS hi | 415 ms | 1,203 ms / 5.6 s audio | 0.213 | **677 ms** | 317 MB | ✅ wav saved & pulled |

### Findings from the emulator run

1. **`OfflineTts.generateWithCallback` hard-crashes with Kotlin 2.x lambdas** — JNI abort `NoSuchMethodError: invoke([F)Ljava/lang/Integer;` (the native side expects a boxed-Integer bridge method Kotlin 2.1.20 doesn't emit). Confirmed SIGABRT on Android 15. **App design consequence: our TTS playback streams by clause-by-clause `generate()` + streaming `AudioTrack`, not via the callback.** (The Python-side callback quirk from Phase 0 is a separate, non-fatal inversion.)
2. Pushing models: Gradle's `connectedAndroidTest` uninstalls the app at the end, and Android deletes `/sdcard/Android/data/<pkg>` on uninstall — harness installs APKs itself and invokes `am instrument` directly. On Android 15 the app uid cannot read shell-pushed files in its own *external* files dir (FUSE owner filtering) — harness pushes to `/data/data/<pkg>/files` with `adb root` (emulator only). The real app uses in-app sideload (SAF) so this is harness-only tooling.
3. Omnilingual input accepts non-16 kHz wavs (24 kHz test wav resampled internally).
4. Whisper tail_paddings=300 produced a repeated trailing sentence — retune to 1000 (library default) in the app.

### Emulator harness environment notes (reproducibility)

- SDK at `%LOCALAPPDATA%\Android\Sdk`, platforms 35–37, emulator image `system-images;android-35;google_apis;x86_64`, AVD `itantra` (Pixel 5).
- Gradle 8.14.3 wrapper + AGP 8.13.0 + Kotlin 2.1.20, JDK = Android Studio JBR 21 (`org.gradle.java.home` in `gradle.properties`).
- Git Bash on Windows mangles adb remote paths — scripts set `MSYS_NO_PATHCONV=1`.

## Phone benchmarks — TODO (Phase 5)

To be filled from real exports on at least one low-end (3 GB) and one mid-range phone.

## Phase 1 app end-to-end (emulator, 2026-09-10) — `tools/android/results/phase1_e2e.json`

Driven through the real app (engines + UI installed) via the debug hook receiver, not test-only code:

| Path | Result |
|---|---|
| STT app engine ← hi.wav | "सुनो ये एक आपात कालीन चेताबणी संदेश है तुरत निकटतम निकास की और जाएँ" — decode 335 ms, RTF 0.066 |
| TTS app engine → hi full text | 2 clauses (danda split works), first clause **619 ms**, 5.08 s audio, wall 4.70 s |
| TTS app engine → en full text | 3 clauses, first clause **50 ms**, 5.26 s audio, wall 4.31 s |

Notes: TTS "wall" includes streaming playback drain — synthesis overlaps playback by design (clause-by-clause generate() into a streaming AudioTrack). Mic capture path (TalkSession → MicSource → VadSegmenter → SttEngine) is compiled and wired to the Talk screen, but headless emulator verification used file input; the "speak a sentence after the pause" acceptance needs a human speaker on a real phone (user's device, airplane mode).


## Phase 2/3 comm e2e (two emulators, 2026-09-10)

| Scenario | Result |
|---|---|
| HELLO handshake both sides, `langs=[hi,en]` exchanged | ✅ |
| A→B English alert (DEBUG_SEND, alert=true) | ✅ B spoke, queue preemption logic active |
| B→A Hindi reply | ✅ A spoke (danda clause split, 2 clauses) |
| ACK + retransmit loop (800 ms ×3) | ✅ (unit tested; retransmit timer live) |
| Screen-off receiving (normal + ALERT) on receiver emulator | ✅ spoke while screen off |
| Foreground service (`specialUse` type, mic added only while capturing) | ✅ after fix |
| Host accept-loop keeps listening across peer reconnects; client auto-rejoin (2/5/10 s backoff) | ✅ after fix |

Bugs found & fixed during P2/P3 e2e (why this testing exists):
1. `NetworkOnMainThreadException` — sendText wrote to socket on main thread → send moved to IO dispatcher.
2. TCP host accepted exactly one connection then stopped (stale `running` flag left the accept loop dead while the listener stayed open, filling the backlog) → accept-loop + lifecycle fix.
3. FGS type crashes: `connectedDevice`/`microphone` types throw `SecurityException` when started without matching eligibility → switched to `specialUse` (declared subtype "offline voice relay"), microphone type added only while capture is active.
4. Android 15 freezes cached apps within seconds of screen-off — without the FGS the receiver's link dies silently; service now starts from `MainActivity.onCreate`.

## Language coverage summary (FLEURS screening, 12 samples each, desktop, omnilingual INT8)

| Language | WER | CER |
|---|---|---|
| English (en_us) | 18.9 % | 7.2 % |
| Hindi (hi_in) | 31.7 % | 13.0 % |
| Odia (or_in) | 53.4 % | 17.3 % |
| Tamil (ta_in) | 24.0 % | 17.8 % |
| Bengali (bn_in) | 39.4 % | 9.2 % |

CER is the meaningful metric for Indic scripts (orthographic word variants inflate WER).

## Release APK sizes (R8 + resource shrink, per-ABI splits)

| APK | Size |
|---|---|
| app-armeabi-v7a-release | 23.9 MB ✅ (≤25 MB budget) |
| app-arm64-v8a-release | 33.5 MB ⚠ (8.5 MB over — sherpa-onnx JNI .so is the floor; option: static-link-onnxruntime AAR variant) |
| app-universal-release | 129.4 MB (convenience only) |

All Phase 1–6 phases verified on emulators 2026-09-10/11; every number in this file is reproducible via the scripts in tools/.
