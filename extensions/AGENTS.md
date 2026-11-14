# `extensions/` — Gboard Runtime Extension Guide

This directory holds the Android runtime library (`extensions/extension`) compiled into `extensions/gboard-multiswipe.rve` and merged into patched Gboard APKs by `GboardPatchesExtensionCarrierPatch`.

---

## 1. Module Structure

```text
extensions/extension/src/main/
├── AndroidManifest.xml                              # Extension package metadata
├── settings-text/gboard_settings_text.xml           # Authoritative settings strings & i18n
├── res/                                             # Drawables & night/light color palettes
└── java/dev/srwi/gboardmultiswipe/extension/
    ├── GboardPatchExtension.java                    # Carrier extension placeholder entry point
    ├── flagsettings/                                # Boolean flag/settings persistence helpers
    ├── matrixsuggestions/                           # 2D Matrix suggestion bar & reflector
    ├── nintype/                                     # Kinetica-ported Nintype decoder & hooks
    └── settings/                                    # Multiswipe & Matrix settings UI & diagnostics
```

---

## 2. Feature Subsystems

### Nintype Multi-Touch Swipe (`nintype/`)
- **Runtime Hooks (`GboardNintypeRuntime.java`)**:
  - Coordinates with bytecode hooks in `MotionEventHandlerManager`, `AbstractGestureMotionEventHandler`, and `BasicMotionEventHandler` to intercept two-finger touchdown (`ACTION_POINTER_DOWN`) before Gboard's chording lock cancels gesture tracking.
  - Extracts exact subpixel key centers, bounds, and `SoftKeyView` labels from Gboard's native geometry field (`Ltjz;` via reflection) with zero static grid guesswork.
  - Draws dual gesture trails using Material You secondary/tertiary accent colors (`nintypeTrailColor`), distinct from Gboard's primary trail color.
- **Decoder (`KineticaDtwDecoder.java`, `DtwMatcher.java`, `MergeAlternatives.java`, `ThumbBeam.java`, `KineticaConstants.java`)**:
  - Continuous DTW path matching & beam search ported from Kinetica (inspired by Nintype).
  - Resolves timing ambiguities for near-simultaneous two-finger swipes/taps (`MergeAlternatives`) and combines DTW scores with language probabilities.
- **Dictionaries (`GboardNintypeDictionaryManager.java`, `GboardLmReader.java`, `NintypeTrie.java`, `NintypeBigramTable.java`, `NintypeAccentFolder.java`)**:
  - Reads Gboard's native binary dictionaries directly on-device and folds accented characters transparently.
- **Settings & Diagnostics (`GboardNintypeSettings.java`, `GboardNintypeTraceLogger.java`)**:
  - Slider controls for beam/timing/autospace tuning, defaults reset, and selectable logcat export window (`1..60` minutes).

### Matrix Word Suggestions (`matrixsuggestions/`)
- **Runtime (`GboardMatrixSuggestionsRuntime.java`, `MatrixGboardReflector.java`)**:
  - Replaces/augments the top candidate strip with `MatrixSuggestionBarView`, `VerticalWordWheelView`, `JumpToEndArrowView`, and `MatrixSentenceBuffer` for 2D alternative browsing and sentence history navigation.
  - Mirrors Gboard's normal trail/primary Material accent color via `MatrixGboardReflector` (`recordStockTrailAccent` / `resolveNormalAccentColor`) rather than borrowing the Nintype trail color.

### Settings UI (`settings/`)
- Built around `GboardPatchesSettingsActivity`, `GboardPatchesSettingsProvider` (`${applicationId}.gboard_multiswipe`), `GboardPatchesFeatureAvailability` (checks `dev.srwi.gboardmultiswipe.feature.*` marker assets), and `GboardPatchesIcons`.
- Do NOT change public method signatures declared in `patches/.../runtimeabi/RuntimeAbi.kt` without updating both modules in lockstep.

---

## 3. Settings Text & Testing Rules

- `:extensions:extension:generateSettingsText` generates Java constants and Android string resources from `src/main/settings-text/gboard_settings_text.xml`.
- **Unit testing on headless / ARM64 Termux hosts**:
  ```bash
  ./gradlew :extensions:extension:testDebugUnitTest \
    --tests "dev.srwi.gboardmultiswipe.extension.matrixsuggestions.*" \
    --tests "dev.srwi.gboardmultiswipe.extension.nintype.*"
  ```
  Robolectric tests in `settings.*` require Robolectric Android runtime JARs (unavailable offline or on ARM64 Termux hosts). Any edits touching `nintype/` or `matrixsuggestions/` logic must keep the filtered unit suites passing alongside `:patches:test`.
