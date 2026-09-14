# Licence Ledger (LICENSES.md)

Every dependency and model used by or bundled with iTantra. **Red = non-commercial or restricted — team decision required.** Amber = research/competition-safe but not fully permissive. Green = permissive open source.

## Software dependencies

| Component | Licence | Source | Status |
|---|---|---|---|
| sherpa-onnx 1.13.8 | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx | 🟢 |
| onnxruntime (bundled inside sherpa-onnx) | MIT | https://github.com/microsoft/onnxruntime | 🟢 |
| Kotlin / Jetpack Compose / Material3 / Room / DataStore / Hilt (Phase 1+) | Apache-2.0 | AOSP / Google | 🟢 |
| numpy | BSD-3-Clause | https://numpy.org | 🟢 |
| soundfile / libsndfile | BSD-3-Clause / LGPL-2.1 | https://python-soundfile.readthedocs.io | 🟢 |
| jiwer | Apache-2.0 | https://github.com/jitsi/jiwer | 🟢 |
| psutil | BSD-3-Clause | https://github.com/giampaolo/psutil | 🟢 |
| datasets (eval only) | Apache-2.0 | https://github.com/huggingface/datasets | 🟢 |
| huggingface_hub (download only) | Apache-2.0 | https://github.com/huggingface/huggingface_hub | 🟢 |
| espeak-ng phonemization data (inside piper pack) | GPL-3.0 (data) | https://github.com/rhasspy/piper-phonemize | 🟡 data-only, unmodified redistribution; check GPL compatibility of pack distribution |

## STT models

| Model | Licence | Source | Status |
|---|---|---|---|
| Omnilingual ASR 300M CTC, INT8 | **Apache-2.0** (Meta Platforms) | https://huggingface.co/csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12 | 🟢 |
| Whisper tiny, INT8 | Apache-2.0 (OpenAI) | https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny | 🟢 |
| Silero VAD | MIT | https://github.com/snakers4/silero-vad (via sherpa-onnx release asset) | 🟢 |
| AI4Bharat IndicConformer per-language (future export) | MIT | https://huggingface.co/ai4bharat (indicconformer_stt_*_hybrid_ctc_rnnt_large) | 🟢 (unused yet; NeMo export tooling pending) |
| ai4bharat/indic-conformer-600m-multilingual | MIT but **access-gated** | https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual | 🔴 gated — cannot redistribute in packs |

## TTS models

| Model | Licence | Source | Status |
|---|---|---|---|
| Piper en_US lessac medium (VITS) | piper code MIT; **voice trained on Blizzard 2013 Lessac dataset — research-use licence** | https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium · dataset licence: cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html | 🟡 fine for SIH/competition & demos; not cleared for commercial product |
| **MMS TTS per-language VITS (all 10 languages)** | **CC-BY-NC-4.0** (Meta MMS; ONNX conversion by willwade) | https://huggingface.co/willwade/mms-tts-multilingual-models-onnx | 🔴 **NON-COMMERCIAL — explicit team sign-off required before it becomes the shipped Indic voice.** Fallback: Rasa below. |
| AI4Bharat Rasa VITS 13-language (future) | CC-BY-4.0 | https://huggingface.co/ai4bharat/vits_rasa_13 | 🟢 but covers only ~6/10 of our languages (no hi/gu/or/en) and needs ONNX export (Phase 4 investigation) |

## Evaluation data

| Dataset | Licence | Use | Status |
|---|---|---|---|
| Google FLEURS test splits | CC-BY-4.0 | STT WER screening | 🟢 |
| facebook/omnilingual-asr-corpus | CC-BY-4.0 | optional larger eval | 🟢 (unused yet) |

## Decisions needed from the team

1. **MMS-TTS CC-BY-NC-4.0 (🔴)**: accept for the SIH competition build, with Rasa (CC-BY-4.0) as the licence-clean roadmap item? SIH is a non-commercial competition, so NC is likely acceptable *for the hackathon*; a real deployment would need it swapped.
2. **Piper lessac voice (🟡)**: keep as English default for Phase 1, or evaluate `vits-mms-eng` (also NC 🔴) / a CC-0 English voice instead? Current recommendation: keep Piper, flag in the deck.
3. espeak-ng data redistribution in packs: verify GPL data-only bundling is acceptable for our distribution model (pack zip, unmodified files).
