# Judge Demo Script (DEMO_SCRIPT.md)

Total: 6–8 minutes. Two phones (or phone + emulator), airplane mode throughout — **no internet at any point**.

## Before the demo (one-time)

1. Install the iTantra APK on both phones.
2. Sideload packs: copy `dist/packs/multi-stt-omnilingual-300m-int8.zip`, `hi-tts-mms.zip`, `en-tts-piper-lessac-medium.zip` (and any other voices) to each phone, unzip into the app's `files/models/` folder, or use phone-to-phone pack transfer over the app's Wi-Fi link.
3. Phone A: Settings → hotspot ON (no internet). Phone B: join that hotspot.
4. Open iTantra on both. Connect tab → A: **Host Wi-Fi**, B: set A's IP → **Join**. "Link: CONNECTED" shows on both; the metrics card shows live RTT.

## Act 1 — Walkie-talkie (official validation scenario) (2 min)

1. A stays on **Talk**, B on **Listen**. Push-to-talk/phone modes per screen.
2. Speak in Hindi on A, e.g. "यह एक आपातकालीन स्थिति है। सभी लोग शांत रहें।" — B's phone speaks it in Hindi after the pause.
3. B replies by voice the same way. Point out the **Live metrics** card: end-to-end delta with clock uncertainty, RTT.
4. Say a sentence with numbers ("मुख्य द्वार पर ५० लोग हैं") — normaliser expands digits into spoken words.

## Act 2 — ALERT (2 min)

1. On A, flip the red **ALERT** toggle (Connect tab), send "Emergency. Evacuate the building now."
2. B's phone: attention tone → speech at **maximum volume**, replays once, vibration, full-screen notification.
3. Try to stop it: volume keys (auto-restore), back, home — it keeps playing. Tell the judges this is deliberate for distress scenarios.

## Act 3 — Embedded receiver (1 min)

1. Power the ESP32 (pairing name `itantra-esp32`). A: Connect → Host BT / Join BT with the paired device.
2. Send a text — it prints on the serial monitor; ALERT frames fire the buzzer.

## Act 4 — Numbers for the deck (1 min)

1. Connect tab → **Export benchmarks CSV** — real measured e2e deltas, uncertainty, RTT, first-clause latencies from this exact session.
2. Quote: fully offline, open-source models (Apache-2.0 STT), text instead of audio ≈ tens of bytes per sentence vs ~100 KB/s raw audio.

## Fallbacks

- Wi-Fi flaky? Same demo over Bluetooth RFCOMM.
- A language pack missing on the receiver? The app warns "Peer has no X voice pack" — switch to EN/HI chip.

## Screens

`docs/screenshots/talk.png`, `listen.png`, `link.png` (live metrics + CSV export visible), `packs.png`.
