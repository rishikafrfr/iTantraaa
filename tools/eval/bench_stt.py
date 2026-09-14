#!/usr/bin/env python3
"""Benchmark a sherpa-onnx offline STT model: decode time, RTF, RAM, load time.

Usage:
    python bench_stt.py --engine omnilingual --model-dir ../../models/cache/omnilingual-300m-int8 \
        --wavs wav1.wav wav2.wav --threads 4 --out results/stt_omnilingual.json
    python bench_stt.py --engine whisper --model-dir ../../models/cache/whisper-tiny-int8 \
        --wavs wavs/*.wav --lang en --threads 4 --out results/stt_whisper_en.json
"""
from __future__ import annotations

import argparse
import glob
import json
import platform
import time
from pathlib import Path

import numpy as np
import psutil
import sherpa_onnx as so
import soundfile as sf

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
    if engine == "nemo_ctc":
        return so.OfflineRecognizer.from_nemo_ctc(
            model=str(model_dir / "model.onnx"),
            tokens=str(model_dir / "tokens.txt"),
            num_threads=threads,
            provider="cpu",
        )
    raise SystemExit(f"unknown engine: {engine}")


def load_wav(path: str) -> tuple[np.ndarray, float]:
    samples, sr = sf.read(path, dtype="float32", always_2d=False)
    if samples.ndim > 1:
        samples = samples.mean(axis=1)
    if sr != TARGET_SR:
        # linear resample is fine for benchmark purposes; WER eval uses 16 kHz sources
        x = np.linspace(0, 1, num=len(samples), endpoint=False)
        n_out = int(round(len(samples) * TARGET_SR / sr))
        xs = np.linspace(0, 1, num=n_out, endpoint=False)
        samples = np.interp(xs, x, samples).astype("float32")
        sr = TARGET_SR
    return samples, len(samples) / sr


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", required=True, choices=["omnilingual", "whisper", "nemo_ctc"])
    ap.add_argument("--model-dir", required=True, type=Path)
    ap.add_argument("--wavs", nargs="+", required=True, help="wav paths (glob patterns allowed)")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--lang", default="en", help="language hint for whisper")
    ap.add_argument("--warmup", type=int, default=1)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    wav_paths: list[str] = []
    for pattern in args.wavs:
        wav_paths.extend(sorted(glob.glob(pattern)))

    proc = psutil.Process()
    rss_before = proc.memory_info().rss

    t0 = time.perf_counter()
    recognizer = build_recognizer(args.engine, args.model_dir, args.threads, args.lang)
    load_s = time.perf_counter() - t0
    rss_after_load = proc.memory_info().rss

    wavs = [load_wav(p) for p in wav_paths]

    # warm-up decode on the first wav (first inference pays ORT graph init costs)
    for _ in range(args.warmup):
        s = recognizer.create_stream()
        s.accept_waveform(TARGET_SR, wavs[0][0])
        recognizer.decode_stream(s)

    per_wav = []
    for path, (samples, dur_s) in zip(wav_paths, wavs):
        s = recognizer.create_stream()
        s.accept_waveform(TARGET_SR, samples)
        t0 = time.perf_counter()
        recognizer.decode_stream(s)
        dt = time.perf_counter() - t0
        per_wav.append(
            {
                "wav": str(path),
                "audio_s": round(dur_s, 3),
                "decode_s": round(dt, 4),
                "rtf": round(dt / dur_s, 4),
                "text": s.result.text.strip(),
            }
        )
        rss_after_load = max(rss_after_load, proc.memory_info().rss)

    total_audio = sum(w["audio_s"] for w in per_wav)
    total_decode = sum(w["decode_s"] for w in per_wav)
    model_bytes = sum(f.stat().st_size for f in args.model_dir.glob("*.onnx"))
    result = {
        "engine": args.engine,
        "model_dir": str(args.model_dir),
        "lang": args.lang,
        "threads": args.threads,
        "machine": platform.processor() or platform.machine(),
        "python": platform.python_version(),
        "sherpa_onnx": so.__version__,
        "model_size_mb": round(model_bytes / 1e6, 1),
        "load_s": round(load_s, 3),
        "rss_delta_after_load_mb": round((rss_after_load - rss_before) / 1e6, 1),
        "rss_process_mb": round(rss_after_load / 1e6, 1),
        "total_audio_s": round(total_audio, 2),
        "total_decode_s": round(total_decode, 3),
        "mean_rtf": round(total_decode / total_audio, 4),
        "per_wav": per_wav,
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
