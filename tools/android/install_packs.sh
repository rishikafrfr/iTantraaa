#!/usr/bin/env bash
# Sideload iTantra language packs onto a REAL phone over USB.
#
# The app currently has no in-app downloader; production phones have no adb
# root, so packs go in through `run-as` (requires the DEBUG apk — release
# builds are not debuggable and refuse run-as).
#
# Windows adb truncates large host->device stdin streams, so the tar is pushed
# whole with `adb push` (native binary-safe protocol) to /data/local/tmp and
# piped ON-DEVICE into run-as (pipes are not SELinux-labeled, so this works on
# production builds of Android).
#
# Usage:
#   tools/android/install_packs.sh <phone-serial> <pack-id> [<pack-id>...]
# Example:
#   tools/android/install_packs.sh 1234567890ABCDEF multi-stt-omnilingual-300m-int8 en-tts-piper-lessac-medium hi-tts-mms
set -euo pipefail
# Git Bash mangles adb remote paths; disable conversion
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")/../.."

if [ $# -lt 2 ]; then
  echo "usage: $0 <adb-serial> <pack-id>..." >&2
  exit 1
fi

SERIAL=$1; shift
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PKG=isro.itantra
STAGE=/data/local/tmp/itantra-pack.tar
mkdir -p build
TARBALL_LOCAL=$(pwd)/build/itantra-pack.tar
TARBALL_WIN=$(cygpath -w "$TARBALL_LOCAL")

# install-dir rule must match PackRegistry: omnilingual STT -> models/omnilingual,
# tts -> models/tts-<lang>, whisper -> models/whisper-tiny
dest_for() {
  python - "$1" <<'PY'
import json, sys
m = json.load(open(f"dist/packs/{sys.argv[1]}/manifest.json", encoding="utf-8"))
kind, mt, lang = m.get("kind"), m.get("modelType"), m.get("lang")
if kind == "stt" and mt == "omnilingual_asr_ctc":
    print("omnilingual")
elif kind == "stt" and mt == "whisper":
    print("whisper-tiny")
elif kind == "tts":
    print(f"tts-{lang}")
else:
    print(m["id"])
PY
}

for PACK in "$@"; do
  DIR=dist/packs/$PACK
  if [ ! -d "$DIR" ]; then
    echo "no such pack: $DIR (build first: tools/packs/build_pack.py --spec tools/packs/packs.json --name $PACK --out dist/packs)" >&2
    exit 1
  fi
  DEST=$(dest_for "$PACK")
  echo "-> installing $PACK into files/models/$DEST"
  tar -C "$DIR" -cf "$TARBALL_LOCAL" .
  "$ADB" -s "$SERIAL" push "$TARBALL_WIN" "$STAGE" >/dev/null
  "$ADB" -s "$SERIAL" shell "cat $STAGE | run-as $PKG sh -c 'mkdir -p files/models/$DEST && cd files/models/$DEST && tar xpf -'"
  "$ADB" -s "$SERIAL" shell "rm -f $STAGE"
  "$ADB" -s "$SERIAL" shell "run-as $PKG ls files/models/$DEST" | tr -d '\r' | head -4
  echo "[ok] $PACK done"
done
echo "All packs installed. Sizes on device:"
"$ADB" -s "$SERIAL" shell "run-as $PKG du -sh /data/data/$PKG/files/models/*" | tr -d '\r'
