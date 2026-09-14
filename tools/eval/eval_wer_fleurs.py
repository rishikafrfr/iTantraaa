#!/usr/bin/env python3
"""Compute WER of a sherpa-onnx STT engine on a streamed FLEURS test split.

Usage:
    python eval_wer_fleurs.py --engine omnilingual --model-dir ../../models/cache/omnilingual-300m-int8 \
        --fleurs-config hi_in --n 12 --out results/wer_omnilingual_hi.json
"""
from __future__ import annotations

import argparse
import io
import json
import re
import unicodedata
from pathlib import Path

import numpy as np
import sherpa_onnx as so
import soundfile as sf
from datasets import Audio, load_dataset
from jiwer import cer, wer

TARGET_SR = 16000


def build_recognizer(engine: str, model_dir: Path, threads: int, lang: str) -> so.OfflineRecognizer:
    if engine == "omnilingual":
        return so.OfflineRecognizer.from_omnilingual_asr_ctc(
            model=str(model_dir / "model.int8.onnx"),
            tokens=str(model_dir / "tokens.txt"),
            num_threads=threads,
            provider="cpu",
        )
    if engine == "whisper":
        return so.OfflineRecognizer.from_whisper(
            encoder=str(model_dir / "tiny-encoder.int8.onnx"),
            decoder=str(model_dir / "tiny-decoder.int8.onnx"),
            tokens=str(model_dir / "tiny-tokens.txt"),
            language=lang,
            task="transcribe",
            num_threads=threads,
            provider="cpu",
            tail_paddings=300,
        )
    raise SystemExit(f"unknown engine: {engine}")


def normalise(text: str) -> str:
    """Common normalisation applied to both hypothesis and reference.

    NFC, lowercase, strip punctuation & combining marks are NOT stripped
    (Indic scripts need matras), collapse whitespace.
    """
    text = unicodedata.normalize("NFC", text).lower()
    # remove everything that is not a letter, digit, space, or Devanagari/Indic char
    text = re.sub(r"[^\w\s\u0900-\u097F\u0980-\u09FF\u0A00-\u0A7F\u0B00-\u0B7F"
                  r"\u0C00-\u0C7F\u0D00-\u0D7F\u0E00-\u0E7F]", " ", text, flags=re.UNICODE)
    return re.sub(r"\s+", " ", text).strip()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", required=True, choices=["omnilingual", "whisper"])
    ap.add_argument("--model-dir", required=True, type=Path)
    ap.add_argument("--fleurs-config", required=True, help="e.g. en_us, hi_in, or_in, ta_in")
    ap.add_argument("--lang", default="en", help="whisper language token (en, hi, ta...)")
    ap.add_argument("--n", type=int, default=12)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    recognizer = build_recognizer(args.engine, args.model_dir, args.threads, args.lang)

    ds = load_dataset("google/fleurs", args.fleurs_config, split="test", streaming=True)
    # datasets>=5 would need torchcodec for decoded audio; instead take raw bytes
    # and decode with soundfile (libsndfile handles FLEURS' flac/opus containers)
    ds = ds.cast_column("audio", Audio(decode=False))
    hyps, refs, details = [], [], []
    for i, row in enumerate(ds):
        if i >= args.n:
            break
        samples, sr = sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="float32", always_2d=False)
        if samples.ndim > 1:
            samples = samples.mean(axis=1)
        if sr != TARGET_SR:
            x = np.linspace(0, 1, num=len(samples), endpoint=False)
            n_out = int(round(len(samples) * TARGET_SR / sr))
            xs = np.linspace(0, 1, num=n_out, endpoint=False)
            samples = np.interp(xs, x, samples).astype("float32")
        stream = recognizer.create_stream()
        stream.accept_waveform(TARGET_SR, samples)
        recognizer.decode_stream(stream)
        hyp = normalise(stream.result.text)
        ref = normalise(row["raw_transcription"])
        hyps.append(hyp)
        refs.append(ref)
        details.append({"ref": ref, "hyp": hyp})

    result = {
        "engine": args.engine,
        "lang": args.lang,
        "fleurs_config": args.fleurs_config,
        "n": len(hyps),
        "wer": round(wer(refs, hyps), 4),
        "cer": round(cer(refs, hyps), 4),
        "details": details,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "details"}, indent=2))


if __name__ == "__main__":
    main()
