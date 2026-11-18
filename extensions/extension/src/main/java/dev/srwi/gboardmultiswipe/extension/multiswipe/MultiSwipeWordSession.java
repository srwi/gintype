package dev.srwi.gboardmultiswipe.extension.multiswipe;

import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeGeometry.KeySpec;
import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeInputToken.TouchPoint;
import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeInputToken.WordCandidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-JVM behavioral state machine governing MultiSwipe's Gboard integration rules
 * (touch routing, buffer promotion, live composing preview, auto-space, compound-word
 * Backspace attachment, capitalization, and hook decision predicates).
 *
 * Kept free of Android framework dependencies so every integration rule (MS-01..MS-19)
 * can be deterministically verified on the JVM.
 */
final class MultiSwipeWordSession {

    static final float MULTI_TOUCH_SWIPE_THRESHOLD_PX = 22f;
    static final int TEXT_SNAPSHOT_CHARS = 256;
    static final int MAX_ROLLBACK_EXTRA_CHARS = 32;
    static final long MAX_PASSIVE_TAP_GAP_MS = 750L;
    static final int MAX_PASSIVE_TAP_TOKENS = 8;
    static final long ROLLOVER_MULTI_TOUCH_WINDOW_MS = 220L;
    static final long DOUBLE_SPACE_PERIOD_TIMEOUT_MS = 500L;
    static final long BACKSPACE_HOLD_TIMEOUT_MS = 280L;

    static final class SettingsSnapshot {
        final boolean enabled;
        final boolean autoSpaceEnabled;
        final int autoSpaceDelayMs;
        final int singleTouchCombineDelayMs;
        final boolean doubleSpacePeriodEnabled;
        final boolean autoSpaceBeforeWord;
        final int tapToMultiSwipeDelayMs;
        final String backspaceMode;

        SettingsSnapshot(
                boolean enabled,
                boolean autoSpaceEnabled,
                int autoSpaceDelayMs,
                int singleTouchCombineDelayMs
        ) {
            this(enabled, autoSpaceEnabled, autoSpaceDelayMs, singleTouchCombineDelayMs,
                    true, false, GboardMultiSwipeSettings.DEFAULT_TAP_TO_MULTISWIPE_DELAY_MS,
                    GboardMultiSwipeSettings.DEFAULT_BACKSPACE_MODE);
        }

        SettingsSnapshot(
                boolean enabled,
                boolean autoSpaceEnabled,
                int autoSpaceDelayMs,
                int singleTouchCombineDelayMs,
                boolean doubleSpacePeriodEnabled
        ) {
            this(enabled, autoSpaceEnabled, autoSpaceDelayMs, singleTouchCombineDelayMs,
                    doubleSpacePeriodEnabled, false,
                    GboardMultiSwipeSettings.DEFAULT_TAP_TO_MULTISWIPE_DELAY_MS,
                    GboardMultiSwipeSettings.DEFAULT_BACKSPACE_MODE);
        }

        SettingsSnapshot(
                boolean enabled,
                boolean autoSpaceEnabled,
                int autoSpaceDelayMs,
                int singleTouchCombineDelayMs,
                boolean doubleSpacePeriodEnabled,
                boolean autoSpaceBeforeWord
        ) {
            this(enabled, autoSpaceEnabled, autoSpaceDelayMs, singleTouchCombineDelayMs,
                    doubleSpacePeriodEnabled, autoSpaceBeforeWord,
                    GboardMultiSwipeSettings.DEFAULT_TAP_TO_MULTISWIPE_DELAY_MS,
                    GboardMultiSwipeSettings.DEFAULT_BACKSPACE_MODE);
        }

        SettingsSnapshot(
                boolean enabled,
                boolean autoSpaceEnabled,
                int autoSpaceDelayMs,
                int singleTouchCombineDelayMs,
                boolean doubleSpacePeriodEnabled,
                boolean autoSpaceBeforeWord,
                int tapToMultiSwipeDelayMs
        ) {
            this(enabled, autoSpaceEnabled, autoSpaceDelayMs, singleTouchCombineDelayMs,
                    doubleSpacePeriodEnabled, autoSpaceBeforeWord, tapToMultiSwipeDelayMs,
                    GboardMultiSwipeSettings.DEFAULT_BACKSPACE_MODE);
        }

        SettingsSnapshot(
                boolean enabled,
                boolean autoSpaceEnabled,
                int autoSpaceDelayMs,
                int singleTouchCombineDelayMs,
                boolean doubleSpacePeriodEnabled,
                boolean autoSpaceBeforeWord,
                int tapToMultiSwipeDelayMs,
                String backspaceMode
        ) {
            this.enabled = enabled;
            this.autoSpaceEnabled = autoSpaceEnabled;
            this.autoSpaceDelayMs = autoSpaceDelayMs;
            this.singleTouchCombineDelayMs = singleTouchCombineDelayMs;
            this.doubleSpacePeriodEnabled = doubleSpacePeriodEnabled;
            this.autoSpaceBeforeWord = autoSpaceBeforeWord;
            this.tapToMultiSwipeDelayMs = tapToMultiSwipeDelayMs;
            this.backspaceMode = GboardMultiSwipeSettings.sanitizeBackspaceMode(backspaceMode);
        }
    }

    interface EditorAdapter {
        String readTextBeforeCursorSnapshot();
        String readCharBeforeCursor();
        void deleteCharsBeforeCursor(int count);
        int previewWord(String word, String baselineBeforeText, boolean prependLeadingSpace);
        void commitFinishedWord(String word, boolean appendSpace);
        void commitText(String text);
        default void deleteActiveComposingWord(int charCount) {
            deleteCharsBeforeCursor(charCount);
        }
        default void replaceComposingWithText(int composingCharCount, String replacement) {
            deleteCharsBeforeCursor(composingCharCount);
            if (replacement != null && !replacement.isEmpty()) {
                commitText(replacement);
            }
        }
    }

    interface DecodePort {
        List<WordCandidate> decode(List<MultiSwipeInputToken> tokens, String prevWord);
    }

    interface LifecycleCallbacks {
        void onTrailAccentChanged(boolean active);
        void onTakeoverActivatedOnFinish();
        void onPointerFinished(int pointerId, boolean isSwipe, boolean inMultiSwipeGesture);
        void onClearTrailManagerActive();
        void onScheduleAutospaceTimer(long delayMs);
        void onCancelAutospaceTimer();
        default void onScheduleStockSwipeAutospaceTimer(long delayMs) {}
        default void onCancelStockSwipeAutospaceTimer() {}
        default void onBufferTimerUpdated(long durationMs) {}
        default void onWordReadyTimerElapsed() {}
        default void onNativeTopBarBlankChanged(boolean blank) {}
        void onWordDecoded(
                String word,
                List<String> alternatives,
                List<MultiSwipeInputToken> tokens,
                boolean capitalizeFirst,
                String prevWord,
                String baseline,
                int promotedPassiveCount,
                int rollbackDeleted,
                int restingDropped,
                List<WordCandidate> candidates
        );
        void onWordCommitted(
                String word,
                boolean autospaceInserted,
                String prevWord,
                List<String> alternatives
        );
        void onSkippedGesture(
                String reason,
                boolean hasSwipe,
                boolean strokeHadMultiTouch,
                int passiveBufferSize,
                int restingDropped,
                String baseline,
                List<MultiSwipeInputToken> completedTokens
        );
    }

    static class PointerTrack {
        final int pointerId;
        final float startX;
        final float startY;
        final long startTimeMs;
        final char startKeyChar;
        final boolean startedOnLetter;
        final int slot; // 0 = LEFT, 1 = RIGHT
        final List<TouchPoint> points = new ArrayList<>(48);
        final List<MultiSwipeInputToken.KeyContact> contacts = new ArrayList<>(12);
        final List<MultiSwipeInputToken.Dwell> dwells =
                new ArrayList<>(KineticaConstants.MAX_DWELL_SEGMENTS);
        float lastX;
        float lastY;
        long lastTimeMs;
        float pathLengthPx = 0f;
        float maxDispSqPx = 0f;
        boolean overlapped = false;
        char currentKeyChar;
        int currentCode = -1;
        long currentEnterTimeMs;
        int runAnchorIdx = 0;
        float runAnchorXKw;
        float runAnchorYKw;
        long runAnchorTimeMs;
        int runLastIdx = 0;
        long runLastTimeMs;
        boolean dwellInitialized = false;
        boolean contactsFinalized = false;
        boolean finished = false;

        PointerTrack(
                int pointerId,
                float x,
                float y,
                long timeMs,
                char startKeyChar,
                boolean startedOnLetter,
                int slot,
                MultiSwipeGeometry geometry
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

            if (geometry != null && geometry.isValid() && geometry.keyWidthPx > 0f) {
                float xKw = x / geometry.keyWidthPx;
                float yKw = y / geometry.keyWidthPx;
                this.runAnchorXKw = xKw;
                this.runAnchorYKw = yKw;
                this.runAnchorTimeMs = timeMs;
                this.runLastTimeMs = timeMs;
                this.dwellInitialized = true;
                this.currentCode = geometry.keyAtKw(xKw, yKw);
                this.currentEnterTimeMs = timeMs;
            }
        }

        void addPoint(float x, float y, long timeMs, MultiSwipeGeometry geometry) {
            long ts = Math.max(lastTimeMs, timeMs);
            float dx = x - lastX;
            float dy = y - lastY;
            float dist = (float) Math.hypot(dx, dy);
            float dStartX = x - startX;
            float dStartY = y - startY;
            float dispSq = dStartX * dStartX + dStartY * dStartY;
            if (dispSq > maxDispSqPx) {
                maxDispSqPx = dispSq;
            }
            if (dist < 1.5f && !points.isEmpty()) {
                if (ts > lastTimeMs) {
                    lastTimeMs = ts;
                    trackDwell(points.size() - 1, x, y, ts, geometry);
                }
                return;
            }
            pathLengthPx += dist;
            lastX = x;
            lastY = y;
            lastTimeMs = ts;
            points.add(new TouchPoint(x, y, ts, pointerId));

            int idx = points.size() - 1;
            trackDwell(idx, x, y, ts, geometry);

            if (geometry != null && geometry.isValid() && geometry.keyWidthPx > 0f) {
                float xKw = x / geometry.keyWidthPx;
                float yKw = y / geometry.keyWidthPx;
                if (currentCode == -1
                        || !geometry.insideInflatedKw(
                                xKw, yKw, currentCode, KineticaConstants.STICKY_INFLATE_KW)) {
                    int newCode = geometry.keyAtKw(xKw, yKw);
                    if (newCode != -1 && newCode != currentCode) {
                        if (currentCode != -1) {
                            contacts.add(new MultiSwipeInputToken.KeyContact(
                                    currentCode, currentEnterTimeMs, ts));
                        }
                        currentCode = newCode;
                        currentEnterTimeMs = ts;
                    }
                }
            }
        }

        private void trackDwell(int idx, float xPx, float yPx, long ts, MultiSwipeGeometry geometry) {
            if (geometry == null || !geometry.isValid() || geometry.keyWidthPx <= 0f) return;
            float xKw = xPx / geometry.keyWidthPx;
            float yKw = yPx / geometry.keyWidthPx;
            if (!dwellInitialized) {
                runAnchorIdx = idx;
                runAnchorXKw = xKw;
                runAnchorYKw = yKw;
                runAnchorTimeMs = ts;
                runLastIdx = idx;
                runLastTimeMs = ts;
                dwellInitialized = true;
                return;
            }
            float dx = xKw - runAnchorXKw;
            float dy = yKw - runAnchorYKw;
            float rSq = KineticaConstants.DWELL_RADIUS_KW * KineticaConstants.DWELL_RADIUS_KW;
            if (dx * dx + dy * dy <= rSq) {
                runLastIdx = idx;
                runLastTimeMs = ts;
                return;
            }
            closeDwellRun();
            runAnchorIdx = idx;
            runAnchorXKw = xKw;
            runAnchorYKw = yKw;
            runAnchorTimeMs = ts;
            runLastIdx = idx;
            runLastTimeMs = ts;
        }

        private void closeDwellRun() {
            if (runLastTimeMs - runAnchorTimeMs < KineticaConstants.DWELL_MIN_MS) return;
            dwells.add(new MultiSwipeInputToken.Dwell(
                    runAnchorIdx, runLastIdx, runAnchorTimeMs, runLastTimeMs));
            if (dwells.size() < KineticaConstants.MAX_DWELL_SEGMENTS) return;
            int shortest = 0;
            for (int i = 1; i < dwells.size(); i++) {
                long dCur = dwells.get(i).tExit - dwells.get(i).tEnter;
                long dBest = dwells.get(shortest).tExit - dwells.get(shortest).tEnter;
                if (dCur < dBest) shortest = i;
            }
            dwells.remove(shortest);
        }

        boolean isSwipe(float swipeThresholdPx) {
            if (points.size() < 2) return false;
            return maxDispSqPx >= swipeThresholdPx * swipeThresholdPx;
        }

        boolean isRestingFinger(float swipeThresholdPx) {
            return overlapped
                    && !isSwipe(swipeThresholdPx)
                    && (lastTimeMs - startTimeMs) >= KineticaConstants.RESTING_FINGER_MIN_MS;
        }

        MultiSwipeInputToken toToken(float swipeThresholdPx) {
            if (!contactsFinalized) {
                contactsFinalized = true;
                if (currentCode != -1) {
                    contacts.add(new MultiSwipeInputToken.KeyContact(
                            currentCode, currentEnterTimeMs, lastTimeMs));
                }
            }
            if (isSwipe(swipeThresholdPx)) {
                return MultiSwipeInputToken.swipe(
                        pointerId,
                        slot,
                        new ArrayList<>(points),
                        pathLengthPx,
                        startTimeMs,
                        lastTimeMs,
                        new ArrayList<>(contacts),
                        new ArrayList<>(dwells),
                        false,
                        false
                );
            }
            if (startedOnLetter && startKeyChar != '\0') {
                return MultiSwipeInputToken.tap(
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

        /**
         * Non-mutating snapshot for live intermediate decoding during ACTION_MOVE.
         */
        MultiSwipeInputToken toSnapshotToken(float swipeThresholdPx) {
            List<MultiSwipeInputToken.KeyContact> snapshotContacts = new ArrayList<>(contacts);
            if (!contactsFinalized && currentCode != -1) {
                snapshotContacts.add(new MultiSwipeInputToken.KeyContact(
                        currentCode, currentEnterTimeMs, lastTimeMs));
            }
            if (isSwipe(swipeThresholdPx)) {
                return MultiSwipeInputToken.swipe(
                        pointerId,
                        slot,
                        new ArrayList<>(points),
                        pathLengthPx,
                        startTimeMs,
                        lastTimeMs,
                        snapshotContacts,
                        new ArrayList<>(dwells),
                        false,
                        false
                );
            }
            if (startedOnLetter && startKeyChar != '\0') {
                return MultiSwipeInputToken.tap(
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

    /**
     * Shared buffer state for retaining short prefix strokes and rolling back Stock Gboard's
     * committed prefix if a subsequent stroke promotes into MultiSwipe.
     */
    static abstract class PendingStrokeBufferBase {
        final List<MultiSwipeInputToken> tokens = new ArrayList<>();
        String baselineText = null;
        long lastUpTimeMs = 0L;

        void clear() {
            tokens.clear();
            baselineText = null;
            lastUpTimeMs = 0L;
        }

        protected abstract boolean isValidTypedPrefix(String typedPrefix);

        protected final void validateGapAndPrefix(
                String snapshotNow,
                boolean downOnLetter,
                long eventTimeMs,
                long maxGapMs
        ) {
            if (tokens.isEmpty()) return;
            long gapMs = (lastUpTimeMs > 0L) ? (eventTimeMs - lastUpTimeMs) : Long.MAX_VALUE;
            if (!downOnLetter || gapMs < 0L || gapMs > maxGapMs) {
                clear();
                return;
            }
            String typedPrefix = extractTypedPrefixSinceBaseline(baselineText, snapshotNow);
            if (!isValidTypedPrefix(typedPrefix)) {
                clear();
            }
        }

        protected final boolean appendTokensWithinCap(
                List<MultiSwipeInputToken> additions,
                String baselineAtDown,
                long eventTimeMs,
                int maxTokens
        ) {
            if (additions == null || additions.isEmpty() || baselineAtDown == null) {
                clear();
                return false;
            }
            if (tokens.isEmpty()) {
                if (additions.size() > maxTokens) {
                    clear();
                    return false;
                }
                baselineText = baselineAtDown;
            } else if (tokens.size() + additions.size() > maxTokens) {
                clear();
                return false;
            }
            tokens.addAll(additions);
            lastUpTimeMs = eventTimeMs;
            return true;
        }

        String promoteInto(List<MultiSwipeInputToken> destWordTokens, String textSnapshotAtActionDown) {
            String restoredSnapshot = peekPromotedBaseline(destWordTokens, textSnapshotAtActionDown);
            clear();
            return restoredSnapshot;
        }

        String peekPromotedBaseline(List<MultiSwipeInputToken> destWordTokens, String textSnapshotAtActionDown) {
            if (tokens.isEmpty()) return textSnapshotAtActionDown;
            String restoredSnapshot = textSnapshotAtActionDown;
            String typedPrefix = extractTypedPrefixSinceBaseline(baselineText, textSnapshotAtActionDown);
            if (isValidTypedPrefix(typedPrefix)) {
                destWordTokens.addAll(tokens);
                restoredSnapshot = baselineText;
            }
            return restoredSnapshot;
        }
    }

    /**
     * Passive pre-swipe tap buffer: retains tap-only prefix strokes and their pre-tap baseline
     * so a subsequent MultiSwipe stroke in the same word rolls back Stock Gboard's prefix.
     */
    static class PassiveTapBuffer extends PendingStrokeBufferBase {
        boolean hadMultiTouch = false;

        @Override
        void clear() {
            super.clear();
            hadMultiTouch = false;
        }

        @Override
        protected boolean isValidTypedPrefix(String typedPrefix) {
            return isPureLetterPrefix(typedPrefix, tokens.size());
        }

        void validateOnActionDown(
                String snapshotNow,
                boolean downOnLetter,
                long eventTimeMs,
                boolean autoSpaceEnabled
        ) {
            validateOnActionDown(
                    snapshotNow,
                    downOnLetter,
                    eventTimeMs,
                    (int) MAX_PASSIVE_TAP_GAP_MS,
                    autoSpaceEnabled);
        }

        void validateOnActionDown(
                String snapshotNow,
                boolean downOnLetter,
                long eventTimeMs,
                int tapToMultiSwipeDelayMs,
                boolean autoSpaceEnabled
        ) {
            if (autoSpaceEnabled && tapToMultiSwipeDelayMs <= 0) {
                clear();
                return;
            }
            long maxGap = autoSpaceEnabled ? tapToMultiSwipeDelayMs : Long.MAX_VALUE;
            validateGapAndPrefix(snapshotNow, downOnLetter, eventTimeMs, maxGap);
        }

        boolean canPromoteMultiTouchSwipe(List<MultiSwipeInputToken> completedTokens) {
            return hadMultiTouch
                    && !tokens.isEmpty()
                    && completedTokens != null
                    && !completedTokens.isEmpty();
        }

        void recordStroke(
                List<MultiSwipeInputToken> completedTokens,
                boolean hasSwipe,
                boolean sawNonLetter,
                boolean strokeHadMultiTouch,
                String tapBaselineAtDown,
                long eventTimeMs,
                boolean autoSpaceEnabled
        ) {
            recordStroke(
                    completedTokens,
                    hasSwipe,
                    sawNonLetter,
                    strokeHadMultiTouch,
                    tapBaselineAtDown,
                    eventTimeMs,
                    (int) MAX_PASSIVE_TAP_GAP_MS,
                    autoSpaceEnabled);
        }

        void recordStroke(
                List<MultiSwipeInputToken> completedTokens,
                boolean hasSwipe,
                boolean sawNonLetter,
                boolean strokeHadMultiTouch,
                String tapBaselineAtDown,
                long eventTimeMs,
                int tapToMultiSwipeDelayMs,
                boolean autoSpaceEnabled
        ) {
            if (autoSpaceEnabled && tapToMultiSwipeDelayMs <= 0) {
                clear();
                return;
            }
            List<MultiSwipeInputToken> tapOnlyTokens = (!hasSwipe && !sawNonLetter)
                    ? collectPureTapTokens(completedTokens)
                    : null;
            int maxTokens = autoSpaceEnabled
                    ? MAX_PASSIVE_TAP_TOKENS
                    : KineticaConstants.MAX_WORD_LEN;
            boolean hadExisting = !tokens.isEmpty();
            if (!appendTokensWithinCap(tapOnlyTokens, tapBaselineAtDown, eventTimeMs, maxTokens)) {
                return;
            }
            hadMultiTouch = hadExisting ? (hadMultiTouch | strokeHadMultiTouch) : strokeHadMultiTouch;
        }
    }

    /**
     * Buffer retaining sequential single-touch strokes so subsequent strokes within the combine
     * timeout (or indefinitely while autoSpaceEnabled == false) promote into MultiSwipe.
     */
    static class SingleTouchCombineBuffer extends PendingStrokeBufferBase {
        boolean hasSwipeToken = false;

        @Override
        void clear() {
            super.clear();
            hasSwipeToken = false;
        }

        @Override
        protected boolean isValidTypedPrefix(String typedPrefix) {
            return isCombinePrefix(typedPrefix);
        }

        void validateOnActionDown(
                String snapshotNow,
                boolean downOnLetter,
                long eventTimeMs,
                int combineDelayMs,
                boolean autoSpaceEnabled
        ) {
            if (autoSpaceEnabled && combineDelayMs <= 0) {
                clear();
                return;
            }
            long maxGap = autoSpaceEnabled ? combineDelayMs : Long.MAX_VALUE;
            validateGapAndPrefix(snapshotNow, downOnLetter, eventTimeMs, maxGap);
        }

        boolean canPromoteWith(List<MultiSwipeInputToken> completedTokens, boolean sawNonLetter) {
            if (sawNonLetter || tokens.isEmpty() || completedTokens == null || completedTokens.isEmpty()) {
                return false;
            }
            if (hasSwipeToken) return true;
            for (int i = 0; i < completedTokens.size(); i++) {
                if (completedTokens.get(i).kind == MultiSwipeInputToken.Kind.SWIPE) {
                    return true;
                }
            }
            return false;
        }

        void recordStroke(
                List<MultiSwipeInputToken> completedTokens,
                boolean strokeHasSwipe,
                boolean sawNonLetter,
                String tapBaselineAtDown,
                long eventTimeMs,
                int combineDelayMs,
                boolean autoSpaceEnabled
        ) {
            if ((autoSpaceEnabled && combineDelayMs <= 0) || sawNonLetter) {
                clear();
                return;
            }
            int maxTokens = autoSpaceEnabled
                    ? MAX_PASSIVE_TAP_TOKENS
                    : KineticaConstants.MAX_WORD_LEN;
            boolean hadExisting = !tokens.isEmpty();
            if (!appendTokensWithinCap(completedTokens, tapBaselineAtDown, eventTimeMs, maxTokens)) {
                return;
            }
            hasSwipeToken = hadExisting ? (hasSwipeToken | strokeHasSwipe) : strokeHasSwipe;
        }
    }

    final MultiSwipeGeometry geometry = new MultiSwipeGeometry();
    final Map<Integer, PointerTrack> activePointers = new LinkedHashMap<>();
    final List<MultiSwipeInputToken> completedTokens = new ArrayList<>(4);
    final List<MultiSwipeInputToken> wordTokens = new ArrayList<>(16);
    final int[] pointerBySlot = new int[] { -1, -1 };
    final Map<Integer, Integer> slotByPointer = new HashMap<>(4);
    final PassiveTapBuffer passiveTapBuffer = new PassiveTapBuffer();
    final SingleTouchCombineBuffer singleTouchCombineBuffer = new SingleTouchCombineBuffer();

    boolean sawMultiTouch = false;
    boolean multiswipeTakeoverActive = false;
    boolean multiswipeWordActive = false;
    boolean sawNonLetterThisSession = false;
    String textSnapshotAtActionDown = null;
    boolean leadingSpaceEvaluated = false;
    boolean suppressNextLeadingSpace = false;
    boolean pendingBackspaceCheck = false;
    String suppressLeadingSpaceBaseline = null;
    String lastObservedTextBeforeCursor = null;
    String expectedSnapshotAfterPreview = null;
    String lastCommittedWord = null;
    long lastTouchUpTimeMs = 0L;
    long lastActionDownTimeMs = 0L;
    boolean lastDownOnLetterArea = false;
    int restingDroppedThisSession = 0;
    int lastPromotedPassiveCount = 0;
    boolean stockSwipeAutospacePending = false;

    static final class LastCommittedWordUndo {
        final String expectedSnapshot;
        final int deleteCharCount;

        LastCommittedWordUndo(String expectedSnapshot, int deleteCharCount) {
            this.expectedSnapshot = expectedSnapshot;
            this.deleteCharCount = deleteCharCount;
        }
    }

    String currentComposingWord = null;
    List<String> currentAlternativeWords = null;
    String promotedTapPrefix = null;
    boolean autospaceInserted = false;
    String autospaceSnapshot = null;
    boolean autospaceTimerPending = false;
    long autospaceFirstSpaceTapDownTimeMs = 0L;
    boolean pendingLeadingSpaceBeforeNextWord = false;
    LastCommittedWordUndo lastCommittedWordUndo = null;
    boolean pendingBackspaceTap = false;
    float backspaceDownX = 0f;
    float backspaceDownY = 0f;
    long backspaceDownTimeMs = 0L;
    long suppressedBasicUpEventTimeMs = -1L;

    void resetGestureState() {
        activePointers.clear();
        completedTokens.clear();
        pointerBySlot[0] = -1;
        pointerBySlot[1] = -1;
        slotByPointer.clear();
        sawMultiTouch = false;
        multiswipeTakeoverActive = false;
        sawNonLetterThisSession = false;
        restingDroppedThisSession = 0;
    }

    void clearWordBuffer(LifecycleCallbacks callbacks) {
        if (callbacks != null && autospaceTimerPending) {
            callbacks.onCancelAutospaceTimer();
        }
        if (callbacks != null && stockSwipeAutospacePending) {
            callbacks.onCancelStockSwipeAutospaceTimer();
        }
        autospaceTimerPending = false;
        stockSwipeAutospacePending = false;
        wordTokens.clear();
        currentComposingWord = null;
        currentAlternativeWords = null;
        promotedTapPrefix = null;
        expectedSnapshotAfterPreview = null;
        multiswipeWordActive = false;
        leadingSpaceEvaluated = false;
        lastTouchUpTimeMs = 0L;
        lastPromotedPassiveCount = 0;
    }

    float multiSwipeThresholdPx() {
        return Math.max(MULTI_TOUCH_SWIPE_THRESHOLD_PX, geometry.keyWidthPx * 0.24f);
    }

    float backspaceSwipeThresholdPx() {
        return Math.max(MULTI_TOUCH_SWIPE_THRESHOLD_PX, geometry.keyWidthPx * 0.35f);
    }

    PointerTrack registerPointerDown(int pointerId, float x, float y, long timeMs) {
        float midX = geometry.isValid() ? geometry.midlinePx() : 540f;
        int slot = (x < midX) ? 0 : 1;
        int other = 1 - slot;
        int currentOwner = pointerBySlot[slot];
        if (currentOwner != -1 && activePointers.containsKey(currentOwner)) {
            slot = other;
        }
        slotByPointer.put(pointerId, slot);
        pointerBySlot[slot] = pointerId;

        boolean onLetter = geometry.isOnLetterArea(x, y);
        if (!onLetter) {
            sawNonLetterThisSession = true;
        }
        KeySpec nearest = geometry.nearestLetterKey(x, y);
        char keyChar = nearest != null ? nearest.character : '\0';
        PointerTrack track = new PointerTrack(
                pointerId, x, y, timeMs, keyChar, onLetter, slot, geometry);
        if (!activePointers.isEmpty()) {
            track.overlapped = true;
            for (PointerTrack existing : activePointers.values()) {
                existing.overlapped = true;
            }
        }
        activePointers.put(pointerId, track);
        return track;
    }

    void releasePointerSlot(int pointerId) {
        Integer slotObj = slotByPointer.remove(pointerId);
        if (slotObj != null) {
            int slot = slotObj;
            if (slot >= 0 && slot < pointerBySlot.length && pointerBySlot[slot] == pointerId) {
                pointerBySlot[slot] = -1;
            }
        }
    }

    boolean hasAnySwipingPointer(float swipeThreshold) {
        for (PointerTrack t : activePointers.values()) {
            if (t.startedOnLetter && t.isSwipe(swipeThreshold)) {
                return true;
            }
        }
        for (int i = 0; i < completedTokens.size(); i++) {
            if (completedTokens.get(i).kind == MultiSwipeInputToken.Kind.SWIPE) {
                return true;
            }
        }
        return false;
    }

    boolean isLivePreviewEligible(float swipeThresholdPx) {
        if (multiswipeTakeoverActive) {
            return true;
        }
        if (sawNonLetterThisSession) {
            return false;
        }
        if (multiswipeWordActive) {
            return hasAnySwipingPointer(swipeThresholdPx) || containsSwipeToken(wordTokens);
        }
        return !singleTouchCombineBuffer.tokens.isEmpty()
                && (singleTouchCombineBuffer.hasSwipeToken || hasAnySwipingPointer(swipeThresholdPx));
    }

    /**
     * Builds an immutable token snapshot of the current in-progress MultiSwipe word for throttled
     * live-preview decoding during ACTION_MOVE, or null if no SWIPE token is present yet.
     */
    List<MultiSwipeInputToken> buildLiveSnapshotTokens() {
        float swipeThreshold = multiSwipeThresholdPx();
        List<MultiSwipeInputToken> out = new ArrayList<>(
                wordTokens.size() + completedTokens.size() + activePointers.size() + 4);
        if (!multiswipeWordActive && !sawNonLetterThisSession) {
            if (!singleTouchCombineBuffer.tokens.isEmpty()) {
                out.addAll(singleTouchCombineBuffer.tokens);
            } else if (!passiveTapBuffer.tokens.isEmpty()) {
                out.addAll(passiveTapBuffer.tokens);
            }
        }
        out.addAll(wordTokens);
        out.addAll(completedTokens);
        for (PointerTrack track : activePointers.values()) {
            if (track.isRestingFinger(swipeThreshold)) {
                continue;
            }
            MultiSwipeInputToken tok = track.toSnapshotToken(swipeThreshold);
            if (tok != null) {
                out.add(tok);
            }
        }
        return containsSwipeToken(out) ? out : null;
    }

    String formatWordForCurrentContext(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        if (isAttachingAfterDeletion(textSnapshotAtActionDown, false, suppressNextLeadingSpace)) {
            return lowercaseFirstChar(word);
        }
        if (shouldCapitalizeFirstLetter(textSnapshotAtActionDown)) {
            return capitalizeFirstChar(word);
        }
        return word;
    }

    boolean onActionDown(
            int pointerId,
            float x,
            float y,
            long eventTimeMs,
            SettingsSnapshot settings,
            EditorAdapter editor,
            LifecycleCallbacks callbacks
    ) {
        lastActionDownTimeMs = eventTimeMs;
        boolean downOnLetter = geometry.isOnLetterArea(x, y);
        lastDownOnLetterArea = downOnLetter;

        if (callbacks != null) {
            callbacks.onPointerFinished(pointerId, false, false);
        }

        boolean downOnSpace = geometry.isSpaceKeyAt(x, y);
        String snapshotNow = (editor != null) ? editor.readTextBeforeCursorSnapshot() : null;

        // MS-12: Retract auto-inserted trailing space before hugging punctuation (preserving
        // autospace across non-character modifier/layout-switch keys like ?123/Shift while the
        // editor text is unchanged), or track Spacebar double-tap-to-period on ACTION_DOWN.
        if (autospaceInserted && autospaceSnapshot != null && snapshotNow != null
                && !snapshotNow.equals(autospaceSnapshot)) {
            autospaceInserted = false;
            autospaceSnapshot = null;
        }
        if (autospaceInserted) {
            KeySpec hit = geometry.findKeyAt(x, y);
            if (hit == null && geometry.isNonCharControlKeyAt(x, y)) {
                // Modifier / layout-switch key (e.g. ?123, Shift): preserve autospaceInserted
                // so a subsequent punctuation key like ':' on the symbols layout still retracts.
                autospaceFirstSpaceTapDownTimeMs = 0L;
            } else {
                if (hit == null) {
                    hit = geometry.nearestAnyKey(x, y);
                }
                if (hit != null && geometry.isPunctuationChar(hit.character) && editor != null) {
                    String before = editor.readCharBeforeCursor();
                    if (before != null && !before.isEmpty() && before.charAt(before.length() - 1) == ' ') {
                        editor.deleteCharsBeforeCursor(1);
                    }
                    autospaceFirstSpaceTapDownTimeMs = 0L;
                    autospaceInserted = false;
                    autospaceSnapshot = null;
                    snapshotNow = editor.readTextBeforeCursorSnapshot();
                } else {
                    autospaceFirstSpaceTapDownTimeMs = (downOnSpace && activePointers.isEmpty()) ? eventTimeMs : 0L;
                    autospaceInserted = false;
                    autospaceSnapshot = null;
                }
            }
        } else {
            if (autospaceFirstSpaceTapDownTimeMs > 0L
                    && downOnSpace
                    && activePointers.isEmpty()
                    && settings.doubleSpacePeriodEnabled
                    && (eventTimeMs - autospaceFirstSpaceTapDownTimeMs) <= DOUBLE_SPACE_PERIOD_TIMEOUT_MS
                    && editor != null) {
                String before = snapshotNow;
                if (before != null && before.length() >= 3 && before.endsWith("  ")) {
                    char prev = before.charAt(before.length() - 3);
                    if (!Character.isWhitespace(prev) && prev != '.') {
                        editor.deleteCharsBeforeCursor(2);
                        editor.commitText(".");
                        snapshotNow = editor.readTextBeforeCursorSnapshot();
                    }
                }
            }
            autospaceFirstSpaceTapDownTimeMs = 0L;
        }
        boolean cursorNavigatedAway = multiswipeWordActive
                && expectedSnapshotAfterPreview != null
                && snapshotNow != null
                && !snapshotNow.equals(expectedSnapshotAfterPreview);

        long timeSinceLastUp = (lastTouchUpTimeMs > 0L)
                ? (eventTimeMs - lastTouchUpTimeMs)
                : Long.MAX_VALUE;
        boolean wasAutospacing = autospaceTimerPending;
        if (callbacks != null) {
            callbacks.onBufferTimerUpdated(0L);
            if (autospaceTimerPending) {
                callbacks.onCancelAutospaceTimer();
            }
            if (stockSwipeAutospacePending) {
                callbacks.onCancelStockSwipeAutospaceTimer();
            }
        }
        autospaceTimerPending = false;
        stockSwipeAutospacePending = false;

        boolean withinTimeout = !settings.autoSpaceEnabled
                || wasAutospacing
                || (timeSinceLastUp <= (settings.autoSpaceDelayMs + 80L) && !wordTokens.isEmpty());
        boolean isWordContinuing = downOnLetter
                && multiswipeWordActive
                && !cursorNavigatedAway
                && (withinTimeout || !activePointers.isEmpty());

        boolean hadMultiSwipeWord = multiswipeWordActive;
        String endedComposingWord = currentComposingWord;
        List<String> endedAlternatives = currentAlternativeWords;
        String endedBaseline = textSnapshotAtActionDown;

        boolean armedLeadingSpace = pendingLeadingSpaceBeforeNextWord;
        boolean snapshotUnchanged = lastObservedTextBeforeCursor == null
                || snapshotNow == null
                || snapshotNow.equals(lastObservedTextBeforeCursor);
        boolean downOnModifier = !downOnLetter
                && !downOnSpace
                && !geometry.isBackspaceKeyAt(x, y)
                && !geometry.isPunctuationAt(x, y)
                && geometry.findKeyAt(x, y) == null
                && geometry.isNonCharControlKeyAt(x, y);
        if (!downOnModifier || !snapshotUnchanged) {
            pendingLeadingSpaceBeforeNextWord = false;
        }
        if (armedLeadingSpace
                && downOnLetter
                && !isWordContinuing
                && settings.autoSpaceEnabled
                && settings.autoSpaceBeforeWord
                && !suppressNextLeadingSpace
                && editor != null
                && snapshotNow != null
                && !snapshotNow.isEmpty()
                && Character.isLetterOrDigit(snapshotNow.charAt(snapshotNow.length() - 1))) {
            editor.commitText(" ");
            snapshotNow = editor.readTextBeforeCursorSnapshot();
            if (snapshotNow != null) {
                lastObservedTextBeforeCursor = snapshotNow;
            }
        }

        resetGestureState();

        boolean downOnBackspace = !downOnLetter && activePointers.isEmpty() && geometry.isBackspaceKeyAt(x, y);
        boolean canHandleBackspace = downOnBackspace
                && ((multiswipeWordActive && currentComposingWord != null
                        && !currentComposingWord.isEmpty() && !cursorNavigatedAway)
                    || (!multiswipeWordActive && lastCommittedWordUndo != null
                        && snapshotNow != null
                        && snapshotNow.equals(lastCommittedWordUndo.expectedSnapshot)));

        if (canHandleBackspace) {
            pendingBackspaceTap = true;
            backspaceDownX = x;
            backspaceDownY = y;
            backspaceDownTimeMs = eventTimeMs;
            updateBackspaceCompoundStateOnActionDown(snapshotNow, downOnLetter, x, y);
            lastObservedTextBeforeCursor = snapshotNow;
            if (callbacks != null) {
                callbacks.onTrailAccentChanged(multiswipeWordActive);
                if (!multiswipeWordActive) {
                    callbacks.onNativeTopBarBlankChanged(false);
                }
            }
            registerPointerDown(pointerId, x, y, eventTimeMs);
            return false;
        }

        pendingBackspaceTap = false;
        lastCommittedWordUndo = null;

        if (!isWordContinuing) {
            boolean hitSpaceOrPunct = !downOnLetter
                    && (geometry.isSpaceKeyAt(x, y) || geometry.isPunctuationAt(x, y));

            if (hadMultiSwipeWord && endedComposingWord != null && !endedComposingWord.isEmpty()
                    && hitSpaceOrPunct && callbacks != null) {
                String prevWord = extractPreviousWord(endedBaseline, snapshotNow, lastCommittedWord);
                lastCommittedWord = endedComposingWord;
                callbacks.onWordCommitted(endedComposingWord, false, prevWord, endedAlternatives);
            }

            clearWordBuffer(callbacks);
            updateBackspaceCompoundStateOnActionDown(snapshotNow, downOnLetter, x, y);

            lastObservedTextBeforeCursor = snapshotNow;
            textSnapshotAtActionDown = snapshotNow;
            passiveTapBuffer.validateOnActionDown(
                    snapshotNow,
                    downOnLetter,
                    eventTimeMs,
                    settings.tapToMultiSwipeDelayMs,
                    settings.autoSpaceEnabled);
            singleTouchCombineBuffer.validateOnActionDown(
                    snapshotNow,
                    downOnLetter,
                    eventTimeMs,
                    settings.singleTouchCombineDelayMs,
                    settings.autoSpaceEnabled);
        }

        if (callbacks != null) {
            callbacks.onTrailAccentChanged(isWordContinuing);
            callbacks.onNativeTopBarBlankChanged(isWordContinuing);
        }
        registerPointerDown(pointerId, x, y, eventTimeMs);
        return isWordContinuing;
    }

    /**
     * Updates MS-13 Backspace-gated compound attachment state.
     * Only deletions immediately preceded by a non-Space, non-punctuation keyboard key
     * (Backspace) latch {@code suppressNextLeadingSpace}. Cursor repositioning in the textbox
     * or Spacebar cursor swipes never arm Backspace detection.
     */
    private void updateBackspaceCompoundStateOnActionDown(
            String snapshotNow,
            boolean downOnLetter,
            float x,
            float y
    ) {
        boolean hitSpace = geometry.isSpaceKeyAt(x, y);
        boolean hitPunct = geometry.isPunctuationAt(x, y);

        if (hitSpace || hitPunct) {
            pendingBackspaceCheck = false;
            suppressNextLeadingSpace = false;
            suppressLeadingSpaceBaseline = null;
            return;
        }

        boolean detectedDeletion = pendingBackspaceCheck
                && didDeleteTrailingChars(lastObservedTextBeforeCursor, snapshotNow);
        if (detectedDeletion) {
            suppressNextLeadingSpace = true;
            suppressLeadingSpaceBaseline = snapshotNow;
        } else if (suppressNextLeadingSpace) {
            boolean stillValid = snapshotNow != null
                    && !snapshotNow.isEmpty()
                    && Character.isLetterOrDigit(snapshotNow.charAt(snapshotNow.length() - 1))
                    && snapshotNow.equals(suppressLeadingSpaceBaseline);
            if (!stillValid) {
                suppressNextLeadingSpace = false;
                suppressLeadingSpaceBaseline = null;
            }
        }

        // Arm Backspace detection only for non-letter, non-Space, non-punctuation keys.
        // Because Shift also lives in nonLetterKeys, Shift taps neither clear an already-latched
        // compound flag nor emit deletions, allowing Backspace -> Shift -> Word.
        pendingBackspaceCheck = !downOnLetter;
    }

    void onPointerDown(int pointerId, float x, float y, long eventTimeMs) {
        pendingBackspaceTap = false;
        registerPointerDown(pointerId, x, y, eventTimeMs);
        sawMultiTouch = true;
    }

    void onPointerUp(
            int pointerId,
            float x,
            float y,
            long eventTimeMs,
            boolean hasCurrentCoords,
            LifecycleCallbacks callbacks
    ) {
        float swipeThreshold = multiSwipeThresholdPx();
        PointerTrack track = activePointers.remove(pointerId);
        boolean isSwipe = false;
        if (track != null) {
            if (hasCurrentCoords) {
                track.addPoint(x, y, eventTimeMs, geometry);
            }
            track.finished = true;
            if (!track.isRestingFinger(swipeThreshold)) {
                isSwipe = track.isSwipe(swipeThreshold);
                MultiSwipeInputToken token = track.toToken(swipeThreshold);
                if (token != null) {
                    completedTokens.add(token);
                }
            } else {
                restingDroppedThisSession++;
            }
        }
        releasePointerSlot(pointerId);
        if (callbacks != null) {
            callbacks.onPointerFinished(pointerId, isSwipe, true);
        }
    }

    void onActionUp(
            long eventTimeMs,
            SettingsSnapshot settings,
            EditorAdapter editor,
            DecodePort decoder,
            LifecycleCallbacks callbacks
    ) {
        if (pendingBackspaceTap || suppressedBasicUpEventTimeMs == eventTimeMs) {
            PointerTrack track = activePointers.isEmpty()
                    ? null : activePointers.values().iterator().next();
            float upX = (track != null) ? track.lastX : backspaceDownX;
            float upY = (track != null) ? track.lastY : backspaceDownY;
            if (tryConsumePendingBackspaceTap(
                    eventTimeMs, upX, upY, settings, editor, decoder, callbacks)) {
                resetGestureState();
                return;
            }
        }
        float swipeThreshold = multiSwipeThresholdPx();
        boolean isMultiSwipeGesture = sawMultiTouch
                || multiswipeTakeoverActive
                || (multiswipeWordActive && !wordTokens.isEmpty());
        boolean anySwipeThisSession = false;

        for (PointerTrack track : activePointers.values()) {
            track.finished = true;
            if (track.isRestingFinger(swipeThreshold)) {
                restingDroppedThisSession++;
                if (isMultiSwipeGesture && callbacks != null) {
                    callbacks.onPointerFinished(track.pointerId, false, true);
                }
                continue;
            }
            boolean isSwipe = track.isSwipe(swipeThreshold);
            if (isSwipe) {
                anySwipeThisSession = true;
            }
            MultiSwipeInputToken token = track.toToken(swipeThreshold);
            if (token != null) {
                completedTokens.add(token);
            }
            if (isMultiSwipeGesture && callbacks != null) {
                callbacks.onPointerFinished(track.pointerId, isSwipe, true);
            }
        }
        activePointers.clear();
        lastTouchUpTimeMs = eventTimeMs;

        boolean hasSwipe = anySwipeThisSession || containsSwipeToken(completedTokens) || containsSwipeToken(wordTokens);

        if (isMultiSwipeGesture && !hasSwipe && callbacks != null) {
            callbacks.onClearTrailManagerActive();
        }

        boolean promoteMultiTouchPassive = !multiswipeWordActive
                && passiveTapBuffer.canPromoteMultiTouchSwipe(completedTokens);
        boolean promoteSingleTouchCombine = !multiswipeWordActive
                && (!settings.autoSpaceEnabled || settings.singleTouchCombineDelayMs > 0)
                && singleTouchCombineBuffer.canPromoteWith(completedTokens, sawNonLetterThisSession);
        boolean shouldDecodeNow = ((isMultiSwipeGesture || promoteMultiTouchPassive) && hasSwipe)
                || promoteSingleTouchCombine;

        if (shouldDecodeNow) {
            List<WordCandidate> preDecodedCandidates = null;
            // MS-02 / MS-03: With Auto-Space ON, probe sequential 1-finger combinations first so a
            // 0-candidate miss lets rapid stock 1-finger typing proceed untouched.
            // With Auto-Space OFF, no Space means same word, so MultiSwipe takes over and retains
            // the existing match/prefix plus all tokens if 0 candidates match.
            if (settings.autoSpaceEnabled
                    && !multiswipeWordActive
                    && promoteSingleTouchCombine
                    && !promoteMultiTouchPassive) {
                List<MultiSwipeInputToken> probeWordTokens = new ArrayList<>();
                String probeBaseline = singleTouchCombineBuffer.peekPromotedBaseline(
                        probeWordTokens, textSnapshotAtActionDown);
                probeWordTokens.addAll(completedTokens);
                String probePrevWord = extractPreviousWord(
                        probeBaseline,
                        (editor != null) ? editor.readTextBeforeCursorSnapshot() : null,
                        lastCommittedWord);
                preDecodedCandidates = (decoder != null)
                        ? decoder.decode(probeWordTokens, probePrevWord)
                        : null;
                if (preDecodedCandidates == null || preDecodedCandidates.isEmpty()) {
                    singleTouchCombineBuffer.clear();
                    handleStockFallback(
                            eventTimeMs,
                            settings,
                            editor,
                            hasSwipe,
                            true,
                            callbacks,
                            decoder != null ? preDecodedCandidates : null,
                            probeWordTokens,
                            probePrevWord,
                            probeBaseline);
                    return;
                }
            }

            boolean wasMultiSwipeWordActive = multiswipeWordActive;
            String snapshotBeforePromotion = textSnapshotAtActionDown;
            multiswipeTakeoverActive = true;
            multiswipeWordActive = true;
            if (callbacks != null) {
                callbacks.onTrailAccentChanged(true);
                callbacks.onNativeTopBarBlankChanged(true);
                callbacks.onTakeoverActivatedOnFinish();
            }

            if (!wasMultiSwipeWordActive) {
                int beforePromoted = wordTokens.size();
                if (promoteSingleTouchCombine && !promoteMultiTouchPassive) {
                    String rawPrefix = extractTypedPrefixSinceBaseline(
                            singleTouchCombineBuffer.baselineText, textSnapshotAtActionDown);
                    textSnapshotAtActionDown = singleTouchCombineBuffer.promoteInto(
                            wordTokens, textSnapshotAtActionDown);
                    promotedTapPrefix = (rawPrefix != null && !containsSwipeToken(wordTokens))
                            ? rawPrefix : null;
                    passiveTapBuffer.clear();
                } else {
                    String rawPrefix = extractTypedPrefixSinceBaseline(
                            passiveTapBuffer.baselineText, textSnapshotAtActionDown);
                    textSnapshotAtActionDown = passiveTapBuffer.promoteInto(
                            wordTokens, textSnapshotAtActionDown);
                    promotedTapPrefix = (rawPrefix != null && !containsSwipeToken(wordTokens))
                            ? rawPrefix : null;
                    singleTouchCombineBuffer.clear();
                }
                lastPromotedPassiveCount += Math.max(0, wordTokens.size() - beforePromoted);
            } else {
                passiveTapBuffer.clear();
                singleTouchCombineBuffer.clear();
            }

            wordTokens.addAll(completedTokens);
            completedTokens.clear();

            decodeAndPreview(
                    settings,
                    editor,
                    decoder,
                    callbacks,
                    preDecodedCandidates,
                    snapshotBeforePromotion);

            if (settings.autoSpaceEnabled) {
                if (callbacks != null) {
                    callbacks.onCancelAutospaceTimer();
                }
                autospaceTimerPending = true;
                if (callbacks != null) {
                    callbacks.onScheduleAutospaceTimer(settings.autoSpaceDelayMs);
                }
            } else {
                autospaceTimerPending = false;
            }
        } else {
            handleStockFallback(
                    eventTimeMs, settings, editor, hasSwipe, false, callbacks, null, null, null, null);
        }
    }

    private void handleStockFallback(
            long eventTimeMs,
            SettingsSnapshot settings,
            EditorAdapter editor,
            boolean hasSwipe,
            boolean failedSingleCombine,
            LifecycleCallbacks callbacks,
            List<WordCandidate> failedCandidates,
            List<MultiSwipeInputToken> failedTokens,
            String failedPrevWord,
            String failedBaseline
    ) {
        if (callbacks != null) {
            callbacks.onTrailAccentChanged(false);
            callbacks.onNativeTopBarBlankChanged(false);
        }
        String tapBaselineAtDown = textSnapshotAtActionDown;
        boolean strokeHadMultiTouch = sawMultiTouch;
        boolean sawNonLetter = sawNonLetterThisSession;
        int restingDropped = restingDroppedThisSession;
        List<MultiSwipeInputToken> completedCopy = new ArrayList<>(completedTokens);
        clearWordBuffer(callbacks);
        completedTokens.clear();
        multiswipeTakeoverActive = false;

        if (failedSingleCombine && callbacks != null && failedTokens != null) {
            callbacks.onWordDecoded(
                    null,
                    null,
                    failedTokens,
                    false,
                    failedPrevWord,
                    failedBaseline,
                    0,
                    0,
                    restingDropped,
                    failedCandidates);
        }

        passiveTapBuffer.recordStroke(
                completedCopy,
                hasSwipe,
                sawNonLetter,
                strokeHadMultiTouch,
                tapBaselineAtDown,
                eventTimeMs,
                settings.tapToMultiSwipeDelayMs,
                settings.autoSpaceEnabled);
        singleTouchCombineBuffer.recordStroke(
                completedCopy,
                hasSwipe,
                sawNonLetter,
                tapBaselineAtDown,
                eventTimeMs,
                settings.singleTouchCombineDelayMs,
                settings.autoSpaceEnabled);

        boolean scheduleStockSwipeAutospace = settings.autoSpaceEnabled
                && !sawNonLetter
                && !strokeHadMultiTouch
                && (hasSwipe || (!singleTouchCombineBuffer.tokens.isEmpty() && singleTouchCombineBuffer.hasSwipeToken));
        if (scheduleStockSwipeAutospace && settings.autoSpaceBeforeWord
                && (settings.singleTouchCombineDelayMs <= 0 || singleTouchCombineBuffer.tokens.isEmpty())) {
            pendingLeadingSpaceBeforeNextWord = true;
            if (callbacks != null) {
                callbacks.onBufferTimerUpdated(0L);
            }
        } else if (scheduleStockSwipeAutospace && callbacks != null) {
            long stockDelayMs = (settings.singleTouchCombineDelayMs > 0 && !singleTouchCombineBuffer.tokens.isEmpty())
                    ? settings.singleTouchCombineDelayMs
                    : settings.autoSpaceDelayMs;
            stockSwipeAutospacePending = true;
            callbacks.onScheduleStockSwipeAutospaceTimer(stockDelayMs);
            callbacks.onBufferTimerUpdated(stockDelayMs);
        } else if (callbacks != null) {
            callbacks.onBufferTimerUpdated(0L);
        }

        if (callbacks != null && (hasSwipe || strokeHadMultiTouch || restingDropped > 0
                || !passiveTapBuffer.tokens.isEmpty())) {
            String reason = !hasSwipe
                    ? (strokeHadMultiTouch ? "rollover_no_swipe" : "tap_only")
                    : "single_touch_swipe";
            callbacks.onSkippedGesture(
                    reason,
                    hasSwipe,
                    strokeHadMultiTouch,
                    passiveTapBuffer.tokens.size(),
                    restingDropped,
                    tapBaselineAtDown,
                    completedCopy);
        }
    }

    private void decodeAndPreview(
            SettingsSnapshot settings,
            EditorAdapter editor,
            DecodePort decoder,
            LifecycleCallbacks callbacks,
            List<WordCandidate> preDecodedCandidates,
            String snapshotBeforePromotion
    ) {
        if (decoder == null && preDecodedCandidates == null) return;
        String liveSnapshot = (editor != null) ? editor.readTextBeforeCursorSnapshot() : null;
        String prevWord = extractPreviousWord(textSnapshotAtActionDown, liveSnapshot, lastCommittedWord);
        List<WordCandidate> candidates = (preDecodedCandidates != null)
                ? preDecodedCandidates
                : decoder.decode(wordTokens, prevWord);
        if (candidates == null || candidates.isEmpty()) {
            if (currentComposingWord == null && snapshotBeforePromotion != null) {
                String retainedPrefix = extractTypedPrefixSinceBaseline(
                        textSnapshotAtActionDown, snapshotBeforePromotion);
                if (retainedPrefix != null && !retainedPrefix.isEmpty()) {
                    currentComposingWord = retainedPrefix;
                    leadingSpaceEvaluated = true;
                }
                expectedSnapshotAfterPreview = snapshotBeforePromotion;
                lastObservedTextBeforeCursor = snapshotBeforePromotion;
            }
            if (callbacks != null) {
                callbacks.onWordDecoded(
                        null,
                        null,
                        wordTokens,
                        false,
                        prevWord,
                        textSnapshotAtActionDown,
                        lastPromotedPassiveCount,
                        0,
                        restingDroppedThisSession,
                        candidates);
            }
            return;
        }

        boolean prependLeadingSpace = !leadingSpaceEvaluated
                && !suppressNextLeadingSpace
                && shouldPrependLeadingSpace(textSnapshotAtActionDown, settings.autoSpaceEnabled);
        boolean attachingAfterDeletion = isAttachingAfterDeletion(
                textSnapshotAtActionDown, prependLeadingSpace, suppressNextLeadingSpace);

        String bestWord = candidates.get(0).word;
        String capContext = (textSnapshotAtActionDown != null) ? textSnapshotAtActionDown : liveSnapshot;
        boolean capitalizeFirst = !attachingAfterDeletion && shouldCapitalizeFirstLetter(capContext);
        if (attachingAfterDeletion && bestWord.length() > 0) {
            bestWord = lowercaseFirstChar(bestWord);
        } else if (capitalizeFirst && bestWord.length() > 0) {
            bestWord = capitalizeFirstChar(bestWord);
        }

        List<String> alternativeWords = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            String w = candidates.get(i).word;
            if (attachingAfterDeletion && w.length() > 0) {
                w = lowercaseFirstChar(w);
            } else if (capitalizeFirst && w.length() > 0) {
                w = capitalizeFirstChar(w);
            }
            if (!alternativeWords.contains(w)) {
                alternativeWords.add(w);
            }
        }

        currentComposingWord = bestWord;
        currentAlternativeWords = alternativeWords;

        int rollbackDeleted = 0;
        if (editor != null) {
            rollbackDeleted = editor.previewWord(
                    bestWord, textSnapshotAtActionDown, prependLeadingSpace);
            if (prependLeadingSpace && textSnapshotAtActionDown != null) {
                textSnapshotAtActionDown = tailSnapshot(textSnapshotAtActionDown + " ");
            }
            leadingSpaceEvaluated = true;
            pendingBackspaceCheck = false;
            expectedSnapshotAfterPreview = (textSnapshotAtActionDown != null)
                    ? tailSnapshot(textSnapshotAtActionDown + bestWord)
                    : null;
            String afterPreview = editor.readTextBeforeCursorSnapshot();
            if (afterPreview != null) {
                expectedSnapshotAfterPreview = afterPreview;
                lastObservedTextBeforeCursor = afterPreview;
            }
        }

        if (callbacks != null) {
            callbacks.onWordDecoded(
                    bestWord,
                    alternativeWords,
                    wordTokens,
                    capitalizeFirst,
                    prevWord,
                    textSnapshotAtActionDown,
                    lastPromotedPassiveCount,
                    rollbackDeleted,
                    restingDroppedThisSession,
                    candidates);
        }
    }

    void onActionCancel(LifecycleCallbacks callbacks) {
        if (callbacks != null) {
            callbacks.onTrailAccentChanged(false);
            callbacks.onNativeTopBarBlankChanged(false);
            callbacks.onClearTrailManagerActive();
        }
        resetGestureState();
        clearWordBuffer(callbacks);
        if (callbacks != null) {
            callbacks.onBufferTimerUpdated(0L);
        }
        passiveTapBuffer.clear();
        singleTouchCombineBuffer.clear();
        pendingBackspaceCheck = false;
        suppressNextLeadingSpace = false;
        suppressLeadingSpaceBaseline = null;
        pendingLeadingSpaceBeforeNextWord = false;
        pendingBackspaceTap = false;
        lastCommittedWordUndo = null;
    }

    void finalizeAutospace(
            boolean autoSpaceEnabled,
            EditorAdapter editor,
            LifecycleCallbacks callbacks
    ) {
        finalizeAutospace(autoSpaceEnabled, false, editor, callbacks);
    }

    void finalizeAutospace(
            boolean autoSpaceEnabled,
            boolean autoSpaceBeforeWord,
            EditorAdapter editor,
            LifecycleCallbacks callbacks
    ) {
        if (!autospaceTimerPending) return;
        autospaceTimerPending = false;
        if (!activePointers.isEmpty()) return;

        String committedWord = currentComposingWord;
        List<String> alternatives = currentAlternativeWords;

        if (editor != null && committedWord != null && !committedWord.isEmpty()) {
            boolean appendSpace = autoSpaceEnabled && !autoSpaceBeforeWord;
            editor.commitFinishedWord(committedWord, appendSpace);
            autospaceInserted = appendSpace;
            pendingLeadingSpaceBeforeNextWord = autoSpaceEnabled && autoSpaceBeforeWord;

            String afterSnapshot = editor.readTextBeforeCursorSnapshot();
            autospaceSnapshot = autoSpaceEnabled ? afterSnapshot : null;
            if (afterSnapshot != null) {
                lastObservedTextBeforeCursor = afterSnapshot;
                lastCommittedWordUndo = new LastCommittedWordUndo(
                        afterSnapshot,
                        committedWord.length() + (appendSpace ? 1 : 0));
            }
            String prevWord = extractPreviousWord(
                    textSnapshotAtActionDown, afterSnapshot, lastCommittedWord);
            lastCommittedWord = committedWord;
            if (callbacks != null) {
                callbacks.onWordCommitted(committedWord, autospaceInserted, prevWord, alternatives);
            }
        }

        clearWordBuffer(callbacks);
        passiveTapBuffer.clear();
        singleTouchCombineBuffer.clear();
        resetGestureState();
        if (callbacks != null) {
            callbacks.onTrailAccentChanged(false);
            callbacks.onWordReadyTimerElapsed();
        }
    }

    void finalizeStockSwipeAutospace(
            boolean autoSpaceEnabled,
            EditorAdapter editor
    ) {
        finalizeStockSwipeAutospace(autoSpaceEnabled, false, editor, null);
    }

    void finalizeStockSwipeAutospace(
            boolean autoSpaceEnabled,
            boolean autoSpaceBeforeWord,
            EditorAdapter editor
    ) {
        finalizeStockSwipeAutospace(autoSpaceEnabled, autoSpaceBeforeWord, editor, null);
    }

    void finalizeStockSwipeAutospace(
            boolean autoSpaceEnabled,
            boolean autoSpaceBeforeWord,
            EditorAdapter editor,
            LifecycleCallbacks callbacks
    ) {
        if (!stockSwipeAutospacePending) return;
        stockSwipeAutospacePending = false;
        if (!autoSpaceEnabled || !activePointers.isEmpty() || editor == null) return;

        String before = editor.readTextBeforeCursorSnapshot();
        if (before == null || before.isEmpty()) return;
        char last = before.charAt(before.length() - 1);
        if (!Character.isLetterOrDigit(last)) return;

        if (autoSpaceBeforeWord) {
            pendingLeadingSpaceBeforeNextWord = true;
            if (callbacks != null) {
                callbacks.onWordReadyTimerElapsed();
            }
            return;
        }

        editor.commitFinishedWord(null, true);
        autospaceInserted = true;

        String afterSnapshot = editor.readTextBeforeCursorSnapshot();
        autospaceSnapshot = afterSnapshot;
        if (afterSnapshot != null) {
            lastObservedTextBeforeCursor = afterSnapshot;
        }
        passiveTapBuffer.clear();
        singleTouchCombineBuffer.clear();
        if (callbacks != null) {
            callbacks.onWordReadyTimerElapsed();
        }
    }

    /**
     * Handles {@code ACTION_DOWN} from Gboard's {@code BasicMotionEventHandler} (which runs on
     * all layouts, including {@code ?123} symbols where {@code AbstractGestureMotionEventHandler}
     * is inactive). Retracts the auto-inserted trailing space immediately before hugging
     * punctuation such as {@code ':'} is committed.
     */
    void onBasicHandlerActionDown(char keyChar, EditorAdapter editor) {
        if (!autospaceInserted || editor == null) {
            return;
        }
        String snapshotNow = editor.readTextBeforeCursorSnapshot();
        if (autospaceSnapshot != null && snapshotNow != null && !snapshotNow.equals(autospaceSnapshot)) {
            autospaceInserted = false;
            autospaceSnapshot = null;
            return;
        }
        if (geometry.isPunctuationChar(keyChar)) {
            String before = editor.readCharBeforeCursor();
            if (before != null && !before.isEmpty() && before.charAt(before.length() - 1) == ' ') {
                editor.deleteCharsBeforeCursor(1);
                String after = editor.readTextBeforeCursorSnapshot();
                if (after != null) {
                    lastObservedTextBeforeCursor = after;
                }
            }
            autospaceInserted = false;
            autospaceSnapshot = null;
            autospaceFirstSpaceTapDownTimeMs = 0L;
        } else if (keyChar != '\0') {
            autospaceInserted = false;
            autospaceSnapshot = null;
        }
    }

    // ---- MS-21: Configurable Backspace Handling ----

    void maybeCancelPendingBackspaceTap(
            float x,
            float y,
            long eventTimeMs,
            LifecycleCallbacks callbacks
    ) {
        if (!pendingBackspaceTap) return;
        float dx = x - backspaceDownX;
        float dy = y - backspaceDownY;
        float maxDist = backspaceSwipeThresholdPx();
        if ((dx * dx + dy * dy) > (maxDist * maxDist)
                || (eventTimeMs - backspaceDownTimeMs) > BACKSPACE_HOLD_TIMEOUT_MS) {
            pendingBackspaceTap = false;
            lastCommittedWordUndo = null;
            boolean wasActive = multiswipeTakeoverActive || multiswipeWordActive;
            multiswipeTakeoverActive = false;
            clearWordBuffer(callbacks);
            if (wasActive && callbacks != null) {
                callbacks.onTrailAccentChanged(false);
                callbacks.onNativeTopBarBlankChanged(false);
            }
        }
    }

    boolean shouldSuppressBasicUpForEvent(long eventTimeMs) {
        return suppressedBasicUpEventTimeMs == eventTimeMs;
    }

    boolean tryConsumePendingBackspaceTap(
            long eventTimeMs,
            float x,
            float y,
            SettingsSnapshot settings,
            EditorAdapter editor,
            DecodePort decoder,
            LifecycleCallbacks callbacks
    ) {
        if (suppressedBasicUpEventTimeMs == eventTimeMs) {
            return true;
        }
        if (!pendingBackspaceTap) {
            return false;
        }
        pendingBackspaceTap = false;

        float dx = x - backspaceDownX;
        float dy = y - backspaceDownY;
        float maxDist = backspaceSwipeThresholdPx();
        long elapsed = eventTimeMs - backspaceDownTimeMs;
        if ((dx * dx + dy * dy) > (maxDist * maxDist) || elapsed < 0L || elapsed > BACKSPACE_HOLD_TIMEOUT_MS) {
            lastCommittedWordUndo = null;
            clearWordBuffer(callbacks);
            return false;
        }

        String snapshotNow = (editor != null) ? editor.readTextBeforeCursorSnapshot() : null;

        // Case 1: Active/pending MultiSwipe word in last_input mode
        if (multiswipeWordActive
                && settings != null
                && GboardMultiSwipeSettings.BACKSPACE_MODE_LAST_INPUT.equals(settings.backspaceMode)
                && !wordTokens.isEmpty()) {
            removeLatestStartedToken(wordTokens);
            if (containsSwipeToken(wordTokens)) {
                suppressedBasicUpEventTimeMs = eventTimeMs;
                pendingBackspaceCheck = false;
                suppressNextLeadingSpace = false;
                suppressLeadingSpaceBaseline = null;
                multiswipeTakeoverActive = true;
                multiswipeWordActive = true;
                lastTouchUpTimeMs = eventTimeMs;
                if (callbacks != null) {
                    callbacks.onTrailAccentChanged(true);
                }
                decodeAndPreview(settings, editor, decoder, callbacks, null, snapshotNow);
                if (settings.autoSpaceEnabled && callbacks != null) {
                    callbacks.onCancelAutospaceTimer();
                    autospaceTimerPending = true;
                    callbacks.onScheduleAutospaceTimer(settings.autoSpaceDelayMs);
                } else {
                    autospaceTimerPending = false;
                }
                return true;
            }
            if (!wordTokens.isEmpty() && currentComposingWord != null && !currentComposingWord.isEmpty()) {
                // Only TAP tokens remain (MS-08 / MS-09): restore typed tap prefix into editor and PassiveTapBuffer.
                String restoredPrefix = buildRestoredTapPrefix(wordTokens, promotedTapPrefix);
                if (editor != null && restoredPrefix != null && !restoredPrefix.isEmpty()) {
                    editor.replaceComposingWithText(currentComposingWord.length(), restoredPrefix);
                }
                List<MultiSwipeInputToken> remainingTaps = new ArrayList<>(wordTokens);
                String baselineBeforeTaps = textSnapshotAtActionDown;
                clearWordBuffer(callbacks);
                if (baselineBeforeTaps != null) {
                    passiveTapBuffer.clear();
                    passiveTapBuffer.tokens.addAll(remainingTaps);
                    passiveTapBuffer.baselineText = baselineBeforeTaps;
                    passiveTapBuffer.lastUpTimeMs = eventTimeMs;
                }
                suppressedBasicUpEventTimeMs = eventTimeMs;
                pendingBackspaceCheck = false;
                suppressNextLeadingSpace = false;
                suppressLeadingSpaceBaseline = null;
                multiswipeTakeoverActive = false;
                if (callbacks != null) {
                    callbacks.onTrailAccentChanged(false);
                }
                if (editor != null) {
                    lastObservedTextBeforeCursor = editor.readTextBeforeCursorSnapshot();
                }
                return true;
            }
            // Fall through to whole-word deletion when 0 tokens remain.
        }

        // Case 2: Whole-word deletion while pending (whole_word mode, or 1-token word in last_input mode)
        if (multiswipeWordActive && currentComposingWord != null && !currentComposingWord.isEmpty()) {
            if (editor != null) {
                editor.deleteActiveComposingWord(currentComposingWord.length());
            }
            suppressedBasicUpEventTimeMs = eventTimeMs;
            clearWordBuffer(callbacks);
            lastCommittedWordUndo = null;
            pendingLeadingSpaceBeforeNextWord = false;
            pendingBackspaceCheck = false;
            suppressNextLeadingSpace = false;
            suppressLeadingSpaceBaseline = null;
            multiswipeTakeoverActive = false;
            if (callbacks != null) {
                callbacks.onTrailAccentChanged(false);
            }
            if (editor != null) {
                lastObservedTextBeforeCursor = editor.readTextBeforeCursorSnapshot();
            }
            return true;
        }

        // Case 3: Whole-word deletion of the immediately preceding committed MultiSwipe word
        if (lastCommittedWordUndo != null
                && snapshotNow != null
                && snapshotNow.equals(lastCommittedWordUndo.expectedSnapshot)) {
            if (editor != null && lastCommittedWordUndo.deleteCharCount > 0) {
                editor.deleteCharsBeforeCursor(lastCommittedWordUndo.deleteCharCount);
            }
            suppressedBasicUpEventTimeMs = eventTimeMs;
            lastCommittedWordUndo = null;
            autospaceInserted = false;
            autospaceSnapshot = null;
            pendingLeadingSpaceBeforeNextWord = false;
            pendingBackspaceCheck = false;
            suppressNextLeadingSpace = false;
            suppressLeadingSpaceBaseline = null;
            if (editor != null) {
                lastObservedTextBeforeCursor = editor.readTextBeforeCursorSnapshot();
            }
            return true;
        }

        lastCommittedWordUndo = null;
        clearWordBuffer(callbacks);
        return false;
    }

    private static void removeLatestStartedToken(List<MultiSwipeInputToken> tokens) {
        if (tokens == null || tokens.isEmpty()) return;
        int bestIdx = tokens.size() - 1;
        long bestStart = tokens.get(bestIdx).startTimeMs;
        for (int i = tokens.size() - 2; i >= 0; i--) {
            long tStart = tokens.get(i).startTimeMs;
            if (tStart > bestStart) {
                bestStart = tStart;
                bestIdx = i;
            }
        }
        tokens.remove(bestIdx);
    }

    private static String buildRestoredTapPrefix(
            List<MultiSwipeInputToken> tapTokens,
            String savedPrefix
    ) {
        if (savedPrefix != null && savedPrefix.length() == tapTokens.size()) {
            return savedPrefix;
        }
        StringBuilder sb = new StringBuilder(tapTokens.size());
        for (int i = 0; i < tapTokens.size(); i++) {
            MultiSwipeInputToken t = tapTokens.get(i);
            if (t != null && t.character != '\0') {
                sb.append(t.character);
            }
        }
        return sb.toString();
    }

    // ---- Hook predicates (MS-06, MS-07, MS-17) ----

    boolean shouldStartGesture(boolean enabled, boolean stockShouldStart, boolean gestureAlreadyActive) {
        if (!enabled) return stockShouldStart;
        boolean multiswipeControlled = sawMultiTouch || multiswipeTakeoverActive || multiswipeWordActive;
        if (stockShouldStart) {
            if (multiswipeControlled) {
                multiswipeTakeoverActive = true;
            }
            return true;
        }
        if (gestureAlreadyActive) {
            return false;
        }
        if (hasAnySwipingPointer(multiSwipeThresholdPx())) {
            if (multiswipeControlled) {
                multiswipeTakeoverActive = true;
            }
            return true;
        }
        return false;
    }

    boolean isControlled() {
        return sawMultiTouch || multiswipeTakeoverActive || multiswipeWordActive;
    }

    boolean resolveLetterStarted(boolean enabled, boolean stockLetterStarted) {
        if (stockLetterStarted || !enabled) {
            return stockLetterStarted;
        }
        return sawMultiTouch || multiswipeTakeoverActive || !completedTokens.isEmpty();
    }

    boolean shouldReceiveEventsDuringOtherOwner(boolean enabled) {
        return enabled && (!activePointers.isEmpty() || sawMultiTouch || multiswipeTakeoverActive);
    }

    boolean shouldSuppressBasicHandler(boolean enabled, boolean isActionDown) {
        return enabled && !isActionDown && multiswipeTakeoverActive;
    }

    boolean shouldSuppressRolloverCommit(
            boolean enabled,
            boolean isActionPointerDown,
            boolean geometryReady,
            long eventTimeMs,
            float newPointerX,
            float newPointerY,
            boolean hasFirstPointer,
            float firstPointerX,
            float firstPointerY
    ) {
        if (!enabled || !isActionPointerDown || !geometryReady) {
            return false;
        }
        if (multiswipeTakeoverActive) {
            return true;
        }
        long deltaMs = eventTimeMs - lastActionDownTimeMs;
        if (!lastDownOnLetterArea || deltaMs < 0L || deltaMs > ROLLOVER_MULTI_TOUCH_WINDOW_MS) {
            return false;
        }
        if (!geometry.isOnLetterArea(newPointerX, newPointerY)) {
            return false;
        }
        if (hasFirstPointer) {
            return geometry.isOnLetterArea(firstPointerX, firstPointerY);
        }
        return true;
    }

    boolean shouldInterceptGestureDispatch() {
        return multiswipeTakeoverActive;
    }

    boolean shouldInterceptGestureReset() {
        boolean protect = !activePointers.isEmpty()
                && (sawMultiTouch || multiswipeTakeoverActive || multiswipeWordActive);
        if (!protect) {
            resetGestureState();
            return false;
        }
        return true;
    }

    // ---- Pure text & prefix helpers ----

    private static boolean containsSwipeToken(List<MultiSwipeInputToken> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).kind == MultiSwipeInputToken.Kind.SWIPE) {
                return true;
            }
        }
        return false;
    }

    private static String tailSnapshot(String s) {
        if (s == null || s.length() <= TEXT_SNAPSHOT_CHARS) {
            return s;
        }
        return s.substring(s.length() - TEXT_SNAPSHOT_CHARS);
    }

    static boolean shouldPrependLeadingSpace(String baselineBeforeText, boolean autoSpaceEnabled) {
        if (!autoSpaceEnabled || baselineBeforeText == null || baselineBeforeText.isEmpty()) {
            return false;
        }
        char last = baselineBeforeText.charAt(baselineBeforeText.length() - 1);
        return Character.isLetterOrDigit(last);
    }

    /**
     * Checks whether trailing characters were deleted between {@code previousSnapshot} and
     * {@code currentSnapshot} while leaving a trailing letter/digit (supports both short texts
     * and saturated >= 256-char sliding snapshots).
     */
    static boolean didDeleteTrailingChars(String previousSnapshot, String currentSnapshot) {
        if (previousSnapshot == null || currentSnapshot == null || currentSnapshot.isEmpty()) {
            return false;
        }
        if (!Character.isLetterOrDigit(currentSnapshot.charAt(currentSnapshot.length() - 1))) {
            return false;
        }
        int prevLen = previousSnapshot.length();
        int curLen = currentSnapshot.length();
        if (curLen < prevLen) {
            return true;
        }
        if (curLen == prevLen && curLen >= 32 && !currentSnapshot.equals(previousSnapshot)) {
            int maxDel = Math.min(MAX_ROLLBACK_EXTRA_CHARS, curLen - 1);
            for (int deleted = 1; deleted <= maxDel; deleted++) {
                if (currentSnapshot.regionMatches(deleted, previousSnapshot, 0, curLen - deleted)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isAttachingAfterDeletion(
            String baselineBeforeText,
            boolean prependLeadingSpace,
            boolean suppressNextLeadingSpace
    ) {
        if (prependLeadingSpace || !suppressNextLeadingSpace
                || baselineBeforeText == null || baselineBeforeText.isEmpty()) {
            return false;
        }
        return Character.isLetterOrDigit(baselineBeforeText.charAt(baselineBeforeText.length() - 1));
    }

    static boolean isAttachingDirectlyToWord(String baselineBeforeText, boolean prependLeadingSpace) {
        if (prependLeadingSpace || baselineBeforeText == null || baselineBeforeText.isEmpty()) {
            return false;
        }
        return Character.isLetterOrDigit(baselineBeforeText.charAt(baselineBeforeText.length() - 1));
    }

    static String lowercaseFirstChar(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        return Character.toLowerCase(word.charAt(0)) + word.substring(1);
    }

    static String capitalizeFirstChar(String word) {
        if (word == null || word.isEmpty()) return word;
        char first = word.charAt(0);
        if (Character.isUpperCase(first)) return word;
        return Character.toUpperCase(first) + word.substring(1);
    }

    static boolean shouldCapitalizeFirstLetter(String before) {
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

    static String extractPreviousWord(
            String baselineBeforeText,
            String fallbackSnapshot,
            String fallbackLastWord
    ) {
        String source = (baselineBeforeText != null) ? baselineBeforeText : fallbackSnapshot;
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

    static boolean isCombinePrefix(String prefix) {
        if (prefix == null || prefix.length() > MAX_ROLLBACK_EXTRA_CHARS) return false;
        if (!prefix.isEmpty() && prefix.charAt(prefix.length() - 1) == ' ') {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!Character.isLetter(c) && c != ' ') {
                return false;
            }
        }
        return true;
    }

    static int computeExtraCharsAfterBaseline(String baselineBeforeText, String currentStr) {
        if (baselineBeforeText == null || currentStr == null) return 0;
        if (currentStr.equals(baselineBeforeText)) return 0;
        int baseLen = baselineBeforeText.length();
        int curLen = currentStr.length();
        if (curLen > baseLen && currentStr.startsWith(baselineBeforeText)) {
            int extra = curLen - baseLen;
            return (extra > 0 && extra <= MAX_ROLLBACK_EXTRA_CHARS) ? extra : 0;
        }
        if (curLen == baseLen && baseLen >= 32) {
            for (int extra = 1; extra <= MAX_ROLLBACK_EXTRA_CHARS && extra < baseLen; extra++) {
                if (baselineBeforeText.regionMatches(extra, currentStr, 0, baseLen - extra)) {
                    return extra;
                }
            }
        }
        return 0;
    }

    static String extractTypedPrefixSinceBaseline(String baselineBeforeText, String currentStr) {
        if (baselineBeforeText == null || currentStr == null) return null;
        if (currentStr.equals(baselineBeforeText)) return "";
        int extra = computeExtraCharsAfterBaseline(baselineBeforeText, currentStr);
        if (extra <= 0) return null;
        return currentStr.substring(currentStr.length() - extra);
    }

    static boolean isPureLetterPrefix(String prefix, int tapTokenCount) {
        if (prefix == null) return false;
        if (prefix.length() > tapTokenCount + 2) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (!Character.isLetter(prefix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static List<MultiSwipeInputToken> collectPureTapTokens(List<MultiSwipeInputToken> tokens) {
        if (tokens == null || tokens.isEmpty()) return null;
        List<MultiSwipeInputToken> out = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            MultiSwipeInputToken t = tokens.get(i);
            if (t == null || t.kind != MultiSwipeInputToken.Kind.TAP) {
                return null;
            }
            out.add(t);
        }
        return out;
    }
}
