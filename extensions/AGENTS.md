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
    ├── thicctopbar/                                # Thicc Top Bar suggestion bar & reflector
    ├── multiswipe/                                 # Kinetica-ported MultiSwipe gesture decoder & hooks
    └── settings/                                    # MultiSwipe & Thicc Top Bar settings UI & diagnostics
```

---

## 2. Feature Subsystems

### MultiSwipe (`multiswipe/`)
- **Behavioral Specification**: See [`docs/multiswipe-integration.md`](../docs/multiswipe-integration.md) (`MS-01`–`MS-21`) for the authoritative Gboard integration rules and test traceability matrix. Any code change that alters a defined `MS-*` rule requires **explicit user confirmation** and synchronized updates to the spec and tests.
- **Runtime Hooks (`GboardMultiSwipeRuntime.java`)**:
  - Coordinates with bytecode hooks in `MotionEventHandlerManager`, `AbstractGestureMotionEventHandler`, and `BasicMotionEventHandler` to intercept two-finger touchdown (`ACTION_POINTER_DOWN`) before Gboard's chording lock cancels gesture tracking.
  - Extracts exact subpixel key centers, bounds, and `SoftKeyView` labels from Gboard's native geometry field (`Ltjz;` via reflection) with zero static grid guesswork.
  - Draws dual gesture trails using Material You secondary/tertiary accent colors (`multiswipeTrailColor`), distinct from Gboard's primary trail color.
- **Decoder (`KineticaDtwDecoder.java`, `DtwMatcher.java`, `MergeAlternatives.java`, `ThumbBeam.java`, `KineticaConstants.java`)**:
  - Continuous DTW path matching & beam search ported from the Kinetica algorithm.
  - Resolves timing ambiguities for near-simultaneous two-finger swipes/taps (`MergeAlternatives`) and combines DTW scores with language probabilities.
- **Dictionaries (`GboardMultiSwipeDictionaryManager.java`, `GboardLmReader.java`, `MultiSwipeTrie.java`, `MultiSwipeBigramTable.java`, `MultiSwipeAccentFolder.java`)**:
  - Reads Gboard's native binary dictionaries directly on-device and folds accented characters transparently.
- **Settings & Diagnostics (`GboardMultiSwipeSettings.java`, `GboardMultiSwipeTraceLogger.java`)**:
  - Slider controls for beam/timing/autospace tuning, defaults reset, and selectable logcat export window (`1..60` minutes).

### Thicc Top Bar (`thicctopbar/`)
- **Runtime (`GboardThiccTopBarRuntime.java`, `ThiccTopBarGboardReflector.java`)**:
  - Replaces/augments the top candidate strip with `ThiccTopBarView`, `VerticalWordWheelView`, `JumpToEndArrowView`, and `ThiccTopBarSentenceBuffer` for alternative browsing and sentence history navigation.
  - Mirrors Gboard's normal trail/primary Material accent color via `ThiccTopBarGboardReflector` (`recordStockTrailAccent` / `resolveNormalAccentColor`) rather than borrowing the MultiSwipe trail color.

### Settings UI (`settings/`)
- Built around `GboardPatchesSettingsActivity`, `GboardPatchesSettingsProvider` (`${applicationId}.gboard_multiswipe`), `GboardPatchesFeatureAvailability` (checks `dev.srwi.gboardmultiswipe.feature.*` marker assets), and `GboardPatchesIcons`.
- Do NOT change public method signatures declared in `patches/.../runtimeabi/RuntimeAbi.kt` without updating both modules in lockstep.

---

## 3. Settings Text & Testing Rules

- `:extensions:extension:generateSettingsText` generates Java constants and Android string resources from `src/main/settings-text/gboard_settings_text.xml`.
- **Unit testing on headless / ARM64 Termux hosts**:
  ```bash
  ./gradlew :extensions:extension:testDebugUnitTest \
    --tests "dev.srwi.gboardmultiswipe.extension.thicctopbar.*" \
    --tests "dev.srwi.gboardmultiswipe.extension.multiswipe.*"
  ```
  Robolectric tests in `settings.*` require Robolectric Android runtime JARs (unavailable offline or on ARM64 Termux hosts). Any edits touching `multiswipe/` or `thicctopbar/` logic must keep the filtered unit suites passing alongside `:patches:test`.
