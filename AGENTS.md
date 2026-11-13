# AGENTS.md — Autonomous Building, Patching, and Wireless Deployment

This document provides instructions for AI agents and automated tooling to build, test, patch, and deploy `gboard-multiswipe` directly to an Android device over Wireless Debugging via Termux or host ADB without manual user intervention.

---

## 1. Project Overview & Architecture

`gboard-multiswipe` is a specialized, standalone Morphe/ReVanced patch repository providing two features for Google Gboard:

1. **Matrix Word Suggestions** (`matrix_word_suggestions`): 2D top-bar suggestions matrix with vertical wheel scrolling and sentence history.
2. **Nintype Multi-Touch Swipe** (`nintype_multi_touch`): Two-finger chorded swipe and tap+swipe gesture typing powered by the Nintype algorithm and Gboard dictionary.

### Non-Interference Guarantee (Coexistence with Upstream)
- **Application Package / Target**: `com.google.android.inputmethod.latin`
- **Isolated Extension Namespace**: `dev.srwi.gboardmultiswipe`
- **Independent Settings Entry Key**: `gboard_multiswipe_entry` ("Multiswipe & Matrix")
- **Independent ContentProvider Authority**: `${applicationId}.gboard_multiswipe`
- **Result**: Can be patched alone or combined simultaneously with upstream Morphe/ReVanced patch bundles without settings collisions or provider crashes.
- **Language**: English only (all code, strings, tests, and documentation).

---

## 2. Environment Prerequisites

- **Java**: JDK 21+ (`JAVA_HOME` pointing to JDK 21)
- **Android SDK**: Build-tools & API platform 36 (`ANDROID_HOME` or `ANDROID_USER_HOME`)
- **ADB**: `android-tools` installed (on Linux, macOS, or inside Termux on Android)
- **Morphe CLI**: `morphe-cli.jar` (or executable binary)

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_USER_HOME=/path/to/android-sdk
```

---

## 3. Building the Patches Bundle

### Build MPP Bundle & Extension DEX
```bash
./gradlew :extensions:extension:compileReleaseJavaWithJavac :patches:buildAndroid
```
Output artifact: `patches/build/libs/patches-4.2.0.mpp`

### Run Tests
```bash
# Patches contract, wiring, and metadata tests
./gradlew :patches:test

# Extension core logic tests (Nintype decoder, accent folding, matrix helpers)
./gradlew :extensions:extension:testDebugUnitTest --tests "dev.srwi.gboardmultiswipe.extension.matrixsuggestions.*" --tests "dev.srwi.gboardmultiswipe.extension.nintype.*"
```

### Refresh `patches-list.json`
```bash
./gradlew :patches:generatePatchesList
```

---

## 4. Patching Gboard

### Using `patch-gboard.sh`
The script automatically builds the MPP if missing, auto-detects Gboard APKs and Morphe CLI:
```bash
./patch-gboard.sh [input_apk_or_xapk] [output_apk] [morphe_cli_jar]
```
Or with explicit flags:
```bash
./patch-gboard.sh \
  -i /path/to/gboard-base.apk \
  -o ./gboard-multiswipe-patched.apk \
  -m /path/to/morphe-cli.jar
```

### Direct Morphe CLI Invocation
```bash
java -jar morphe-cli.jar patch \
  --patches=patches/build/libs/patches-4.2.0.mpp \
  --exclusive \
  -e "Matrix Word Suggestions" \
  -e "Nintype Multi-Touch Swipe" \
  -o ./gboard-multiswipe-patched.apk \
  /path/to/gboard-base.apk
```

### Coexisting with Upstream Morphe Patches
To include both this repo's patches AND upstream Morphe patches:
```bash
java -jar morphe-cli.jar patch \
  --patches=patches/build/libs/patches-4.2.0.mpp \
  --patches=/path/to/upstream-morphe-patches.mpp \
  -e "Matrix Word Suggestions" \
  -e "Nintype Multi-Touch Swipe" \
  -e "Custom Symbols" \
  -e "Latin Globe Key Ignore Interval" \
  -o ./gboard-all-patches.apk \
  /path/to/gboard-base.apk
```

---

## 5. Wireless Debugging & Direct Device Deployment (Termux / ADB)

An agent running in Termux on the target Android phone (or on a local machine on the same Wi-Fi) can pull, patch, and install Gboard directly.

### Step 1: Pair and Connect over Wireless Debugging
1. On the Android device: Go to **Settings > Developer Options > Wireless debugging**.
2. Tap **Pair device with pairing code**. Note the IP, pairing port, and 6-digit pairing code.
3. In Termux or host shell:
   ```bash
   adb pair localhost:<pairing_port> <pairing_code>
   # Or from host computer: adb pair <device_ip>:<pairing_port> <pairing_code>
   ```
4. Connect to the debugging port shown on the main Wireless debugging screen:
   ```bash
   adb connect localhost:<debug_port>
   # Or from host computer: adb connect <device_ip>:<debug_port>
   ```
5. Confirm connection:
   ```bash
   adb devices
   ```

### Step 2: Extract Current Gboard Base APK from Phone
Extract the clean, already-installed base APK directly from the device:
```bash
GBOARD_APK_PATH=$(adb shell pm path com.google.android.inputmethod.latin | grep "base.apk" | head -n 1 | cut -d: -f2 | tr -d '\r')
adb pull "$GBOARD_APK_PATH" ./gboard-base.apk
```

### Step 3: Patch the Extracted APK
```bash
./patch-gboard.sh -i ./gboard-base.apk -o ./gboard-multiswipe-patched.apk -m ./morphe-cli.jar
```

### Step 4: Install Patched APK Wirelessly
```bash
# -r replaces existing application, -d allows version code downgrade if needed
adb install -r -d ./gboard-multiswipe-patched.apk
```

### Step 5: Restart Gboard
Force-stop Gboard so the new DEX extension and patched bytecode take effect immediately:
```bash
adb shell am force-stop com.google.android.inputmethod.latin
```

### Step 6: Verify Deployment
1. Verify that Gboard is running:
   ```bash
   adb shell pidof com.google.android.inputmethod.latin
   ```
2. Check logcat for initialization tags:
   ```bash
   adb logcat -d -s GboardPatches NintypeDecoder MatrixSuggestions | tail -n 50
   ```
3. Open Gboard Settings on the phone: tap the gear icon -> select **Multiswipe & Matrix** to configure Nintype swipe timing, autospace, and 2D matrix suggestions.

---

## 6. Coding Standards for Autonomous Agents

- **Language**: English exclusively across all commit messages, code, comments, resource strings, and documentation.
- **Commit Cadence**: Commit after each logical, verified step using conventional commits (`feat`, `refactor`, `fix`, `docs`).
- **Focus**: Maintain only `Matrix Word Suggestions` and `Nintype Multi-Touch Swipe`. Do not re-add upstream features or dependencies.
- **Namespace Integrity**: Never revert package namespace back to upstream `dev.jason.gboardpatches`; always maintain `dev.srwi.gboardmultiswipe`.
