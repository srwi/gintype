package dev.jason.gboardpatches.extension.matrixsuggestions;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runtime coordinator for Matrix Word Suggestions.
 * Intercepts candidates passed to LatinFixedCountCandidatesHolderView and
 * renders them via MatrixSuggestionBarView (2D matrix of words & alternatives).
 */
public final class GboardMatrixSuggestionsRuntime {
    private static final String TAG = "GboardMatrixSuggestions";
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

    private static final Map<ViewGroup, MatrixSuggestionBarView> ACTIVE_BARS = new WeakHashMap<>();
    private static final MatrixSentenceBuffer SENTENCE_BUFFER = new MatrixSentenceBuffer();
    private static final Map<String, List<String>> WORD_ALTERNATIVES_CACHE = new LinkedHashMap<String, List<String>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > 50;
        }
    };

    private static List<CandidateItem> lastComposingCandidates = Collections.emptyList();
    private static List<String> activeOriginalSuggestions = null;
    private static volatile long lastSelfReplaceTimestamp = 0;
    private static volatile String lastSelfReplacedWord = null;

    private static View currentVoiceButton = null;
    private static JumpToEndArrowView currentJumpToEndArrow = null;
    private static Runnable pendingJumpRunnable = null;

    private GboardMatrixSuggestionsRuntime() {
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

        // Run after LatinFixedCountCandidatesHolderView populates slot views
        holderView.post(() -> {
            try {
                if (!GboardMatrixSuggestionsSettings.readEnabled(holderView.getContext())) {
                    ViewGroup parent = (ViewGroup) holderView.getParent();
                    ViewGroup attachTarget = parent != null ? parent : holderView;
                    MatrixSuggestionBarView existing = ACTIVE_BARS.get(attachTarget);
                    if (existing != null && existing.getVisibility() != View.GONE) {
                        existing.setVisibility(View.GONE);
                        MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                    }
                    return;
                }

                MatrixSuggestionBarView barView = getOrCreateBarView(holderView);
                if (barView == null) {
                    return;
                }
                ViewGroup parent = (ViewGroup) holderView.getParent();
                ViewGroup attachTarget = parent != null ? parent : holderView;

                // Extract slot candidates directly from holderView slots
                List<CandidateItem> items = MatrixGboardReflector.extractSlotCandidatesFromHolder(holderView, candidateListObj);

                // Check if Gboard is in Undo state (e.g. after swipe-to-delete or revert auto-correct)
                if (MatrixGboardReflector.isUndoActive(holderView, items)) {
                    Log.i(TAG, "onCandidatesUpdated: Undo is active. Dismissing matrix bar and restoring native Undo UI.");
                    SENTENCE_BUFFER.clear();
                    activeOriginalSuggestions = null;
                    hideJumpToEndArrow();
                    barView.setVisibility(View.GONE);
                    MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                    return;
                }

                if (barView.getSelectedHistoryIndex() < 0 && MatrixTextHelper.isCursorAtEnd(holderView)) {
                    hideJumpToEndArrow();
                }

                String currentWord = MatrixTextHelper.getActiveWord(holderView, items);
                Log.i(TAG, "onCandidatesUpdated: resolved " + items.size() + " candidates, currentWord='" + currentWord + "': " + items);

                // Case 1: No active word being typed/swiped (cursor after manual space, punctuation, or empty)
                if (currentWord == null || currentWord.isEmpty()) {
                    activeOriginalSuggestions = null;
                    if (MatrixTextHelper.isSentenceEnded(holderView) || MatrixTextHelper.isInputEmpty(holderView)) {
                        SENTENCE_BUFFER.clear();
                        hideJumpToEndArrow();
                        barView.setVisibility(View.GONE);
                        MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                    } else {
                        MatrixTextHelper.syncSentenceBufferFromText(holderView, null, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
                        if (!SENTENCE_BUFFER.isEmpty()) {
                            barView.updateHistoryOnly(
                                    SENTENCE_BUFFER.getEntries(),
                                    (histColIdx, oldWord, newWord) -> {
                                        MatrixTextHelper.replaceHistoryWord(holderView, SENTENCE_BUFFER, histColIdx, oldWord, newWord);
                                    },
                                    createHistoryWordClickCallback(holderView, barView)
                            );
                            barView.setVisibility(View.VISIBLE);
                            MatrixGboardReflector.applyKeyboardBackground(barView, holderView);
                            MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
                            barView.bringToFront();
                        } else {
                            hideJumpToEndArrow();
                            barView.setVisibility(View.GONE);
                            MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                        }
                    }
                    return;
                }

                // Check if currentWord is in items (case-insensitive)
                int currentWordIdx = -1;
                for (int i = 0; i < items.size(); i++) {
                    if (currentWord.equalsIgnoreCase(items.get(i).text)) {
                        currentWordIdx = i;
                        break;
                    }
                }

                // If items does not contain currentWord (e.g. glide typing or custom/English word like 'toggles'),
                // inject currentWord into items as the active candidate!
                if (currentWordIdx < 0) {
                    CandidateItem activeItem = new CandidateItem(currentWord, -1, true, true, false, "GLIDE_ACTIVE", null, null);
                    int insertPos = (items.size() >= 2) ? 1 : 0;
                    items.add(insertPos, activeItem);
                    currentWordIdx = insertPos;
                }

                int centerIndex = (currentWordIdx >= 0) ? currentWordIdx : determineCenterCandidateIndex(holderView, items, currentWord);

                List<String> words = new ArrayList<>();
                for (CandidateItem ci : items) {
                    words.add(ci.text);
                }

                if (words.isEmpty()) {
                    words.add(currentWord);
                    centerIndex = 0;
                }

                // Enforce exact typed casing on the candidate matching currentWord
                if (centerIndex >= 0 && centerIndex < words.size() && words.get(centerIndex).equalsIgnoreCase(currentWord)) {
                    words.set(centerIndex, currentWord);
                }

                // Preserve original 3 suggestions during flick navigation so user can flick back and forth!
                boolean isFromSelfReplace = (lastSelfReplacedWord != null && lastSelfReplacedWord.equalsIgnoreCase(currentWord))
                        || (SystemClock.uptimeMillis() - lastSelfReplaceTimestamp < 800);

                if (activeOriginalSuggestions != null && activeOriginalSuggestions.size() >= 2
                        && (isFromSelfReplace || activeOriginalSuggestions.contains(currentWord))) {
                    words = new ArrayList<>(activeOriginalSuggestions);
                    int idx = -1;
                    for (int i = 0; i < words.size(); i++) {
                        if (words.get(i).equalsIgnoreCase(currentWord)) {
                            idx = i;
                            break;
                        }
                    }
                    centerIndex = (idx >= 0) ? idx : 0;
                    words.set(centerIndex, currentWord);
                    Log.i(TAG, "onCandidatesUpdated: preserving original 3 suggestions: " + words + ", centerIndex=" + centerIndex);
                } else {
                    // New word session: trim to exactly 3 suggestions (active center + 2 alternatives)
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
                    activeOriginalSuggestions = new ArrayList<>(words);
                    cacheAlternativeGroup(words);
                }

                // Update sentence buffer with completed words strictly before current active word
                MatrixTextHelper.syncSentenceBufferFromText(holderView, currentWord, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);

                lastComposingCandidates = new ArrayList<>(items);
                final List<CandidateItem> finalItems = new ArrayList<>(items);
                final List<String> currentWordsList = new ArrayList<>(words);

                barView.updateMatrix(
                        SENTENCE_BUFFER.getEntries(),
                        words,
                        centerIndex,
                        // onCommit: Tapping active word center pill commits with trailing space
                        (selectedIndex, word) -> {
                            if (MatrixTextHelper.isCursorAtEnd(holderView)) {
                                hideJumpToEndArrow();
                            }
                            CandidateItem chosen = (selectedIndex >= 0 && selectedIndex < finalItems.size())
                                    ? finalItems.get(selectedIndex)
                                    : new CandidateItem(word, selectedIndex, false, false, false, "", null, null);
                            commitCandidate(holderView, chosen, finalItems);
                        },
                        // onActiveSwap: Flick UP/DOWN or tap Top/Bottom swaps active word WITHOUT space
                        (selectedIndex, newWord) -> {
                            if (MatrixTextHelper.isCursorAtEnd(holderView)) {
                                hideJumpToEndArrow();
                            }
                            lastSelfReplaceTimestamp = SystemClock.uptimeMillis();
                            lastSelfReplacedWord = newWord;
                            MatrixTextHelper.replaceActiveWordInTextField(holderView, newWord);
                            cacheAlternativeGroup(currentWordsList);
                        },
                        // onHistoryReplace: Flick or tap on a history column swaps that past word in sentence
                        (histColIdx, oldWord, newWord) -> {
                            MatrixTextHelper.replaceHistoryWord(holderView, SENTENCE_BUFFER, histColIdx, oldWord, newWord);
                        },
                        createHistoryWordClickCallback(holderView, barView)
                );
                barView.setVisibility(View.VISIBLE);
                MatrixGboardReflector.applyKeyboardBackground(barView, holderView);
                MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
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

        Log.i(TAG, "onCandidatesCleared");
        holderView.post(() -> {
            try {
                if (!GboardMatrixSuggestionsSettings.readEnabled(holderView.getContext())) {
                    ViewGroup parent = (ViewGroup) holderView.getParent();
                    ViewGroup attachTarget = parent != null ? parent : holderView;
                    MatrixSuggestionBarView existing = ACTIVE_BARS.get(attachTarget);
                    if (existing != null && existing.getVisibility() != View.GONE) {
                        existing.setVisibility(View.GONE);
                        MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                    }
                    return;
                }

                ViewGroup parent = (ViewGroup) holderView.getParent();
                ViewGroup attachTarget = parent != null ? parent : holderView;
                MatrixSuggestionBarView barView = getOrCreateBarView(holderView);
                if (barView == null) {
                    return;
                }

                // Check if Gboard is in Undo state
                if (MatrixGboardReflector.isUndoActive(holderView, Collections.emptyList())) {
                    Log.i(TAG, "onCandidatesCleared: Undo is active. Dismissing matrix bar and restoring native Undo UI.");
                    SENTENCE_BUFFER.clear();
                    activeOriginalSuggestions = null;
                    hideJumpToEndArrow();
                    barView.setVisibility(View.GONE);
                    MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                    return;
                }

                if (barView.getSelectedHistoryIndex() < 0 && MatrixTextHelper.isCursorAtEnd(holderView)) {
                    hideJumpToEndArrow();
                }

                if (MatrixTextHelper.isSentenceEnded(holderView) || MatrixTextHelper.isInputEmpty(holderView)) {
                    SENTENCE_BUFFER.clear();
                    activeOriginalSuggestions = null;
                    hideJumpToEndArrow();
                    barView.setVisibility(View.GONE);
                    MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                    return;
                }

                // If an active word is present (allowTrailingSpace = false: if candidates are cleared after space, no active word!)
                String activeWord = MatrixTextHelper.getActiveWord(holderView, Collections.emptyList(), false);
                if (activeWord != null && !activeWord.isEmpty()) {
                    List<String> words = Collections.singletonList(activeWord);
                    MatrixTextHelper.syncSentenceBufferFromText(holderView, activeWord, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
                    barView.updateMatrix(
                            SENTENCE_BUFFER.getEntries(),
                            words,
                            0,
                            (selectedIndex, word) -> {
                                if (MatrixTextHelper.isCursorAtEnd(holderView)) {
                                    hideJumpToEndArrow();
                                }
                                commitCandidate(holderView, new CandidateItem(word, -1, true, true, false, "CLEARED_ACTIVE", null, null), Collections.emptyList());
                            },
                            (selectedIndex, newWord) -> {
                                if (MatrixTextHelper.isCursorAtEnd(holderView)) {
                                    hideJumpToEndArrow();
                                }
                                lastSelfReplaceTimestamp = SystemClock.uptimeMillis();
                                lastSelfReplacedWord = newWord;
                                MatrixTextHelper.replaceActiveWordInTextField(holderView, newWord);
                            },
                            (histColIdx, oldWord, newWord) -> {
                                MatrixTextHelper.replaceHistoryWord(holderView, SENTENCE_BUFFER, histColIdx, oldWord, newWord);
                            },
                            createHistoryWordClickCallback(holderView, barView)
                    );
                    barView.setVisibility(View.VISIBLE);
                    MatrixGboardReflector.applyKeyboardBackground(barView, holderView);
                    MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
                    barView.bringToFront();
                    return;
                }

                MatrixTextHelper.syncSentenceBufferFromText(holderView, null, SENTENCE_BUFFER, WORD_ALTERNATIVES_CACHE);
                if (!SENTENCE_BUFFER.isEmpty()) {
                    barView.updateHistoryOnly(
                            SENTENCE_BUFFER.getEntries(),
                            (histColIdx, oldWord, newWord) -> {
                                MatrixTextHelper.replaceHistoryWord(holderView, SENTENCE_BUFFER, histColIdx, oldWord, newWord);
                            },
                            createHistoryWordClickCallback(holderView, barView)
                    );
                    barView.setVisibility(View.VISIBLE);
                    MatrixGboardReflector.applyKeyboardBackground(barView, holderView);
                    MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
                    barView.bringToFront();
                } else {
                    hideJumpToEndArrow();
                    barView.setVisibility(View.GONE);
                    MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to clear matrix suggestions", t);
            }
        });
    }

    private static MatrixSuggestionBarView getOrCreateBarView(ViewGroup holderView) {
        ViewGroup parent = (ViewGroup) holderView.getParent();
        ViewGroup attachTarget = parent != null ? parent : holderView;

        MatrixSuggestionBarView bar = ACTIVE_BARS.get(attachTarget);
        if (bar != null && bar.getParent() == attachTarget) {
            return bar;
        }

        // Look for existing tagged child
        for (int i = 0; i < attachTarget.getChildCount(); i++) {
            View child = attachTarget.getChildAt(i);
            if (child instanceof MatrixSuggestionBarView existing && MATRIX_BAR_TAG.equals(existing.getTag())) {
                ACTIVE_BARS.put(attachTarget, existing);
                return existing;
            }
        }

        // Create new
        Context context = attachTarget.getContext();
        bar = new MatrixSuggestionBarView(context);
        bar.setTag(MATRIX_BAR_TAG);
        MatrixGboardReflector.applyKeyboardBackground(bar, holderView);

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

        final MatrixSuggestionBarView finalBar = bar;
        // Keep quick toggles suppressed and MatrixBar at front whenever MatrixBar is visible,
        // UNLESS Gboard surfaces an Undo view!
        attachTarget.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (MatrixGboardReflector.isUndoViewActive(attachTarget)) {
                if (finalBar.getVisibility() != View.GONE) {
                    finalBar.setVisibility(View.GONE);
                }
                MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                return;
            }
            if (finalBar.getVisibility() == View.VISIBLE) {
                MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, true);
                if (attachTarget.getChildCount() > 0 && attachTarget.getChildAt(attachTarget.getChildCount() - 1) != finalBar) {
                    finalBar.bringToFront();
                }
            }
        });

        // Watch for holderView visibility / layout changes
        holderView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (MatrixGboardReflector.isUndoViewActive(attachTarget)) {
                if (finalBar.getVisibility() != View.GONE) {
                    finalBar.setVisibility(View.GONE);
                }
                MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
                return;
            }
            if (v.getVisibility() != View.VISIBLE || (right - left <= 0)) {
                if (MatrixTextHelper.isSentenceEnded(v) || MatrixTextHelper.isInputEmpty(v)) {
                    if (finalBar.getVisibility() != View.GONE) {
                        finalBar.setVisibility(View.GONE);
                        MatrixGboardReflector.updateHeaderViewsVisibility(attachTarget, holderView, false);
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
        String lastWord = (currentWord != null && !currentWord.isEmpty()) ? currentWord : MatrixTextHelper.getActiveWord(holderView, items);
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
        boolean nativeCommitted = MatrixGboardReflector.commitCandidateNative(holderView, selectedItem);

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

    private static void replaceActiveCandidateViaInputConnection(ViewGroup holderView, CandidateItem selectedItem) {
        try {
            InputConnection ic = MatrixTextHelper.getInputConnection(holderView);
            if (ic == null) return;

            CharSequence cs = ic.getTextBeforeCursor(200, 0);
            if (cs == null) {
                ic.commitText(selectedItem.text + " ", 1);
                return;
            }

            String text = cs.toString();
            String trimmed = text.trim();
            int lastBoundary = -1;
            for (int i = trimmed.length() - 1; i >= 0; i--) {
                if (MatrixTextHelper.isBoundaryChar(trimmed, i)) {
                    lastBoundary = i;
                    break;
                }
            }
            String lastWord = lastBoundary >= 0 ? trimmed.substring(lastBoundary + 1) : trimmed;

            if (lastWord.equalsIgnoreCase(selectedItem.text)) {
                // Word is already in the text field; ensure trailing space
                if (!text.endsWith(" ")) {
                    ic.commitText(" ", 1);
                }
                return;
            }

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

            ic.commitText(selectedItem.text + " ", 1);
        } catch (Throwable t) {
            Log.e(TAG, "replaceActiveCandidateViaInputConnection error", t);
        }
    }

    private static void cacheAlternativeGroup(List<String> words) {
        if (words == null || words.isEmpty()) return;
        List<String> copy = new ArrayList<>(words);
        for (String w : words) {
            if (w != null && !w.isEmpty()) {
                WORD_ALTERNATIVES_CACHE.put(w.toLowerCase(), copy);
            }
        }
    }

    private static MatrixSuggestionBarView.OnHistoryWordClickCallback createHistoryWordClickCallback(
            ViewGroup holderView, MatrixSuggestionBarView barView) {
        return (histColIdx, word) -> {
            boolean selected = MatrixTextHelper.selectHistoryWordInTextField(holderView, SENTENCE_BUFFER, histColIdx, word);
            if (selected) {
                showJumpToEndArrow(holderView, () -> {
                    MatrixTextHelper.jumpToEndOfTextField(holderView);
                    barView.clearHistorySelection();
                    hideJumpToEndArrow();
                    barView.post(() -> barView.fullScroll(View.FOCUS_RIGHT));
                });
            }
        };
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
            currentJumpToEndArrow.updateIconColor();
            currentJumpToEndArrow.setVisibility(View.VISIBLE);
            currentJumpToEndArrow.bringToFront();
            return;
        }

        ViewGroup headerContainer = MatrixGboardReflector.findHeaderContainer(holderView);
        View voiceBtn = null;
        if (headerContainer != null) {
            voiceBtn = MatrixGboardReflector.findVoiceButton(headerContainer);
        }
        if (voiceBtn == null) {
            ViewGroup holderParent = (ViewGroup) holderView.getParent();
            if (holderParent != null) {
                voiceBtn = MatrixGboardReflector.findVoiceButton(holderParent);
            }
        }

        Context context = holderView.getContext();
        if (currentJumpToEndArrow == null) {
            currentJumpToEndArrow = new JumpToEndArrowView(context);
        }
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

    public static MatrixSentenceBuffer getSentenceBuffer() {
        return SENTENCE_BUFFER;
    }

    public static void recordNintypeDecodedWord(String committedWord, List<String> alternatives) {
        try {
            if (alternatives != null && !alternatives.isEmpty()) {
                cacheAlternativeGroup(alternatives);
            }
            if (committedWord != null && !committedWord.isEmpty()) {
                List<String> alts = (alternatives != null && !alternatives.isEmpty())
                        ? new ArrayList<>(alternatives)
                        : Collections.singletonList(committedWord);
                SENTENCE_BUFFER.addWord(committedWord, alts, 0);
            }
        } catch (Throwable ignored) {
        }
    }
}
