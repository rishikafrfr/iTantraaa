# Phase 0 — Model Selection Report (MODELS.md)

**Date:** 2026-09-10 · **Runtime:** sherpa-onnx **1.13.8** (pinned) on Windows, Python 3.14, CPU only
**Bench CPU:** Intel Core i9-class (28 logical cores), 4 inference threads. **Desktop numbers are optimistic upper bounds** — on-device numbers on the low/mid-range reference phones come in Phase 5; relative ordering between models is what matters here.
**Test data:** FLEURS `test` split (streamed, 12 samples/language; en_us, hi_in, or_in), WER/CER after identical NFC/lowercase/punctuation-strip normalisation on both hypothesis and reference. Round-trip CER = TTS output → our own STT → compare (intelligibility proxy).

All raw JSON results: `tools/eval/results/`. Reproduce with `tools/eval/bench_stt.py`, `bench_tts.py`, `eval_wer_fleurs.py`.

---

## 1. STT (speech-to-text)

### Measured

| Model | Licence | Files size | Load | RSS Δ | RTF (desktop, 4 thr) | WER en | WER hi | CER hi | CER or (Odia) |
|---|---|---|---|---|---|---|---|---|---|
| **Omnilingual ASR 300M CTC INT8** (Meta, sherpa conv. by csukuangfj) | **Apache-2.0** | 365.4 MB | 0.61 s | 554 MB | **0.060** | 18.9 % | 31.7 % | **13.0 %** | **17.3 %** |
| Whisper tiny INT8 (OpenAI, sherpa conv.) | Apache-2.0 | 102.8 MB | 0.28 s | 280 MB | 0.021 | **16.2 %** | **124.8 %** ❌ | 105.7 % ❌ | n/a (unsupported) |

### Verdicts

- **Omnilingual 300M INT8 is the Phase 0 winner and the recommended default STT for all 10 languages.**
  - Single model covers 1600+ languages; empirically confirmed on en/hi/or: outputs **native script automatically** (Devanagari for Hindi, Odia script for Odia) with no language parameter at all — CTC output follows the audio's language. This simplifies the protocol (no lang id strictly needed for recognition) though we still transmit a language id for TTS voice selection.
  - Odia — the make-or-break language with no other small-model option — works (CER 17.3 % on FLEURS or_in, 12 samples).
  - Pure CTC → no autoregressive decoder → tiny RTF (0.06) and predictable latency; ideal for VAD-segmented one-shot decoding.
  - Hindi CER 13 % is respectable for a 1600-language model; WER 31.7 % mostly reflects orthographic word variants, CER is the better quality signal for Indic scripts.
- **Whisper tiny INT8: rejected for all Indic languages.** With `language=hi` it hallucinates Romanised/English output instead of Devanagari (WER > 100 %). Fine for English-only (WER 16.2 %) and 3.5× smaller than Omnilingual, so it stays in the registry as an optional English fallback pack, not the default.
- **IndicConformer per-language (ai4bharat/indicconformer_stt_{hi,or,...})** — MIT licence ✅, best published Indic WER, but ships as **PyTorch/NeMo `.nemo` only** (~300+ MB fp32 each); needs NeMo→ONNX→INT8 export tooling (planned `tools/export/`) and one model per language blows the pack-size budget. **Deferred to Phase 4/6 as the accuracy upgrade path** for languages where Omnilingual underperforms (re-evaluate against in-house noisy test set first).
- **ai4bharat/indic-conformer-600m-multilingual** — MIT and ships ONNX (encoder + per-language RNNT joints), which would give true *streaming* Indic ASR… but the repo is **access-gated** (login + approval), which blocks open redistribution in packs. Rejected for the hackathon unless access is granted; revisit if approved.
- **IndicWhisper / whisper-small-indic** — decoder-based RTF penalty and ≥3× size of tiny; no sherpa-format prebuilts found for our languages. Not pursued.

### Pack size caveat (honest report)

The efficiency budget aims ≤150 MB for an STT pack; Omnilingual INT8 is **365 MB raw / 280 MB zipped**. Options, in order of preference:
1. Accept the size for the hackathon (installed via sideloaded pack, not APK).
2. Ask Meta's release for a smaller vocab/scope export (9812-token multilingual vocab inflates the embedding table) — out of our control.
3. Per-language IndicConformer INT8 exports (Phase 4) would land ~100–150 MB per language and improve WER — the clean long-term answer, at the cost of export work.

---

## 2. TTS (text-to-speech)

### Measured

| Model | Licence | Size | Sample rate | RTF (2 thr) | First chunk | RSS Δ | Notes |
|---|---|---|---|---|---|---|---|
| **Piper en_US lessac medium** (VITS) | MIT code / Blizzard-2013 dataset licence (research use) ⚠️ | 63.2 MB + espeak data | 22.05 kHz | **0.046** | **0.10 s** | 104 MB | chunks per sentence — streams beautifully |
| **MMS eng** (VITS, willwade conv.) | CC-BY-NC-4.0 🔴 | 114 MB | 16 kHz | 0.199 | 0.33 s | 160 MB | |
| **MMS hin** (VITS, willwade conv.) | CC-BY-NC-4.0 🔴 | 114 MB | 16 kHz | 0.214 | 1.09 s* | 160 MB | *danda `।` not split — one big chunk (see findings) |

**Round-trip CER through Omnilingual STT** (intelligibility proxy): Piper-en **1.1 %**, MMS-hi **7.5 %** — both TTS outputs are accurately re-transcribable; the Hindi loop (speech→text→TTS→speech→text) works end to end on desktop.

### Verdicts

- **English TTS: Piper lessac** — fastest, smallest, streams per sentence. Amber flag: the voice was trained on the Blizzard 2013 Lessac corpus whose licence is research-use; acceptable for SIH, noted in ledger. (Piper code itself is MIT.)
- **Indic TTS (9 languages): MMS per-language VITS** (willwade/mms-tts-multilingual-models-onnx) — **all 10 of our languages are already converted** to sherpa-onnx format, verified working for Hindi. 🔴 **CC-BY-NC-4.0 — non-commercial.** For a hackathon/competition deployment this is defensible, but the team must explicitly accept it. Red-flagged in `docs/LICENSES.md`.
- **Licence-clean alternative (Phase 1 investigation): AI4Bharat Rasa** (`ai4bharat/vits_rasa_13`, **CC-BY-4.0**) — multilingual Indic VITS, but covers only ~6 of our 10 languages (**no Hindi, Gujarati, Odia, English**), is a transformers custom-code model, and needs an ONNX export with an extra emotion input (sherpa-onnx supports it in recent builds). Cannot be the primary; possible per-language swap later for its covered languages.
- **AI4Bharat FastPitch Indic-TTS**: no public HF repos found under `ai4bharat/indic-tts-*` — the public AI4Bharat TTS offers are Indic-Parler-TTS (way too large for on-device). Ruled out.
- **Kokoro**: no Indic voices. **Piper**: no hi/ta/te/… voices. Ruled out.

---

## 3. Recommended Phase 1 configuration

| Slot | Model | Pack |
|---|---|---|
| STT, all languages (default) | Omnilingual 300M CTC INT8 | `multi-stt-omnilingual-300m-int8` |
| STT, English (optional fallback) | Whisper tiny INT8 | `en-stt-whisper-tiny-int8` |
| TTS en | Piper lessac medium | `en-tts-piper-lessac-medium` |
| TTS hi (and remaining 8 in Phase 4) | MMS VITS per language | `hi-tts-mms` |
| VAD | Silero VAD (ships in APK, 0.6 MB) | bundled |

## 4. Engineering findings (library-vs-doc mismatches & gotchas)

1. **TTS streaming callback contract is inverted vs its docstring** in sherpa-onnx 1.13.8: the Python callback must **return non-zero (1/True) to CONTINUE**; returning 0 stops generation after the first chunk. Verified empirically on piper and MMS. Our `TtsEngine` wrapper must normalise this.
2. **`OfflineRecognizer` cannot be constructed directly** in 1.13.8; use factories `OfflineRecognizer.from_omnilingual_asr_ctc(...)`, `from_whisper(...)`, etc. (mirrors Android `sherpaOnnx.OfflineRecognizer` companions — Phase 1 Kotlin code must use the same factories).
3. **Hindi danda (`।`) is not a sentence-split boundary** for sherpa-onnx TTS chunking → whole Hindi paragraphs synthesise as one chunk (first-chunk latency = full synthesis). Our text normaliser (7.4) must pre-split Indic text on `।`/`॥` and feed clauses separately.
4. **datasets ≥ 5.0 requires torchcodec for audio**; we bypass by `cast_column("audio", Audio(decode=False))` + decoding bytes with soundfile (handles FLEURS' flac/opus). Documented in `eval_wer_fleurs.py`.
5. **Omnilingual needs no language parameter** — output script follows input audio. Language selection in our UI/packets is only needed for TTS voice choice and UI chrome.
6. FLEURS streaming + 12 samples/language is a **screening** sample size; Phase 5 must run ≥ 200 samples/language plus the in-house noisy set before publishing WER claims.

## 5. Files produced this phase

- `tools/eval/bench_stt.py`, `bench_tts.py`, `eval_wer_fleurs.py` + `results/*.json` + `results/audio/*.wav` (listen to `tts_mms_hi.wav` / `tts_piper_en.wav`)
- `tools/packs/build_pack.py` + `packs.json` → `dist/packs/*.zip` (4 packs, manifests with SHA-256)
- `docs/LICENSES.md` — full licence ledger
- Model cache under `models/cache/` (gitignore candidate)
