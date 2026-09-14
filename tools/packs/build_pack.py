#!/usr/bin/env python3
"""Build iTantra language packs: copy files, verify, write manifest.json, zip.

A pack is a directory (and a zip of it) whose layout is fully described by its
manifest.json — see docs/MASTER_BUILD_PROMPT.md section 7.9. Packs are engine
declarative: the Android app never hard-codes model names, it reads manifests.

Usage:
    python build_pack.py --spec packs.json --name hi-tts-mms --out ../../dist/packs
    python build_pack.py --spec packs.json --all --out ../../dist/packs
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

PACK_SPEC_VERSION = "1"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_pack(spec: dict, cache_root: Path, out_dir: Path) -> Path:
    name = spec["id"]
    pack_dir = out_dir / name
    if pack_dir.exists():
        shutil.rmtree(pack_dir)
    pack_dir.mkdir(parents=True)

    hashes: dict[str, str] = {}
    total = 0
    for src_rel, dest_rel in spec["files"].items():
        src = cache_root / src_rel
        if not src.is_file():
            raise FileNotFoundError(f"missing pack source: {src}")
        dest = pack_dir / dest_rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest)
        hashes[dest_rel] = sha256_file(dest)
        total += dest.stat().st_size

    for src_rel, dest_rel in spec.get("dirs", {}).items():
        src = cache_root / src_rel
        if not src.is_dir():
            raise FileNotFoundError(f"missing pack dir: {src}")
        for f in sorted(src.rglob("*")):
            if not f.is_file():
                continue
            rel = f.relative_to(src)
            dest = pack_dir / dest_rel / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dest)
            hashes[f"{dest_rel}/{rel.as_posix()}"] = sha256_file(dest)
            total += dest.stat().st_size

    manifest = {
        "id": name,
        "lang": spec["lang"],
        "kind": spec["kind"],
        "engine": spec["engine"],
        "modelType": spec["modelType"],
        "files": spec["filesOut"] if "filesOut" in spec else {v: v for v in dict(spec["files"]).values()},
        "sampleRate": spec.get("sampleRate"),
        "sizeBytes": total,
        "sha256": hashes,
        "license": spec["license"],
        "source": spec["source"],
        "version": PACK_SPEC_VERSION,
    }
    (pack_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    zip_path = out_dir / f"{name}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in sorted(pack_dir.rglob("*")):
            if f.is_file():
                zf.write(f, f.relative_to(pack_dir))
    print(f"built {name}: {total / 1e6:.1f} MB raw, zip {zip_path.stat().st_size / 1e6:.1f} MB")
    return zip_path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--spec", required=True, type=Path, help="packs.json registry")
    ap.add_argument("--name", action="append", help="pack id to build (repeatable)")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--cache-root", type=Path, default=Path("../../models/cache"))
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    specs = json.loads(args.spec.read_text(encoding="utf-8"))
    if isinstance(specs, dict):
        specs = specs.get("packs", [])
    selected = args.name or ([s["id"] for s in specs] if args.all else [])
    if not selected:
        raise SystemExit("nothing selected: pass --name or --all")

    args.out.mkdir(parents=True, exist_ok=True)
    for spec in specs:
        if spec["id"] in selected:
            build_pack(spec, args.cache_root, args.out)


if __name__ == "__main__":
    main()
