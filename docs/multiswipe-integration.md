# MultiSwipe Gboard Integration Specification

This document is the **single source of truth** for how **MultiSwipe** integrates into Google Gboard (`com.google.android.inputmethod.latin`). It defines all normative behavioral rules, edge-case handling, and test coverage expectations for the Gboard integration layer.

> **Scope Boundary (Integration vs. Upstream Kinetica):**
> This specification covers **only the Gboard integration behavior** (touch routing, stock-Gboard preservation, motion-handler hooks, trail accenting, multi-stroke buffers, text/spacing/punctuation context, keyboard geometry boundaries, dictionary loading, Thicc Top Bar synchronization, settings, and telemetry).
> Internal mathematical details of the upstream **Kinetica** decoding engine (`KineticaDtwDecoder`, `DtwMatcher`, `KineticaMatcher`, `ThumbBeam`, `MergeAlternatives`) are intentionally out of scope so upstream algorithm updates can be synced independently without rewriting this specification.

---

## 1. Change Control & Review Policy

1. **Authoritative Behavior Contract:** The rules (`MS-01` through `MS-19`) in this document define the intended runtime behavior of the MultiSwipe integration. Code and tests must conform to this specification.
2. **Explicit User Confirmation Required for Behavioral Changes:**
   - Whenever a proposed code change (feature, bugfix, refactor, or default/threshold adjustment) would **alter, add, remove, or contradict** any rule defined in this document, **the user must explicitly confirm the specification change** before the behavioral change is accepted.
   - Any agent or contributor proposing such a change must clearly identify which `MS-*` rule(s) are affected, describe the old vs. new behavior, and ask for explicit confirmation so previously defined behaviors are never broken accidentally.
3. **Spec–Code–Test Synchronization:**
   - Every approved change to an integration rule must update:
     1. The corresponding `MS-*` rule(s) in this document,
     2. The implementation, and
     3. Automated test coverage in the traceability matrix below.
4. **Upstream Kinetica Sync Invariant:**
   - Re-syncing Kinetica decoder files from upstream must preserve all integration interfaces consumed by `GboardMultiSwipeRuntime`, `MultiSwipeWordSession`, `GboardMultiSwipeSettings`, `GboardMultiSwipeDictionaryManager`, and `MultiSwipeGeometry` (including runtime tuning constants via `KineticaConstants.applyRuntimeTuning`, `MAX_WORD_LEN`, `RESTING_FINGER_MIN_MS`, `PERSONAL_MERGE_MIN_COUNT`, and token/geometry inputs).

---

## 2. Integration Architecture Overview

```mermaid
flowchart TD
    A["MotionEvent in AbstractGestureMotionEventHandler.g"] --> B{"MS-01: MultiSwipe enabled?"}
    B -- No --> S["Stock Gboard Pipeline"]
    B -- Yes --> C["Update geometry & schedule LM load (MS-15, MS-17)"]
    C --> D{"Multi-touch / active takeover / continuing word?"}
    D -- Yes --> E["Suppress rollover & BasicMotionEventHandler; keep trails & dispatcher ownership (MS-06, MS-07)"]
    D -- No --> F["Allow stock Gboard to handle stroke while tracking tokens"]
    E --> G["ACTION_UP: Classify tokens & evaluate MS-03 / MS-08 / MS-09"]
    F --> G
    G -- "Stay on stock Gboard (MS-02)" --> H["Record passive/combine buffers if eligible (MS-08, MS-09); log pass if enabled"]
    G -- "Decode with MultiSwipe" --> I["Roll back stock prefix if promoted; apply spacing & casing rules (MS-11..MS-14)"]
    I --> J["Set live composing preview, notify Thicc Top Bar (MS-18), start autospace timer if MS-12 enabled"]
```

### Key Integration Components

| Responsibility | Primary Files |
| :--- | :--- |
| Bytecode hooks into Gboard touch pipeline | `patches/.../multiswipe/GboardMultiSwipeBytecodePatch.kt`, `patches/.../runtimeabi/RuntimeAbi.kt` |
| Android touch routing, trail styling, reflection | `extensions/.../multiswipe/GboardMultiSwipeRuntime.java` |
| Pure-JVM word session, buffers, spacing, Backspace compound state | `extensions/.../multiswipe/MultiSwipeWordSession.java` |
| Key bounds, letter-area hit testing, midline | `extensions/.../multiswipe/MultiSwipeGeometry.java` |
| Gboard `.lm` extraction, `UserDictionary`, session learning | `extensions/.../multiswipe/GboardMultiSwipeDictionaryManager.java`, `GboardLmReader.java` |
| Settings defaults, clamping, UI visibility, decoder tuning sync | `extensions/.../multiswipe/GboardMultiSwipeSettings.java`, `GboardMultiSwipeSettingsFeature.java` |
| JSONL trace logging & time-window export | `extensions/.../multiswipe/GboardMultiSwipeTraceLogger.java` |
| Top bar candidate & sentence-history sync | `extensions/.../thicctopbar/GboardThiccTopBarRuntime.java`, `ThiccTopBarGboardReflector.java` |

---

## 3. Normative Integration Rules

### A. Activation, Routing & Stock Gboard Coexistence

#### `MS-01` — Feature Toggle & Safe Bypass
- **Rule:** When MultiSwipe is disabled (`pref_multiswipe_enabled == false`), all Gboard touch and gesture events must pass through to stock Gboard without modification.
- **Details:**
  - `onGestureEventEntry` returns immediately.
  - `shouldStartGesture` returns `stockShouldStart` and `resolveLetterStarted` returns `stockLetterStarted`.
  - `shouldReceiveEventsDuringOtherOwner`, `shouldSuppressBasicMotionEventHandler`, and `shouldSuppressRolloverCommitOnPointerDown` all return `false`.
- **Implementation:** `GboardMultiSwipeRuntime`, `MultiSwipeWordSession`, `GboardMultiSwipeSettings.readEnabled`.

#### `MS-02` — Single-Finger Stock Gboard Preservation
- **Rule:** Normal single-finger taps and single-finger swipes stay on stock Gboard's native pipeline (stock recognition, stock commit, stock gesture trail color), except when explicitly promoted or continued under `MS-03`, `MS-08`, `MS-09`, or `MS-10`, or when post-swipe autospace (`MS-12`, only when `pref_multiswipe_auto_space_before_word == false`) appends a trailing space after the combine window expires. When `pref_multiswipe_auto_space_before_word == true` (Gboard style), single-finger stock swipes remain 100% on stock Gboard with no post-swipe autospace timer.
- **Details:**
  - A standalone single-finger stroke does not activate `multiswipeTakeoverActive` and does not intercept `Lqit;->n(...)V` dispatch (`shouldInterceptGestureDispatch` returns `false`).
  - Pressing Space (or Enter, punctuation, or navigating in the text field) clears open buffers so the next single-finger stroke again executes purely on stock Gboard (e.g., swipe `"ha"` → Space → swipe `"lo"` produces `"ha lo"` via stock Gboard).
  - Pure multi-touch rollover taps that contain **no** swipe anywhere in the current stroke or active word (`hasSwipe == false`) also remain on stock Gboard (`reason = "rollover_no_swipe"`).
- **Implementation:** `GboardMultiSwipeRuntime.onGestureEventEntry`, `MultiSwipeWordSession`, `shouldInterceptGestureDispatch`.

#### `MS-03` — MultiSwipe Decode Trigger Conditions
- **Rule:** MultiSwipe takes over on `ACTION_UP` and decodes via Kinetica if and only if at least one of the following holds:
  1. The stroke involved multi-touch or active MultiSwipe takeover (`sawMultiTouch || multiswipeTakeoverActive`) **and** at least one `SWIPE` token is present in the stroke or active word buffer;
  2. The stroke is continuing an active MultiSwipe word (`multiswipeWordActive && !wordTokens.isEmpty()`) **and** at least one `SWIPE` token is present;
  3. `PassiveTapBuffer` holds a prior multi-touch tap prefix (`hadMultiTouch == true`) and the current stroke completes with a `SWIPE` (`promoteMultiTouchPassive && hasSwipe`);
  4. `SingleTouchCombineBuffer` is active (`!autoSpaceEnabled || singleTouchCombineDelayMs > 0`) and promotes sequential single-touch strokes containing at least one `SWIPE` without non-letter touches (`promoteSingleTouchCombine`).
- **Empty Candidate Fallback (`0` Candidates):**
  - **First promotion of `SingleTouchCombineBuffer` when `autoSpaceEnabled == true`:** If Kinetica returns `0` candidates (`!wasMultiSwipeWordActive && !isMultiSwipeGesture`), MultiSwipe leaves the existing editor text untouched and does **not** block stock Gboard's dispatch so fast consecutive 1-finger stock words are never swallowed.
  - **First promotion of `SingleTouchCombineBuffer` when `autoSpaceEnabled == false`:** MultiSwipe still takes over (`multiswipeTakeoverActive = true`, `multiswipeWordActive = true`), suppresses stock Gboard dispatch (preventing Gboard from inserting a second separated word without Space), keeps the existing best match/stock prefix intact in the editor, and retains all combined tokens in `wordTokens` so subsequent strokes can continue combining.
  - **Continuation of an already active MultiSwipe word (`wasMultiSwipeWordActive == true`):** Regardless of `autoSpaceEnabled`, if Kinetica returns `0` candidates after adding another stroke, MultiSwipe keeps the previous best decoded match in the editor, blocks stock dispatch, and retains the newly added tokens in `wordTokens`.
- **Implementation:** `GboardMultiSwipeRuntime.onGestureEventEntry`, `MultiSwipeWordSession.onActionUp`.

#### `MS-04` — Pointer Token Classification (Swipe vs. Tap vs. Resting Finger)
- **Rule:** Each pointer track is classified into a `SWIPE`, a `TAP`, a dropped resting finger, or ignored non-letter touch using net displacement rather than jitter arc length:
  1. **Swipe:** Requires `>= 2` points and net displacement from start `>= max(22px, keyWidthPx * 0.24f)`.
  2. **Resting Finger (Dropped):** If a pointer overlapped with another pointer (`overlapped == true`), did not exceed the swipe threshold, and remained down for `>= 400ms` (`KineticaConstants.RESTING_FINGER_MIN_MS`), it is dropped and emits **no** token.
  3. **Tap:** Otherwise, if the pointer started on the letter area (`startedOnLetter && startKeyChar != '\0'`), it emits a `TAP` token at its nearest letter key.
  4. **Non-Letter Touch:** Touching outside the letter area emits no token and marks `sawNonLetterThisSession = true`.
- **Implementation:** `GboardMultiSwipeRuntime.PointerTrack` (`isSwipe`, `isRestingFinger`, `toToken`).

#### `MS-05` — Dynamic Thumb Midline & Slot Assignment
- **Rule:** Active pointers are assigned to thumb slot `0` (left) or `1` (right) based on `x < geometry.midlinePx()`, falling back to the free opposite slot if the preferred slot is already occupied by an active pointer.
- **Details:** `midlinePx()` is calculated from the midpoint between `t` and `y`/`z` (using whichever is on the top row with `t`) and between `g` and `h`, so asymmetric right-hand columns (such as German `ü`/`ö`/`ä`) do not shift the midline.
- **Implementation:** `GboardMultiSwipeRuntime.registerPointerDown`, `MultiSwipeGeometry.midlinePx`.

---

### B. Gboard Motion-Handler Hooks & Visual Trail

#### `MS-06` — Multi-Touch Dispatcher & Rollover Protection
- **Rule:** When multi-touch or an active MultiSwipe word is in progress, Gboard's internal single-touch assumptions must not cancel gesture tracking, commit premature rollover taps, or overwrite MultiSwipe output:
  1. **Gesture Start (`shouldStartGesture`):** Forces native gesture start once multi-touch (or an active MultiSwipe word) has a swiping pointer (`startedOnLetter && isSwipe`), enables trail recording (`U.a = true`), and claims dispatcher ownership (`p.m()`).
  2. **Letter Guard (`resolveLetterStarted`):** Forces `letterStarted` (`u`) to `true` during multi-touch, takeover, or when completed tokens exist so `g(MotionEvent)` does not early-return.
  3. **Dispatcher Coexistence (`gu()` / `shouldReceiveEventsDuringOtherOwner`):** Returns `true` whenever pointers are active, multi-touch was seen, or takeover is active so ownership changes away from `BasicMotionEventHandler` do not starve the gesture handler.
  4. **Reset Protection (`B()` / `shouldInterceptGestureReset`):** Aborts Gboard's gesture state reset while pointers remain down in an active MultiSwipe session so `softKeyMap`, `letterStarted`, and trails stay intact.
  5. **Tap Handler Suppression (`shouldSuppressBasicMotionEventHandler`):** Suppresses `BasicMotionEventHandler.t(MotionEvent)` while `multiswipeTakeoverActive` is `true`, **except** for fresh `ACTION_DOWN` events (which must pass through so subsequent single-finger typing continues to work).
  6. **Rollover Suppression (`shouldSuppressRolloverCommitOnPointerDown`):** Suppresses `Lqil`'s premature pointer-0 commit on `ACTION_POINTER_DOWN` when `multiswipeTakeoverActive` is `true`, OR when both pointers land on the letter area within `220ms` of `ACTION_DOWN`.
  7. **Native Gesture Dispatch Suppression (`shouldInterceptGestureDispatch`):** Blocks `Lqit;->n(...)V` once `multiswipeTakeoverActive` is `true` so stock Gboard does not decode and overwrite the MultiSwipe result.
- **Implementation:** `GboardMultiSwipeBytecodePatch.kt`, `GboardMultiSwipeRuntime`, `MultiSwipeWordSession`.

#### `MS-07` — Trail Stability & Distinct MultiSwipe Trail Accent
- **Rule:** MultiSwipe reuses Gboard's native trail renderer safely and visually distinguishes MultiSwipe gestures from stock single-finger swipes via a distinct trail color:
  1. **Index Safety & Cleanup:** Trail start indices (`U.c` vs `U.b`) are sanitized on entry, during multi-touch `ACTION_MOVE`, and on pointer finish to prevent out-of-bounds crashes in Gboard's redraw runnable. Finished swipe trails with `>= 3` points are transferred to Gboard's fading list (`U.e`); shorter/tap trails are recycled without leaving stray dots.
  2. **Distinct Accent Color:** When MultiSwipe takeover or word continuation is active, `GestureOverlayView`'s `Paint` `ColorMatrixColorFilter` is switched to:
     - Android 12+ Material You `system_accent3` (tertiary) if color distance to stock primary RGB is `>= 56`,
     - Otherwise `system_accent2` (secondary) if color distance is `>= 56`,
     - Otherwise a `+120°` hue rotation of the stock accent (or fixed rose `0xEFB8C8` / `0x7D5260` if achromatic).
  3. **Stock Restoration:** Returning to single-finger/idle state or `ACTION_CANCEL` restores the captured stock `ColorFilter`.
  4. **Theme Capture:** Capturing the stock filter also reports the stock trail RGB to `ThiccTopBarGboardReflector.recordStockTrailAccent`.
- **Implementation:** `GboardMultiSwipeRuntime` (`sanitizeTrailIndices`, `finishTrailForPointer`, `setMultiSwipeTrailAccentActive`, `chooseMultiSwipeTrailColor`).

---

### C. Multi-Stroke Buffers & Word Composition

#### `MS-08` — Passive Pre-Swipe Tap Prefix Promotion & Baseline Rollback
- **Rule:** Pure-letter tap prefixes (including multi-touch rollover taps) followed by a MultiSwipe swipe combine into a single MultiSwipe word, rolling back the prefix characters already inserted by stock Gboard.
- **Timing & Capacity:**
  - When `autoSpaceEnabled == true`: active only when `pref_multiswipe_tap_to_multiswipe_delay_ms > 0` (default `750ms`, range `0..2000ms`), gap must be `<= tapToMultiSwipeDelayMs`, and token count `<= 8` (`MAX_PASSIVE_TAP_TOKENS`).
  - When `autoSpaceEnabled == false`: time gap is **unlimited** (timeout setting ignored) and capacity extends to `KineticaConstants.MAX_WORD_LEN` (`40`).
- **Guards & Rollback:**
  - Cleared immediately on any expired gap (when `autoSpaceEnabled == true`), non-letter `ACTION_DOWN` (Space, Enter, Backspace, punctuation), non-letter token (`sawNonLetterThisSession`), any non-MultiSwipe swipe, cursor navigation, or if the typed delta since `baselineText` exceeds `tokenCount + 2` or contains non-letter characters.
  - Rollback supports both exact prefix extension (`currentStr.startsWith(baseline)`) and `>= 256`-character sliding-window `InputConnection` snapshots (up to `32` extra chars).
  - *Example:* Tapping `f` then `n` (stock Gboard displays `"fn"`), then multi-touch swiping `"ktio"` + `"niert"` rolls back `"fn"` and decodes `"funktioniert"`.
- **Implementation:** `MultiSwipeWordSession.PassiveTapBuffer`, `GboardMultiSwipeRuntime.PassiveTapBuffer`, `computeExtraCharsAfterBaseline`.

#### `MS-09` — Sequential Single-Touch Combine Buffer
- **Rule:** Sequential single-touch strokes combine into one MultiSwipe word if at least one stroke is a `SWIPE`, rolling back stock Gboard's intermediate text:
  - **When `autoSpaceEnabled == true`:** Active only when `pref_multiswipe_single_touch_combine_delay_ms > 0` (default `150ms`, range `0..500ms`) and strokes are separated by `<= combineDelayMs`.
  - **When `autoSpaceEnabled == false`:** Timeout settings have no effect (`combineDelayMs` is ignored even if `0`); consecutive single-touch letter strokes remain combinable across arbitrarily long pauses until an explicit word boundary (Space, Enter, Backspace, punctuation, or cursor navigation). Even if a promoted continuation yields `0` Kinetica candidates, stock Gboard dispatch is suppressed so no unintended second Gboard word appears (`MS-03`).
- **Guards:**
  - Never promotes pure tap-only sequences with zero swipes.
  - Cleared immediately if the inter-stroke gap exceeds `combineDelayMs` (only when `autoSpaceEnabled == true`), `ACTION_DOWN` is outside the letter area, `sawNonLetterThisSession` is `true`, or the typed text since baseline ends with `' '` (e.g., stock Gboard committed `"Seiten "`), contains non-letter/non-space characters, or reflects cursor navigation (`isCombinePrefix` / `extractTypedPrefixSinceBaseline`).
  - Allows internal spaces within the prefix (e.g., `" Mi r"`) so multi-stroke split candidates can still roll back cleanly.
  - *Example (Auto-Space off):* Swipe `"ha"` → long pause → tap `'l'` → long pause → swipe `"lo"` → decodes `"hallo"`. Swipe `"ha"` → Space → swipe `"lo"` → Space clears buffer, producing `"ha lo"` via stock Gboard.
- **Implementation:** `MultiSwipeWordSession.SingleTouchCombineBuffer`, `GboardMultiSwipeRuntime.SingleTouchCombineBuffer`, `isCombinePrefix`.

#### `MS-10` — Multi-Stroke Word Continuation & Live Composing Preview
- **Rule:** While a MultiSwipe word is active (`multiswipeWordActive`), touching down on the letter area continues the same word instead of starting a new one:
  - **When `autoSpaceEnabled == true`:** Continues if the autospace timer is pending OR within `autoSpaceDelayMs + 80ms` of the previous lift.
  - **When `autoSpaceEnabled == false`:** Continues across **any** pause duration as long as the cursor has not navigated away from the active composing word (`snapshotNow` still matches the expected preview snapshot).
- **Details:**
  - Cancels any pending autospace timer, preserves `wordTokens`, keeps the MultiSwipe trail accent active, appends the new stroke's tokens, and re-decodes the full `wordTokens` buffer (retaining all `wordTokens` and the previous displayed match if `0` candidates are returned).
  - Displays the updated top candidate as live composing text (`ic.setComposingText(word, 1)`).
  - Touching down outside the letter area (Space, Enter, Shift, punctuation) or navigating in the text field immediately breaks continuation and clears the word buffer. A short Backspace tap in `last_input` mode (`MS-21`) keeps `multiswipeWordActive` active as long as at least one `SWIPE` token remains after removing the latest-started token.
- **Implementation:** `MultiSwipeWordSession.onActionDown`, `GboardMultiSwipeRuntime.onGestureEventEntry`, `previewWordToInputConnection`.

---

### D. Text Context, Spacing, Punctuation & Compound Attachment

#### `MS-11` — Pre-Word Leading Space Insertion
- **Rule:** When starting a new MultiSwipe word (`!leadingSpaceEvaluated`), if `autoSpaceEnabled == true` and the text immediately before the cursor at `ACTION_DOWN` ends with a letter or digit (`Character.isLetterOrDigit`), MultiSwipe commits a leading `" "` before the composing word unless suppressed by `MS-13`.
- **Rationale:** Stock Gboard single-finger swipes and taps leave no trailing space after a word; without a leading space, `"es"` followed by MultiSwipe `"dir"` would become `"esdir"` instead of `"es dir"`.
- **Details:** No leading space is inserted at the start of a field, after whitespace/newlines, after opening punctuation like `"("`, or when `autoSpaceEnabled == false`.
- **Implementation:** `MultiSwipeWordSession.shouldPrependLeadingSpace`, `GboardMultiSwipeRuntime.shouldPrependLeadingSpace`.

#### `MS-12` — Post-Word Autospace & Punctuation Retraction (`eatBefore`)
- **Rule:** When `autoSpaceEnabled == true`:
  - **After-Word Mode (`pref_multiswipe_auto_space_before_word == false`):**
    - **MultiSwipe words:** After all fingers lift from a MultiSwipe word, a timer runs for `pref_multiswipe_auto_space_delay_ms` (default `300ms`, range `0..2000ms`). On expiry (if no fingers are down), `ic.finishComposingText()` commits the active word, a trailing `" "` is committed, and `autospaceInserted` is set to `true`.
    - **Single-finger stock swipes:** After a single-finger letter swipe (`hasSwipe == true`, `!sawNonLetterThisSession`, `!strokeHadMultiTouch`), a timer runs for `pref_multiswipe_single_touch_combine_delay_ms` (when `> 0`) or `pref_multiswipe_auto_space_delay_ms` (when `0`). On expiry (if no fingers are down and the text immediately before the cursor ends with a letter or digit), `ic.finishComposingText()` + `ic.commitText(" ", 1)` appends a trailing `" "` and sets `autospaceInserted = true`. Pure taps (`hasSwipe == false`) never trigger post-stroke autospace.
  - **Before-Next-Word Mode (`pref_multiswipe_auto_space_before_word == true`, Gboard style, default):**
    - **MultiSwipe words:** The same `pref_multiswipe_auto_space_delay_ms` timer commits the active MultiSwipe word **without** appending a trailing `" "` (`appendSpace = false`, `autospaceInserted = false`) and arms `pendingLeadingSpaceBeforeNextWord`.
    - **Single-finger stock swipes:** No trailing `" "` is appended (`eatBefore` / double-space-period overrides remain inactive), and once the combine window (if any) expires, `pendingLeadingSpaceBeforeNextWord` is armed.
    - **Next word (`tap` or `swipe`):** On the next letter `ACTION_DOWN` (when not continuing the word and not suppressed by `MS-13`), if the text before the cursor ends with a letter or digit, `" "` is inserted before that letter so both tapped words (e.g. `"so"`) and swiped words receive a leading space. Pure taps never arm `pendingLeadingSpaceBeforeNextWord`.
  - **Punctuation `eatBefore` & Post-Autospace Double-Space Period:**
    - If the very next `ACTION_DOWN` lands on a hugging punctuation key (`.,!?;:)]}»…`, `MultiSwipeGeometry.NO_SPACE_BEFORE`) while `autospaceInserted == true` and the character before the cursor is `' '`, MultiSwipe immediately deletes that trailing space (`ic.deleteSurroundingText(1, 0)`) before the punctuation is handled. If the user first taps a non-character modifier/layout-switch key (such as `?123` or `Shift`, `character == '\0'` and not Space) without changing the editor text (`autospaceSnapshot` unchanged), `autospaceInserted` is preserved so tapping `:` or other punctuation on the symbols layout still retracts the trailing space.
    - If the user taps Spacebar once after an autospace (`autospaceInserted == true`), the auto-inserted space is **kept** so multiple spaces can still be typed normally (`"word  "`, `"word   "`). However, if the user performs a quick second Spacebar tap within `500ms` (`DOUBLE_SPACE_PERIOD_TIMEOUT_MS`) and Gboard's own `enable_double_space_period` preference is enabled (default `true`), MultiSwipe replaces the two trailing spaces before the second tap (`"  "`) with `"."` on `ACTION_DOWN` right before Gboard inserts its space, yielding `". "` just like native double-space-period. When `enable_double_space_period` is disabled, double-tapping Spacebar inserts spaces normally without inserting a period.
    - `autospaceInserted` is cleared on any subsequent character/Space `ACTION_DOWN` or if the editor text changed away from `autospaceSnapshot`.
- **When `autoSpaceEnabled == false`:** No autospace timer is started (for either MultiSwipe or single-finger stock swipes), no trailing space is appended, and `autospaceInserted` remains `false`.
- **Single-Word Commit Undo Tracking (`MS-21`):** When `finalizeAutospace` commits a MultiSwipe word, MultiSwipe retains an undo record for that single committed word (exact post-commit snapshot, word length + any trailing autospace) until the next non-Backspace key press, cursor/text change, or `MS-21` Backspace consumption.
- **Implementation:** `MultiSwipeWordSession.finalizeAutospace`, `MultiSwipeWordSession.finalizeStockSwipeAutospace`, `GboardMultiSwipeRuntime.finalizeComposingAndAutospace`, `MultiSwipeGeometry.NO_SPACE_BEFORE`.

#### `MS-13` — Compound Word Attachment & Lowercasing Strictly After Backspace
- **Rule:** If and only if the user deletes characters via **Backspace** back onto an existing letter/digit stem and immediately MultiSwipes a second word, MultiSwipe attaches directly to the remaining stem with **no** leading space and **lowercases** the attached word's initial letter.
- **Concrete Example:**
  1. User swipes `"Seiten"` → autospace commits `"Seiten "`.
  2. User taps Backspace → `"Seiten"` (cursor text shrank relative to `"Seiten "` and ends in a letter/digit).
  3. User MultiSwipes `"Leiste"` → leading space is suppressed and `"Leiste"` is lowercased to `"leiste"`, producing **`"Seitenleiste"`** instead of `"Seiten Leiste"` or `"SeitenLeiste"`.
- **Strict Guards (No False Triggers):**
  - Deletion is only armed when a non-Space, non-punctuation keyboard key press (`!downOnLetter && !isSpaceKey && !isPunctuationKey`, i.e. Backspace) preceded the text shrinkage (`didDeleteTrailingChars`).
  - **Sliding Window (`>= 256` chars):** `didDeleteTrailingChars` detects both length reduction (`cur.length() < prev.length()`) and shifted 256-char sliding-window deletions (`cur.regionMatches(d, prev, 0, len - d)` for `1 <= d <= 32`).
  - **Cursor Repositioning & Spacebar Swipe:** Moving the cursor in the text field (no Backspace key press) or swiping on the Spacebar (`isSpaceKey == true`) does **not** arm compound attachment — when `autoSpaceEnabled == true`, a normal leading space is inserted (e.g., `"Hallo schöne Welt"`), and when `autoSpaceEnabled == false`, initial casing is **not** forced lowercase.
  - Pressing Space or punctuation clears any armed Backspace attachment immediately; if text advances past the post-Backspace baseline via unrelated stock typing without buffer promotion, armed attachment also resets.
  - When armed (`attachingAfterBackspace`), MultiSwipe suppresses sentence-start capitalization (`capitalizeFirst = false`) and lowercases the first character (`lowercaseFirstChar`) of both `bestWord` and every entry in `alternativeWords`.
  - When `MS-21` deletes a whole MultiSwipe word, any leading space that preceded the word (e.g., `"Hallo "` before `"Welt"`) remains intact, so `MS-13` does not trigger unless the user explicitly presses Backspace again to delete that space back onto a letter/digit stem.
- **Implementation:** `MultiSwipeWordSession` (`didDeleteTrailingChars`, `isAttachingAfterDeletion`, `lowercaseFirstChar`), `GboardMultiSwipeRuntime`, `MultiSwipeGeometry.isSpaceKeyAt`.

#### `MS-14` — Sentence-Start & Post-Newline Capitalization
- **Rule:** When not attaching directly after Backspace (`!attachingAfterBackspace`), MultiSwipe capitalizes the first character of `bestWord` and all `alternativeWords` if the text before the cursor is empty, contains only whitespace, ends with `'\n'`, or ends with `'.'`, `'!'`, or `'?'` (ignoring trailing whitespace).
- **Implementation:** `MultiSwipeWordSession.shouldCapitalizeFirstLetter`, `capitalizeFirstChar`.

---

### E. Keyboard Geometry & Non-Letter Boundaries

#### `MS-15` — Dynamic Keyboard Geometry Extraction
- **Rule:** MultiSwipe derives per-key bounds and centers from the active Gboard layout rather than relying on a fixed grid:
  1. **Primary:** Gboard's internal native layout object (`Ltjz` via reflection), which works on Canvas-rendered themes without `TextView` children (requires `>= 20` keys).
  2. **Secondary:** `SoftKeyboardView` view-hierarchy traversal (`SoftKeyView` / `TextView` / `contentDescription`, requires `>= 20` keys).
  3. **Fallback:** Synthetic standard QWERTZ (`de`) or QWERTY layout.
- **Details:** Supported character labels are `a–z`, `ä`, `ö`, `ü`, and `NO_SPACE_BEFORE` punctuation; all other visible keys (`w > 10 && h > 10`, e.g., Shift, Backspace, Space, Enter) are stored with `character = '\0'` as explicit non-letter keys. Geometry refreshes automatically whenever keyboard dimensions or native layout instance change.
- **Implementation:** `GboardMultiSwipeRuntime` (`ensureGeometryUpdated`, `extractKeysFromNativeLayout`, `collectLetterKeysFromViewHierarchy`).

#### `MS-16` — Strict Letter-Area Hit Testing & Dedicated Umlaut Inclusion
- **Rule:** A touch position `(px, py)` counts as being on the letter area (`isOnLetterArea`) if and only if:
  1. It lies inside any `a–z` letter key's bounding box OR any dedicated letter key's bounding box (including **`ä`, `ö`, `ü`**), OR
  2. It does **not** lie inside any explicit non-letter key's bounding box (e.g., Shift, Backspace, Space, Comma, Enter) **and** falls within a `0.12 * keyWidthPx` / `0.12 * keyHeightPx` inter-key gap margin around the letter rows (`minLetterY..maxLetterY`) and nearest letter key.
- **Rationale:** Dedicated umlaut keys (`ä`, `ö`, `ü`) must behave as normal letters for word continuation (`MS-10`), buffer promotion (`MS-08`, `MS-09`), and tap/swipe start (`MS-04`, `MS-06`), while Shift, Backspace, Space, punctuation, and number/toolbar rows must never count as letter touches.
- **Implementation:** `MultiSwipeGeometry` (`setKeys`, `isOnLetterArea`, `nearestLetterKey`, `isSpaceKeyAt`, `isPunctuationAt`).

---

### F. Dictionaries, Thicc Top Bar, Settings & Telemetry

#### `MS-17` — Native Gboard Dictionary Extraction, Accent Folding & Session Learning
- **Rule:** MultiSwipe reads Gboard's installed language model directly on-device and augments it with user vocabulary:
  - **Language Detection:** Normalized base tag from `Delight5Facilitator` (fallback `Locale.getDefault()`, fallback `"de"`).
  - **Source Resolution:** Primary via `Delight5Facilitator` main LM descriptors (type `1`); fallback via recursive disk scan of `superpacks`/`delight`/`delight5` excluding userhistory/contacts/shortcuts/blocklist. Refreshes when dedicated-umlaut layout state or underlying file timestamp/size changes.
  - **Token Filtering & Folding:** Excludes internal tokens (`<epsilon>`, `<UNK>`, `_`-prefixed/suffixed, digits, control bytes) and folds accented characters transparently according to `hasDedicatedUmlauts`.
  - **User & Session Augmentation:** Merges Android `UserDictionary.Words` entries and adds words committed via MultiSwipe after `2` commits (`KineticaConstants.PERSONAL_MERGE_MIN_COUNT`) alongside committed `(prevWord, committedWord)` bigrams.
- **Implementation:** `GboardMultiSwipeDictionaryManager`, `GboardLmReader`.

#### `MS-18` — Top Bar Synchronization & Native Quick-Access Suppression
- **Rule:**
  - **Thicc Top Bar enabled:** Whenever MultiSwipe decodes a live preview (including re-decoding after `MS-21` `last_input` Backspace) or finalizes a committed word, it sends `(word, alternativeWords)` to `GboardThiccTopBarRuntime.recordMultiSwipeDecodedWord(...)` so Thicc Top Bar's vertical alternative wheels and sentence branch history remain synchronized with MultiSwipe words.
  - **Thicc Top Bar disabled (Native Top Bar):** When MultiSwipe takeover activates (`multiswipeTakeoverActive == true` or `ACTION_UP` MultiSwipe decode), MultiSwipe clears stale 1-finger candidate slots in `LatinFixedCountCandidatesHolderView` and suppresses Gboard's Quick-Access/toolbar sibling views (`setNativeTopBarBlankForMultiSwipe(true)`) so the native top bar stays blank instead of showing a frozen 1-finger Gboard guess or flashing Quick-Access buttons between `ACTION_UP` and Gboard's asynchronous candidate update. During active MultiSwipe movement (`ACTION_MOVE`), throttled (`>= 100ms`, single-flight) background Kinetica snapshot decodes may display an intermediate centered guess in the native top bar overlay (`updateNativeTopBarLiveGuess`) without mutating the editor text. Blanking and any live-guess overlay end as soon as Gboard's `onCandidatesUpdated` populates non-empty word candidates, on `ACTION_CANCEL` / stock fallback / non-letter key down (Space, Enter, Backspace), or after a `600ms` safety timeout.
- **Implementation:** `GboardMultiSwipeRuntime`, `MultiSwipeWordSession`, `GboardThiccTopBarRuntime` (`recordMultiSwipeDecodedWord`, `setNativeTopBarBlankForMultiSwipe`, `updateNativeTopBarLiveGuess`).

#### `MS-19` — Settings Persistence, UI Visibility, Clamping & Trace Diagnostics
- **Rule:** All MultiSwipe settings persist in Gboard shared preferences with strict range sanitization, defaults reset, conditional timeout UI visibility, and opt-in JSONL telemetry:
  - **Defaults & Bounds:** `enabled=true`, `autoSpace=true`, `autoSpaceBeforeWord=true`, `autoSpaceDelayMs=300` `[0..2000]`, `singleTouchCombineDelayMs=150` `[0..500]`, `tapToMultiSwipeDelayMs=750` `[0..2000]`, `spacebarTimerIndicator=false`, `wordReadyVibration=false`, `backspaceMode="whole_word"` (`{"whole_word", "last_input"}`), `beamWidth=256` `[32..1024]`, `freqWeightFloor=0.25` `[0.05..1.0]`, `geoExponent=3.75` `[1.0..8.0]`, `beamFreqWeight=0.50` `[0.0..2.0]`, `traceLogging=false`.
  - **UI Visibility:** When `autoSpaceEnabled == false`, the `Auto-space before next word (Gboard style)`, `Auto-space delay`, `Single-finger combine timeout`, `Tap-to-MultiSwipe timeout`, `Spacebar timer indicator`, and `Vibrate when ready for new word` rows are hidden/disabled in the MultiSwipe settings screen because auto-space modes and timers do not apply when Auto-Space is off. The `Backspace behavior` row remains visible regardless of `autoSpaceEnabled`.
  - **Trace Logging:** Disabled by default. When enabled, emits `geom` (once per unique geometry hash), `word`, `pass` (skipped stock gestures), and `commit` JSONL records to logcat (`GboardMultiSwipeTrace`) and rolling `multiswipe_trace.jsonl` files, exportable by trailing minute window (`1..1440`, or `0` for all) while retaining referenced `geom` headers.
- **Implementation:** `GboardMultiSwipeSettings`, `GboardMultiSwipeSettingsFeature`, `GboardMultiSwipeTraceLogger`.

#### `MS-20` — Optional Spacebar Timer Indicator, Elapse Flash & Word-Ready Vibration
- **Rule:** When `autoSpaceEnabled == true`, MultiSwipe tracks the active **word-finalizing timer** that currently delays finalization/Auto-Space and prevents the next input from starting a new word (`MS-12` MultiSwipe word autospace timer `autospaceTimerPending`, or the stock-swipe autospace/combine timer `stockSwipeAutospacePending` when `!autoSpaceBeforeWord || singleTouchCombineDelayMs > 0`). Pure-tap strokes without any `SWIPE` token (`MS-08` passive tap prefix / tap-only `MS-09` combine buffer) remain silent in the background and do **not** trigger the spacebar countdown or word-ready vibration, because subsequent taps continue typing in the same word without Auto-Space:
  1. **Center-Out Fill (`pref_multiswipe_spacebar_timer_indicator == true`):** Renders an animated translucent pill-shaped shading directly on the spacebar `SoftKeyView` (or its pill background child `View`) via `ViewOverlay`, using `Drawable.getOutline` to match the spacebar's exact pill geometry and corner radius, expanding smoothly from the center toward both edges over the active timer's duration. All word-finalizing timer types share the same visual style.
  2. **Timer-Elapse Flash & Word-Ready Vibration:** Whenever an active word-finalizing timer completes without being cancelled (meaning the word is finalized and the next input will now start a new word), the spacebar indicator briefly flashes and fades out smoothly (if `pref_multiswipe_spacebar_timer_indicator == true`), and MultiSwipe emits a subtle haptic tick/vibration (if `pref_multiswipe_word_ready_vibration == true`).
  3. **Cancellation & Reset:** Any touch down (`ACTION_DOWN`), buffer clear, or gesture reset cancels pending timers, suppresses vibration, and smoothly fades out an active fill without flashing.
- **Implementation:** `SpacebarTimerOverlay`, `GboardMultiSwipeRuntime` (`resolveSpacebarTargetView`, `triggerWordReadyVibration`), `MultiSwipeWordSession` (`onScheduleAutospaceTimer`, `onCancelAutospaceTimer`, `onScheduleStockSwipeAutospaceTimer`, `onCancelStockSwipeAutospaceTimer`, `onBufferTimerUpdated`, `onWordReadyTimerElapsed`).

#### `MS-21` — Configurable MultiSwipe Backspace Behavior (`whole_word` vs. `last_input`)
- **Rule:** A short tap on the Backspace key immediately following a MultiSwipe word (either while `multiswipeWordActive == true`, or immediately after `finalizeAutospace` while no other key press or cursor/text change has occurred) deletes according to `pref_multiswipe_backspace_mode`:
  1. **Scope & Native Preservation:** Applies **only** to short single taps on Backspace (no drag/swipe-delete and no long-press repeat; holding or swiping from Backspace passes through 100% natively to stock Gboard). Does not apply to stock single-finger swipes (`MS-02`), nor after Space/Enter/punctuation has been pressed.
  2. **`whole_word` Mode (Default):** Both while pending (`multiswipeWordActive == true`) and after commit, a single Backspace tap deletes the entire MultiSwipe word (including any trailing auto-space appended in `autoSpaceBeforeWord == false` mode). Any leading space that preceded the word (e.g., `"Hallo "` before `"Welt"`) is preserved. A subsequent Backspace tap deletes a single character normally.
  3. **`last_input` Mode:**
     - **While pending (`multiswipeWordActive == true`):** Removes the single token in `wordTokens` with the latest `startTimeMs` (tie-breaking on the most recently added token):
       - If at least one `SWIPE` token remains: re-decodes `wordTokens`, updates the live composing preview, notifies Thicc Top Bar (`MS-18`), keeps `multiswipeWordActive = true`, and restarts the autospace timer if `autoSpaceEnabled == true`.
       - If only `TAP` tokens remain (no `SWIPE` token left, e.g., after `MS-08` `"fn"` + `"ktion"`): restores the originally typed tap prefix (`"fn"`) as normal text, returns the tap tokens to `PassiveTapBuffer`, and ends `multiswipeWordActive`.
       - If no tokens remain: deletes the entire word just like `whole_word`.
     - **After commit (`multiswipeWordActive == false`):** Individual stroke tokens are no longer active; a single Backspace tap deletes the entire last committed MultiSwipe word just like `whole_word`.
- **Implementation:** `MultiSwipeWordSession` (`onBackspaceTap`, `LastCommittedWordUndo`), `MultiSwipeGeometry.isBackspaceKeyAt`, `GboardMultiSwipeRuntime`, `GboardMultiSwipeSettings`.

---

## 4. Rule-to-Test Traceability Matrix

| Rule ID | Implementation | Automated Test Coverage | Status |
| :--- | :--- | :--- | :--- |
| `MS-01` | `GboardMultiSwipeRuntime`, `MultiSwipeWordSession`, `GboardMultiSwipeSettings` | `MultiSwipeIntegrationTest#disabledFeatureBypassesAllHooksAndWordSession`, `GboardMultiSwipeSettingsTest#defaultsTraceLoggingOffAndResetsAllSettingsToDefaults` | **Covered** |
| `MS-02` | `MultiSwipeWordSession`, `GboardMultiSwipeRuntime` | `MultiSwipeIntegrationTest#singleFingerSwipeAndSpaceStayOnStockGboard`, `rolloverMultiTouchWithoutSwipeStaysOnStockGboard` | **Covered** |
| `MS-03` | `MultiSwipeWordSession.onActionUp`, `GboardMultiSwipeRuntime` | `MultiSwipeIntegrationTest#singleTouchCombineLeavesStockTextIntactWhenZeroCandidates`, `autoSpaceOffRetainsBestMatchAndTokensWhenAdditionalSwipeYieldsZeroCandidates`, `pureTapsWithoutSwipeStayOnStockEvenWithAutoSpaceOff` | **Covered** |
| `MS-04` | `GboardMultiSwipeRuntime.PointerTrack` | `MultiSwipeDecoderTest#runtimePointerTrackRejectsJitterAndDropsRestingFinger`, `pointerTrackExtractsStickyKeyContactsAndDwellRuns` | **Covered** |
| `MS-05` | `GboardMultiSwipeRuntime.registerPointerDown`, `MultiSwipeGeometry.midlinePx` | `MultiSwipeDecoderTest#dynamicMidlineIgnoresAsymmetricUmlautColumn` | **Covered** |
| `MS-06` | `GboardMultiSwipeBytecodePatch.kt`, `MultiSwipeWordSession` hook predicates | `GboardMultiSwipeSmaliCompilationTest` (all 7 Smali hook tests), `MultiSwipeIntegrationTest#motionHandlerHooksProtectMultiTouchAndKeepFreshActionDown` | **Covered** |
| `MS-07` | `GboardMultiSwipeRuntime` (trail cleanup & accenting) | `GboardMultiSwipeSettingsTest#selectsDistinctMaterialDynamicAccentForMultiSwipeTrail` | **Partial** (accent color covered; Android `SparseArray`/`View` trail rendering runtime-only) |
| `MS-08` | `MultiSwipeWordSession.PassiveTapBuffer`, `GboardMultiSwipeRuntime.PassiveTapBuffer` | `MultiSwipeDecoderTest#passiveTapBufferPromotesTapPrefixAcrossLiftsAndRollsBackSlidingWindow`, `MultiSwipeIntegrationTest#autoSpaceOffCombinesPassiveTapsAndSwipeAcrossLongPauses` | **Covered** |
| `MS-09` | `MultiSwipeWordSession.SingleTouchCombineBuffer`, `GboardMultiSwipeRuntime.SingleTouchCombineBuffer` | `MultiSwipeDecoderTest#singleTouchCombineBufferPromotesSequentialSwipesAndSwipeTapWithinTimeout`, `MultiSwipeIntegrationTest#autoSpaceOffCombinesSequentialStrokesAcrossLongPausesUntilSpaceOrNavigation` | **Covered** |
| `MS-10` | `MultiSwipeWordSession.onActionDown`, `decodeAndPreview` | `MultiSwipeIntegrationTest#wordContinuationRespectsDelayWhenAutoSpaceOnAndHasNoTimeoutWhenOff` | **Covered** |
| `MS-11` | `MultiSwipeWordSession.shouldPrependLeadingSpace` | `GboardMultiSwipeSettingsTest#detectsWhenLeadingSpaceIsNeededBeforeMultiSwipeWord`, `MultiSwipeIntegrationTest#prependsLeadingSpaceAfterStockWordWhenAutoSpaceOn` | **Covered** |
| `MS-12` | `MultiSwipeWordSession.finalizeAutospace`, `eatBefore` | `GboardMultiSwipeSettingsTest#sanitizesAndPersistsAutoSpaceAndDelayPreferences`, `MultiSwipeIntegrationTest#autospaceCommitsSpaceAndRetractsBeforeHuggingPunctuation` | **Covered** |
| `MS-13` | `MultiSwipeWordSession` (`didDeleteTrailingChars`, `isAttachingAfterDeletion`) | `MultiSwipeDecoderTest#suppressesLeadingSpaceAndLowercasesWhenAttachingAfterBackspace`, `MultiSwipeIntegrationTest#backspaceCompoundAttachmentWorksInShortAndLongTextsAndIgnoresCursorMoves` | **Covered** |
| `MS-14` | `MultiSwipeWordSession.shouldCapitalizeFirstLetter`, `capitalizeFirstChar` | `MultiSwipeIntegrationTest#capitalizesSentenceStartsAndNewlinesUnlessAttachingAfterBackspace` | **Covered** |
| `MS-15` | `GboardMultiSwipeRuntime` (`extractKeysFromNativeLayoutArrays`, etc.) | `MultiSwipeDecoderTest#extractsNativeLayoutKeysWithoutTextViewsIncludingUmlauts` | **Covered** |
| `MS-16` | `MultiSwipeGeometry` (`setKeys`, `isOnLetterArea`, `isSpaceKeyAt`) | `MultiSwipeDecoderTest#isOnLetterAreaRejectsNonLetterKeysAndAcceptsLetterKeysAndUmlauts` | **Covered** |
| `MS-17` | `GboardMultiSwipeDictionaryManager`, `GboardLmReader` | `GboardLmReaderTest#parsesUnigramsBigramsAndFiltersInternalTokens`, `capsBigramsByStrengthAndSupportsNestedMarisaTailTrie`, `normalizesLanguageTagsConsistently`, `filtersInternalVocabularyTokensAsExpected` | **Partial** (LM parsing/filtering covered; Android `UserDictionary` ContentResolver query runtime-only) |
| `MS-18` | `MultiSwipeWordSession` -> `LifecycleCallbacks.onWordDecoded` / `onWordCommitted` / `onNativeTopBarBlankChanged`, `GboardThiccTopBarRuntime.setNativeTopBarBlankForMultiSwipe` | `MultiSwipeIntegrationTest#notifiesThiccTopBarListenerOnPreviewAndCommit`, `nativeTopBarBlankSuppressesQuickAccessAcrossActionUpAndClearsOnCancelOrSpace` | **Covered** (Android `ViewTreeObserver` guard runtime-only) |
| `MS-19` | `GboardMultiSwipeSettings`, `GboardMultiSwipeSettingsFeature`, `GboardMultiSwipeTraceLogger` | `GboardMultiSwipeSettingsTest` (all tests, incl. hiding timeout rows when Auto-Space is off), `MultiSwipeDecoderTest#traceLoggerEmitsGeometryOnceAndSerializesRawTouchPointsAndCandidates` | **Covered** |
| `MS-20` | `SpacebarTimerOverlay`, `MultiSwipeGeometry.getSpaceKey`, `MultiSwipeWordSession`, `GboardMultiSwipeRuntime` | `GboardMultiSwipeSettingsTest`, `MultiSwipeIntegrationTest#spacebarTimerIndicatorNotifiesBufferAndAutospaceTimers`, `wordReadyTimerAndSpacebarIndicatorAlignAcrossAllTimers` | **Partial** (timer callbacks & settings covered; Android `ViewOverlay`/`ValueAnimator` rendering & `Vibrator` runtime-only) |
| `MS-21` | `MultiSwipeWordSession` (`onBackspaceTap`, `LastCommittedWordUndo`), `MultiSwipeGeometry.isBackspaceKeyAt`, `GboardMultiSwipeRuntime`, `GboardMultiSwipeSettings` | `GboardMultiSwipeSettingsTest#persistsAndResetsBackspaceModePreference`, `MultiSwipeIntegrationTest#backspaceWholeWordModeDeletesPendingAndCommittedWordsPreservingLeadingSpace`, `backspaceLastInputModeRemovesLatestStartTimeTokenAndRestoresTapPrefix`, `backspaceAfterSpaceOrSecondTapDeletesSingleChar` | **Covered** |

---

## 5. Notes & Residual Runtime-Only Boundaries

All pure-JVM integration rules (`MS-01`–`MS-20`) are aligned and covered by automated tests. Only three Android-framework interactions remain runtime-verified on device rather than pure JVM:
- Android `GestureOverlayView` / `TrailManager` (`SparseArray`) visual drawing (`MS-07` index sanitization).
- Android `UserDictionary.Words` `ContentResolver` IPC query (`MS-17`).
- Android `ViewOverlay` / `ValueAnimator` spacebar timer indicator drawing (`MS-20`).
