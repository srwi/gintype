# Gboard Multiswipe

Morphe patches for Google Gboard adding two-finger chorded gesture swiping (**Nintype**) and vertical 2D suggestion grids (**Matrix Word Suggestions**).

This fork is intentionally streamlined: it contains **only** these two custom features, isolated into their own namespace (`dev.srwi.gboardmultiswipe`) so they can seamlessly coexist alongside upstream patches (e.g. from JasonWu's Gboard-patches or ReVanced) without DEX class overwrites, ContentProvider authority conflicts, or settings menu collisions.

---

## Features

### 1. Nintype Multi-Touch Swipe (`nintype_multi_touch`)
- **Two-finger simultaneous swiping**: Swipe with two thumbs/fingers at the same time to construct words rapidly.
- **Tap + Swipe chording**: Tap initial letters while swiping the rest (or vice versa).
- **Kinetica/Nintype DTW algorithm**: Dynamic time warping path matching ported for Gboard.
- **Native dictionary integration**: Feeds from Gboard's language model (`.dict` / delight binary format) for candidate predictions and ranking.
- **Configurable tuning**: Customize beam widths, speed exponents, distance thresholds, and trace logging directly from the settings page.

### 2. Matrix Word Suggestions (`matrix_word_suggestions`)
- **2D vertical word wheels**: In the top suggestion strip, swipe up/down on candidate chips to navigate alternative suggestions and previous sentence variants.
- **Sentence buffer history**: Retains sentence contexts so you can flick backward through previously constructed branches.
- **Jump to end shortcut**: Quick tap to jump to the newest branch in sentence navigation.

---

## Coexistence with Upstream Patches

Unlike standard forks that keep the original namespace and overwrite classes, **Gboard Multiswipe** uses Option A isolation:

| Component | Upstream Patches | Gboard Multiswipe |
|---|---|---|
| **Package Namespace** | `dev.jason.gboardpatches.*` | `dev.srwi.gboardmultiswipe.*` |
| **Provider Authority** | `${packageName}.gboard_patches` | `${packageName}.gboard_multiswipe` |
| **Settings Entry Key** | `gboard_patches_entry` | `gboard_multiswipe_entry` |
| **Settings Entry Title** | `Patches` | `Multiswipe & Matrix` |
| **Runtime Extension Dex** | `extensions/gboard-patches.rve` | `extensions/gboard-multiswipe.rve` |

If you patch Gboard using both this bundle and upstream bundles (or use Morphe / ReVanced CLI with multiple `--patches` bundles), both patch suites apply cleanly without colliding:
- Gboard Settings will show both **"Patches"** and **"Multiswipe & Matrix"** as independent sub-menus.
- Neither ContentProvider will fail initialization.

---

## Building

### Requirements
- JDK 21 (Temurin OpenJDK 21 recommended)
- Android SDK with platform 36 (`local.properties` with `sdk.dir=...`)

### Build Commands

```bash
# Build extension and assemble Morphe patch bundle (.mpp)
./gradlew :extensions:extension:compileReleaseJavaWithJavac :patches:buildAndroid

# Run all unit tests
./gradlew :patches:test :extensions:extension:testDebugUnitTest \
  --tests "dev.srwi.gboardmultiswipe.extension.nintype.*" \
  --tests "dev.srwi.gboardmultiswipe.extension.matrixsuggestions.*"

# Generate patch metadata list
./gradlew :patches:generatePatchesList
```

The resulting patch bundle is placed at:
```
patches/build/libs/patches-4.2.0.mpp
```

---

## Patching Gboard

### Using Morphe CLI

```bash
java -jar morphe-cli.jar patch \
  --patches=patches/build/libs/patches-4.2.0.mpp \
  --exclusive \
  -e "Matrix Word Suggestions" \
  -e "Nintype Multi-Touch Swipe" \
  -o Gboard-multiswipe-patched.apk \
  gboard-base.apk
```

### Combining with Upstream Patches

```bash
java -jar morphe-cli.jar patch \
  --patches=upstream-gboard-patches.mpp \
  --patches=patches/build/libs/patches-4.2.0.mpp \
  -o Gboard-combined-patched.apk \
  gboard-base.apk
```

---

## License

GPLv3. See [LICENSE](LICENSE) for details.
