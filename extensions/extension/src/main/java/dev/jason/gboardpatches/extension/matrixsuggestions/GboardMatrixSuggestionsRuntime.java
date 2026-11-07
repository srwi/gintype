package dev.jason.gboardpatches.extension.matrixsuggestions;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

        public CandidateItem(String text, int slotIndex, boolean isRaw, boolean isAutoCorrect, boolean isPrediction, String candidateType, Object rawCandidateObj, View softKey) {
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

    private static Field candidateTextField = null;
    private static Field candidateTypeField = null;
    private static Field candidateAutoCorrectField = null;
    private static boolean candidateFieldsLookupAttempted = false;

    private static List<CandidateItem> lastComposingCandidates = Collections.emptyList();
    private static List<String> activeOriginalSuggestions = null;
    private static volatile long lastSelfReplaceTimestamp = 0;
    private static volatile String lastSelfReplacedWord = null;

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

        // Run after LatinFixedCountCandidatesHolderView.D completes and populates slot views
        holderView.post(() -> {
            try {
                MatrixSuggestionBarView barView = getOrCreateBarView(holderView);
                if (barView == null) {
                    return;
                }
                ViewGroup parent = (ViewGroup) holderView.getParent();
                ViewGroup attachTarget = parent != null ? parent : holderView;

                // Extract slot candidates directly from holderView slots
                List<CandidateItem> items = extractSlotCandidatesFromHolder(holderView, candidateListObj);

                String currentWord = getActiveWord(holderView, items);
                Log.i(TAG, "onCandidatesUpdated: resolved " + items.size() + " candidates, currentWord='" + currentWord + "': " + items);

                // Case 1: No active word being typed/swiped (cursor after manual space, punctuation, or empty)
                if (currentWord == null || currentWord.isEmpty()) {
                    activeOriginalSuggestions = null;
                    if (isSentenceEnded(holderView) || isInputEmpty(holderView)) {
                        SENTENCE_BUFFER.clear();
                        barView.setVisibility(View.GONE);
                        suppressQuickToggles(attachTarget, false);
                    } else {
                        updateSentenceBufferFromText(holderView, null);
                        if (!SENTENCE_BUFFER.isEmpty()) {
                            barView.updateHistoryOnly(SENTENCE_BUFFER.getEntries(), (histColIdx, oldWord, newWord) -> {
                                replaceHistoryWord(holderView, histColIdx, oldWord, newWord);
                            });
                            barView.setVisibility(View.VISIBLE);
                            suppressQuickToggles(attachTarget, true);
                            barView.bringToFront();
                        } else {
                            barView.setVisibility(View.GONE);
                            suppressQuickToggles(attachTarget, false);
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

                // Enforce exact typed casing on the candidate matching currentWord!
                if (centerIndex >= 0 && centerIndex < words.size() && words.get(centerIndex).equalsIgnoreCase(currentWord)) {
                    words.set(centerIndex, currentWord);
                }

                // If this candidate update was caused by our own flick/tap word replacement,
                // OR if currentWord belongs to our activeOriginalSuggestions group:
                // PRESERVE the exact 3 original suggestions so the user can flick back and forth!
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
                    // Enforce exact typed casing
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
                    // Enforce exact typed casing
                    if (centerIndex >= 0 && centerIndex < words.size() && words.get(centerIndex).equalsIgnoreCase(currentWord)) {
                        words.set(centerIndex, currentWord);
                    }
                    activeOriginalSuggestions = new ArrayList<>(words);
                    cacheAlternativeGroup(words);
                }

                // Update sentence buffer with completed words strictly before current active word
                updateSentenceBufferFromText(holderView, currentWord);

                lastComposingCandidates = new ArrayList<>(items);
                final List<CandidateItem> finalItems = new ArrayList<>(items);
                final List<String> currentWordsList = new ArrayList<>(words);

                holderView.setAlpha(0.0f);
                barView.updateMatrix(
                        SENTENCE_BUFFER.getEntries(),
                        words,
                        centerIndex,
                        // onCommit: Tapping active word center pill commits with trailing space!
                        (selectedIndex, word) -> {
                            CandidateItem chosen = (selectedIndex >= 0 && selectedIndex < finalItems.size())
                                    ? finalItems.get(selectedIndex)
                                    : new CandidateItem(word, selectedIndex, false, false, false, "", null, null);
                            commitCandidate(holderView, chosen, finalItems);
                        },
                        // onActiveSwap: Flick UP/DOWN or tap Top/Bottom swaps active word WITHOUT space!
                        (selectedIndex, newWord) -> {
                            replaceActiveWordInTextField(holderView, newWord);
                            cacheAlternativeGroup(currentWordsList);
                        },
                        // onHistoryReplace: Flick or tap on a history column swaps that past word in sentence!
                        (histColIdx, oldWord, newWord) -> {
                            replaceHistoryWord(holderView, histColIdx, oldWord, newWord);
                        }
                );
                barView.setVisibility(View.VISIBLE);
                suppressQuickToggles(attachTarget, true);
                barView.bringToFront();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to update matrix suggestions", t);
            }
        });
    }

    /**
     * Extracts candidates directly from the holder view's slots (0, 1, 2) where
     * Gboard has assigned them. Falls back to candidateListObj if slots are empty.
     */
    private static List<CandidateItem> extractSlotCandidatesFromHolder(ViewGroup holderView, Object fallbackListObj) {
        List<CandidateItem> items = new ArrayList<>();

        try {
            Method aMethod = null;
            Method rMethod = null;
            Method bMethod = null;
            Method zMethod = null;
            for (Method m : holderView.getClass().getMethods()) {
                if (m.getName().equals("a") && m.getParameterCount() == 0 && m.getReturnType() == int.class) {
                    aMethod = m;
                } else if (m.getName().equals("B") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                    bMethod = m;
                } else if (m.getName().equals("z") && m.getParameterCount() == 1) {
                    zMethod = m;
                }
            }

            try {
                rMethod = holderView.getClass().getDeclaredMethod("R", int.class);
                rMethod.setAccessible(true);
            } catch (Throwable ignored) {
            }

            int count = 0;
            if (aMethod != null) {
                count = (Integer) aMethod.invoke(holderView);
            }

            if (count > 0) {
                for (int slot = 0; slot < count; slot++) {
                    Object cand = null;
                    if (rMethod != null) {
                        try {
                            cand = rMethod.invoke(holderView, slot);
                        } catch (Throwable ignored) {
                        }
                    }
                    View softKey = (bMethod != null) ? (View) bMethod.invoke(holderView, slot) : null;
                    if (cand == null && softKey != null && zMethod != null) {
                        try {
                            cand = zMethod.invoke(null, softKey);
                        } catch (Throwable ignored) {
                        }
                    }

                    if (cand != null) {
                        String text = extractCandidateText(cand);
                        if (text != null && !text.trim().isEmpty()) {
                            boolean isRaw = extractCandidateIsRaw(cand);
                            boolean isAuto = extractCandidateIsAutoCorrect(cand);
                            boolean isPred = extractCandidateIsPrediction(cand);
                            String type = extractCandidateType(cand);
                            items.add(new CandidateItem(text, slot, isRaw, isAuto, isPred, type, cand, softKey));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "extractSlotCandidatesFromHolder error: " + t.getMessage());
        }

        // If holder slots were empty, fall back to parsing the list
        if (items.isEmpty() && fallbackListObj != null) {
            items = extractCandidates(fallbackListObj);
        }

        return items;
    }

    /**
     * Determines which candidate corresponds to the active word in the middle line.
     * 1. Matches verbatim typed or swiped word from text field.
     * 2. Checks if any SoftKeyView was activated by Gboard.
     * 3. Checks holderView field 'r' (Gboard's active candidate slot).
     * 4. For normal typing: verbatim typed word has isRaw == true.
     * 5. For swipe / glide typing: swiped word has isAutoCorrect == true (Lowu.g == true).
     * 6. Center slot (index 1 if >= 2 items, else 0).
     */
    private static int determineCenterCandidateIndex(ViewGroup holderView, List<CandidateItem> items, String currentWord) {
        if (items.isEmpty()) return 0;

        // 1. Exact or case-insensitive match with current typed or swiped word before cursor
        String lastWord = (currentWord != null && !currentWord.isEmpty()) ? currentWord : getActiveWord(holderView, items);
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

        // 5. Swipe / glide typing: swiped word has isAutoCorrect flag (Lowu.g == true)
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
                ViewGroup parent = (ViewGroup) holderView.getParent();
                ViewGroup attachTarget = parent != null ? parent : holderView;
                MatrixSuggestionBarView barView = getOrCreateBarView(holderView);
                if (barView == null) {
                    return;
                }

                if (isSentenceEnded(holderView) || isInputEmpty(holderView)) {
                    SENTENCE_BUFFER.clear();
                    activeOriginalSuggestions = null;
                    barView.setVisibility(View.GONE);
                    suppressQuickToggles(attachTarget, false);
                    return;
                }

                // If an active word is present (e.g. unknown swiped word like 'toggles' whose candidates were cleared):
                String activeWord = getActiveWord(holderView, Collections.emptyList());
                if (activeWord != null && !activeWord.isEmpty()) {
                    List<String> words = Collections.singletonList(activeWord);
                    updateSentenceBufferFromText(holderView, activeWord);
                    barView.updateMatrix(
                            SENTENCE_BUFFER.getEntries(),
                            words,
                            0,
                            (selectedIndex, word) -> {
                                commitCandidate(holderView, new CandidateItem(word, -1, true, true, false, "CLEARED_ACTIVE", null, null), Collections.emptyList());
                            },
                            (selectedIndex, newWord) -> {
                                replaceActiveWordInTextField(holderView, newWord);
                            },
                            (histColIdx, oldWord, newWord) -> {
                                replaceHistoryWord(holderView, histColIdx, oldWord, newWord);
                            }
                    );
                    barView.setVisibility(View.VISIBLE);
                    suppressQuickToggles(attachTarget, true);
                    barView.bringToFront();
                    return;
                }

                updateSentenceBufferFromText(holderView, null);
                if (!SENTENCE_BUFFER.isEmpty()) {
                    // Keep sentence history visible even when no active candidate during sentence!
                    barView.updateHistoryOnly(SENTENCE_BUFFER.getEntries(), (histColIdx, oldWord, newWord) -> {
                        replaceHistoryWord(holderView, histColIdx, oldWord, newWord);
                    });
                    barView.setVisibility(View.VISIBLE);
                    suppressQuickToggles(attachTarget, true);
                    barView.bringToFront();
                } else {
                    barView.setVisibility(View.GONE);
                    suppressQuickToggles(attachTarget, false);
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
        // Keep quick toggles suppressed and MatrixBar at front whenever MatrixBar is visible!
        attachTarget.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (finalBar.getVisibility() == View.VISIBLE) {
                suppressQuickToggles(attachTarget, true);
                if (attachTarget.getChildCount() > 0 && attachTarget.getChildAt(attachTarget.getChildCount() - 1) != finalBar) {
                    finalBar.bringToFront();
                }
            }
        });

        // Watch for holderView visibility / layout changes (e.g. when Gboard shows shortcut icons)
        holderView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (v.getVisibility() != View.VISIBLE || (right - left <= 0)) {
                // Only hide if sentence ended or input empty!
                if (isSentenceEnded(v) || isInputEmpty(v)) {
                    if (finalBar.getVisibility() != View.GONE) {
                        finalBar.setVisibility(View.GONE);
                        suppressQuickToggles(attachTarget, false);
                    }
                }
            }
        });

        return bar;
    }

    private static void suppressQuickToggles(ViewGroup attachTarget, boolean suppress) {
        if (attachTarget == null) return;
        try {
            float alpha = suppress ? 0.0f : 1.0f;
            int vis = suppress ? View.INVISIBLE : View.VISIBLE;

            // 1. All siblings of MatrixSuggestionBarView inside attachTarget
            for (int i = 0; i < attachTarget.getChildCount(); i++) {
                View child = attachTarget.getChildAt(i);
                if (child instanceof MatrixSuggestionBarView) {
                    continue;
                }
                child.setAlpha(alpha);
                child.setVisibility(vis);
            }

            // 2. All siblings of attachTarget inside its parent (if header separates candidates & access points)
            ViewGroup parent = (ViewGroup) attachTarget.getParent();
            if (parent != null) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View sibling = parent.getChildAt(i);
                    if (sibling != attachTarget && !(sibling instanceof MatrixSuggestionBarView)) {
                        String name = sibling.getClass().getName();
                        if (name.contains("AccessPoint") || name.contains("accesspoint") || name.contains("Header") || name.contains("Strip") || name.contains("Toolbar")) {
                            sibling.setAlpha(alpha);
                            sibling.setVisibility(vis);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "suppressQuickToggles error: " + t.getMessage());
        }
    }

    /**
     * Updates the sentence history buffer from the actual text before cursor,
     * archiving completed words strictly before currentWord.
     */
    private static void updateSentenceBufferFromText(ViewGroup holderView, String currentWord) {
        try {
            InputConnection ic = getInputConnection(holderView);
            if (ic == null) return;

            CharSequence cs = ic.getTextBeforeCursor(1000, 0);
            if (cs == null || cs.length() == 0) {
                SENTENCE_BUFFER.clear();
                return;
            }

            String text = cs.toString();
            int sentStart = 0;
            for (int i = text.length() - 1; i >= 0; i--) {
                char c = text.charAt(i);
                if (c == '\n' || c == '.' || c == '!' || c == '?') {
                    if (i < text.length() - 1) {
                        sentStart = i + 1;
                        break;
                    }
                }
            }
            String currentSentence = text.substring(sentStart).trim();
            if (currentSentence.isEmpty()) {
                SENTENCE_BUFFER.clear();
                return;
            }

            String[] rawWords = currentSentence.split("[\\s,\\;\\:\\-\\(\\)\"/]+");
            List<String> sentenceWords = new ArrayList<>();
            for (String w : rawWords) {
                String tw = w.trim();
                if (!tw.isEmpty()) {
                    sentenceWords.add(tw);
                }
            }

            if (sentenceWords.isEmpty()) {
                SENTENCE_BUFFER.clear();
                return;
            }

            // The last word in the sentence is the active word being typed/swiped.
            // Only words before currentWord belong in the sentence history columns:
            int historyWordCount = sentenceWords.size() - 1;
            if (currentWord == null || currentWord.isEmpty()) {
                historyWordCount = sentenceWords.size();
            }

            SENTENCE_BUFFER.clear();
            for (int i = 0; i < historyWordCount; i++) {
                String w = sentenceWords.get(i);
                List<String> alts = WORD_ALTERNATIVES_CACHE.get(w.toLowerCase());
                int selIdx = 0;
                if (alts != null && !alts.isEmpty()) {
                    for (int a = 0; a < alts.size(); a++) {
                        if (alts.get(a).equalsIgnoreCase(w)) {
                            selIdx = a;
                            break;
                        }
                    }
                } else {
                    alts = Collections.singletonList(w);
                }
                SENTENCE_BUFFER.addWord(w, alts, selIdx);
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateSentenceBufferFromText error: " + t.getMessage());
        }
    }

    /**
     * Commits the chosen candidate into Gboard and the target text field.
     * Invokes Gboard's native SoftKeyboardView.a(SoftKeyView) exclusively.
     * Falls back to clean InputConnection replacement only if native invocation failed.
     */
    private static void commitCandidate(ViewGroup holderView, CandidateItem selectedItem, List<CandidateItem> allItems) {
        Log.i(TAG, "commitCandidate: word=" + selectedItem.text + " slotIndex=" + selectedItem.slotIndex);
        boolean nativeCommitted = false;

        try {
            ViewGroup softKeyboardView = findSoftKeyboardView(holderView);

            // 1. Resolve SoftKeyView for the candidate's slot
            View softKey = selectedItem.softKey;
            if (softKey == null && selectedItem.slotIndex >= 0) {
                try {
                    Method bMethod = holderView.getClass().getMethod("B", int.class);
                    softKey = (View) bMethod.invoke(holderView, selectedItem.slotIndex);
                } catch (Throwable ignored) {
                }
            }

            // 2. Invoke SoftKeyboardView.a(softKey) EXCLUSIVELY
            if (softKeyboardView != null && softKey != null && softKey.isAttachedToWindow()) {
                try {
                    for (Method m : softKeyboardView.getClass().getMethods()) {
                        if (m.getName().equals("a") && m.getParameterCount() == 1
                                && m.getParameterTypes()[0].isAssignableFrom(softKey.getClass())) {
                            m.setAccessible(true);
                            m.invoke(softKeyboardView, softKey);
                            nativeCommitted = true;
                            Log.i(TAG, "commitCandidate: successfully invoked SoftKeyboardView.a(softKey)");
                            break;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed calling SoftKeyboardView.a: " + t.getMessage());
                }
            }

            // 3. Fallback: clean InputConnection replacement
            if (!nativeCommitted) {
                replaceActiveCandidateViaInputConnection(holderView, selectedItem, allItems);
            }

            // 4. Record in sentence buffer
            List<String> altStrings = new ArrayList<>();
            for (CandidateItem ci : allItems) {
                altStrings.add(ci.text);
            }
            int selIdx = allItems.indexOf(selectedItem);
            SENTENCE_BUFFER.addWord(selectedItem.text, altStrings, selIdx >= 0 ? selIdx : 0);
        } catch (Throwable t) {
            Log.e(TAG, "Failed committing candidate: " + selectedItem, t);
        }
    }

    private static void replaceActiveCandidateViaInputConnection(ViewGroup holderView, CandidateItem selectedItem, List<CandidateItem> allItems) {
        try {
            InputConnection ic = getInputConnection(holderView);
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
                char c = trimmed.charAt(i);
                if (isBoundaryChar(c)) {
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

    private static ViewGroup findSoftKeyboardView(ViewGroup holderView) {
        View p = holderView;
        while (p != null) {
            if (p.getClass().getName().contains("SoftKeyboardView")) {
                return (ViewGroup) p;
            }
            if (p.getParent() instanceof View) {
                p = (View) p.getParent();
            } else {
                break;
            }
        }
        return null;
    }

    private static Object findKeyboardController(ViewGroup holderView) {
        try {
            Field eField = null;
            for (Field f : holderView.getClass().getDeclaredFields()) {
                if (f.getName().equals("e")) {
                    eField = f;
                    break;
                }
            }
            if (eField == null) return null;
            eField.setAccessible(true);
            Object leyn = eField.get(holderView);
            if (leyn == null) return null;

            Field aField = null;
            for (Field f : leyn.getClass().getDeclaredFields()) {
                if (f.getName().equals("a")) {
                    aField = f;
                    break;
                }
            }
            if (aField == null) return null;
            aField.setAccessible(true);
            Object leyo = aField.get(leyn);
            if (leyo == null) return null;

            Field bField = null;
            for (Field f : leyo.getClass().getDeclaredFields()) {
                if (f.getName().equals("b")) {
                    bField = f;
                    break;
                }
            }
            if (bField == null) return null;
            bField.setAccessible(true);
            return bField.get(leyo);
        } catch (Throwable t) {
            Log.w(TAG, "findKeyboardController failed: " + t.getMessage());
            return null;
        }
    }

    public static boolean isInputEmpty(View view) {
        try {
            InputConnection ic = getInputConnection(view);
            if (ic == null) return true;
            CharSequence cs = ic.getTextBeforeCursor(10, 0);
            return cs == null || cs.length() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isSentenceEnded(View view) {
        try {
            InputConnection ic = getInputConnection(view);
            if (ic == null) return false;
            CharSequence cs = ic.getTextBeforeCursor(20, 0);
            if (cs == null || cs.length() == 0) return false;
            String s = cs.toString();
            int idx = s.length() - 1;
            while (idx >= 0 && Character.isWhitespace(s.charAt(idx))) {
                idx--;
            }
            if (idx < 0) return false;
            char last = s.charAt(idx);
            return (last == '.' || last == '!' || last == '?' || last == '\n');
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean allPredictions(List<CandidateItem> items) {
        if (items == null || items.isEmpty()) return false;
        for (CandidateItem ci : items) {
            if (!ci.isPrediction) return false;
        }
        return true;
    }

    public static String getActiveWord(View view, List<CandidateItem> items) {
        try {
            InputConnection ic = getInputConnection(view);
            if (ic == null) return null;
            CharSequence cs = ic.getTextBeforeCursor(100, 0);
            if (cs == null || cs.length() == 0) return null;
            String s = cs.toString();
            int len = s.length();
            if (len == 0) return null;

            int end = len;
            if (Character.isWhitespace(s.charAt(len - 1))) {
                // Trailing whitespace present.
                // If there are multiple trailing whitespaces, user pressed multiple spaces -> no active word.
                if (len >= 2 && Character.isWhitespace(s.charAt(len - 2))) {
                    return null;
                }

                // If items is non-empty and EVERY item is a next-word prediction:
                // then the word was completed and user moved on. Suppress next-word predictions per user request.
                if (items != null && !items.isEmpty() && allPredictions(items)) {
                    return null;
                }

                // Otherwise (swipe typing auto-space, or word without predictions like 'toggles'):
                // the word right before the single trailing space is our active word!
                end--;
                if (end <= 0 || Character.isWhitespace(s.charAt(end - 1))) {
                    return null;
                }
            }

            int start = end - 1;
            while (start >= 0 && !isBoundaryChar(s.charAt(start))) {
                start--;
            }
            if (start < end - 1) {
                String word = s.substring(start + 1, end).trim();
                if (!word.isEmpty()) {
                    return word;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getCurrentComposingWord(View view) {
        try {
            InputConnection ic = getInputConnection(view);
            if (ic == null) return null;
            CharSequence cs = ic.getTextBeforeCursor(100, 0);
            if (cs == null || cs.length() == 0) return null;
            String s = cs.toString();
            int end = s.length();
            if (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
                return null;
            }
            int start = end;
            while (start > 0 && !Character.isWhitespace(s.charAt(start - 1))) {
                start--;
            }
            if (start < end) {
                return s.substring(start, end);
            }
        } catch (Throwable ignored) {
        }
        return null;
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

    public static void replaceActiveWordInTextField(ViewGroup holderView, String newWord) {
        if (newWord == null || newWord.isEmpty()) return;
        try {
            lastSelfReplaceTimestamp = SystemClock.uptimeMillis();
            lastSelfReplacedWord = newWord;
            InputConnection ic = getInputConnection(holderView);
            if (ic == null) return;

            ic.finishComposingText();

            CharSequence cs = ic.getTextBeforeCursor(200, 0);
            if (cs == null || cs.length() == 0) {
                ic.commitText(newWord, 1);
                return;
            }

            String text = cs.toString();
            int end = text.length();
            while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            if (end <= 0) {
                ic.commitText(newWord, 1);
                return;
            }

            int start = end;
            while (start > 0 && !isBoundaryChar(text.charAt(start - 1))) {
                start--;
            }

            String oldWord = text.substring(start, end);
            if (oldWord.equals(newWord)) {
                return;
            }

            int deleteLen = text.length() - start;
            if (deleteLen > 0 && deleteLen <= text.length()) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(deleteLen, 0);
                ic.commitText(newWord, 1);
                ic.endBatchEdit();
                Log.i(TAG, "replaceActiveWordInTextField: cleanly replaced '" + oldWord + "' with '" + newWord + "' (no space)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "replaceActiveWordInTextField failed", t);
        }
    }

    public static void replaceHistoryWord(View anchorView, int historyColIndex, String oldWord, String newWord) {
        try {
            InputConnection ic = getInputConnection(anchorView);
            if (ic == null) return;

            ic.finishComposingText();

            CharSequence textBefore = ic.getTextBeforeCursor(2000, 0);
            if (textBefore == null) return;

            String text = textBefore.toString();
            List<MatrixSentenceBuffer.WordEntry> entries = SENTENCE_BUFFER.getEntries();
            int[] pos = findHistoryWordPosition(text, entries, historyColIndex, oldWord);

            if (pos != null && pos[0] >= 0 && pos[1] <= text.length() && pos[0] < pos[1]) {
                String textAfter = text.substring(pos[1]);
                int deleteCount = text.length() - pos[0];
                ic.beginBatchEdit();
                ic.deleteSurroundingText(deleteCount, 0);
                ic.commitText(newWord + textAfter, 1);
                ic.endBatchEdit();
                Log.i(TAG, "replaceHistoryWord: replaced '" + oldWord + "' with '" + newWord + "' at [" + pos[0] + ", " + pos[1] + "]");
            } else {
                Log.w(TAG, "replaceHistoryWord: could not find boundary position for '" + oldWord + "' (col=" + historyColIndex + ")");
            }
        } catch (Throwable t) {
            Log.e(TAG, "replaceHistoryWord failed", t);
        }
    }

    public static int[] findHistoryWordPosition(String text, List<MatrixSentenceBuffer.WordEntry> history, int targetColIndex, String oldWord) {
        if (text == null || oldWord == null || text.isEmpty() || oldWord.isEmpty()) {
            return null;
        }

        int wordsAfter = (history != null && targetColIndex >= 0 && targetColIndex < history.size())
                ? (history.size() - 1 - targetColIndex)
                : 0;

        int end = text.length();
        int wordsSkipped = 0;

        while (end > 0) {
            while (end > 0 && isBoundaryChar(text.charAt(end - 1))) {
                end--;
            }
            if (end <= 0) break;

            int start = end;
            while (start > 0 && !isBoundaryChar(text.charAt(start - 1))) {
                start--;
            }

            String currentWord = text.substring(start, end);

            if (wordsSkipped == wordsAfter) {
                if (currentWord.equalsIgnoreCase(oldWord)) {
                    return new int[]{start, end};
                }
            } else if (wordsSkipped < wordsAfter) {
                wordsSkipped++;
                end = start;
                continue;
            }

            end = start;
        }

        return findLastWordBoundary(text, oldWord);
    }

    public static int[] findLastWordBoundary(String text, String word) {
        if (text == null || word == null || text.isEmpty() || word.isEmpty()) {
            return null;
        }
        int wLen = word.length();
        int tLen = text.length();

        for (int i = tLen - wLen; i >= 0; i--) {
            if (text.regionMatches(true, i, word, 0, wLen)) {
                boolean beforeOk = (i == 0) || isBoundaryChar(text.charAt(i - 1));
                boolean afterOk = (i + wLen == tLen) || isBoundaryChar(text.charAt(i + wLen));
                if (beforeOk && afterOk) {
                    return new int[]{i, i + wLen};
                }
            }
        }
        return null;
    }

    private static boolean isBoundaryChar(char c) {
        return Character.isWhitespace(c) || c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' || c == '"' || c == '\'' || c == '-' || c == '/' || c == '(' || c == ')';
    }

    /**
     * Resolves the InputConnection robustly through Context wrappers,
     * Keyboard instance, and view hierarchy.
     */
    public static InputConnection getInputConnection(View view) {
        if (view == null) return null;

        // 1. Check View's context hierarchy
        Context ctx = view.getContext();
        while (ctx instanceof ContextWrapper && !(ctx instanceof InputMethodService)) {
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        if (ctx instanceof InputMethodService ims) {
            InputConnection ic = ims.getCurrentInputConnection();
            if (ic != null) return ic;
        }

        // 2. Traversal through Keyboard controller (AbstractKeyboard.z holds LatinIME Context)
        try {
            if (view instanceof ViewGroup vg) {
                Object keyboard = findKeyboardController(vg);
                if (keyboard != null) {
                    for (Class<?> c = keyboard.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        try {
                            Field zField = c.getDeclaredField("z");
                            zField.setAccessible(true);
                            Object zContext = zField.get(keyboard);
                            if (zContext instanceof Context) {
                                Context kCtx = (Context) zContext;
                                while (kCtx instanceof ContextWrapper && !(kCtx instanceof InputMethodService)) {
                                    kCtx = ((ContextWrapper) kCtx).getBaseContext();
                                }
                                if (kCtx instanceof InputMethodService ims) {
                                    InputConnection ic = ims.getCurrentInputConnection();
                                    if (ic != null) return ic;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    // Traversal through keyboard.A (Lpkz / Loid) -> field j (Lpdb) -> getCurrentInputConnection()
                    for (Class<?> c = keyboard.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        try {
                            Field aField = c.getDeclaredField("A");
                            aField.setAccessible(true);
                            Object pkz = aField.get(keyboard);
                            if (pkz != null) {
                                for (Field f : pkz.getClass().getDeclaredFields()) {
                                    f.setAccessible(true);
                                    Object val = f.get(pkz);
                                    if (val != null) {
                                        try {
                                            Method m = val.getClass().getMethod("getCurrentInputConnection");
                                            InputConnection ic = (InputConnection) m.invoke(val);
                                            if (ic != null) return ic;
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getInputConnection traversal error: " + t.getMessage());
        }

        return null;
    }

    private static String extractCandidateText(Object item) {
        if (item == null) return null;
        ensureCandidateFields(item);
        if (candidateTextField != null) {
            try {
                Object textObj = candidateTextField.get(item);
                if (textObj instanceof CharSequence cs && cs.length() > 0) {
                    return cs.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String extractCandidateType(Object item) {
        if (item == null) return "";
        ensureCandidateFields(item);
        if (candidateTypeField != null) {
            try {
                Object typeObj = candidateTypeField.get(item);
                if (typeObj instanceof Enum<?> e) {
                    return e.name();
                } else if (typeObj != null) {
                    return typeObj.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static boolean extractCandidateIsPrediction(Object item) {
        if (item == null) return false;
        ensureCandidateFields(item);
        if (candidateTypeField != null) {
            try {
                Object typeObj = candidateTypeField.get(item);
                if (typeObj instanceof Enum<?> e) {
                    return e.ordinal() == 2 || "PREDICTION".equalsIgnoreCase(e.name()) || e.name().contains("PREDICTION");
                } else if (typeObj != null) {
                    String name = typeObj.toString();
                    return "PREDICTION".equalsIgnoreCase(name) || name.contains("PREDICTION");
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean extractCandidateIsRaw(Object item) {
        if (item == null) return false;
        ensureCandidateFields(item);
        if (candidateTypeField != null) {
            try {
                Object typeObj = candidateTypeField.get(item);
                if (typeObj instanceof Enum<?> e) {
                    if (e.ordinal() == 1 || "RAW".equalsIgnoreCase(e.name()) || e.name().contains("RAW")) {
                        return true;
                    }
                } else if (typeObj != null) {
                    String name = typeObj.toString();
                    if ("RAW".equalsIgnoreCase(name) || name.contains("RAW")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean extractCandidateIsAutoCorrect(Object item) {
        if (item == null) return false;
        ensureCandidateFields(item);
        if (candidateAutoCorrectField != null) {
            try {
                return candidateAutoCorrectField.getBoolean(item);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void ensureCandidateFields(Object item) {
        if (candidateFieldsLookupAttempted) return;
        candidateFieldsLookupAttempted = true;

        try {
            candidateTextField = item.getClass().getDeclaredField("a");
            candidateTextField.setAccessible(true);
        } catch (Throwable ignored) {
            for (Field f : item.getClass().getDeclaredFields()) {
                if (CharSequence.class.isAssignableFrom(f.getType())) {
                    candidateTextField = f;
                    candidateTextField.setAccessible(true);
                    break;
                }
            }
        }

        try {
            candidateTypeField = item.getClass().getDeclaredField("e");
            candidateTypeField.setAccessible(true);
        } catch (Throwable ignored) {
        }

        try {
            candidateAutoCorrectField = item.getClass().getDeclaredField("g");
            candidateAutoCorrectField.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CandidateItem> extractCandidates(Object candidateListObj) {
        if (!(candidateListObj instanceof Iterable<?> iterable)) {
            return Collections.emptyList();
        }

        List<CandidateItem> result = new ArrayList<>();
        int slotIndex = 0;

        for (Object item : iterable) {
            if (item == null) continue;
            String text = extractCandidateText(item);
            if (text == null || text.trim().isEmpty()) {
                slotIndex++;
                continue;
            }
            boolean isRaw = extractCandidateIsRaw(item);
            boolean isAutoCorrect = extractCandidateIsAutoCorrect(item);
            boolean isPred = extractCandidateIsPrediction(item);
            String type = extractCandidateType(item);
            result.add(new CandidateItem(text, slotIndex, isRaw, isAutoCorrect, isPred, type, item, null));
            slotIndex++;
        }

        return result;
    }

    public static MatrixSentenceBuffer getSentenceBuffer() {
        return SENTENCE_BUFFER;
    }
}
