package dev.jason.gboardpatches.extension.nintype;

import android.content.Context;
import android.content.ContextWrapper;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;
import dev.jason.gboardpatches.extension.matrixsuggestions.GboardMatrixSuggestionsRuntime;
import dev.jason.gboardpatches.extension.matrixsuggestions.MatrixGboardReflector;
import dev.jason.gboardpatches.extension.nintype.GboardNintypeDictionaryManager.LanguagePack;
import dev.jason.gboardpatches.extension.nintype.NintypeGeometry.KeySpec;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.TouchPoint;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.WordCandidate;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Runtime hook coordinator for Nintype multi-finger swipe & tap+swipe gesture typing.
 *
 * Hooked into Gboard's AbstractGestureMotionEventHandler:
 * - Single-finger taps and single-finger swipes pass through 100% untouched to Gboard's stock pipeline.
 * - Multi-finger gestures (2-finger swipe, tap+swipe, swipe+tap) and short neighbor swipes (e.g. a->s + l)
 *   are recognized with full trail rendering and decoded using the Nintype beam search + dual-thumb engine.
 */
public final class GboardNintypeRuntime {

    private static final String TAG = "GboardNintype";
    private static final float MULTI_TOUCH_SWIPE_THRESHOLD_PX = 22f;
    private static final float SINGLE_FINGER_NEIGHBOR_SWIPE_THRESHOLD_PX = 30f;
    private static final long MAX_CHORD_GAP_MS = 420L;
    private static final long MAX_SEQUENTIAL_CHORD_GAP_MS = 180L;

    private static final class PointerTrack {
        final int pointerId;
        final float startX;
        final float startY;
        final long startTimeMs;
        final char startKeyChar;
        final boolean startedOnLetter;
        final int slot; // 0 = LEFT, 1 = RIGHT
        final List<TouchPoint> points = new ArrayList<>(48);
        float lastX;
        float lastY;
        long lastTimeMs;
        float pathLengthPx = 0f;
        char currentKeyChar;
        boolean crossedKeyBoundary = false;
        boolean finished = false;

        PointerTrack(
                int pointerId,
                float x,
                float y,
                long timeMs,
                char startKeyChar,
                boolean startedOnLetter,
                int slot
        ) {
            this.pointerId = pointerId;
            this.startX = x;
            this.startY = y;
            this.lastX = x;
            this.lastY = y;
            this.startTimeMs = timeMs;
            this.lastTimeMs = timeMs;
            this.startKeyChar = startKeyChar;
            this.currentKeyChar = startKeyChar;
            this.startedOnLetter = startedOnLetter;
            this.slot = slot;
            this.points.add(new TouchPoint(x, y, timeMs, pointerId));
        }

        void addPoint(float x, float y, long timeMs, NintypeGeometry geometry) {
            float dx = x - lastX;
            float dy = y - lastY;
            float dist = (float) Math.hypot(dx, dy);
            if (dist < 1.5f && !points.isEmpty()) {
                return;
            }
            pathLengthPx += dist;
            lastX = x;
            lastY = y;
            lastTimeMs = Math.max(lastTimeMs, timeMs);
            points.add(new TouchPoint(x, y, this.lastTimeMs, pointerId));

            if (geometry != null && startedOnLetter) {
                KeySpec nearest = geometry.nearestLetterKey(x, y);
                if (nearest != null && nearest.character != '\0') {
                    currentKeyChar = nearest.character;
                    if (startKeyChar != '\0' && currentKeyChar != startKeyChar) {
                        float netDisp = (float) Math.hypot(x - startX, y - startY);
                        float minBoundaryDisp = (geometry.isValid())
                                ? Math.max(26f, geometry.keyWidthPx * 0.32f)
                                : 26f;
                        if (netDisp >= minBoundaryDisp) {
                            crossedKeyBoundary = true;
                        }
                    }
                }
            }
        }

        boolean isSwipe(float swipeThresholdPx) {
            if (points.size() < 2) return false;
            if (pathLengthPx >= swipeThresholdPx) return true;
            return startedOnLetter && crossedKeyBoundary && pathLengthPx >= Math.max(20f, swipeThresholdPx * 0.75f);
        }

        NintypeInputToken toToken(float swipeThresholdPx) {
            if (isSwipe(swipeThresholdPx)) {
                return NintypeInputToken.swipe(
                        pointerId,
                        slot,
                        new ArrayList<>(points),
                        pathLengthPx,
                        startTimeMs,
                        lastTimeMs
                );
            }
            if (startedOnLetter && startKeyChar != '\0') {
                return NintypeInputToken.tap(
                        pointerId,
                        slot,
                        startKeyChar,
                        startX,
                        startY,
                        startTimeMs,
                        lastTimeMs
                );
            }
            return null;
        }
    }

    private static final class HandlerSession {
        final SparseArray<PointerTrack> activePointers = new SparseArray<>(4);
        final List<NintypeInputToken> completedTokens = new ArrayList<>(4);
        final List<NintypeInputToken> wordTokens = new ArrayList<>(16);
        final NintypeGeometry geometry = new NintypeGeometry();
        final int[] pointerBySlot = new int[] {-1, -1};
        final SparseIntArray slotByPointer = new SparseIntArray(4);
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        boolean geometryReady = false;
        int lastKeyboardWidth = 0;
        int lastKeyboardHeight = 0;
        boolean sawMultiTouch = false;
        boolean nintypeTakeoverActive = false;
        boolean shortSwipeForceStarted = false;
        boolean decodedThisSession = false;
        String textSnapshotAtActionDown = null;
        NintypeInputToken pendingChordToken = null;
        String pendingChordBaselineText = null;
        float pendingChordAnchorX = 0f;
        String lastCommittedWord = null;
        long lastTouchUpTimeMs = 0L;

        // Auto-space & Live Composing (WordComposer) state
        String currentComposingWord = null;
        List<String> currentAlternativeWords = null;
        boolean autospaceInserted = false;
        boolean autospaceTimerPending = false;
        Runnable autospaceRunnable = null;

        void cancelAutospaceTimer() {
            if (autospaceTimerPending && autospaceRunnable != null) {
                mainHandler.removeCallbacks(autospaceRunnable);
                autospaceTimerPending = false;
            }
        }

        void resetGestureState() {
            activePointers.clear();
            completedTokens.clear();
            pointerBySlot[0] = -1;
            pointerBySlot[1] = -1;
            slotByPointer.clear();
            sawMultiTouch = false;
            nintypeTakeoverActive = false;
            shortSwipeForceStarted = false;
            decodedThisSession = false;
        }

        void clearPendingChord() {
            pendingChordToken = null;
            pendingChordBaselineText = null;
            pendingChordAnchorX = 0f;
        }

        void clearWordBuffer() {
            cancelAutospaceTimer();
            wordTokens.clear();
            currentComposingWord = null;
            currentAlternativeWords = null;
            lastTouchUpTimeMs = 0L;
        }
    }

    private static final Map<Object, HandlerSession> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Field contextField;
    private static volatile Field keyboardViewField;
    private static volatile Field hostBridgeField;
    private static volatile Method claimOwnershipMethod;
    private static volatile Field gestureActiveFlagField;
    private static volatile Field trailManagerField;
    private static volatile Field trailActiveBoolField;
    private static volatile Field trailSparseArrayField;
    private static volatile Field trailStartIndexArrayField;
    private static volatile Field trailFadingListField;
    private static volatile Method recyclePointsMethod;
    private static volatile Field letterStartedField;
    private static volatile Field gestureResetField;
    private static volatile Field softKeyMapField;
    private static final Set<Class<?>> probedClasses = Collections.synchronizedSet(new HashSet<>());
    private static volatile Context cachedContext = null;
    private static volatile Object cachedSampleKeyView = null;
    private static volatile boolean crashHandlerInstalled = false;

    static {
        installCrashHandler();
    }

    private static void installCrashHandler() {
        if (crashHandlerInstalled) return;
        synchronized (GboardNintypeRuntime.class) {
            if (crashHandlerInstalled) return;
            crashHandlerInstalled = true;
            try {
                final Thread.UncaughtExceptionHandler defaultHandler =
                        Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                    try {
                        writeCrashToFile(thread, throwable);
                    } catch (Throwable ignored) {
                    }
                    if (defaultHandler != null) {
                        defaultHandler.uncaughtException(thread, throwable);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "Failed to install crash handler", t);
            }
        }
    }

    private static void writeCrashToFile(Thread thread, Throwable throwable) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            pw.println("=== Gboard Nintype Crash Report ===");
            pw.println("Timestamp: " + new java.util.Date());
            pw.println("Thread: " + (thread != null ? thread.getName() : "unknown"));
            if (throwable != null) {
                pw.println("Exception: " + throwable.getClass().getName());
                pw.println("Message: " + throwable.getMessage());
                throwable.printStackTrace(pw);
            }
            pw.println("===================================");
            pw.flush();
            String report = sw.toString();

            try {
                java.io.File file = new java.io.File("/sdcard/Download/gboard_crash.txt");
                java.io.FileWriter fw = new java.io.FileWriter(file, true);
                fw.write(report);
                fw.write("\n\n");
                fw.close();
            } catch (Throwable ignored) {
            }

            if (cachedContext != null) {
                try {
                    java.io.File file = new java.io.File(cachedContext.getCacheDir(), "gboard_crash.txt");
                    java.io.FileWriter fw = new java.io.FileWriter(file, true);
                    fw.write(report);
                    fw.write("\n\n");
                    fw.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private GboardNintypeRuntime() {}

    /**
     * Injected at the start of AbstractGestureMotionEventHandler.g(MotionEvent).
     */
    public static void onGestureEventEntry(Object handler, MotionEvent event) {
        if (handler == null || event == null) return;
        try {
            Context context = extractContext(handler);
            if (context != null && !GboardNintypeSettings.readEnabled(context)) {
                return;
            }

            HandlerSession session = getOrCreateSession(handler);
            ensureGeometryUpdated(handler, session, context);

            String activeLang = GboardNintypeDictionaryManager.detectActiveLanguage(context);
            if (context != null) {
                GboardNintypeDictionaryManager.getOrScheduleLoad(
                        context, activeLang, session.geometry.hasDedicatedUmlauts());
            }

            probeHandlerFields(handler);
            sanitizeTrailIndices(handler);

            int actionMasked = event.getActionMasked();
            int actionIndex = event.getActionIndex();
            long eventTime = event.getEventTime();
            float multiSwipeThreshold = Math.max(
                    MULTI_TOUCH_SWIPE_THRESHOLD_PX, session.geometry.keyWidthPx * 0.24f);

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN: {
                    int pid = event.getPointerId(0);
                    float x = event.getX(0);
                    float y = event.getY(0);

                    // Clear any lingering trail segment for this pointer
                    finishTrailForPointer(handler, pid, false);

                    // 1. Kinetica eatBefore: retract automatically inserted space if a hugging punctuation is tapped
                    if (session.autospaceInserted) {
                        KeySpec hit = session.geometry.findKeyAt(x, y);
                        if (hit == null) {
                            hit = session.geometry.nearestAnyKey(x, y);
                        }
                        if (hit != null && session.geometry.isPunctuationChar(hit.character)) {
                            InputConnection ic = resolveActiveInputConnection(handler, context);
                            if (ic != null) {
                                CharSequence before = ic.getTextBeforeCursor(1, 0);
                                if (before != null && before.length() > 0 && before.charAt(before.length() - 1) == ' ') {
                                    ic.deleteSurroundingText(1, 0);
                                }
                            }
                        }
                        session.autospaceInserted = false;
                    }

                    // 2. WordComposer continuation:
                    // If the user touches again within 380ms of last lift (or autospace timer was ticking),
                    // cancel autospace and keep existing wordTokens to continue the word in progress.
                    long timeSinceLastUp = (session.lastTouchUpTimeMs > 0)
                            ? (eventTime - session.lastTouchUpTimeMs)
                            : Long.MAX_VALUE;
                    boolean wasAutospacing = session.autospaceTimerPending;
                    session.cancelAutospaceTimer();

                    boolean isWordContinuing = wasAutospacing
                            || (timeSinceLastUp <= 380L && !session.wordTokens.isEmpty())
                            || (session.activePointers.size() > 0);

                    session.resetGestureState();

                    if (!isWordContinuing) {
                        session.clearWordBuffer();
                        session.textSnapshotAtActionDown = readTextBeforeCursorSnapshot(handler, context);
                    }

                    registerPointerDown(session, pid, x, y, eventTime);
                    break;
                }

                case MotionEvent.ACTION_POINTER_DOWN: {
                    int pid = event.getPointerId(actionIndex);
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);
                    finishTrailForPointer(handler, pid, false);
                    PointerTrack track = registerPointerDown(session, pid, x, y, eventTime);
                    session.sawMultiTouch = true;
                    if (letterStartedField != null) {
                        try {
                            letterStartedField.setBoolean(handler, true);
                            if (gestureResetField != null) {
                                gestureResetField.setBoolean(handler, false);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    ensureSoftKeyMapPopulated(handler, session, event);
                    if (session.sawMultiTouch && hasAnySwipingPointer(session, multiSwipeThreshold)) {
                        session.nintypeTakeoverActive = true;
                        ensureTrailRecorderEnabled(handler);
                    }
                    break;
                }

                case MotionEvent.ACTION_MOVE: {
                    int pointerCount = event.getPointerCount();
                    int historySize = event.getHistorySize();

                    for (int p = 0; p < pointerCount; p++) {
                        int pid = event.getPointerId(p);
                        PointerTrack track = session.activePointers.get(pid);
                        if (track == null) {
                            track = registerPointerDown(
                                    session, pid, event.getX(p), event.getY(p), eventTime);
                        }
                        for (int h = 0; h < historySize; h++) {
                            track.addPoint(
                                    event.getHistoricalX(p, h),
                                    event.getHistoricalY(p, h),
                                    event.getHistoricalEventTime(h),
                                    session.geometry
                            );
                        }
                        track.addPoint(event.getX(p), event.getY(p), eventTime, session.geometry);
                    }

                    if (pointerCount >= 2) {
                        session.sawMultiTouch = true;
                    }

                    if (session.sawMultiTouch) {
                        ensureSoftKeyMapPopulated(handler, session, event);
                        sanitizeTrailIndices(handler);
                    }

                    if ((session.sawMultiTouch || !session.completedTokens.isEmpty()) && letterStartedField != null) {
                        try {
                            letterStartedField.setBoolean(handler, true);
                            if (gestureResetField != null) {
                                gestureResetField.setBoolean(handler, false);
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    if (session.sawMultiTouch && hasAnySwipingPointer(session, multiSwipeThreshold)) {
                        session.nintypeTakeoverActive = true;
                        ensureTrailRecorderEnabled(handler);
                    }
                    break;
                }

                case MotionEvent.ACTION_POINTER_UP: {
                    int pid = event.getPointerId(actionIndex);
                    PointerTrack track = session.activePointers.get(pid);
                    boolean isSwipe = false;
                    if (track != null) {
                        track.addPoint(
                                event.getX(actionIndex),
                                event.getY(actionIndex),
                                eventTime,
                                session.geometry
                        );
                        track.finished = true;
                        isSwipe = track.isSwipe(multiSwipeThreshold);
                        session.activePointers.remove(pid);
                        NintypeInputToken token = track.toToken(multiSwipeThreshold);
                        if (token != null) {
                            session.completedTokens.add(token);
                        }
                    }
                    releasePointerSlot(session, pid);
                    finishTrailForPointer(handler, pid, isSwipe);

                    if (session.sawMultiTouch) {
                        ensureSoftKeyMapPopulated(handler, session, event);
                    }

                    if ((session.sawMultiTouch || !session.completedTokens.isEmpty()) && letterStartedField != null) {
                        try {
                            letterStartedField.setBoolean(handler, true);
                            if (gestureResetField != null) {
                                gestureResetField.setBoolean(handler, false);
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    if (session.sawMultiTouch && hasAnySwipingPointer(session, multiSwipeThreshold)) {
                        session.nintypeTakeoverActive = true;
                        ensureTrailRecorderEnabled(handler);
                    }
                    break;
                }

                case MotionEvent.ACTION_UP: {
                    boolean anySwipeThisSession = false;
                    for (int i = 0; i < session.activePointers.size(); i++) {
                        PointerTrack track = session.activePointers.valueAt(i);
                        int pIdx = event.findPointerIndex(track.pointerId);
                        if (pIdx >= 0) {
                            track.addPoint(
                                    event.getX(pIdx),
                                    event.getY(pIdx),
                                    eventTime,
                                    session.geometry
                            );
                        }
                        track.finished = true;
                        boolean isSwipe = track.isSwipe(multiSwipeThreshold);
                        if (isSwipe) {
                            anySwipeThisSession = true;
                        }
                        NintypeInputToken token = track.toToken(multiSwipeThreshold);
                        if (token != null) {
                            session.completedTokens.add(token);
                        }
                        finishTrailForPointer(handler, track.pointerId, isSwipe);
                    }
                    session.activePointers.clear();
                    session.lastTouchUpTimeMs = eventTime;

                    boolean hasSwipe = anySwipeThisSession;
                    if (!hasSwipe) {
                        for (int i = 0; i < session.completedTokens.size(); i++) {
                            if (session.completedTokens.get(i).kind == NintypeInputToken.Kind.SWIPE) {
                                hasSwipe = true;
                                break;
                            }
                        }
                    }
                    if (!hasSwipe) {
                        for (int i = 0; i < session.wordTokens.size(); i++) {
                            if (session.wordTokens.get(i).kind == NintypeInputToken.Kind.SWIPE) {
                                hasSwipe = true;
                                break;
                            }
                        }
                    }

                    if (!hasSwipe && trailActiveBoolField != null) {
                        try {
                            Object tm = trailManagerField.get(handler);
                            if (tm != null) {
                                trailActiveBoolField.setBoolean(tm, false);
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    boolean shouldDecodeNow = hasSwipe
                            || (session.sawMultiTouch && session.completedTokens.size() >= 2)
                            || (!session.wordTokens.isEmpty() && !session.completedTokens.isEmpty());

                    if (shouldDecodeNow) {
                        session.nintypeTakeoverActive = true;
                        activateNintypeTakeoverOnFinish(handler);

                        // Accumulate all completed tokens into persistent word buffer
                        session.wordTokens.addAll(session.completedTokens);
                        session.completedTokens.clear();

                        // 1. Live Composing Text preview (exact Kinetica WordComposer)
                        decodeAndPreviewSession(handler, session, context, activeLang);

                        // 2. Schedule 300ms autospace timer from lift (exact Kinetica Autospace)
                        session.cancelAutospaceTimer();
                        session.autospaceTimerPending = true;
                        session.autospaceRunnable = () -> finalizeComposingAndAutospace(handler, session, context, activeLang);
                        session.mainHandler.postDelayed(session.autospaceRunnable, 300L);
                    } else {
                        // Single tap without any swipe yet:
                        // Save in wordTokens buffer so subsequent taps/swipes within 380ms can combine
                        session.wordTokens.addAll(session.completedTokens);
                        session.completedTokens.clear();
                    }
                    break;
                }

                case MotionEvent.ACTION_CANCEL: {
                    session.resetGestureState();
                    session.clearWordBuffer();
                    for (int i = 0; i < 10; i++) {
                        finishTrailForPointer(handler, i, false);
                    }
                    if (trailActiveBoolField != null) {
                        try {
                            Object tm = trailManagerField.get(handler);
                            if (tm != null) {
                                trailActiveBoolField.setBoolean(tm, false);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    break;
                }

                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "onGestureEventEntry error", t);
        }
    }

    /**
     * Injected right after `invoke-virtual {v0}, AbstractGestureMotionEventHandler;->t()Z; move-result v1`
     * at [0490] in AbstractGestureMotionEventHandler.g(MotionEvent).
     *
     * Returning true here when `this.v` is still false triggers Gboard's native gesture-start block
     * [0494]..[04f9], which sets `this.v = true`, calls `this.p.m()` (cancelling BasicMotionEventHandler),
     * sets `this.U.a = true` (`Lnhj.a = true`, enabling trail rendering!), attaches GestureOverlayView,
     * and posts the trail redraw runnable.
     */
    public static boolean shouldStartGesture(Object handler, boolean stockShouldStart) {
        if (handler == null) return stockShouldStart;
        try {
            HandlerSession session = SESSIONS.get(handler);
            if (session == null) return stockShouldStart;

            if (stockShouldStart) {
                ensureTrailRecorderEnabled(handler);
                session.nintypeTakeoverActive = true;
                return true;
            }

            // Gboard's [0494]..[04cd] transition block must only run when `this.v` is currently false.
            if (isGestureAlreadyActive(handler)) {
                ensureTrailRecorderEnabled(handler);
                return false;
            }

            // When multi-touch is detected (both thumbs active), start gesture as soon as any thumb moves >= multiSwipeThreshold
            float multiSwipeThreshold = Math.max(
                    MULTI_TOUCH_SWIPE_THRESHOLD_PX, session.geometry.keyWidthPx * 0.24f);
            if (session.sawMultiTouch && hasAnySwipingPointer(session, multiSwipeThreshold)) {
                session.nintypeTakeoverActive = true;
                ensureTrailRecorderEnabled(handler);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "shouldStartGesture error", t);
        }
        return stockShouldStart;
    }

    /**
     * Injected right before `invoke-interface {v4, v7}, Lqit;->n(Lohe;)V` inside
     * AbstractGestureMotionEventHandler.g(MotionEvent).
     *
     * @return true if Nintype has taken over this swipe gesture (suppressing Gboard's
     *         native gesture event dispatch), or false for normal tap typing.
     */
    public static boolean shouldInterceptGestureDispatch(Object handler) {
        if (handler == null) return false;
        HandlerSession session = SESSIONS.get(handler);
        return session != null && session.nintypeTakeoverActive;
    }

    /**
     * Injected at the start of AbstractGestureMotionEventHandler.B() (reset gesture state).
     *
     * @return true to intercept and abort B()'s body when active fingers are down
     *         (preserving softKeyMap this.d, letterStarted this.u, and trail state),
     *         false to proceed with stock cleanup when no touches are active.
     */
    public static boolean shouldInterceptGestureReset(Object handler) {
        if (handler == null) return false;
        try {
            HandlerSession session = SESSIONS.get(handler);
            if (session == null) return false;

            boolean hasFingersDown = session.activePointers.size() > 0;
            if (hasFingersDown) {
                ensureGestureHandlerOwnsDispatcher(handler);
                return true;
            }

            session.resetGestureState();
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "shouldInterceptGestureReset error", t);
            return false;
        }
    }

    private static void ensureGestureHandlerOwnsDispatcher(Object handler) {
        probeHandlerFields(handler);
        try {
            if (letterStartedField != null) {
                try {
                    letterStartedField.setBoolean(handler, true);
                } catch (Throwable ignored) {
                }
            }
            if (gestureResetField != null) {
                try {
                    gestureResetField.setBoolean(handler, false);
                } catch (Throwable ignored) {
                }
            }
            if (hostBridgeField != null && claimOwnershipMethod != null) {
                Object bridge = hostBridgeField.get(handler);
                if (bridge != null) {
                    claimOwnershipMethod.invoke(bridge);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void sanitizeTrailIndices(Object handler) {
        probeHandlerFields(handler);
        try {
            if (trailManagerField == null || trailStartIndexArrayField == null || trailSparseArrayField == null) {
                return;
            }
            Object trailMgr = trailManagerField.get(handler);
            if (trailMgr == null) return;

            Object cObj = trailStartIndexArrayField.get(trailMgr);
            Object bObj = trailSparseArrayField.get(trailMgr);

            if (cObj instanceof SparseIntArray && bObj instanceof SparseArray) {
                SparseIntArray c = (SparseIntArray) cObj;
                SparseArray<?> b = (SparseArray<?>) bObj;
                for (int i = 0; i < c.size(); i++) {
                    int pid = c.keyAt(i);
                    Object listObj = b.get(pid);
                    int listSize = (listObj instanceof List) ? ((List<?>) listObj).size() : 0;
                    if (c.valueAt(i) > listSize) {
                        c.put(pid, 0);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void finishTrailForPointer(Object handler, int pointerId, boolean isSwipe) {
        probeHandlerFields(handler);
        try {
            if (trailManagerField == null) return;
            Object trailMgr = trailManagerField.get(handler);
            if (trailMgr == null) return;

            // 1. Delete pointerId from c (SparseIntArray) so gyq.run() never sees a stale fromIndex
            if (trailStartIndexArrayField != null) {
                Object cObj = trailStartIndexArrayField.get(trailMgr);
                if (cObj instanceof SparseIntArray) {
                    ((SparseIntArray) cObj).delete(pointerId);
                }
            }

            // 2. Remove pointer trail from b (SparseArray)
            if (trailSparseArrayField != null) {
                Object sparseObj = trailSparseArrayField.get(trailMgr);
                if (sparseObj instanceof SparseArray) {
                    SparseArray<?> array = (SparseArray<?>) sparseObj;
                    Object listObj = array.get(pointerId);
                    if (listObj instanceof List) {
                        List<?> list = (List<?>) listObj;
                        if (isSwipe && list.size() >= 3) {
                            if (trailFadingListField != null) {
                                Object fadingObj = trailFadingListField.get(trailMgr);
                                if (fadingObj instanceof List) {
                                    ((List<Object>) fadingObj).add(list);
                                }
                            }
                        } else {
                            if (recyclePointsMethod != null) {
                                try {
                                    recyclePointsMethod.invoke(trailMgr, list);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        array.remove(pointerId);
                    }
                }
            }

            // 3. Keep indices sanitized
            sanitizeTrailIndices(handler);
        } catch (Throwable ignored) {
        }
    }

    private static void releasePointerSlot(HandlerSession session, int pointerId) {
        if (session == null) return;
        int slot = session.slotByPointer.get(pointerId, -1);
        if (slot >= 0 && slot < 2 && session.pointerBySlot[slot] == pointerId) {
            session.pointerBySlot[slot] = -1;
        }
        session.slotByPointer.delete(pointerId);
    }

    private static PointerTrack registerPointerDown(
            HandlerSession session,
            int pointerId,
            float x,
            float y,
            long timeMs
    ) {
        float midX = (session.geometry != null && session.geometry.isValid())
                ? session.geometry.midlinePx()
                : 540f;
        int slot = (x < midX) ? 0 : 1;
        int other = 1 - slot;
        int currentOwner = session.pointerBySlot[slot];
        if (currentOwner != -1 && session.activePointers.indexOfKey(currentOwner) >= 0) {
            slot = other;
        }
        session.slotByPointer.put(pointerId, slot);
        session.pointerBySlot[slot] = pointerId;

        boolean onLetter = session.geometry.isOnLetterArea(x, y);
        KeySpec nearest = session.geometry.nearestLetterKey(x, y);
        char keyChar = nearest != null ? nearest.character : '\0';
        PointerTrack track = new PointerTrack(pointerId, x, y, timeMs, keyChar, onLetter, slot);
        session.activePointers.put(pointerId, track);
        return track;
    }

    private static int countLetterPointers(HandlerSession session) {
        int count = 0;
        for (int i = 0; i < session.activePointers.size(); i++) {
            if (session.activePointers.valueAt(i).startedOnLetter) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasAnySwipingPointer(HandlerSession session, float swipeThreshold) {
        for (int i = 0; i < session.activePointers.size(); i++) {
            PointerTrack t = session.activePointers.valueAt(i);
            if (t.startedOnLetter && t.isSwipe(swipeThreshold)) {
                return true;
            }
        }
        for (int i = 0; i < session.completedTokens.size(); i++) {
            if (session.completedTokens.get(i).kind == NintypeInputToken.Kind.SWIPE) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldDecodeMultiFingerSession(HandlerSession session) {
        List<NintypeInputToken> tokens = session.completedTokens;
        if (tokens.size() < 2) return false;

        int swipeCount = 0;
        long minStart = Long.MAX_VALUE;
        long maxStart = Long.MIN_VALUE;
        for (int i = 0; i < tokens.size(); i++) {
            NintypeInputToken t = tokens.get(i);
            if (t.kind == NintypeInputToken.Kind.SWIPE) {
                swipeCount++;
            }
            if (t.startTimeMs < minStart) minStart = t.startTimeMs;
            if (t.startTimeMs > maxStart) maxStart = t.startTimeMs;
        }
        // Require at least one swipe (so pure 2-finger rollover tap typing stays on Gboard's tap engine)
        return swipeCount >= 1 && (maxStart - minStart <= MAX_CHORD_GAP_MS * 3);
    }

    private static boolean isGestureAlreadyActive(Object handler) {
        probeHandlerFields(handler);
        try {
            if (gestureActiveFlagField != null) {
                return gestureActiveFlagField.getBoolean(handler);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void ensureTrailRecorderEnabled(Object handler) {
        probeHandlerFields(handler);
        try {
            if (trailManagerField != null && trailActiveBoolField != null) {
                Object trailMgr = trailManagerField.get(handler);
                if (trailMgr != null) {
                    trailActiveBoolField.setBoolean(trailMgr, true);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void activateNintypeTakeoverOnFinish(Object handler) {
        probeHandlerFields(handler);
        try {
            ensureTrailRecorderEnabled(handler);
            if (hostBridgeField != null && claimOwnershipMethod != null) {
                Object bridge = hostBridgeField.get(handler);
                if (bridge != null) {
                    claimOwnershipMethod.invoke(bridge);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void decodeAndPreviewSession(
            Object handler,
            HandlerSession session,
            Context context,
            String lang
    ) {
        try {
            LanguagePack pack = GboardNintypeDictionaryManager.loadBlockingIfNeeded(
                    context, lang, session.geometry.hasDedicatedUmlauts());
            if (pack == null) {
                return;
            }

            KineticaDtwDecoder decoder = new KineticaDtwDecoder();
            InputConnection ic = resolveActiveInputConnection(handler, context);
            String prevWord = extractPreviousWord(session.textSnapshotAtActionDown, ic, session.lastCommittedWord);

            // Re-decode full wordTokens buffer using Kinetica's continuous DTW engine
            List<WordCandidate> candidates = decoder.decode(
                    session.wordTokens,
                    pack.trie,
                    pack.bigrams,
                    session.geometry,
                    prevWord
            );
            if (candidates.isEmpty()) {
                return;
            }

            String bestWord = candidates.get(0).word;
            boolean capitalizeFirst = shouldCapitalizeFirstLetter(session.textSnapshotAtActionDown, ic);
            if (capitalizeFirst && bestWord.length() > 0) {
                bestWord = capitalizeFirstChar(bestWord);
            }

            List<String> alternativeWords = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                String w = candidates.get(i).word;
                if (capitalizeFirst && w.length() > 0) {
                    w = capitalizeFirstChar(w);
                }
                if (!alternativeWords.contains(w)) {
                    alternativeWords.add(w);
                }
            }

            session.currentComposingWord = bestWord;
            session.currentAlternativeWords = alternativeWords;

            if (ic != null) {
                previewWordToInputConnection(ic, bestWord, session.textSnapshotAtActionDown);
            }

            GboardMatrixSuggestionsRuntime.recordNintypeDecodedWord(bestWord, alternativeWords);
        } catch (Throwable t) {
            Log.e(TAG, "decodeAndPreviewSession failed", t);
        }
    }

    private static void previewWordToInputConnection(
            InputConnection ic,
            String word,
            String baselineBeforeText
    ) {
        try {
            ic.beginBatchEdit();
            try {
                // If BasicMotionEventHandler committed any stray rollover tap characters after ACTION_DOWN,
                // cleanly roll back to the pre-gesture baseline before displaying composing preview.
                if (baselineBeforeText != null) {
                    CharSequence currentSeq = ic.getTextBeforeCursor(64, 0);
                    if (currentSeq != null) {
                        String currentStr = currentSeq.toString();
                        if (currentStr.length() > baselineBeforeText.length()
                                && currentStr.startsWith(baselineBeforeText)) {
                            int extraChars = currentStr.length() - baselineBeforeText.length();
                            if (extraChars > 0 && extraChars <= 12) {
                                ic.deleteSurroundingText(extraChars, 0);
                            }
                        }
                    }
                }

                // Display Live Composing preview (underlined text, exact Kinetica behavior)
                ic.setComposingText(word, 1);
            } finally {
                ic.endBatchEdit();
            }
        } catch (Throwable t) {
            Log.w(TAG, "previewWordToInputConnection failed", t);
        }
    }

    private static void finalizeComposingAndAutospace(
            Object handler,
            HandlerSession session,
            Context context,
            String lang
    ) {
        try {
            if (!session.autospaceTimerPending) return;
            session.autospaceTimerPending = false;

            // Autospace fires only after all thumbs have lifted
            if (session.activePointers.size() > 0) return;

            InputConnection ic = resolveActiveInputConnection(handler, context);
            String committedWord = session.currentComposingWord;

            if (ic != null && committedWord != null && !committedWord.isEmpty()) {
                ic.beginBatchEdit();
                try {
                    // 1) Finish composing text
                    ic.finishComposingText();

                    // 2) Append space BEHIND the committed word (exact Kinetica autospace)
                    boolean autospaceEnabled = GboardNintypeSettings.isAutoSpaceEnabled(context);
                    if (autospaceEnabled) {
                        ic.commitText(" ", 1);
                        session.autospaceInserted = true;
                    }
                } finally {
                    ic.endBatchEdit();
                }

                session.lastCommittedWord = committedWord;
                String prevWord = extractPreviousWord(session.textSnapshotAtActionDown, ic, session.lastCommittedWord);
                GboardNintypeDictionaryManager.recordCommittedWord(prevWord, committedWord, lang);
                if (session.currentAlternativeWords != null) {
                    GboardMatrixSuggestionsRuntime.recordNintypeDecodedWord(committedWord, session.currentAlternativeWords);
                }
            }

            session.clearWordBuffer();
            session.resetGestureState();
        } catch (Throwable t) {
            Log.w(TAG, "finalizeComposingAndAutospace failed", t);
        }
    }

    private static String readTextBeforeCursorSnapshot(Object handler, Context context) {
        InputConnection ic = resolveActiveInputConnection(handler, context);
        if (ic == null) return null;
        try {
            CharSequence before = ic.getTextBeforeCursor(64, 0);
            return before != null ? before.toString() : "";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractPreviousWord(
            String baselineBeforeText,
            InputConnection ic,
            String fallbackLastWord
    ) {
        String source = baselineBeforeText;
        if (source == null && ic != null) {
            try {
                CharSequence before = ic.getTextBeforeCursor(48, 0);
                if (before != null) {
                    source = before.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        if (source != null && !source.isEmpty()) {
            String s = source.trim();
            if (!s.isEmpty()) {
                int lastSpace = s.lastIndexOf(' ');
                String tail = (lastSpace >= 0) ? s.substring(lastSpace + 1) : s;
                String cleaned = tail.replaceAll("[^\\p{L}]", "");
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
        }
        return fallbackLastWord;
    }

    private static boolean shouldCapitalizeFirstLetter(String baselineBeforeText, InputConnection ic) {
        String before = baselineBeforeText;
        if (before == null && ic != null) {
            try {
                CharSequence seq = ic.getTextBeforeCursor(32, 0);
                if (seq != null) {
                    before = seq.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        if (before == null) return false;
        if (before.isEmpty()) return true;
        for (int i = before.length() - 1; i >= 0; i--) {
            char c = before.charAt(i);
            if (c == '\n') return true;
            if (Character.isWhitespace(c)) continue;
            return c == '.' || c == '!' || c == '?';
        }
        return true;
    }

    private static String capitalizeFirstChar(String word) {
        if (word == null || word.isEmpty()) return word;
        char first = word.charAt(0);
        if (Character.isUpperCase(first)) return word;
        return Character.toUpperCase(first) + word.substring(1);
    }

    private static HandlerSession getOrCreateSession(Object handler) {
        synchronized (SESSIONS) {
            HandlerSession session = SESSIONS.get(handler);
            if (session == null) {
                session = new HandlerSession();
                SESSIONS.put(handler, session);
            }
            return session;
        }
    }

    private static Context extractContext(Object handler) {
        probeHandlerFields(handler);
        try {
            if (contextField != null) {
                Object c = contextField.get(handler);
                if (c instanceof Context) {
                    cachedContext = (Context) c;
                    return (Context) c;
                }
            }
            if (keyboardViewField != null) {
                Object v = keyboardViewField.get(handler);
                if (v instanceof View) {
                    Context c = ((View) v).getContext();
                    if (c != null) {
                        cachedContext = c;
                        return c;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return cachedContext;
    }

    private static View extractKeyboardView(Object handler) {
        probeHandlerFields(handler);
        try {
            if (keyboardViewField != null) {
                Object v = keyboardViewField.get(handler);
                if (v instanceof View) return (View) v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static InputConnection resolveActiveInputConnection(Object handler, Context context) {
        try {
            Context cur = context;
            while (cur instanceof ContextWrapper && !(cur instanceof InputMethodService)) {
                cur = ((ContextWrapper) cur).getBaseContext();
            }
            if (cur instanceof InputMethodService) {
                InputConnection ic = ((InputMethodService) cur).getCurrentInputConnection();
                if (ic != null) return ic;
            }
        } catch (Throwable ignored) {
        }
        View kv = extractKeyboardView(handler);
        if (kv != null) {
            try {
                Context vCtx = kv.getContext();
                while (vCtx instanceof ContextWrapper && !(vCtx instanceof InputMethodService)) {
                    vCtx = ((ContextWrapper) vCtx).getBaseContext();
                }
                if (vCtx instanceof InputMethodService) {
                    InputConnection ic = ((InputMethodService) vCtx).getCurrentInputConnection();
                    if (ic != null) return ic;
                }
            } catch (Throwable ignored) {
            }
            return MatrixGboardReflector.resolveInputConnectionViaHierarchy(kv);
        }
        return null;
    }

    private static void ensureGeometryUpdated(
            Object handler,
            HandlerSession session,
            Context context
    ) {
        probeHandlerFields(handler);
        View keyboardView = null;
        try {
            if (keyboardViewField != null) {
                Object v = keyboardViewField.get(handler);
                if (v instanceof View) {
                    keyboardView = (View) v;
                }
            }
        } catch (Throwable ignored) {
        }

        int width = (keyboardView != null && keyboardView.getWidth() > 0)
                ? keyboardView.getWidth()
                : 1080;
        int height = (keyboardView != null && keyboardView.getHeight() > 0)
                ? keyboardView.getHeight()
                : 680;

        if (session.geometryReady
                && session.geometry.isValid()
                && width == session.lastKeyboardWidth
                && height == session.lastKeyboardHeight) {
            return;
        }

        session.lastKeyboardWidth = width;
        session.lastKeyboardHeight = height;

        if (keyboardView instanceof ViewGroup) {
            List<KeySpec> extracted = new ArrayList<>(32);
            boolean[] hasUmlaut = new boolean[] {false};
            collectLetterKeysFromViewHierarchy((ViewGroup) keyboardView, keyboardView, extracted, hasUmlaut);
            if (extracted.size() >= 20) {
                float sumW = 0f;
                float sumH = 0f;
                for (int i = 0; i < extracted.size(); i++) {
                    sumW += extracted.get(i).width;
                    sumH += extracted.get(i).height;
                }
                session.geometry.setKeys(
                        extracted,
                        sumW / extracted.size(),
                        sumH / extracted.size(),
                        hasUmlaut[0]
                );
                session.geometryReady = true;
                return;
            }
        }

        String lang = GboardNintypeDictionaryManager.detectActiveLanguage(context);
        boolean qwertz = "de".equalsIgnoreCase(lang);
        NintypeGeometry fallback = NintypeGeometry.createStandardLayout(width, height, qwertz);
        List<KeySpec> specs = new ArrayList<>(26);
        for (int i = 0; i < NintypeAlphabet.ALPHABET_SIZE; i++) {
            KeySpec ks = fallback.keySpecByIdx(i);
            if (ks != null) specs.add(ks);
        }
        session.geometry.setKeys(specs, fallback.keyWidthPx, fallback.keyHeightPx, false);
        session.geometryReady = true;
    }

    private static void collectLetterKeysFromViewHierarchy(
            ViewGroup group,
            View rootKeyboardView,
            List<KeySpec> out,
            boolean[] hasUmlaut
    ) {
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;

            if (child.getClass().getSimpleName().contains("SoftKeyView")) {
                char labelChar = extractSingleLetterFromSoftKeyView(child);
                if (labelChar != '\0') {
                    if (labelChar == 'ä' || labelChar == 'ö' || labelChar == 'ü') {
                        hasUmlaut[0] = true;
                    }
                    int idx = NintypeAlphabet.charIndex(labelChar);
                    if (child.getWidth() > 0 && child.getHeight() > 0) {
                        float cx = child.getX() + child.getWidth() * 0.5f;
                        float cy = child.getY() + child.getHeight() * 0.5f;
                        View parent = (View) child.getParent();
                        while (parent != null && parent != rootKeyboardView) {
                            cx += parent.getX();
                            cy += parent.getY();
                            parent = (parent.getParent() instanceof View)
                                    ? (View) parent.getParent()
                                    : null;
                        }
                        char storeChar = (idx >= 0) ? NintypeAlphabet.indexToChar(idx) : labelChar;
                        out.add(new KeySpec(
                                storeChar,
                                cx,
                                cy,
                                child.getWidth(),
                                child.getHeight()
                        ));
                    }
                }
            } else if (child instanceof ViewGroup) {
                collectLetterKeysFromViewHierarchy((ViewGroup) child, rootKeyboardView, out, hasUmlaut);
            }
        }
    }

    private static char extractSingleLetterFromSoftKeyView(View softKeyView) {
        if (softKeyView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) softKeyView;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof TextView && c.getVisibility() == View.VISIBLE) {
                    CharSequence text = ((TextView) c).getText();
                    if (text != null && text.length() == 1) {
                        char ch = Character.toLowerCase(text.charAt(0));
                        if ((ch >= 'a' && ch <= 'z') || ch == 'ä' || ch == 'ö' || ch == 'ü'
                                || NintypeGeometry.NO_SPACE_BEFORE.indexOf(ch) >= 0) {
                            return ch;
                        }
                    }
                }
            }
        }
        CharSequence desc = softKeyView.getContentDescription();
        if (desc != null && desc.length() >= 1) {
            char ch = Character.toLowerCase(desc.charAt(0));
            if (desc.length() == 1 && ((ch >= 'a' && ch <= 'z') || ch == 'ä' || ch == 'ö' || ch == 'ü'
                    || NintypeGeometry.NO_SPACE_BEFORE.indexOf(ch) >= 0)) {
                return ch;
            }
        }
        return '\0';
    }

    private static Object findSampleKeyView(View root) {
        if (root == null) return null;
        if (root.getClass().getName().contains("SoftKeyView")) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                Object res = findSampleKeyView(vg.getChildAt(i));
                if (res != null) return res;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void ensureSoftKeyMapPopulated(Object handler, HandlerSession session, MotionEvent event) {
        if (softKeyMapField == null || event == null) return;
        try {
            SparseArray<Object> map = (SparseArray<Object>) softKeyMapField.get(handler);
            if (map != null) {
                Object sample = null;
                if (map.size() > 0) {
                    sample = map.valueAt(0);
                    if (sample != null) cachedSampleKeyView = sample;
                }
                if (sample == null) {
                    sample = cachedSampleKeyView;
                }
                if (sample == null && keyboardViewField != null) {
                    Object kbView = keyboardViewField.get(handler);
                    if (kbView instanceof View) {
                        sample = findSampleKeyView((View) kbView);
                        if (sample != null) cachedSampleKeyView = sample;
                    }
                }
                if (sample != null) {
                    int count = event.getPointerCount();
                    for (int p = 0; p < count; p++) {
                        int pid = event.getPointerId(p);
                        if (map.get(pid) == null) {
                            float px = event.getX(p);
                            float py = event.getY(p);
                            if (session.geometry.isOnLetterArea(px, py)) {
                                map.put(pid, sample);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void probeHandlerFields(Object handler) {
        if (handler == null) return;
        Class<?> handlerClass = handler.getClass();
        if (probedClasses.contains(handlerClass)) return;
        synchronized (GboardNintypeRuntime.class) {
            if (probedClasses.contains(handlerClass)) return;
            try {
                Class<?> cur = handlerClass;
                while (cur != null && cur != Object.class) {
                    for (Field f : cur.getDeclaredFields()) {
                        f.setAccessible(true);
                        Class<?> type = f.getType();
                        String name = f.getName();
                        if (Context.class.isAssignableFrom(type) && contextField == null) {
                            contextField = f;
                        } else if (type.getName().endsWith("SoftKeyboardView") && keyboardViewField == null) {
                            keyboardViewField = f;
                        } else if ("v".equals(name) && type == boolean.class) {
                            gestureActiveFlagField = f;
                        } else if ("u".equals(name) && type == boolean.class) {
                            letterStartedField = f;
                        } else if ("Q".equals(name) && type == boolean.class) {
                            gestureResetField = f;
                        } else if ("d".equals(name) && SparseArray.class.isAssignableFrom(type)) {
                            softKeyMapField = f;
                        } else if ("U".equals(name) && !type.isPrimitive()) {
                            trailManagerField = f;
                            try {
                                Field aField = type.getDeclaredField("a");
                                if (aField.getType() == boolean.class) {
                                    aField.setAccessible(true);
                                    trailActiveBoolField = aField;
                                }
                            } catch (Throwable ignored) {
                            }
                            try {
                                Field bField = type.getDeclaredField("b");
                                bField.setAccessible(true);
                                trailSparseArrayField = bField;
                            } catch (Throwable ignored) {
                            }
                            try {
                                Field cField = type.getDeclaredField("c");
                                cField.setAccessible(true);
                                trailStartIndexArrayField = cField;
                            } catch (Throwable ignored) {
                            }
                            try {
                                Field eField = type.getDeclaredField("e");
                                eField.setAccessible(true);
                                trailFadingListField = eField;
                            } catch (Throwable ignored) {
                            }
                            try {
                                Method eMeth = type.getDeclaredMethod("e", List.class);
                                eMeth.setAccessible(true);
                                recyclePointsMethod = eMeth;
                            } catch (Throwable ignored) {
                            }
                        } else if ("p".equals(name) && type.isInterface()) {
                            hostBridgeField = f;
                            try {
                                Method m = type.getDeclaredMethod("m");
                                m.setAccessible(true);
                                claimOwnershipMethod = m;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    cur = cur.getSuperclass();
                }
            } catch (Throwable ignored) {
            } finally {
                probedClasses.add(handlerClass);
            }
        }
    }
}
