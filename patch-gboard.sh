#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=== 1. Building patches bundle ==="
./gradlew :extensions:extension:compileReleaseJavaWithJavac :patches:buildAndroid

MPP_FILE="$DIR/patches/build/libs/patches-4.2.0.mpp"
XAPK_FILE="/data/data/com.termux/files/home/storage/downloads/gboard-18-5-4-993608717-release-arm64-v8a.xapk"
BASE_APK="/data/data/com.termux/files/home/.gemini/antigravity-cli/brain/e0fcb8d4-3444-408d-bb0b-37867b8c8ffb/scratch/apk/gboard-base.apk"
INPUT_BUNDLE="${XAPK_FILE}"
if [ ! -f "$INPUT_BUNDLE" ]; then
  INPUT_BUNDLE="$BASE_APK"
fi
OUT_APK="/data/data/com.termux/files/home/storage/downloads/GboardPatched.apk"
MORPHE_JAR="/data/data/com.termux/files/home/.gemini/antigravity-cli/brain/e0fcb8d4-3444-408d-bb0b-37867b8c8ffb/scratch/morphe-desktop.jar"

echo "=== 2. Copying MPP bundle to Downloads ==="
cp "$MPP_FILE" ~/storage/downloads/gboard-matrix-suggestions-patches.mpp
cp "$MPP_FILE" ~/storage/downloads/gboard-nintype-patches.mpp
cp "$MPP_FILE" ~/storage/downloads/patches-4.2.0.mpp

echo "=== 3. Patching bundle ($INPUT_BUNDLE) with Matrix Word Suggestions + Nintype Multi-Touch Swipe + Signature Bypass + Package Rename (GboardPatched) ==="
java -jar "$MORPHE_JAR" patch \
  --patches="$MPP_FILE" \
  --exclusive \
  -e "Matrix Word Suggestions" \
  -e "Nintype Multi-Touch Swipe" \
  -e "Add Gboard Signature Bypass" \
  -e "Package Rename" \
  -O app_display_name="GboardPatched" \
  -o "$OUT_APK" \
  "$INPUT_BUNDLE"

echo "=== Done! Patched APK ready at $OUT_APK ==="
