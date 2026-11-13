# Gboard Nintype 2-Finger Swipe: Technical Analysis & Integration Roadmap

This document summarizes the technical review of the 2-finger swipe algorithm (ported from Kinetica to Gboard). It documents the exact root causes for gesture recognition issues during simultaneous thumb input and the lower recognition rate compared to the original Kinetica project, followed by a prioritized TODO action plan.

---

## 1. Problem Statement & Symptoms

1. **Failure during simultaneous swipe initiation:**
   When two thumbs touch the screen simultaneously or in rapid succession (< 40 ms) and begin a gesture, Gboard's gesture processing frequently aborts completely. Either no trails are drawn, only one thumb is registered, or unwanted individual key presses (ghost taps) are emitted instead.
2. **Reduced word recognition rate compared to Kinetica:**
   Even when both swipe trails are drawn visually, recognition accuracy was noticeably worse than in Kinetica. Words were missed or recognized incorrectly, accented/umlaut words failed, and thumb timing order caused early beam search pruning.

---

## 2. Root Cause Analysis

Decompilation and Smali analysis of Gboard internals (`AbstractGestureMotionEventHandler`, `BasicMotionEventHandler`, `MotionEventHandlerManager` / `pmm`, `pml`, `tjz`) and Kinetica source code identified key structural issues:

### A. Dispatcher Ownership Conflict (`BasicMotionEventHandler` locks `pmm.k`)

In Gboard, touch events are routed sequentially through `MotionEventHandlerManager` (`pmm`) to active handlers (`pmm.g:[Lqis;`):
- Handler `[0]`: `LatinMotionEventHandler` (extends `BasicMotionEventHandler`) – handles single taps, multi-touch chording, and key presses.
- Handler `[1]`: `LatinGestureMotionEventHandler` (extends `AbstractGestureMotionEventHandler`) – handles swipes and trails.

#### What happens during simultaneous touchdown:
1. When thumb 1 touches the screen (`ACTION_DOWN`), `pmm.a` resets exclusive lock `pmm.k = null`. Both handlers receive the event.
2. When thumb 2 touches down shortly after (`ACTION_POINTER_DOWN`), the action code is `5` (not `0`), so `pmm.k` is **not** reset.
3. Handler `[0]` (`BasicMotionEventHandler`) receives `ACTION_POINTER_DOWN` **first**.
4. In `BasicMotionEventHandler.t(MotionEvent)`, internal chording logic (`Lqhw;`) detects multiple fingers:
   ```smali
   # BasicMotionEventHandler.t(MotionEvent)
   iget-object v14, v1, Lqhw;->q:Lcom/google/android/libraries/inputmethod/widgets/ChordTrackOverlayView;
   if-eqz v14, :cond_chord
   invoke-virtual {v14, p1}, Lcom/google/android/libraries/inputmethod/widgets/ChordTrackOverlayView;->c(Landroid/view/MotionEvent;)V
   :cond_chord
   iget-object v14, v1, Lqhw;->c:Lqit;
   invoke-interface {v14}, Lqit;->m()V   # <-- Requests exclusive lock!
   ```
5. `Lqit;->m()` is implemented by bridge `Lpml;->m()`:
   ```smali
   # Lpml.m()
   iget-object v1, v0, Lpmm;->k:Lqis;
   if-nez v1, :cond_locked
   iput-object v4, v0, Lpmm;->k:Lqis;          # pmm.k = BasicMotionEventHandler
   # Iterate over all other handlers:
   invoke-interface {v2}, Lqis;->gu()Z        # Check gu() on AbstractGestureMotionEventHandler
   move-result v3
   if-nez v3, :cond_skip_cancel
   invoke-interface {v2}, Lqis;->l()V         # <-- CALLS GESTURE CANCEL/RESET!
   :cond_skip_cancel
   ```
6. **Consequence:**
   - `pmm.k` is claimed exclusively by `BasicMotionEventHandler`.
   - Because `AbstractGestureMotionEventHandler` defaults to `gu() == false`, `pml.m()` immediately invokes `AbstractGestureMotionEventHandler.l()` – resetting and canceling gesture handling.
   - For subsequent `ACTION_MOVE` events, `pmm.a` skips `AbstractGestureMotionEventHandler`. Swiping gestures never reach the gesture handler.

---

### B. The `this.u` (letterStarted) Override in `AbstractGestureMotionEventHandler.g()`

Even if an event passed through, an override existed in Gboard bytecode:

```smali
# AbstractGestureMotionEventHandler.g(MotionEvent) (:pswitch_2 ACTION_POINTER_DOWN)
invoke-virtual {p1}, Landroid/view/MotionEvent;->getActionIndex()I
move-result v2
invoke-direct {p0, p1, v2}, LabstractGestureMotionEventHandler;->v(Landroid/view/MotionEvent;I)LSoftKeyView;
move-result-object v2
invoke-direct {p0, v2}, LabstractGestureMotionEventHandler;->p(LSoftKeyView;)Z
move-result v2
iput-boolean v2, p0, LabstractGestureMotionEventHandler;->u:Z
```

- `v(event, actionIndex)` resolves the `SoftKeyView` under pointer 1.
- `p(softKeyView)` checks whether it is a printable primary key (`f.c() == 1`).
- If thumb 2 touches down between keys, on a margin, or on a non-printable key, `p()` returned `false`.
- This reset `this.u = false`, causing `ACTION_MOVE` to immediately abort and ignore both pointers.

---

### C. Geometry Resolution via Native `Ltjz;`

Early implementations attempted to find key coordinates by crawling the Android View tree for `TextView` children of `SoftKeyView`:
1. In modern Gboard themes, key labels are rendered directly on the Canvas or via Drawables without `TextView` children.
2. When fewer than 20 keys were discovered, it fell back to a static grid estimate (`rowH = height / 4`), which did not account for the suggestion strip, header padding, or non-uniform keyboard layouts.
3. **Native Solution:** `AbstractGestureMotionEventHandler` holds the exact subpixel geometry structure `Ltjz;`:
   - `f: [F]` = exact X key centers.
   - `g: [F]` = exact Y key centers.
   - `d: [I]`, `e: [I]` = exact key widths and heights.
   - `a: SparseArray<SoftKeyView>` = all key views with their labels (`SoftKeyDef.i`, `SoftKeyDef.m`).

---

### D. Decoder Architecture Alignment with Kinetica

In Kinetica (`com.kinetica.input`), word recognition uses a multi-stage architecture:

1. **`MergeAlternatives` (Timing Ambiguity):**
   When two thumbs swipe or tap, contacts often occur within small time windows (< 120 ms). `MergeAlternatives.sequences()` generates candidate interleavings so timing jitter does not break word prefix matching.
2. **`midlinePx` Calculation & Slot Assignment:**
   Dynamically computed partition between left and right thumb clusters based on actual keyboard geometry.
3. **Dwell and Direction Change Detection:**
   Accurate anchor point detection for path segmentation.

---

## 3. Implementation Plan & Status

### Phase 1: Smali Bytecode Hooks (`GboardNintypeBytecodePatch.kt`)
- [x] **1.1: `AbstractGestureMotionEventHandler.gu()Z` hook:** Prevents `pmm.a` from skipping gesture handling and `pml.m()` from resetting gesture state.
- [x] **1.2: `BasicMotionEventHandler.t(MotionEvent)` hook:** Suppresses chording takeover while multi-touch swipe is active.
- [x] **1.3: `this.u` (letterStarted) protection in `g(MotionEvent)`:** Preserves gesture state during pointer down events.
- [x] **1.4: Preemption of `pmm.k` lock:** Ensures gesture handler retains touch dispatch when Nintype is active.

### Phase 2: Geometry Engine (`GboardNintypeRuntime.java`)
- [x] **2.1: Native geometry extraction from `Ltjz;`:** Subpixel-accurate key bounds directly from Gboard runtime state.
- [x] **2.2: Dynamic `midlinePx` calculation:** Automatic partition based on key centers.

### Phase 3: Decoder Pipeline (`KineticaDtwDecoder.java` & `ThumbBeam.java`)
- [x] **3.1: Porting `MergeAlternatives`:** Interleaved sequence generation for near-simultaneous touch events.
- [x] **3.2: Removal of `i % 2` split hack:** Proper slot-based tracking (LEFT vs RIGHT).
- [x] **3.3: Dwell and corner detection in `PointerTrack`:** Anchor generation on sharp curvature or speed drop.
- [x] **3.4: Multi-pass search:** Fallback pass for fast gestures.

### Phase 4: Integration, Commit & Stability
- [x] **4.1: Ghost-tap suppression:** Proper state reset on gesture start.
- [x] **4.2: Autospace & composing synchronization:** Live composing updates and configurable autospace delay.
