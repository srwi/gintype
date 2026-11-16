package dev.srwi.gboardmultiswipe.extension.thicctopbar;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import dev.srwi.gboardmultiswipe.extension.multiswipe.GboardMultiSwipeRuntime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runtime coordinator for Thicc Top Bar.
 * Intercepts candidates passed to LatinFixedCountCandidatesHolderView and
 * renders them via ThiccTopBarView (2D matrix of words & alternatives).
 */
public final class GboardThiccTopBarRuntime {
    private static final String TAG = "GboardThiccTopBar";
    private static final String MATRIX_BAR_TAG = "gboardpatches:matrix_bar_view";

    public static final class CandidateItem {
        public final String text;
        public final int slotIndex;
        public final boolean isRaw;
        public final boolean isAutoCorrect;
        public final boolean isPrediction;
        public final String candidateType;
        public final Object rawCandidateObj;
        public final View softKey;

        public CandidateItem(String text, int slotIndex, boolean isRaw, boolean isAutoCorrect,
                             boolean isPrediction, String candidateType, Object rawCandidateObj, View softKey) {
            this.text = text;
            this.slotIndex = slotIndex;
            this.isRaw = isRaw;
            this.isAutoCorrect = isAutoCorrect;
            this.isPrediction = isPrediction;
            this.candidateType = candidateType != null ? candidateType : "";
            this.rawCandidateObj = rawCandidateObj;
            this.softKey = softKey;
        }

        @Override
        public String toString() {
            return text + "[slot=" + slotIndex + ",type=" + candidateType + ",raw=" + isRaw + ",auto=" + isAutoCorrect + ",pred=" + isPrediction + "]";
        }
    }

    private static final Map<ViewGroup, ThiccTopBarView> ACTIVE_BARS = new WeakHashMap<>();
    private static final Map<View, Integer> BASE_HEADER_HEIGHTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThiccTopBarSentenceBuffer SENTENCE_BUFFER = new ThiccTopBarSentenceBuffer();
    private static final Map<String, List<String>> WORD_ALTERNATIVES_CACHE = new LinkedHashMap<String, List<String>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > 600;
        }
    };

    private static List<CandidateItem> lastComposingCandidates = Collections.emptyList();
    private static List<String> activeOriginalSuggestions = null;
    private static volatile long lastSelfReplaceTimestamp = 0;
    private static volatile String lastSelfReplacedWord = null;
    private static volatile long lastHistoryInteractionTimestamp = 0;
    private static volatile long lastWordNavTimestamp = 0;
    private static volatile ViewGroup lastHolderView = null;
    private static volatile String lastMultiSwipeWord = null;
    private static volatile List<String> lastMultiSwipeSuggestions = null;
    private static volatile long lastMultiSwipeTimestamp = 0;

    private static View currentVoiceButton = null;
    private static JumpToEndArrowView currentJumpToEndArrow = null;
    private static Runnable pendingJumpRunnable = null;

    /**
     * Hook called after SoftKeyboardView finishes inflating to apply the configured
     * top bar scale before the first layout pass.
     */
    public static void onSoftKeyboardViewInflated(Object softKeyboardViewObj) {
        try {
            if (!(softKeyboardViewObj instanceof View view)) {
                return;
            }
            if (!ThiccTopBarGboardReflector.isHeaderSoftKeyboardView(view)) {
                return;
            }
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp == null || lp.height <= 0) {
                return;
            }
            BASE_HEADER_HEIGHTS.put(view, lp.height);
            applyHeaderViewHeight(view, readActiveTopbarScale(view.getContext()));
        } catch (Throwable t) {
            Log.e(TAG, "onSoftKeyboardViewInflated failed", t);
        }
    }

    /**
     * Hook called at the computed-height return of SoftKeyboardView target height
     * calculation so Gboard's header and IME body size calculations match the scale.
     */
    public static int scaleHeaderTargetHeight(Object softKeyboardViewObj, int targetHeight) {
        try {
            if (targetHeight <= 0 || !(softKeyboardViewObj instanceof View view)) {
                return targetHeight;
            }
            if (!ThiccTopBarGboardReflector.isHeaderSoftKeyboardView(view)) {
                return targetHeight;
            }
            BASE_HEADER_HEIGHTS.put(view, targetHeight);
            float scale = readActiveTopbarScale(view.getContext());
            if (scale <= 1.001f) {
                return targetHeight;
            }
            return Math.max(targetHeight, Math.round(targetHeight * scale));
        } catch (Throwable t) {
            Log.e(TAG, "scaleHeaderTargetHeight failed", t);
            return targetHeight;
        }
    }

    private static float readActiveTopbarScale(Context context) {
        if (context == null || !GboardThiccTopBarSettings.readEnabled(context)) {
            return GboardThiccTopBarSettings.DEFAULT_TOPBAR_SCALE;
        }
        return GboardThiccTopBarSettings.readTopbarScale(context);
    }

    private static void syncHeaderHeight(ViewGroup holderView, float scale) {
        if (holderView == null) {
            return;
        }
        View headerView = ThiccTopBarGboardReflector.findSoftKeyboardView(holderView);
        applyHeaderViewHeight(headerView, scale);
    }

    private static void applyHeaderViewHeight(View headerView, float scale) {
        if (headerView == null) {
            return;
        }
        ViewGroup.LayoutParams lp = headerView.getLayoutParams();
        if (lp == null || lp.height <= 0) {
            return;
        }
        Integer recordedBase = BASE_HEADER_HEIGHTS.get(headerView);
        int baseHeight;
        if (recordedBase != null && recordedBase > 0) {
            baseHeight = recordedBase;
        } else {
            baseHeight = lp.height;
            BASE_HEADER_HEIGHTS.put(headerView, baseHeight);
        }
        float sanitized = GboardThiccTopBarSettings.sanitizeTopbarScale(scale);
        int desiredHeight = sanitized > 1.001f
                ? Math.max(baseHeight, Math.round(baseHeight * sanitized))
                : baseHeight;
        if (lp.height != desiredHeight) {
            lp.height = desiredHeight;
            headerView.setLayoutParams(lp);
            headerView.requestLayout();
            if (headerView.getParent() instanceof View parentView) {
                parentView.requestLayout();
            }
        }
    }

    /** Immediately synchronizes tracked header views and suggestion bars after a settings change. */
    public static void syncAllHeaderHeights(Context context) {
        if (context == null) {
            return;
        }
        float activeScale = readActiveTopbarScale(context);
        float swipeSensitivity = GboardThiccTopBarSettings.readSwipeSensitivity(context);
        boolean invertSwipe = GboardThiccTopBarSettings.readInvertSwipe(context);
        List<View> trackedHeaders;
        synchronized (BASE_HEADER_HEIGHTS) {
            trackedHeaders = new ArrayList<>(BASE_HEADER_HEIGHTS.keySet());
        }
        for (View headerView : trackedHeaders) {
            if (headerView != null) {
                headerView.post(() -> applyHeaderViewHeight(headerView, activeScale));
            }
        }
        for (ThiccTopBarView bar : ACTIVE_BARS.values()) {
            if (bar != null) {
                bar.post(() -> {
                    bar.setScaleFactor(activeScale);
                    bar.setSwipeSensitivity(swipeSensitivity);
                    bar.setInvertVerticalSwipe(invertSwipe);
                });
            }
        }
    }

    private static void handleDisabledState(ViewGroup holderView) {
        syncHeaderHeight(holderView, GboardThiccTopBarSettings.DEFAULT_TOPBAR_SCALE);
        ViewGroup parent = (ViewGroup) holderView.getParent();
        ViewGroup attachTarget = parent != null ? parent : holderView;
        ThiccTopBarView existing = ACTIVE_BARS.get(attachTarget);
        if (existing != null && existing.getVisibility() != View.GONE) {
            existing.setVisibility(View.GONE);
            ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
        }
    }

    private static void hideBarForDictation(
            ViewGroup attachTarget, ViewGroup holderView, ThiccTopBarView barView) {
        activeOriginalSuggestions = null;
        hideJumpToEndArrow();
        if (barView != null && barView.getVisibility() != View.GONE) {
            barView.setVisibility(View.GONE);
        }
        ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
    }

    private GboardThiccTopBarRuntime() {
    }

    /**
     * Injected by bytecode patch at the entry of LatinFixedCountCandidatesHolderView.D
     *
     * @param holderViewObj    The LatinFixedCountCandidatesHolderView instance.
     * @param candidateListObj The candidate list (List<Lowu>).
     */
    public static void onCandidatesUpdated(Object holderViewObj, Object candidateListObj) {
        if (!(holderViewObj instanceof ViewGroup holderView)) {
            return;
        }
        lastHolderView = holderView;

        // Cache non-prediction alternatives synchronously before fast space/punctuation commits clear them
        if (candidateListObj != null && !GboardMultiSwipeRuntime.isMultiSwipeWordActive()) {
            try {
                ViewGroup preParent = (holderView.getParent() instanceof ViewGroup p) ? p : holderView;
                ThiccTopBarView existingBar = ACTIVE_BARS.get(preParent);
                if (!isHistorySelectionSessionActive(holderView, existingBar)) {
                    List<CandidateItem> preItems = ThiccTopBarGboardReflector.extractCandidatesFromIterable(candidateListObj);
                    if (preItems.size() >= 2
                            && !ThiccTopBarTextHelper.allPredictions(preItems)
                            && !ThiccTopBarGboardReflector.hasUndoCandidate(preItems)) {
                        String preActiveWord = ThiccTopBarTextHelper.getActiveWord(holderView, preItems);
                        if (preActiveWord != null && !preActiveWord.isEmpty() && !isRecentMultiSwipeWord(preActiveWord)) {
                            List<String> preWords = buildTrimmedSuggestions(preItems, preActiveWord, new int[1]);
                            cacheAlternativeGroup(preWords, preActiveWord);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // Run after LatinFixedCountCandidatesHolderView populates slot views
        holderView.post(() -> {
            try {
                if (!GboardThiccTopBarSettings.readEnabled(holderView.getContext())) {
                    handleDisabledState(holderView);
                    return;
                }

                ThiccTopBarView barView = getOrCreateBarView(holderView);
                if (barView == null) {
                    return;
                }
                ViewGroup parent = (ViewGroup) holderView.getParent();
                ViewGroup attachTarget = parent != null ? parent : holderView;

                // Extract slot candidates directly from holderView slots
                List<CandidateItem> items = ThiccTopBarGboardReflector.extractSlotCandidatesFromHolder(holderView, candidateListObj);

                if (ThiccTopBarGboardReflector.isDictationActive(holderView)) {
                    hideBarForDictation(attachTarget, holderView, barView);
                    return;
                }

                // Ignore Gboard's native Undo/restore candidate when Thicc Top Bar is active:
                // sync the bar with the remaining text instead of dismissing Thicc Top Bar.
                if (ThiccTopBarGboardReflector.isUndoActive(holderView, items)) {
                    activeOriginalSuggestions = null;
                    if (ThiccTopBarTextHelper.isSentenceEnded(holderView) || ThiccTopBarTextHelper.isInputEmpty(holderView)) {
                        SENTENCE_BUFFER.clear();
                        hideJumpToEndArrow();
                        barView.setVisibility(View.GONE);
                        ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                    } else {
                        ThiccTopBarTextHelper.syncSentenceBufferFromText(holderView, null, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
                        if (!SENTENCE_BUFFER.isEmpty()) {
                            showHistoryOnlyBar(holderView, barView);
                        } else {
                            hideJumpToEndArrow();
                            barView.setVisibility(View.GONE);
                            ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                        }
                    }
                    return;
                }

                if (isHistorySelectionSessionActive(holderView, barView)) {
                    barView.setVisibility(View.VISIBLE);
                    ThiccTopBarGboardReflector.applyKeyboardBackground(barView, holderView);
                    ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
                    barView.bringToFront();
                    return;
                }

                if (barView.getSelectedHistoryIndex() >= 0 && ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                    barView.clearHistorySelection();
                    hideJumpToEndArrow();
                } else if (barView.getSelectedHistoryIndex() < 0 && ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                    hideJumpToEndArrow();
                }

                String currentWord = ThiccTopBarTextHelper.getActiveWord(holderView, items);
                if (currentWord == null && !ThiccTopBarTextHelper.hasTextAfterCursorOnSameLine(holderView)) {
                    String editorActiveWord = ThiccTopBarTextHelper.getActiveWord(holderView, Collections.emptyList(), false);
                    if (editorActiveWord != null && isRecentMultiSwipeWord(editorActiveWord)) {
                        currentWord = editorActiveWord;
                    }
                }
                Log.i(TAG, "onCandidatesUpdated: resolved " + items.size() + " candidates, currentWord='" + currentWord + "': " + items);

                // Case 1: No active word being typed/swiped (cursor after manual space, punctuation, or empty)
                if (currentWord == null || currentWord.isEmpty()) {
                    activeOriginalSuggestions = null;
                    if (ThiccTopBarTextHelper.isSentenceEnded(holderView) || ThiccTopBarTextHelper.isInputEmpty(holderView)) {
                        SENTENCE_BUFFER.clear();
                        hideJumpToEndArrow();
                        barView.setVisibility(View.GONE);
                        ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                    } else {
                        ThiccTopBarTextHelper.syncSentenceBufferFromText(holderView, null, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
                        if (!SENTENCE_BUFFER.isEmpty()) {
                            showHistoryOnlyBar(holderView, barView);
                        } else {
                            hideJumpToEndArrow();
                            barView.setVisibility(View.GONE);
                            ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                        }
                    }
                    return;
                }

                int[] centerIdxOut = new int[1];
                List<String> words = buildTrimmedSuggestions(items, currentWord, centerIdxOut);
                int centerIndex = centerIdxOut[0];

                // Preserve original 3 suggestions during flick navigation so user can flick back and forth!
                boolean isFromSelfReplace = lastSelfReplacedWord != null
                        && lastSelfReplacedWord.equalsIgnoreCase(currentWord)
                        && (SystemClock.uptimeMillis() - lastSelfReplaceTimestamp < 1500);

                if (isRecentMultiSwipeWord(currentWord) && lastMultiSwipeSuggestions != null && !lastMultiSwipeSuggestions.isEmpty()) {
                    words = new ArrayList<>(lastMultiSwipeSuggestions);
                    int idx = findCaseInsensitiveIndex(words, currentWord);
                    centerIndex = (idx >= 0) ? idx : 0;
                    words.set(centerIndex, currentWord);
                    activeOriginalSuggestions = new ArrayList<>(words);
                    cacheAlternativeGroup(words, currentWord);
                } else if (shouldPreserveActiveSuggestions(activeOriginalSuggestions, currentWord, isFromSelfReplace, items)) {
                    words = new ArrayList<>(activeOriginalSuggestions);
                    int idx = findCaseInsensitiveIndex(words, currentWord);
                    centerIndex = (idx >= 0) ? idx : 0;
                    words.set(centerIndex, currentWord);
                    Log.i(TAG, "onCandidatesUpdated: preserving original 3 suggestions: " + words + ", centerIndex=" + centerIndex);
                } else {
                    activeOriginalSuggestions = new ArrayList<>(words);
                    cacheAlternativeGroup(words, currentWord);
                }

                // Update sentence buffer with completed words strictly before current active word
                ThiccTopBarTextHelper.syncSentenceBufferFromText(holderView, currentWord, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);

                lastComposingCandidates = new ArrayList<>(items);
                final List<CandidateItem> finalItems = new ArrayList<>(items);
                final List<String> currentWordsList = new ArrayList<>(words);

                barView.updateMatrix(
                        SENTENCE_BUFFER.getEntries(),
                        words,
                        centerIndex,
                        // onCommit: Tapping active word center pill selects the word if already in editor, or commits
                        (selectedIndex, word) -> {
                            if (selectActiveWordIfAlreadyInEditor(holderView, barView, word)) {
                                return;
                            }
                            if (ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                                hideJumpToEndArrow();
                            }
                            CandidateItem chosen = findCandidateByWord(finalItems, word);
                            commitCandidate(holderView, chosen, finalItems);
                        },
                        // onActiveSwap: Flick UP/DOWN or tap Top/Bottom swaps active word WITHOUT space
                        (selectedIndex, newWord) -> {
                            GboardMultiSwipeRuntime.onTopBarUserInteraction();
                            if (ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                                hideJumpToEndArrow();
                            }
                            lastSelfReplaceTimestamp = SystemClock.uptimeMillis();
                            lastSelfReplacedWord = newWord;
                            ThiccTopBarTextHelper.replaceActiveWordInTextField(holderView, newWord);
                            cacheAlternativeGroup(currentWordsList, newWord);
                        },
                        // onHistoryReplace: Flick or tap on a history column swaps that past word in sentence
                        (histColIdx, oldWord, newWord) -> {
                            GboardMultiSwipeRuntime.onTopBarUserInteraction();
                            lastHistoryInteractionTimestamp = SystemClock.uptimeMillis();
                            ThiccTopBarTextHelper.replaceHistoryWord(holderView, SENTENCE_BUFFER, histColIdx, oldWord, newWord);
                        },
                        createHistoryWordClickCallback(holderView, barView)
                );
                barView.setVisibility(View.VISIBLE);
                ThiccTopBarGboardReflector.applyKeyboardBackground(barView, holderView);
                ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
                barView.bringToFront();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to update matrix suggestions", t);
            }
        });
    }

    /**
     * Injected by bytecode patch at the entry of LatinFixedCountCandidatesHolderView.l
     *
     * @param holderViewObj The LatinFixedCountCandidatesHolderView instance.
     */
    public static void onCandidatesCleared(Object holderViewObj) {
        if (!(holderViewObj instanceof ViewGroup holderView)) {
            return;
        }
        lastHolderView = holderView;

        Log.i(TAG, "onCandidatesCleared");
        holderView.post(() -> {
            try {
                if (!GboardThiccTopBarSettings.readEnabled(holderView.getContext())) {
                    handleDisabledState(holderView);
                    return;
                }

                ViewGroup parent = (ViewGroup) holderView.getParent();
                ViewGroup attachTarget = parent != null ? parent : holderView;
                ThiccTopBarView barView = getOrCreateBarView(holderView);
                if (barView == null) {
                    return;
                }

                if (ThiccTopBarGboardReflector.isDictationActive(holderView)) {
                    hideBarForDictation(attachTarget, holderView, barView);
                    return;
                }

                if (isHistorySelectionSessionActive(holderView, barView)) {
                    barView.setVisibility(View.VISIBLE);
                    ThiccTopBarGboardReflector.applyKeyboardBackground(barView, holderView);
                    ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
                    barView.bringToFront();
                    return;
                }

                if (barView.getSelectedHistoryIndex() >= 0 && ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                    barView.clearHistorySelection();
                    hideJumpToEndArrow();
                } else if (barView.getSelectedHistoryIndex() < 0 && ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                    hideJumpToEndArrow();
                }

                if (ThiccTopBarTextHelper.isSentenceEnded(holderView) || ThiccTopBarTextHelper.isInputEmpty(holderView)) {
                    SENTENCE_BUFFER.clear();
                    activeOriginalSuggestions = null;
                    hideJumpToEndArrow();
                    barView.setVisibility(View.GONE);
                    ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                    return;
                }

                // If an active word is present (allowTrailingSpace = false: if candidates are cleared after space, no active word!)
                // Also skip active-word mode when text follows on the same line (e.g., cursor moved left via spacebar).
                String activeWord = ThiccTopBarTextHelper.hasTextAfterCursorOnSameLine(holderView)
                        ? null
                        : ThiccTopBarTextHelper.getActiveWord(holderView, Collections.emptyList(), false);
                if (activeWord != null && !activeWord.isEmpty()) {
                    List<String> words = resolveSuggestionsForWord(activeWord);
                    int centerIdx = Math.max(0, findCaseInsensitiveIndex(words, activeWord));
                    words.set(centerIdx, activeWord);
                    final List<String> currentWordsList = new ArrayList<>(words);
                    ThiccTopBarTextHelper.syncSentenceBufferFromText(holderView, activeWord, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
                    barView.updateMatrix(
                            SENTENCE_BUFFER.getEntries(),
                            words,
                            centerIdx,
                            (selectedIndex, word) -> {
                                if (selectActiveWordIfAlreadyInEditor(holderView, barView, word)) {
                                    return;
                                }
                                if (ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                                    hideJumpToEndArrow();
                                }
                                commitCandidate(holderView, new CandidateItem(word, -1, true, true, false, "CLEARED_ACTIVE", null, null), Collections.emptyList());
                            },
                            (selectedIndex, newWord) -> {
                                GboardMultiSwipeRuntime.onTopBarUserInteraction();
                                if (ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                                    hideJumpToEndArrow();
                                }
                                lastSelfReplaceTimestamp = SystemClock.uptimeMillis();
                                lastSelfReplacedWord = newWord;
                                ThiccTopBarTextHelper.replaceActiveWordInTextField(holderView, newWord);
                                cacheAlternativeGroup(currentWordsList, newWord);
                            },
                            (histColIdx, oldWord, newWord) -> {
                                GboardMultiSwipeRuntime.onTopBarUserInteraction();
                                lastHistoryInteractionTimestamp = SystemClock.uptimeMillis();
                                ThiccTopBarTextHelper.replaceHistoryWord(holderView, SENTENCE_BUFFER, histColIdx, oldWord, newWord);
                            },
                            createHistoryWordClickCallback(holderView, barView)
                    );
                    barView.setVisibility(View.VISIBLE);
                    ThiccTopBarGboardReflector.applyKeyboardBackground(barView, holderView);
                    ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
                    barView.bringToFront();
                    return;
                }

                ThiccTopBarTextHelper.syncSentenceBufferFromText(holderView, null, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
                if (!SENTENCE_BUFFER.isEmpty()) {
                    showHistoryOnlyBar(holderView, barView);
                } else {
                    hideJumpToEndArrow();
                    barView.setVisibility(View.GONE);
                    ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to clear matrix suggestions", t);
            }
        });
    }

    private static ThiccTopBarView getOrCreateBarView(ViewGroup holderView) {
        float activeScale = readActiveTopbarScale(holderView.getContext());
        float swipeSensitivity = GboardThiccTopBarSettings.readSwipeSensitivity(holderView.getContext());
        boolean invertSwipe = GboardThiccTopBarSettings.readInvertSwipe(holderView.getContext());
        syncHeaderHeight(holderView, activeScale);
        ThiccTopBarView bar = resolveOrCreateBarView(holderView);
        if (bar != null) {
            bar.setScaleFactor(activeScale);
            bar.setSwipeSensitivity(swipeSensitivity);
            bar.setInvertVerticalSwipe(invertSwipe);
        }
        return bar;
    }

    private static ThiccTopBarView resolveOrCreateBarView(ViewGroup holderView) {
        ViewGroup parent = (ViewGroup) holderView.getParent();
        ViewGroup attachTarget = parent != null ? parent : holderView;

        ThiccTopBarView bar = ACTIVE_BARS.get(attachTarget);
        if (bar != null && bar.getParent() == attachTarget) {
            return bar;
        }

        // Look for existing tagged child
        for (int i = 0; i < attachTarget.getChildCount(); i++) {
            View child = attachTarget.getChildAt(i);
            if (child instanceof ThiccTopBarView existing && MATRIX_BAR_TAG.equals(existing.getTag())) {
                ACTIVE_BARS.put(attachTarget, existing);
                attachWordCursorNavigator(holderView, existing);
                return existing;
            }
        }

        // Create new
        Context context = attachTarget.getContext();
        bar = new ThiccTopBarView(context);
        bar.setTag(MATRIX_BAR_TAG);
        attachWordCursorNavigator(holderView, bar);
        ThiccTopBarGboardReflector.applyKeyboardBackground(bar, holderView);

        ViewGroup.LayoutParams lp;
        if (attachTarget instanceof FrameLayout) {
            lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        attachTarget.addView(bar, lp);
        ACTIVE_BARS.put(attachTarget, bar);

        final ThiccTopBarView finalBar = bar;
        // Keep quick toggles suppressed and MatrixBar at front whenever MatrixBar is visible.
        attachTarget.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (finalBar.getVisibility() == View.VISIBLE) {
                if (ThiccTopBarGboardReflector.isDictationActive(holderView)) {
                    hideBarForDictation(attachTarget, holderView, finalBar);
                    return;
                }
                ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
                if (attachTarget.getChildCount() > 0 && attachTarget.getChildAt(attachTarget.getChildCount() - 1) != finalBar) {
                    finalBar.bringToFront();
                }
            }
        });

        // Watch for holderView visibility / layout changes
        holderView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (finalBar.getVisibility() != View.VISIBLE
                    && ThiccTopBarGboardReflector.getHolderCandidateCount(holderView) == 0) {
                ThiccTopBarGboardReflector.hideStaleCandidateSlots(holderView);
            }
            if (v.getVisibility() != View.VISIBLE || (right - left <= 0)) {
                if (isHistorySelectionSessionActive(holderView, finalBar)) {
                    return;
                }
                if (ThiccTopBarTextHelper.isSentenceEnded(v) || ThiccTopBarTextHelper.isInputEmpty(v)) {
                    if (finalBar.getVisibility() != View.GONE) {
                        finalBar.setVisibility(View.GONE);
                        ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                    }
                }
            }
        });

        return bar;
    }

    /**
     * Determines which candidate corresponds to the active word in the middle line.
     */
    private static int determineCenterCandidateIndex(ViewGroup holderView, List<CandidateItem> items, String currentWord) {
        if (items.isEmpty()) return 0;

        // 1. Exact or case-insensitive match with current typed or swiped word before cursor
        String lastWord = (currentWord != null && !currentWord.isEmpty()) ? currentWord : ThiccTopBarTextHelper.getActiveWord(holderView, items);
        if (lastWord != null && !lastWord.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                if (lastWord.equals(items.get(i).text)) {
                    return i;
                }
            }
            for (int i = 0; i < items.size(); i++) {
                if (lastWord.equalsIgnoreCase(items.get(i).text)) {
                    return i;
                }
            }
        }

        // 2. Check if Gboard set any SoftKeyView to activated
        for (int i = 0; i < items.size(); i++) {
            View sk = items.get(i).softKey;
            if (sk != null && sk.isActivated()) {
                return i;
            }
        }

        // 3. Fallback: if holderView has field 'r' set, use that slot
        try {
            Field rField = holderView.getClass().getDeclaredField("r");
            rField.setAccessible(true);
            int r = rField.getInt(holderView);
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).slotIndex == r) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }

        // 4. Normal typing: verbatim typed word has isRaw flag
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isRaw) {
                return i;
            }
        }

        // 5. Swipe / glide typing: swiped word has isAutoCorrect flag
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isAutoCorrect) {
                return i;
            }
        }

        // 6. Default: Gboard center slot (index 1) if available, else 0
        if (items.size() >= 2) {
            return 1;
        }
        return 0;
    }

    /**
     * Commits the chosen candidate into Gboard and the target text field.
     */
    private static void commitCandidate(ViewGroup holderView, CandidateItem selectedItem, List<CandidateItem> allItems) {
        Log.i(TAG, "commitCandidate: word=" + selectedItem.text + " slotIndex=" + selectedItem.slotIndex);

        // 1. Try native commit via SoftKeyboardView.a(softKey)
        boolean nativeCommitted = ThiccTopBarGboardReflector.commitCandidateNative(holderView, selectedItem);

        // 2. Fallback: clean InputConnection replacement
        if (!nativeCommitted) {
            replaceActiveCandidateViaInputConnection(holderView, selectedItem);
        }

        // 3. Record in sentence buffer
        List<String> altStrings = new ArrayList<>();
        for (CandidateItem ci : allItems) {
            altStrings.add(ci.text);
        }
        int selIdx = allItems.indexOf(selectedItem);
        SENTENCE_BUFFER.addWord(selectedItem.text, altStrings, selIdx >= 0 ? selIdx : 0);
    }

    static CandidateItem findCandidateByWord(List<CandidateItem> items, String word) {
        if (items != null && word != null) {
            for (CandidateItem ci : items) {
                if (word.equals(ci.text)) {
                    return ci;
                }
            }
            for (CandidateItem ci : items) {
                if (word.equalsIgnoreCase(ci.text)) {
                    return ci;
                }
            }
        }
        return new CandidateItem(word != null ? word : "", -1, false, false, false, "", null, null);
    }

    private static List<String> buildTrimmedSuggestions(
            List<CandidateItem> items,
            String currentWord,
            int[] outCenterIndex
    ) {
        int currentWordIdx = -1;
        for (int i = 0; i < items.size(); i++) {
            if (currentWord.equalsIgnoreCase(items.get(i).text)) {
                currentWordIdx = i;
                break;
            }
        }

        // Inject currentWord when absent (e.g. glide typing or words absent from candidates)
        if (currentWordIdx < 0) {
            CandidateItem activeItem = new CandidateItem(currentWord, -1, true, true, false, "GLIDE_ACTIVE", null, null);
            int insertPos = (items.size() >= 2) ? 1 : 0;
            items.add(insertPos, activeItem);
            currentWordIdx = insertPos;
        }

        int centerIndex = currentWordIdx;

        List<String> words = new ArrayList<>();
        for (CandidateItem ci : items) {
            words.add(ci.text);
        }

        if (words.isEmpty()) {
            words.add(currentWord);
            centerIndex = 0;
        }

        if (words.size() > 3) {
            if (centerIndex <= 0) {
                words = new ArrayList<>(words.subList(0, 3));
                centerIndex = 0;
            } else if (centerIndex >= words.size() - 1) {
                words = new ArrayList<>(words.subList(words.size() - 3, words.size()));
                centerIndex = 2;
            } else {
                words = new ArrayList<>(words.subList(centerIndex - 1, centerIndex + 2));
                centerIndex = 1;
            }
        }
        if (centerIndex >= 0 && centerIndex < words.size() && words.get(centerIndex).equalsIgnoreCase(currentWord)) {
            words.set(centerIndex, currentWord);
        }
        outCenterIndex[0] = centerIndex;
        return words;
    }

    private static void showHistoryOnlyBar(ViewGroup holderView, ThiccTopBarView barView) {
        List<ThiccTopBarSentenceBuffer.WordEntry> entries = SENTENCE_BUFFER.getEntries();
        barView.updateHistoryOnly(
                entries,
                (histColIdx, oldWord, newWord) -> {
                    GboardMultiSwipeRuntime.onTopBarUserInteraction();
                    lastHistoryInteractionTimestamp = SystemClock.uptimeMillis();
                    ThiccTopBarTextHelper.replaceHistoryWord(holderView, SENTENCE_BUFFER, histColIdx, oldWord, newWord);
                },
                createHistoryWordClickCallback(holderView, barView)
        );
        ViewGroup parent = (ViewGroup) holderView.getParent();
        ViewGroup attachTarget = parent != null ? parent : holderView;
        barView.setVisibility(View.VISIBLE);
        ThiccTopBarGboardReflector.applyKeyboardBackground(barView, holderView);
        ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
        barView.bringToFront();
    }

    private static void attachWordCursorNavigator(ViewGroup holderView, ThiccTopBarView barView) {
        barView.setOnWordCursorNavigateCallback(direction -> {
            GboardMultiSwipeRuntime.onTopBarUserInteraction();
            lastWordNavTimestamp = SystemClock.uptimeMillis();
            activeOriginalSuggestions = null;
            ThiccTopBarTextHelper.moveCursorByWord(holderView, direction);
            boolean hadActiveWheel = barView.getCurrentWheelView().getParent() != null;
            int prevHistoryCount = barView.getHistoryWheelCount();
            int prevWindowStart = SENTENCE_BUFFER.getWindowStartWordIndex();
            ThiccTopBarTextHelper.syncSentenceBufferFromText(holderView, null, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
            if (!ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                if (!SENTENCE_BUFFER.isEmpty() && (hadActiveWheel
                        || prevHistoryCount != SENTENCE_BUFFER.getEntries().size()
                        || prevWindowStart != SENTENCE_BUFFER.getWindowStartWordIndex())) {
                    showHistoryOnlyBar(holderView, barView);
                }
                if (!ThiccTopBarTextHelper.hasSelection(holderView)) {
                    barView.clearHistorySelection();
                    barView.centerOnColumn(0);
                } else {
                    int colIdx = ThiccTopBarTextHelper.getWordIndexAtCursor(holderView, SENTENCE_BUFFER);
                    if (colIdx >= 0 && colIdx < barView.getHistoryWheelCount()) {
                        barView.selectHistoryWord(colIdx);
                    } else {
                        barView.clearHistorySelection();
                        if (colIdx >= 0) {
                            barView.centerOnColumn(colIdx);
                        }
                    }
                }
                showJumpToEndArrow(holderView, () -> handleJumpToEnd(holderView, barView));
            } else {
                lastWordNavTimestamp = 0;
                barView.clearHistorySelection();
                hideJumpToEndArrow();
                if (!SENTENCE_BUFFER.isEmpty()) {
                    showHistoryOnlyBar(holderView, barView);
                }
                barView.post(() -> barView.fullScroll(View.FOCUS_RIGHT));
            }
        });
    }

    private static boolean isHistorySelectionSessionActive(ViewGroup holderView, ThiccTopBarView barView) {
        if ((SystemClock.uptimeMillis() - lastWordNavTimestamp) < 600) {
            return !ThiccTopBarTextHelper.isCursorAtEnd(holderView) || ThiccTopBarTextHelper.hasSelection(holderView);
        }
        if (barView == null || barView.getSelectedHistoryIndex() < 0) {
            return false;
        }
        boolean withinGrace = (SystemClock.uptimeMillis() - lastHistoryInteractionTimestamp) < 600;
        return ThiccTopBarTextHelper.hasSelection(holderView) || withinGrace;
    }

    private static void replaceActiveCandidateViaInputConnection(ViewGroup holderView, CandidateItem selectedItem) {
        try {
            InputConnection ic = ThiccTopBarTextHelper.getInputConnection(holderView);
            if (ic == null) return;

            ic.finishComposingText();

            CharSequence cs = ic.getTextBeforeCursor(200, 0);
            if (cs == null) {
                ic.commitText(selectedItem.text + " ", 1);
                return;
            }

            String text = cs.toString();
            String trimmed = text.trim();
            int lastBoundary = -1;
            for (int i = trimmed.length() - 1; i >= 0; i--) {
                if (ThiccTopBarTextHelper.isBoundaryChar(trimmed, i)) {
                    lastBoundary = i;
                    break;
                }
            }
            String lastWord = lastBoundary >= 0 ? trimmed.substring(lastBoundary + 1) : trimmed;

            if (lastWord.equals(selectedItem.text)) {
                // Word is already in the text field with exact casing; ensure trailing space
                if (!text.endsWith(" ")) {
                    ic.commitText(" ", 1);
                }
                return;
            }

            if (!lastWord.isEmpty()) {
                int lastWordStart = text.lastIndexOf(lastWord);
                if (lastWordStart >= 0) {
                    int deleteLen = text.length() - lastWordStart;
                    if (deleteLen > 0 && deleteLen <= text.length()) {
                        ic.beginBatchEdit();
                        ic.deleteSurroundingText(deleteLen, 0);
                        ic.commitText(selectedItem.text + " ", 1);
                        ic.endBatchEdit();
                        Log.i(TAG, "replaceActiveCandidateViaInputConnection: cleanly replaced '" + lastWord + "' with '" + selectedItem.text + "'");
                        return;
                    }
                }
            }

            ic.commitText(selectedItem.text + " ", 1);
        } catch (Throwable t) {
            Log.e(TAG, "replaceActiveCandidateViaInputConnection error", t);
        }
    }

    static boolean shouldPreserveActiveSuggestions(
            List<String> activeOriginalSuggestions,
            String currentWord,
            boolean isFromSelfReplace,
            List<CandidateItem> items
    ) {
        if (activeOriginalSuggestions == null || activeOriginalSuggestions.isEmpty()) {
            return false;
        }
        if (isFromSelfReplace && activeOriginalSuggestions.size() >= 2) {
            return true;
        }
        if (currentWord == null || currentWord.isEmpty()) {
            return false;
        }
        boolean containsCurrent = false;
        for (String w : activeOriginalSuggestions) {
            if (currentWord.equalsIgnoreCase(w)) {
                containsCurrent = true;
                break;
            }
        }
        if (!containsCurrent) {
            return false;
        }
        if (activeOriginalSuggestions.size() >= 2) {
            return true;
        }
        return items != null && items.size() == 1;
    }

    static void cacheAlternativeGroup(List<String> words) {
        String primary = (words != null && !words.isEmpty()) ? words.get(0) : null;
        cacheAlternativeGroup(words, primary);
    }

    static void cacheAlternativeGroup(List<String> words, String primaryWord) {
        if (words == null || words.isEmpty()) return;
        List<String> copy = new ArrayList<>();
        String primaryExact = (primaryWord != null && !primaryWord.isEmpty()) ? primaryWord : null;
        if (primaryExact != null) {
            copy.add(primaryExact);
        }
        for (String w : words) {
            if (w == null || w.isEmpty()) continue;
            if (findCaseInsensitiveIndex(copy, w) < 0) {
                copy.add(w);
            }
            if (copy.size() >= 3) {
                break;
            }
        }
        if (copy.isEmpty()) return;
        String primaryKey = primaryExact != null ? primaryExact.toLowerCase() : null;
        if (primaryExact != null) {
            putAlternativeGroupIfBetter(primaryExact, copy, true);
            putAlternativeGroupIfBetter(primaryKey, copy, true);
        }
        for (String w : copy) {
            if (w == null || w.isEmpty()) continue;
            boolean isPrimary = primaryExact != null && w.equalsIgnoreCase(primaryExact);
            putAlternativeGroupIfBetter(w, copy, isPrimary && copy.size() >= 2);
            String key = w.toLowerCase();
            if (!key.equals(primaryKey)) {
                putAlternativeGroupIfBetter(key, copy, false);
            }
        }
    }

    private static void putAlternativeGroupIfBetter(String key, List<String> copy, boolean overwriteMulti) {
        if (key == null || key.isEmpty()) return;
        List<String> existing = WORD_ALTERNATIVES_CACHE.get(key);
        if (existing == null || existing.isEmpty() || (overwriteMulti && copy.size() >= 2) || (existing.size() < 2 && copy.size() >= 2)) {
            WORD_ALTERNATIVES_CACHE.put(key, copy);
        }
    }

    static List<String> buildMultiSwipeSuggestions(String committedWord, List<String> alternatives) {
        List<String> result = new ArrayList<>(3);
        if (committedWord != null && !committedWord.isEmpty()) {
            result.add(committedWord);
        }
        if (alternatives != null) {
            for (String alt : alternatives) {
                if (alt == null || alt.isEmpty()) {
                    continue;
                }
                if (findCaseInsensitiveIndex(result, alt) < 0) {
                    result.add(alt);
                }
                if (result.size() >= 3) {
                    break;
                }
            }
        }
        return result;
    }

    private static int findCaseInsensitiveIndex(List<String> list, String target) {
        if (list == null || target == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (target.equalsIgnoreCase(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isRecentMultiSwipeWord(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        long elapsed = SystemClock.uptimeMillis() - lastMultiSwipeTimestamp;
        if (elapsed > 2500) {
            return false;
        }
        if (lastMultiSwipeWord != null && lastMultiSwipeWord.equalsIgnoreCase(word)) {
            return true;
        }
        return lastMultiSwipeSuggestions != null && findCaseInsensitiveIndex(lastMultiSwipeSuggestions, word) >= 0;
    }

    private static List<String> resolveSuggestionsForWord(String word) {
        if (word == null || word.isEmpty()) {
            return new ArrayList<>(Collections.singletonList(""));
        }
        if (isRecentMultiSwipeWord(word) && lastMultiSwipeSuggestions != null && !lastMultiSwipeSuggestions.isEmpty()) {
            List<String> list = new ArrayList<>(lastMultiSwipeSuggestions);
            if (findCaseInsensitiveIndex(list, word) < 0) {
                list.set(0, word);
            }
            return list;
        }
        List<String> cached = WORD_ALTERNATIVES_CACHE.get(word);
        if (cached == null || cached.isEmpty()) {
            cached = WORD_ALTERNATIVES_CACHE.get(word.toLowerCase());
        }
        if (cached != null && !cached.isEmpty()) {
            List<String> list = new ArrayList<>(cached);
            if (findCaseInsensitiveIndex(list, word) < 0) {
                list.set(0, word);
            }
            return list;
        }
        return new ArrayList<>(Collections.singletonList(word));
    }

    private static boolean selectActiveWordIfAlreadyInEditor(
            ViewGroup holderView,
            ThiccTopBarView barView,
            String word
    ) {
        if (holderView == null || barView == null || word == null || word.isEmpty()) {
            return false;
        }
        if (ThiccTopBarTextHelper.hasTextAfterCursorOnSameLine(holderView)) {
            return false;
        }
        String activeInEditor = ThiccTopBarTextHelper.getActiveWord(holderView, Collections.emptyList(), false);
        if (activeInEditor == null || !activeInEditor.equalsIgnoreCase(word)) {
            return false;
        }
        GboardMultiSwipeRuntime.onTopBarUserInteraction();
        lastHistoryInteractionTimestamp = SystemClock.uptimeMillis();
        activeOriginalSuggestions = null;
        ThiccTopBarTextHelper.syncSentenceBufferFromText(holderView, null, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
        if (SENTENCE_BUFFER.isEmpty()) {
            return false;
        }
        showHistoryOnlyBar(holderView, barView);
        int lastColIdx = SENTENCE_BUFFER.getEntries().size() - 1;
        barView.selectHistoryWord(lastColIdx);
        boolean selected = ThiccTopBarTextHelper.selectHistoryWordInTextField(
                holderView, SENTENCE_BUFFER, lastColIdx, activeInEditor);
        if (selected) {
            showJumpToEndArrow(holderView, () -> {
                ThiccTopBarTextHelper.jumpToEndOfTextField(holderView);
                barView.clearHistorySelection();
                hideJumpToEndArrow();
                barView.post(() -> barView.fullScroll(View.FOCUS_RIGHT));
            });
        }
        return selected;
    }

    private static ThiccTopBarView.OnHistoryWordClickCallback createHistoryWordClickCallback(
            ViewGroup holderView, ThiccTopBarView barView) {
        return (histColIdx, word) -> {
            GboardMultiSwipeRuntime.onTopBarUserInteraction();
            lastHistoryInteractionTimestamp = SystemClock.uptimeMillis();
            boolean selected = ThiccTopBarTextHelper.selectHistoryWordInTextField(holderView, SENTENCE_BUFFER, histColIdx, word);
            if (selected) {
                showJumpToEndArrow(holderView, () -> handleJumpToEnd(holderView, barView));
            }
        };
    }

    private static void handleJumpToEnd(ViewGroup holderView, ThiccTopBarView barView) {
        lastWordNavTimestamp = 0;
        lastHistoryInteractionTimestamp = 0;
        activeOriginalSuggestions = null;
        ThiccTopBarTextHelper.jumpToEndOfTextField(holderView);
        barView.clearHistorySelection();
        hideJumpToEndArrow();
        ThiccTopBarTextHelper.syncSentenceBufferFromText(holderView, null, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
        if (!SENTENCE_BUFFER.isEmpty()
                && !ThiccTopBarTextHelper.isSentenceEnded(holderView)
                && !ThiccTopBarTextHelper.isInputEmpty(holderView)) {
            showHistoryOnlyBar(holderView, barView);
        } else {
            ViewGroup parent = (ViewGroup) holderView.getParent();
            ViewGroup attachTarget = parent != null ? parent : holderView;
            barView.setVisibility(View.GONE);
            ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
        }
        barView.post(() -> barView.fullScroll(View.FOCUS_RIGHT));
    }

    private static ViewGroup.LayoutParams copyLayoutParams(View sourceView) {
        ViewGroup.LayoutParams srcLp = sourceView.getLayoutParams();
        if (srcLp == null) {
            return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        try {
            Constructor<?> ctor = srcLp.getClass().getConstructor(srcLp.getClass());
            ctor.setAccessible(true);
            return (ViewGroup.LayoutParams) ctor.newInstance(srcLp);
        } catch (Throwable t1) {
            try {
                if (srcLp instanceof ViewGroup.MarginLayoutParams mlp) {
                    Constructor<?> ctor = srcLp.getClass().getConstructor(ViewGroup.MarginLayoutParams.class);
                    ctor.setAccessible(true);
                    return (ViewGroup.LayoutParams) ctor.newInstance(mlp);
                }
            } catch (Throwable t2) {
            }
            try {
                Constructor<?> ctor = srcLp.getClass().getConstructor(ViewGroup.LayoutParams.class);
                ctor.setAccessible(true);
                return (ViewGroup.LayoutParams) ctor.newInstance(srcLp);
            } catch (Throwable t3) {
                return new ViewGroup.LayoutParams(srcLp.width, srcLp.height);
            }
        }
    }

    private static void showJumpToEndArrow(ViewGroup holderView, Runnable onJump) {
        pendingJumpRunnable = onJump;
        if (currentJumpToEndArrow != null && currentJumpToEndArrow.getParent() != null) {
            currentJumpToEndArrow.setScaleFactor(
                readActiveTopbarScale(currentJumpToEndArrow.getContext()));
        currentJumpToEndArrow.updateIconColor();
            currentJumpToEndArrow.setVisibility(View.VISIBLE);
            currentJumpToEndArrow.bringToFront();
            return;
        }

        ViewGroup headerContainer = ThiccTopBarGboardReflector.findHeaderContainer(holderView);
        View voiceBtn = null;
        if (headerContainer != null) {
            voiceBtn = ThiccTopBarGboardReflector.findVoiceButton(headerContainer);
        }
        if (voiceBtn == null) {
            ViewGroup holderParent = (ViewGroup) holderView.getParent();
            if (holderParent != null) {
                voiceBtn = ThiccTopBarGboardReflector.findVoiceButton(holderParent);
            }
        }

        Context context = holderView.getContext();
        if (currentJumpToEndArrow == null) {
            currentJumpToEndArrow = new JumpToEndArrowView(context);
        }
        currentJumpToEndArrow.setScaleFactor(
                readActiveTopbarScale(currentJumpToEndArrow.getContext()));
        currentJumpToEndArrow.updateIconColor();
        currentJumpToEndArrow.setOnJumpClickListener(() -> {
            if (pendingJumpRunnable != null) {
                pendingJumpRunnable.run();
            }
        });

        if (voiceBtn != null && voiceBtn.getParent() instanceof ViewGroup voiceParent) {
            currentVoiceButton = voiceBtn;
            voiceBtn.setVisibility(View.INVISIBLE);
            ViewGroup.LayoutParams lp = copyLayoutParams(voiceBtn);
            if (currentJumpToEndArrow.getParent() != null) {
                ((ViewGroup) currentJumpToEndArrow.getParent()).removeView(currentJumpToEndArrow);
            }
            voiceParent.addView(currentJumpToEndArrow, lp);
        } else {
            ViewGroup fallbackTarget = headerContainer != null ? headerContainer : (ViewGroup) holderView.getParent();
            if (fallbackTarget != null) {
                if (currentJumpToEndArrow.getParent() != null) {
                    ((ViewGroup) currentJumpToEndArrow.getParent()).removeView(currentJumpToEndArrow);
                }
                int width = (int) TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 44f, context.getResources().getDisplayMetrics());
                ViewGroup.LayoutParams lp;
                if (fallbackTarget instanceof FrameLayout) {
                    lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL);
                } else {
                    lp = new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
                }
                fallbackTarget.addView(currentJumpToEndArrow, lp);
            }
        }

        currentJumpToEndArrow.setVisibility(View.VISIBLE);
        currentJumpToEndArrow.bringToFront();
    }

    public static void hideJumpToEndArrow() {
        try {
            if (currentVoiceButton != null) {
                currentVoiceButton.setVisibility(View.VISIBLE);
                currentVoiceButton = null;
            }
            if (currentJumpToEndArrow != null) {
                currentJumpToEndArrow.setVisibility(View.GONE);
                ViewGroup p = (ViewGroup) currentJumpToEndArrow.getParent();
                if (p != null) {
                    p.removeView(currentJumpToEndArrow);
                }
                currentJumpToEndArrow = null;
            }
            pendingJumpRunnable = null;
        } catch (Throwable t) {
            Log.w(TAG, "hideJumpToEndArrow error: " + t.getMessage());
        }
    }

    public static ThiccTopBarSentenceBuffer getSentenceBuffer() {
        return SENTENCE_BUFFER;
    }

    static Map<String, List<String>> getWordAlternativesCacheForTesting() {
        return WORD_ALTERNATIVES_CACHE;
    }

    public static void recordMultiSwipeDecodedWord(String committedWord, List<String> alternatives) {
        try {
            if (committedWord == null || committedWord.isEmpty()) {
                return;
            }
            List<String> trimmedAlts = buildMultiSwipeSuggestions(committedWord, alternatives);
            cacheAlternativeGroup(trimmedAlts, committedWord);
            lastMultiSwipeWord = committedWord;
            lastMultiSwipeSuggestions = new ArrayList<>(trimmedAlts);
            lastMultiSwipeTimestamp = safeUptimeMillis();
            activeOriginalSuggestions = new ArrayList<>(trimmedAlts);

            ViewGroup holderView = lastHolderView;
            if (holderView != null) {
                holderView.post(() -> refreshBarFromMultiSwipe(holderView));
            } else {
                ThiccTopBarSentenceBuffer.WordEntry lastEntry = SENTENCE_BUFFER.getLastEntry();
                if (lastEntry == null || !committedWord.equals(lastEntry.committedWord)) {
                    SENTENCE_BUFFER.addWord(committedWord, trimmedAlts, 0);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static long safeUptimeMillis() {
        try {
            return SystemClock.uptimeMillis();
        } catch (Throwable ignored) {
            return System.currentTimeMillis();
        }
    }

    private static void refreshBarFromMultiSwipe(ViewGroup holderView) {
        try {
            if (holderView == null || !GboardThiccTopBarSettings.readEnabled(holderView.getContext())) {
                return;
            }
            ThiccTopBarView barView = getOrCreateBarView(holderView);
            if (barView == null) {
                return;
            }
            ViewGroup parent = (ViewGroup) holderView.getParent();
            ViewGroup attachTarget = parent != null ? parent : holderView;

            if (ThiccTopBarGboardReflector.isDictationActive(holderView)) {
                hideBarForDictation(attachTarget, holderView, barView);
                return;
            }
            if (isHistorySelectionSessionActive(holderView, barView)) {
                return;
            }

            if (barView.getSelectedHistoryIndex() >= 0 && ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                barView.clearHistorySelection();
                hideJumpToEndArrow();
            } else if (barView.getSelectedHistoryIndex() < 0 && ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                hideJumpToEndArrow();
            }

            if (ThiccTopBarTextHelper.isSentenceEnded(holderView) || ThiccTopBarTextHelper.isInputEmpty(holderView)) {
                SENTENCE_BUFFER.clear();
                activeOriginalSuggestions = null;
                hideJumpToEndArrow();
                barView.setVisibility(View.GONE);
                ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                return;
            }

            String activeWord = ThiccTopBarTextHelper.hasTextAfterCursorOnSameLine(holderView)
                    ? null
                    : ThiccTopBarTextHelper.getActiveWord(holderView, Collections.emptyList(), false);

            if (activeWord != null && !activeWord.isEmpty()) {
                List<String> words = resolveSuggestionsForWord(activeWord);
                int centerIdx = Math.max(0, findCaseInsensitiveIndex(words, activeWord));
                words.set(centerIdx, activeWord);
                activeOriginalSuggestions = new ArrayList<>(words);
                cacheAlternativeGroup(words, activeWord);
                final List<String> currentWordsList = new ArrayList<>(words);

                ThiccTopBarTextHelper.syncSentenceBufferFromText(holderView, activeWord, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
                barView.updateMatrix(
                        SENTENCE_BUFFER.getEntries(),
                        words,
                        centerIdx,
                        (selectedIndex, word) -> {
                            if (selectActiveWordIfAlreadyInEditor(holderView, barView, word)) {
                                return;
                            }
                            if (ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                                hideJumpToEndArrow();
                            }
                            commitCandidate(holderView, new CandidateItem(word, -1, true, true, false, "MULTISWIPE_ACTIVE", null, null), Collections.emptyList());
                        },
                        (selectedIndex, newWord) -> {
                            GboardMultiSwipeRuntime.onTopBarUserInteraction();
                            if (ThiccTopBarTextHelper.isCursorAtEnd(holderView)) {
                                hideJumpToEndArrow();
                            }
                            lastSelfReplaceTimestamp = SystemClock.uptimeMillis();
                            lastSelfReplacedWord = newWord;
                            ThiccTopBarTextHelper.replaceActiveWordInTextField(holderView, newWord);
                            cacheAlternativeGroup(currentWordsList, newWord);
                        },
                        (histColIdx, oldWord, newWord) -> {
                            GboardMultiSwipeRuntime.onTopBarUserInteraction();
                            lastHistoryInteractionTimestamp = SystemClock.uptimeMillis();
                            ThiccTopBarTextHelper.replaceHistoryWord(holderView, SENTENCE_BUFFER, histColIdx, oldWord, newWord);
                        },
                        createHistoryWordClickCallback(holderView, barView)
                );
                barView.setVisibility(View.VISIBLE);
                ThiccTopBarGboardReflector.applyKeyboardBackground(barView, holderView);
                ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
                barView.bringToFront();
                return;
            }

            ThiccTopBarTextHelper.syncSentenceBufferFromText(holderView, null, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
            if (!SENTENCE_BUFFER.isEmpty()) {
                showHistoryOnlyBar(holderView, barView);
            } else {
                hideJumpToEndArrow();
                barView.setVisibility(View.GONE);
                ThiccTopBarGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to refresh matrix bar from MultiSwipe", t);
        }
    }
}
