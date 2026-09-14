#!/usr/bin/env bash
# Phase 0.5 — run the on-device model verification on the Android emulator.
# Verifies: omnilingual STT (en+hi), Silero VAD, Piper-en TTS, MMS-hi TTS, Whisper-en STT.
#
# NOTE: we install APKs + push models + invoke am instrument directly instead of
# `gradlew connectedDebugAndroidTest`, because Gradle uninstalls the app after the
# test run, and Android then deletes /sdcard/Android/data/<pkg> — taking the
# pushed models with it.
#
# Usage: tools/android/run_verification.sh
set -euo pipefail
# Git Bash mangles adb remote paths (/sdcard -> C:/Program Files/Git/sdcard); disable conversion
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")/../.."

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
PKG=isro.itantra
SDK="${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb.exe"
EMU="$SDK/emulator/emulator.exe"
APP_FILES="/data/data/$PKG/files"
APK_APP="app/build/outputs/apk/debug/app-debug.apk"
APK_TEST="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

echo "== 1. boot emulator (headless) =="
if ! "$ADB" devices | grep -E "emulator-[0-9]+\s+device"; then
  "$EMU" -avd itantra -no-window -gpu swiftshader_indirect -no-audio \
         -memory 4096 -no-boot-anim -no-snapshot >/tmp/emu.log 2>&1 &
  "$ADB" wait-for-device
fi
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 3
done
echo "emulator ready"

echo "== 2. build + install APKs =="
./gradlew assembleDebug assembleDebugAndroidTest --no-daemon -q
"$ADB" install -r "$APK_APP" >/dev/null
"$ADB" install -r "$APK_TEST" >/dev/null
# google_apis emulator images allow adb root; required to write into /data/data/<pkg>
"$ADB" root >/dev/null 2>&1 || true
sleep 3

echo "== 3. push models + audio (app must stay installed: uninstall wipes its files) =="
push_dir() { # local_dir remote_dir
  echo "  push $1 -> $APP_FILES/$2"
  "$ADB" shell "mkdir -p $APP_FILES/$2"
  "$ADB" push "$1/." "$APP_FILES/$2/"
  "$ADB" shell "chmod -R a+rwX $APP_FILES/$2"
}
"$ADB" shell "mkdir -p $APP_FILES/models $APP_FILES/audio $APP_FILES/results"
push_dir dist/packs/multi-stt-omnilingual-300m-int8 models/omnilingual
push_dir dist/packs/en-stt-whisper-tiny-int8      models/whisper-tiny
push_dir dist/packs/en-tts-piper-lessac-medium    models/tts-en
push_dir dist/packs/hi-tts-mms                    models/tts-hi
"$ADB" shell "mkdir -p $APP_FILES/models/vad"
"$ADB" push models/cache/silero_vad.onnx "$APP_FILES/models/vad/silero_vad.onnx"
"$ADB" push models/cache/omnilingual-300m-int8/test_wavs/en.wav "$APP_FILES/audio/en.wav"
"$ADB" push tools/eval/results/audio/tts_mms_hi.wav "$APP_FILES/audio/hi.wav"

echo "== 4. run instrumented tests =="
"$ADB" logcat -c
"$ADB" shell am instrument -w -e class isro.itantra.ModelVerificationTest \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee /tmp/instrument.log
"$ADB" logcat -d -s ITANTRA:V | tee tools/android/results_logcat.txt || true

echo "== 5. pull results =="
mkdir -p tools/android/results
"$ADB" pull "$APP_FILES/results/device_verification.json" tools/android/results/
"$ADB" pull "$APP_FILES/results/tts_piper_en.wav" tools/android/results/ || true
"$ADB" pull "$APP_FILES/results/tts_mms_hi.wav" tools/android/results/ || true
"$ADB" unroot >/dev/null 2>&1 || true
echo "done: tools/android/results/device_verification.json"
