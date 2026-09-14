#!/usr/bin/env python3
"""Benchmark a sherpa-onnx offline TTS model: synthesis RTF, first-chunk latency, RAM.

Usage:
    python bench_tts.py --model-dir ../../models/cache/piper-en_US-lessac-medium \
        --style piper --text "Hello there." --out results/tts_piper_en.json
    python bench_tts.py --model-dir ../../models/cache/vits-mms-eng --style mms \
        --text "Hello there." --out results/tts_mms_en.json
"""
from __future__ import annotations

import argparse
import json
import platform
import time
from pathlib import Path

import psutil
import sherpa_onnx as so


def build_tts(model_dir: Path, threads: int, style: str) -> so.OfflineTts:
    vits = so.OfflineTtsVitsModelConfig(
        model=str(model_dir / "model.onnx") if style == "mms" else str(model_dir / "en_US-lessac-medium.onnx"),
        lexicon="",
        tokens=str(model_dir / "tokens.txt") if (model_dir / "tokens.txt").exists() else "",
        data_dir=str(model_dir / "espeak-ng-data") if (model_dir / "espeak-ng-data").exists() else "",
        dict_dir=str(model_dir / "dict") if (model_dir / "dict").exists() else "",
    )
    config = so.OfflineTtsConfig(
        model=so.OfflineTtsModelConfig(vits=vits, num_threads=threads, provider="cpu")
    )
    return so.OfflineTts(config)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True, type=Path)
    ap.add_argument("--style", choices=["piper", "mms", "vits"], default="vits")
    ap.add_argument("--text", required=True)
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--wav-out", type=Path, default=None)
    args = ap.parse_args()

    proc = psutil.Process()
    rss_before = proc.memory_info().rss

    t0 = time.perf_counter()
    tts = build_tts(args.model_dir, args.threads, args.style)
    load_s = time.perf_counter() - t0
    rss_after_load = proc.memory_info().rss
    sample_rate = tts.sample_rate

    # warm-up (first inference pays graph init)
    tts.generate("warm up.")

    first_chunk_s: list[float] = []
    t_start = time.perf_counter()

    def on_chunk(samples, progress: float) -> int:
        if not first_chunk_s:
            first_chunk_s.append(time.perf_counter() - t_start)
        # NOTE: contrary to the docstring, in sherpa-onnx 1.13.8 returning 0 STOPS
        # generation; return 1 to continue (verified empirically on piper + mms).
        return 1

    audio = tts.generate(args.text, callback=on_chunk)
    total_s = time.perf_counter() - t_start
    rss_peak = max(rss_after_load, proc.memory_info().rss)

    audio_s = len(audio.samples) / sample_rate
    result = {
        "style": args.style,
        "model_dir": str(args.model_dir),
        "threads": args.threads,
        "machine": platform.processor() or platform.machine(),
        "sherpa_onnx": so.__version__,
        "model_size_mb": round(
            sum(
                f.stat().st_size
                for f in args.model_dir.rglob("*")
                if f.is_file() and "espeak" not in str(f) and f.suffix in (".onnx",)
            )
            / 1e6,
            1,
        ),
        "sample_rate": sample_rate,
        "text": args.text,
        "load_s": round(load_s, 3),
        "rss_delta_after_load_mb": round((rss_after_load - rss_before) / 1e6, 1),
        "rss_process_mb": round(rss_peak / 1e6, 1),
        "audio_s": round(audio_s, 3),
        "synthesis_s": round(total_s, 3),
        "rtf": round(total_s / audio_s, 4),
        "first_chunk_s": round(first_chunk_s[0], 4) if first_chunk_s else None,
    }

    if args.wav_out:
        args.wav_out.parent.mkdir(parents=True, exist_ok=True)
        so.write_wave(str(args.wav_out), audio.samples, sample_rate)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
