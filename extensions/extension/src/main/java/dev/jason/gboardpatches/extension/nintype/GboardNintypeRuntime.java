package dev.jason.gboardpatches.extension.nintype;

import android.content.Context;
import android.content.ContextWrapper;
import android.inputmethodservice.InputMethodService;
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
                        if (netDisp >= 14f) {
                            crossedKeyBoundary = true;
                        }
                    }
                }
            }
        }

        boolean isSwipe(float swipeThresholdPx) {
            if (points.size() < 2) return false;
            if (pathLengthPx >= swipeThresholdPx) return true;
            return startedOnLetter && crossedKeyBoundary && pathLengthPx >= 15f;
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
        final NintypeGeometry geometry = new NintypeGeometry();
        final int[] pointerBySlot = new int[] {-1, -1};
        final SparseIntArray slotByPointer = new SparseIntArray(4);
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
    private static volatile Field minSampleDistanceSqField;
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
            if (minSampleDistanceSqField != null) {
                try {
                    // Clamp min squared distance threshold from stock ~144 px^2 to 4 px^2 (2px)
                    // so 120Hz frames with 3-8px displacement increment the gesture motion step counter.
                    minSampleDistanceSqField.setInt(handler, 4);
                } catch (Throwable ignored) {
                }
            }

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

                    boolean keepPendingChord = false;
                    if (session.pendingChordToken != null
                            && (eventTime - session.pendingChordToken.endTimeMs <= MAX_SEQUENTIAL_CHORD_GAP_MS)
                            && session.geometry.isOnLetterArea(x, y)) {
                        float minHandSeparation = Math.max(120f, session.geometry.keyWidthPx * 1.45f);
                        if (Math.abs(x - session.pendingChordAnchorX) >= minHandSeparation) {
                            keepPendingChord = true;
                        }
                    }

                    session.resetGestureState();
                    if (!keepPendingChord) {
                        session.clearPendingChord();
                        session.textSnapshotAtActionDown = readTextBeforeCursorSnapshot(handler, context);
                    } else {
                        session.textSnapshotAtActionDown = session.pendingChordBaselineText;
                    }

                    registerPointerDown(session, pid, x, y, eventTime);
                    break;
                }

                case MotionEvent.ACTION_POINTER_DOWN: {
                    session.clearPendingChord();
                    int pid = event.getPointerId(actionIndex);
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);
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
                    if (hasAnySwipingPointer(session, 10f)) {
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

                    // If a recent opposite-hand tap preceded this swipe within 180ms, promote it into a chord
                    if (!session.sawMultiTouch
                            && session.pendingChordToken != null
                            && session.pendingChordToken.kind == NintypeInputToken.Kind.TAP
                            && hasAnySwipingPointer(session, multiSwipeThreshold)) {
                        session.completedTokens.add(session.pendingChordToken);
                        session.sawMultiTouch = true;
                        session.textSnapshotAtActionDown = session.pendingChordBaselineText;
                        session.clearPendingChord();
                    }

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

                    if (session.sawMultiTouch && hasAnySwipingPointer(session, 10f)) {
                        session.nintypeTakeoverActive = true;
                        ensureTrailRecorderEnabled(handler);
                    }
                    break;
                }

                case MotionEvent.ACTION_POINTER_UP: {
                    int pid = event.getPointerId(actionIndex);
                    PointerTrack track = session.activePointers.get(pid);
                    if (track != null) {
                        track.addPoint(
                                event.getX(actionIndex),
                                event.getY(actionIndex),
                                eventTime,
                                session.geometry
                        );
                        track.finished = true;
                        session.activePointers.remove(pid);
                        NintypeInputToken token = track.toToken(multiSwipeThreshold);
                        if (token != null) {
                            session.completedTokens.add(token);
                        }
                    }
                    releasePointerSlot(session, pid);
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

                    if (session.sawMultiTouch && hasAnySwipingPointer(session, 10f)) {
                        session.nintypeTakeoverActive = true;
                        ensureTrailRecorderEnabled(handler);
                    }
                    break;
                }

                case MotionEvent.ACTION_UP: {
                    float lastUpX = event.getX();
                    for (int i = 0; i < session.activePointers.size(); i++) {
                        PointerTrack track = session.activePointers.valueAt(i);
                        int pIdx = event.findPointerIndex(track.pointerId);
                        if (pIdx >= 0) {
                            lastUpX = event.getX(pIdx);
                            track.addPoint(
                                    event.getX(pIdx),
                                    event.getY(pIdx),
                                    eventTime,
                                    session.geometry
                            );
                        }
                        track.finished = true;
                        NintypeInputToken token = track.toToken(multiSwipeThreshold);
                        if (token != null) {
                            session.completedTokens.add(token);
                        }
                    }
                    session.activePointers.clear();

                    // Check if a recent opposite-hand swipe (e.g. a->s) was followed within 180ms by this tap (e.g. l)
                    if (!session.sawMultiTouch
                            && session.pendingChordToken != null
                            && session.completedTokens.size() == 1) {
                        NintypeInputToken currentToken = session.completedTokens.get(0);
                        if (session.pendingChordToken.kind == NintypeInputToken.Kind.SWIPE
                                || currentToken.kind == NintypeInputToken.Kind.SWIPE) {
                            session.completedTokens.add(0, session.pendingChordToken);
                            session.sawMultiTouch = true;
                            session.textSnapshotAtActionDown = session.pendingChordBaselineText;
                        }
                        session.clearPendingChord();
                    }

                    if (session.sawMultiTouch && shouldDecodeMultiFingerSession(session)) {
                        session.nintypeTakeoverActive = true;
                        session.clearPendingChord();
                        activateNintypeTakeoverOnFinish(handler);
                        if (!session.decodedThisSession) {
                            session.decodedThisSession = true;
                            decodeAndCommitSession(handler, session, context, activeLang);
                        }
                    } else if (!session.sawMultiTouch && session.completedTokens.size() == 1) {
                        NintypeInputToken singleToken = session.completedTokens.get(0);
                        session.pendingChordToken = singleToken;
                        session.pendingChordBaselineText = session.textSnapshotAtActionDown;
                        session.pendingChordAnchorX = lastUpX;
                    } else {
                        session.clearPendingChord();
                    }
                    break;
                }

                case MotionEvent.ACTION_CANCEL: {
                    session.resetGestureState();
                    session.clearPendingChord();
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
                if (session.sawMultiTouch) {
                    session.nintypeTakeoverActive = true;
                }
                return true;
            }

            // Gboard's [0494]..[04cd] transition block must only run when `this.v` is currently false.
            if (isGestureAlreadyActive(handler)) {
                ensureTrailRecorderEnabled(handler);
                return false;
            }

            // When multi-touch is detected (both thumbs active), start gesture as soon as any thumb moves >= 10px
            if (session.sawMultiTouch && hasAnySwipingPointer(session, 10f)) {
                session.nintypeTakeoverActive = true;
                ensureTrailRecorderEnabled(handler);
                return true;
            }

            // Also start trail & gesture ownership when a single finger slides across a key boundary
            // or moves >= 20px (e.g. sliding a -> s on the left thumb before the right thumb taps l for "als").
            float singleNeighborThreshold = Math.max(
                    SINGLE_FINGER_NEIGHBOR_SWIPE_THRESHOLD_PX, session.geometry.keyWidthPx * 0.22f);
            for (int i = 0; i < session.activePointers.size(); i++) {
                PointerTrack t = session.activePointers.valueAt(i);
                if (t.startedOnLetter && (t.crossedKeyBoundary || t.pathLengthPx >= singleNeighborThreshold)) {
                    session.shortSwipeForceStarted = true;
                    ensureTrailRecorderEnabled(handler);
                    return true;
                }
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
     * @return true if Nintype has taken over this multi-finger gesture (suppressing Gboard's
     *         1-finger gesture event dispatch), or false for normal 1-finger swipes.
     */
    public static boolean shouldInterceptGestureDispatch(Object handler) {
        if (handler == null) return false;
        HandlerSession session = SESSIONS.get(handler);
        return session != null && session.nintypeTakeoverActive;
    }

    /**
     * Injected at the start of AbstractGestureMotionEventHandler.B() (reset gesture state).
     * Only resets Nintype session state if all active fingers have been lifted. If fingers are
     * still touching the keyboard (e.g. holding 'i' while tapping 'c'), the session is preserved.
     */
    public static void onGestureReset(Object handler) {
        if (handler == null) return;
        try {
            HandlerSession session = SESSIONS.get(handler);
            if (session != null) {
                if (session.activePointers.size() == 0) {
                    session.resetGestureState();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onGestureReset error", t);
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

    private static void decodeAndCommitSession(
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

            NintypeDecoder decoder = new NintypeDecoder(pack.trie, pack.bigrams, session.geometry);
            InputConnection ic = resolveActiveInputConnection(handler, context);
            String prevWord = extractPreviousWord(session.textSnapshotAtActionDown, ic, session.lastCommittedWord);

            List<WordCandidate> candidates = decoder.decode(
                    session.completedTokens,
                    prevWord,
                    12,
                    words -> GboardNintypeDictionaryManager.checkWordsInGboard(context, words)
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

            if (ic != null) {
                commitWordToInputConnection(context, ic, bestWord, session.textSnapshotAtActionDown);
            }

            session.lastCommittedWord = bestWord;
            GboardNintypeDictionaryManager.recordCommittedWord(prevWord, bestWord, lang);
            GboardMatrixSuggestionsRuntime.recordNintypeDecodedWord(bestWord, alternativeWords);
        } catch (Throwable t) {
            Log.e(TAG, "decodeAndCommitSession failed", t);
        }
    }

    private static void commitWordToInputConnection(
            Context context,
            InputConnection ic,
            String word,
            String baselineBeforeText
    ) {
        try {
            ic.beginBatchEdit();
            try {
                // 1) Clear any partial composing preview that 1-finger swipe or tap might have started
                ic.setComposingText("", 1);
                ic.finishComposingText();

                // 2) If BasicMotionEventHandler committed any stray rollover tap characters after ACTION_DOWN,
                //    cleanly roll back to the pre-gesture baseline before committing the Nintype word.
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

                boolean autoSpace = GboardNintypeSettings.isAutoSpaceEnabled(context);
                boolean prependSpace = false;
                if (autoSpace) {
                    CharSequence before = ic.getTextBeforeCursor(2, 0);
                    if (before != null && before.length() > 0) {
                        char lastChar = before.charAt(before.length() - 1);
                        if (Character.isLetterOrDigit(lastChar)
                                || lastChar == '.'
                                || lastChar == ','
                                || lastChar == '!'
                                || lastChar == '?'
                                || lastChar == ')'
                                || lastChar == '"'
                                || lastChar == '\'') {
                            prependSpace = true;
                        }
                    }
                }

                String toCommit = prependSpace ? (" " + word) : word;
                ic.commitText(toCommit, 1);
            } finally {
                ic.endBatchEdit();
            }
        } catch (Throwable t) {
            Log.w(TAG, "commitWordToInputConnection failed", t);
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
                    if (idx >= 0 && child.getWidth() > 0 && child.getHeight() > 0) {
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
                        out.add(new KeySpec(
                                NintypeAlphabet.indexToChar(idx),
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
                        if ((ch >= 'a' && ch <= 'z') || ch == 'ä' || ch == 'ö' || ch == 'ü') {
                            return ch;
                        }
                    }
                }
            }
        }
        CharSequence desc = softKeyView.getContentDescription();
        if (desc != null && desc.length() >= 1) {
            char ch = Character.toLowerCase(desc.charAt(0));
            if (desc.length() == 1 && ((ch >= 'a' && ch <= 'z') || ch == 'ä' || ch == 'ö' || ch == 'ü')) {
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
                        } else if ("w".equals(name) && type == int.class) {
                            minSampleDistanceSqField = f;
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
