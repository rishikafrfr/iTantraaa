#!/usr/bin/env bash
# Provision the PHYSICAL phone (Phone B) and wire it to the emulator (Phone A).
#
# The emulator sits behind its own NAT, so the two cannot see each other on the
# LAN. The link goes through adb instead:
#
#   emulator --> 10.0.2.2:4747 --> [host PC loopback] --> adb forward --> phone:4747
#
# So Phone B hosts, Phone A joins at 10.0.2.2 (which is what the Peer IP field
# already defaults to).
#
# Usage: tools/android/setup_phone_b.sh [pack-id ...]
#   default packs: omnilingual STT + English and Hindi voices
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")/../.."

ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PORT=4747

# the one physical device (anything that is not an emulator)
SERIAL=$("$ADB" devices | awk '/\tdevice$/ && $1 !~ /^emulator-/ {print $1}' | head -1)
if [ -z "$SERIAL" ]; then
  echo "No physical device found." >&2
  echo "Plug the phone in, enable USB debugging, and accept the RSA prompt." >&2
  "$ADB" devices >&2
  exit 1
fi
echo "Phone B: $SERIAL"

# arm64 covers essentially every phone made since 2016; fall back to v7a
ABI=$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
case "$ABI" in
  arm64*) APK=app/build/outputs/apk/debug/app-arm64-v8a-debug.apk ;;
  armeabi*) APK=app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk ;;
  x86_64) APK=app/build/outputs/apk/debug/app-x86_64-debug.apk ;;
  *) echo "unexpected ABI $ABI" >&2; exit 1 ;;
esac
echo "ABI $ABI -> $APK"

echo "== installing app =="
# debug build on purpose: run-as pack sideloading needs a debuggable package
"$ADB" -s "$SERIAL" install -r "$APK"

PACKS=("$@")
if [ ${#PACKS[@]} -eq 0 ]; then
  PACKS=(multi-stt-omnilingual-300m-int8 en-tts-piper-lessac-medium hi-tts-mms)
fi
echo "== sideloading packs: ${PACKS[*]} =="
ADB="$ADB" ./tools/android/install_packs.sh "$SERIAL" "${PACKS[@]}"

echo "== bridging emulator <-> phone on tcp:$PORT =="
"$ADB" -s "$SERIAL" forward --remove tcp:$PORT >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" forward tcp:$PORT tcp:$PORT
"$ADB" -s "$SERIAL" forward --list

cat <<EOF

Ready. Now, in this order:

  Phone B (the real phone)   Connect tab -> HOST
  Phone A (the emulator)     Connect tab -> Peer IP 10.0.2.2 -> JOIN

The carrier bar under the header turns solid amber on both when the link is up.
EOF
