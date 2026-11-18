package dev.srwi.gboardmultiswipe.extension.thicctopbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2D Matrix Suggestion Bar:
 * Displays previous words of the sentence as interactive columns on the left,
 * and the active candidate word wheel with Material You pill focus on the right.
 * Aligned to the RIGHT so the current/active word is always anchored on the right edge,
 * pushing older words to the left as typing progresses.
 */
public class ThiccTopBarView extends HorizontalScrollView {

    public interface OnCommitCallback {
        void onCommit(int index, String word);
    }

    public interface OnActiveWordSwapCallback {
        void onActiveWordSwapped(int index, String word);
    }

    public interface OnChangeCallback {
        void onChange(int index, String word);
    }

    public interface OnHistoryWordReplaceCallback {
        void onHistoryWordReplaced(int historyIndex, String oldWord, String newWord);
    }

    public interface OnHistoryWordClickCallback {
        void onHistoryWordClicked(int historyIndex, String word);
    }

    public interface OnWordCursorNavigateCallback {
        void onNavigateWord(int direction);
        default void onMultiWordSelectStart() {}
        default void onMultiWordSelectStep(int direction) {}
    }

    private static final long ENTRANCE_ANIM_DURATION_MS = 250L;

    private final LinearLayout contentLayout;
    private final VerticalWordWheelView currentWheelView;
    private final List<VerticalWordWheelView> historyWheels = new ArrayList<>();
    private final List<String> displayedColumnWords = new ArrayList<>();
    private OnHistoryWordReplaceCallback activeHistoryReplaceCallback;
    private OnHistoryWordClickCallback activeHistoryClickCallback;
    private int selectedHistoryIndex = -1;
    private int activeColumnSplitIndex = -1;
    private float scaleFactor = GboardThiccTopBarSettings.DEFAULT_TOPBAR_SCALE;
    private float swipeSensitivity = GboardThiccTopBarSettings.DEFAULT_SWIPE_SENSITIVITY;
    private boolean invertVerticalSwipe = GboardThiccTopBarSettings.DEFAULT_INVERT_SWIPE;

    /** Update whether vertical flick gestures rotate word wheels in the inverted direction. */
    public void setInvertVerticalSwipe(boolean invert) {
        if (this.invertVerticalSwipe == invert) {
            return;
        }
        this.invertVerticalSwipe = invert;
        currentWheelView.setInvertVerticalSwipe(invert);
        for (VerticalWordWheelView wheel : historyWheels) {
            wheel.setInvertVerticalSwipe(invert);
        }
    }

    /** Update the horizontal swipe sensitivity factor for word-cursor navigation. */
    public void setSwipeSensitivity(float sensitivity) {
        float sanitized = GboardThiccTopBarSettings.sanitizeSwipeSensitivity(sensitivity);
        if (Math.abs(this.swipeSensitivity - sanitized) < 0.001f) {
            return;
        }
        this.swipeSensitivity = sanitized;
        updateNavStepThreshold();
    }

    /** Update the scale factor for all word wheels and column dividers. */
    public void setScaleFactor(float scale) {
        float sanitized = GboardThiccTopBarSettings.sanitizeTopbarScale(scale);
        if (Math.abs(this.scaleFactor - sanitized) < 0.001f) {
            return;
        }
        this.scaleFactor = sanitized;
        updateNavStepThreshold();
        currentWheelView.setScaleFactor(sanitized);
        for (VerticalWordWheelView wheel : historyWheels) {
            wheel.setScaleFactor(sanitized);
        }
        int divMargin = dpToPx(2f * sanitized);
        int divMarginV = dpToPx(4f * sanitized);
        for (int i = 0; i < contentLayout.getChildCount(); i++) {
            android.view.View child = contentLayout.getChildAt(i);
            if (!(child instanceof VerticalWordWheelView)
                    && child.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
                lp.setMargins(divMargin, divMarginV, divMargin, divMarginV);
                child.setLayoutParams(lp);
            }
        }
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private static final long SCROLL_ANIM_DURATION_MS = 180L;

    private ValueAnimator activeEntranceAnimator;
    private ValueAnimator activeScrollAnimator;
    private ViewTreeObserver.OnPreDrawListener pendingPreDrawListener;
    private boolean pendingEntranceAnimation = false;
    private int pendingEntrancePrevWidth = 0;
    private boolean pendingEntranceUseNewestColOnly = false;

    private OnWordCursorNavigateCallback wordCursorNavigateCallback;
    private boolean multiWordSelectEnabled = true;
    private float navDownX = 0f;
    private float navDownY = 0f;
    private float navLastStepX = 0f;
    private long navDownTimeMs = 0L;
    private long lastTapUpTimeMs = 0L;
    private float lastTapUpX = 0f;
    private float lastTapUpY = 0f;
    private boolean isPendingDoubleTapDrag = false;
    private boolean isMultiWordSelecting = false;
    private boolean isHorizontalNavigating = false;
    private final float navTouchSlopPx;
    private final float doubleTapSlopPx;
    private final long doubleTapTimeoutMs;
    private final long tapTimeoutMs;
    private final float baseNavStepThresholdPx;
    private float navStepThresholdPx;

    public void setMultiWordSelectEnabled(boolean enabled) {
        this.multiWordSelectEnabled = enabled;
        if (!enabled) {
            isPendingDoubleTapDrag = false;
            isMultiWordSelecting = false;
            lastTapUpTimeMs = 0L;
        }
    }

    /**
     * Compute the horizontal swipe distance in pixels required for one word-cursor step.
     * Scales up with top bar size so larger text requires proportionally longer swipes,
     * and scales inversely with user-configured swipe sensitivity.
     */
    public static float computeHorizontalStepThresholdPx(
            float baseStepPx, float topbarScale, float swipeSensitivity) {
        float safeBase = Float.isFinite(baseStepPx) && baseStepPx > 0f ? baseStepPx : 1f;
        float safeScale = GboardThiccTopBarSettings.sanitizeTopbarScale(topbarScale);
        float safeSensitivity = GboardThiccTopBarSettings.sanitizeSwipeSensitivity(swipeSensitivity);
        return (safeBase * safeScale) / safeSensitivity;
    }

    private void updateNavStepThreshold() {
        navStepThresholdPx = computeHorizontalStepThresholdPx(
                baseNavStepThresholdPx, scaleFactor, swipeSensitivity);
    }

    public ThiccTopBarView(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);
        setClipChildren(true);
        setClipToPadding(false);

        navTouchSlopPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, getResources().getDisplayMetrics());
        baseNavStepThresholdPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, getResources().getDisplayMetrics());
        android.view.ViewConfiguration vc = android.view.ViewConfiguration.get(context);
        doubleTapSlopPx = vc.getScaledDoubleTapSlop();
        doubleTapTimeoutMs = android.view.ViewConfiguration.getDoubleTapTimeout();
        tapTimeoutMs = android.view.ViewConfiguration.getTapTimeout() + 120L;
        updateNavStepThreshold();

        contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.HORIZONTAL);
        contentLayout.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        contentLayout.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        contentLayout.setClipChildren(false);
        contentLayout.setClipToPadding(false);

        currentWheelView = new VerticalWordWheelView(context);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        addView(contentLayout, lp);
    }

    public VerticalWordWheelView getCurrentWheelView() {
        return currentWheelView;
    }

    public void updateMatrix(List<ThiccTopBarSentenceBuffer.WordEntry> history,
                             List<String> currentAlternatives,
                             int preferredSelected,
                             OnCommitCallback onCommit,
                             OnActiveWordSwapCallback onActiveSwap,
                             OnHistoryWordReplaceCallback onHistoryReplace) {
        updateMatrix(history, currentAlternatives, preferredSelected, onCommit, onActiveSwap, onHistoryReplace, null);
    }

    public void updateMatrix(List<ThiccTopBarSentenceBuffer.WordEntry> history,
                             List<String> currentAlternatives,
                             int preferredSelected,
                             OnCommitCallback onCommit,
                             OnActiveWordSwapCallback onActiveSwap,
                             OnHistoryWordReplaceCallback onHistoryReplace,
                             OnHistoryWordClickCallback onHistoryClick) {
        updateMatrix(history, currentAlternatives, preferredSelected, Collections.emptyList(),
                onCommit, onActiveSwap, onHistoryReplace, onHistoryClick);
    }

    public void updateMatrix(List<ThiccTopBarSentenceBuffer.WordEntry> historyBefore,
                             List<String> currentAlternatives,
                             int preferredSelected,
                             List<ThiccTopBarSentenceBuffer.WordEntry> historyAfter,
                             OnCommitCallback onCommit,
                             OnActiveWordSwapCallback onActiveSwap,
                             OnHistoryWordReplaceCallback onHistoryReplace,
                             OnHistoryWordClickCallback onHistoryClick) {
        this.activeHistoryReplaceCallback = onHistoryReplace;
        this.activeHistoryClickCallback = onHistoryClick;
        final List<ThiccTopBarSentenceBuffer.WordEntry> beforeList =
                historyBefore != null ? historyBefore : Collections.emptyList();
        final List<ThiccTopBarSentenceBuffer.WordEntry> afterList =
                historyAfter != null ? historyAfter : Collections.emptyList();
        final boolean wasVisible = (getVisibility() == View.VISIBLE);
        final List<String> prevWords = wasVisible
                ? new ArrayList<>(displayedColumnWords)
                : Collections.emptyList();
        final int prevContentWidth = wasVisible ? getChildrenTotalWidth() : 0;

        boolean hasCurrent = (currentAlternatives != null && !currentAlternatives.isEmpty());
        if (wasVisible
                && hasCurrent
                && currentWheelView.getParent() == contentLayout
                && activeColumnSplitIndex == beforeList.size()
                && matchesHistoryColumns(beforeList, afterList)) {
            currentWheelView.setAlpha(1.0f);
            currentWheelView.setIsActiveWord(true);
            currentWheelView.setAlternatives(currentAlternatives, preferredSelected, true);
            currentWheelView.setOnWordActionListener((newIndex, newWord) -> {
                if (onActiveSwap != null) {
                    onActiveSwap.onActiveWordSwapped(newIndex, newWord);
                }
            });
            currentWheelView.setOnCenterClickListener((index, word) -> {
                if (onCommit != null) {
                    onCommit.onCommit(index, word);
                }
            });
            List<String> newWords = collectColumnWords(beforeList, currentAlternatives, preferredSelected, afterList);
            displayedColumnWords.clear();
            displayedColumnWords.addAll(newWords);
            if (afterList.isEmpty() && !currentWheelView.isWidthAnimationActive()) {
                scheduleRightDockPositioning(prevWords, prevContentWidth, newWords);
            }
            return;
        }

        // Preserve anchor position/width when transitioning a selected/active column in-place,
        // or animate a newly inserted mid-sentence column from 1px at the previous word's right edge
        // to being centered where that previous word was.
        int anchorOldWidth = 0;
        int anchorHeight = 0;
        float anchorStartViewportCenterX = 0f;
        float anchorEndViewportCenterX = 0f;
        if (wasVisible && hasCurrent) {
            VerticalWordWheelView anchorWheel = null;
            if (selectedHistoryIndex >= 0 && selectedHistoryIndex < historyWheels.size()
                    && selectedHistoryIndex == beforeList.size()) {
                anchorWheel = historyWheels.get(selectedHistoryIndex);
            } else if (!afterList.isEmpty() && currentWheelView.getParent() == contentLayout
                    && activeColumnSplitIndex == beforeList.size()) {
                anchorWheel = currentWheelView;
            }
            if (anchorWheel != null && anchorWheel.getWidth() > 0 && anchorWheel.getHeight() > 0) {
                anchorOldWidth = anchorWheel.getWidth();
                anchorHeight = anchorWheel.getHeight();
                anchorStartViewportCenterX = anchorWheel.getLeft() + anchorOldWidth * 0.5f - getScrollX();
                anchorEndViewportCenterX = anchorStartViewportCenterX;
            } else if (!afterList.isEmpty() && !beforeList.isEmpty()) {
                int prevIdx = beforeList.size() - 1;
                VerticalWordWheelView prevWheel = null;
                if (currentWheelView.getParent() == contentLayout && activeColumnSplitIndex == prevIdx) {
                    prevWheel = currentWheelView;
                } else {
                    int histIdx = (currentWheelView.getParent() == contentLayout && activeColumnSplitIndex >= 0
                            && activeColumnSplitIndex < prevIdx) ? prevIdx - 1 : prevIdx;
                    if (histIdx >= 0 && histIdx < historyWheels.size()) {
                        prevWheel = historyWheels.get(histIdx);
                    }
                }
                if (prevWheel != null && prevWheel.getWidth() > 0 && prevWheel.getHeight() > 0) {
                    anchorOldWidth = 1;
                    anchorHeight = prevWheel.getHeight();
                    anchorStartViewportCenterX = prevWheel.getRight() - getScrollX();
                    anchorEndViewportCenterX = prevWheel.getLeft() + prevWheel.getWidth() * 0.5f - getScrollX();
                }
            }
        }

        contentLayout.removeAllViews();
        historyWheels.clear();
        selectedHistoryIndex = -1;

        populateHistoryColumns(beforeList, 0, hasCurrent || !afterList.isEmpty(), onHistoryReplace, onHistoryClick);

        // 2. Add current active candidate wheel at the split index (right edge when afterList is empty)
        if (hasCurrent) {
            currentWheelView.setAlpha(1.0f);
            currentWheelView.setIsActiveWord(true);
            currentWheelView.setAlternatives(currentAlternatives, preferredSelected);

            // Discrete vertical flick swaps the active unconfirmed word WITHOUT space
            currentWheelView.setOnWordActionListener((newIndex, newWord) -> {
                if (onActiveSwap != null) {
                    onActiveSwap.onActiveWordSwapped(newIndex, newWord);
                }
            });

            // Tap anywhere in the column commits/confirms the word WITH trailing space
            currentWheelView.setOnCenterClickListener((index, word) -> {
                if (onCommit != null) {
                    onCommit.onCommit(index, word);
                }
            });

            contentLayout.addView(currentWheelView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activeColumnSplitIndex = beforeList.size();
            if (!afterList.isEmpty()) {
                addColumnDivider();
            }
        } else {
            activeColumnSplitIndex = -1;
        }

        populateHistoryColumns(afterList, beforeList.size(), false, onHistoryReplace, onHistoryClick);

        List<String> newWords = collectColumnWords(
                beforeList, hasCurrent ? currentAlternatives : null, preferredSelected, afterList);
        displayedColumnWords.clear();
        displayedColumnWords.addAll(newWords);
        if (anchorOldWidth > 0 && hasCurrent) {
            currentWheelView.animateWidthFrom(
                    anchorOldWidth, anchorHeight, anchorStartViewportCenterX, anchorEndViewportCenterX);
            if (afterList.isEmpty() && !currentWheelView.isWidthAnimationActive()) {
                scheduleRightDockPositioning(prevWords, prevContentWidth, newWords);
            }
        } else if (afterList.isEmpty()) {
            scheduleRightDockPositioning(prevWords, prevContentWidth, newWords);
        }
    }

    private boolean matchesHistoryColumns(
            List<ThiccTopBarSentenceBuffer.WordEntry> beforeList,
            List<ThiccTopBarSentenceBuffer.WordEntry> afterList) {
        int beforeCount = beforeList != null ? beforeList.size() : 0;
        int afterCount = afterList != null ? afterList.size() : 0;
        if (historyWheels.size() != beforeCount + afterCount) {
            return false;
        }
        for (int i = 0; i < beforeCount; i++) {
            if (!matchesHistoryWheel(historyWheels.get(i), beforeList.get(i))) {
                return false;
            }
        }
        for (int i = 0; i < afterCount; i++) {
            if (!matchesHistoryWheel(historyWheels.get(beforeCount + i), afterList.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesHistoryWheel(
            VerticalWordWheelView wheel,
            ThiccTopBarSentenceBuffer.WordEntry entry) {
        if (!wheel.getAlternatives().equals(entry.alternatives)) {
            return false;
        }
        String wheelWord = wheel.getSelectedWord();
        return wheelWord == null ? entry.committedWord == null : wheelWord.equals(entry.committedWord);
    }

    public void updateHistoryOnly(List<ThiccTopBarSentenceBuffer.WordEntry> history,
                                  OnHistoryWordReplaceCallback onHistoryReplace) {
        updateHistoryOnly(history, onHistoryReplace, null);
    }

    public void updateHistoryOnly(List<ThiccTopBarSentenceBuffer.WordEntry> history,
                                  OnHistoryWordReplaceCallback onHistoryReplace,
                                  OnHistoryWordClickCallback onHistoryClick) {
        this.activeHistoryReplaceCallback = onHistoryReplace;
        this.activeHistoryClickCallback = onHistoryClick;
        final boolean wasVisible = (getVisibility() == View.VISIBLE);
        final List<String> prevWords = wasVisible
                ? new ArrayList<>(displayedColumnWords)
                : Collections.emptyList();
        final int prevContentWidth = wasVisible ? getChildrenTotalWidth() : 0;

        final int prevSplitIndex = activeColumnSplitIndex;
        contentLayout.removeAllViews();
        historyWheels.clear();
        selectedHistoryIndex = -1;
        activeColumnSplitIndex = -1;

        if (history == null || history.isEmpty()) {
            displayedColumnWords.clear();
            cancelEntranceAnimation();
            setVisibility(View.GONE);
            return;
        }

        populateHistoryColumns(history, 0, false, onHistoryReplace, onHistoryClick);
        List<String> newWords = collectColumnWords(history, null, 0, null);
        displayedColumnWords.clear();
        displayedColumnWords.addAll(newWords);
        setVisibility(View.VISIBLE);
        boolean wasMidSentence = wasVisible && prevSplitIndex >= 0 && prevSplitIndex < history.size() - 1;
        if (!wasMidSentence) {
            scheduleRightDockPositioning(prevWords, prevContentWidth, newWords);
        }
    }

    private void addColumnDivider() {
        int divWidth = dpToPx(1f);
        int divMargin = dpToPx(2f * scaleFactor);
        int divMarginV = dpToPx(4f * scaleFactor);
        boolean dark = ThiccTopBarGboardReflector.isKeyboardDark(this);
        int dividerColor = dark ? Color.argb(45, 255, 255, 255) : Color.argb(35, 0, 0, 0);
        View divider = new View(getContext());
        divider.setBackgroundColor(dividerColor);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                divWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        divParams.setMargins(divMargin, divMarginV, divMargin, divMarginV);
        contentLayout.addView(divider, divParams);
    }

    private void populateHistoryColumns(List<ThiccTopBarSentenceBuffer.WordEntry> history,
                                        int startColIndex,
                                        boolean trailingDivider,
                                        OnHistoryWordReplaceCallback onHistoryReplace,
                                        OnHistoryWordClickCallback onHistoryClick) {
        if (history == null || history.isEmpty()) {
            return;
        }

        for (int i = 0; i < history.size(); i++) {
            final int historyColIndex = startColIndex + i;
            final ThiccTopBarSentenceBuffer.WordEntry entry = history.get(i);

            VerticalWordWheelView historyWheel = new VerticalWordWheelView(getContext());
            historyWheel.setScaleFactor(scaleFactor);
            historyWheel.setInvertVerticalSwipe(invertVerticalSwipe);
            historyWheel.setAlternatives(entry.alternatives, entry.selectedIndex);
            historyWheel.setIsActiveWord(false);
            historyWheel.setAlpha(1.0f);

            // Tapping anywhere in the history column marks/selects that word in text field
            historyWheel.setOnCenterClickListener((idx, word) -> {
                selectHistoryWord(historyColIndex);
                if (activeHistoryClickCallback != null) {
                    activeHistoryClickCallback.onHistoryWordClicked(historyColIndex, word);
                }
            });

            // Vertical flick swaps that word's alternative
            historyWheel.setOnWordActionListener((newIndex, newWord) -> {
                String oldWord = entry.getSelectedWord();
                entry.selectedIndex = newIndex;
                entry.committedWord = newWord;
                if (activeHistoryReplaceCallback != null && oldWord != null && !oldWord.equals(newWord)) {
                    activeHistoryReplaceCallback.onHistoryWordReplaced(historyColIndex, oldWord, newWord);
                }
            });

            historyWheels.add(historyWheel);
            contentLayout.addView(historyWheel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (i < history.size() - 1 || trailingDivider) {
                addColumnDivider();
            }
        }
    }

    private int getDisplayedColumnCount() {
        return historyWheels.size() + (currentWheelView.getParent() == contentLayout ? 1 : 0);
    }

    private int getChildrenTotalWidth() {
        int count = contentLayout.getChildCount();
        if (count == 0) {
            return 0;
        }
        View first = contentLayout.getChildAt(0);
        View last = contentLayout.getChildAt(count - 1);
        int span = last.getRight() - first.getLeft();
        if (span > 0) {
            return span;
        }
        int total = 0;
        for (int i = 0; i < count; i++) {
            View child = contentLayout.getChildAt(i);
            total += child.getMeasuredWidth();
            if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                total += mlp.leftMargin + mlp.rightMargin;
            }
        }
        return total;
    }

    private int getNewestColumnSpanWidth() {
        int count = contentLayout.getChildCount();
        if (count == 0) {
            return 0;
        }
        View last = contentLayout.getChildAt(count - 1);
        if (count >= 3) {
            View prevCol = contentLayout.getChildAt(count - 3);
            int span = last.getRight() - prevCol.getRight();
            if (span > 0) {
                return span;
            }
        }
        return last.getWidth();
    }

    private static List<String> collectColumnWords(
            List<ThiccTopBarSentenceBuffer.WordEntry> historyBefore,
            List<String> currentAlternatives,
            int preferredSelected,
            List<ThiccTopBarSentenceBuffer.WordEntry> historyAfter
    ) {
        List<String> words = new ArrayList<>();
        appendHistoryColumnWords(historyBefore, words);
        if (currentAlternatives != null && !currentAlternatives.isEmpty()) {
            int sel = (preferredSelected >= 0 && preferredSelected < currentAlternatives.size())
                    ? preferredSelected
                    : 0;
            String cur = currentAlternatives.get(sel);
            words.add(cur != null ? cur : "");
        }
        appendHistoryColumnWords(historyAfter, words);
        return words;
    }

    private static void appendHistoryColumnWords(
            List<ThiccTopBarSentenceBuffer.WordEntry> history,
            List<String> outWords
    ) {
        if (history == null) {
            return;
        }
        for (ThiccTopBarSentenceBuffer.WordEntry entry : history) {
            String w = entry.getSelectedWord();
            outWords.add(w != null ? w : (entry.committedWord != null ? entry.committedWord : ""));
        }
    }

    private static boolean equalsIgnoreCaseSafe(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    static boolean isSlidingWindowShift(List<String> prevWords, List<String> newWords) {
        if (prevWords == null || newWords == null) {
            return false;
        }
        if (prevWords.size() != newWords.size() || newWords.size() < 15) {
            return false;
        }
        int n = newWords.size();
        boolean samePrefixInPlace = true;
        for (int i = 0; i <= n - 3; i++) {
            if (!equalsIgnoreCaseSafe(newWords.get(i), prevWords.get(i + 1))) {
                return false;
            }
            if (!equalsIgnoreCaseSafe(newWords.get(i), prevWords.get(i))) {
                samePrefixInPlace = false;
            }
        }
        return !samePrefixInPlace;
    }

    static boolean shouldAnimateNewWordEntrance(List<String> prevWords, List<String> newWords) {
        int prevCount = prevWords != null ? prevWords.size() : 0;
        int newCount = newWords != null ? newWords.size() : 0;
        if (shouldAnimateNewWordEntrance(prevCount, newCount)) {
            return true;
        }
        return isSlidingWindowShift(prevWords, newWords);
    }

    static boolean shouldAnimateNewWordEntrance(int prevVisibleColCount, int newColCount) {
        return newColCount > prevVisibleColCount && (prevVisibleColCount > 0 || newColCount == 1);
    }

    static float computeEntranceTranslationX(
            int prevVisibleContentWidth,
            float prevTranslationX,
            int newContentWidth,
            int barWidth
    ) {
        if (barWidth <= 0 || newContentWidth <= 0) {
            return 0f;
        }
        float addedWidth = (prevVisibleContentWidth > 0 && newContentWidth > prevVisibleContentWidth)
                ? (newContentWidth - prevVisibleContentWidth)
                : newContentWidth;
        float totalShift = addedWidth + Math.max(0f, prevTranslationX);
        return Math.min((float) barWidth, Math.max(0f, totalShift));
    }

    private void cancelEntranceAnimation() {
        clearPendingPreDrawListener();
        pendingEntranceAnimation = false;
        pendingEntrancePrevWidth = 0;
        pendingEntranceUseNewestColOnly = false;
        if (activeEntranceAnimator != null) {
            activeEntranceAnimator.cancel();
            activeEntranceAnimator = null;
        }
        contentLayout.setTranslationX(0f);
    }

    private void clearPendingPreDrawListener() {
        if (pendingPreDrawListener != null) {
            ViewTreeObserver vto = getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.removeOnPreDrawListener(pendingPreDrawListener);
            }
            pendingPreDrawListener = null;
        }
    }

    private void scheduleRightDockPositioning(List<String> prevWords, int prevContentWidth, List<String> newWords) {
        clearPendingPreDrawListener();
        int prevColCount = prevWords != null ? prevWords.size() : 0;
        int newColCount = newWords != null ? newWords.size() : 0;
        boolean windowShift = isSlidingWindowShift(prevWords, newWords);

        if (shouldAnimateNewWordEntrance(prevWords, newWords)) {
            if (!pendingEntranceAnimation) {
                pendingEntrancePrevWidth = prevContentWidth;
                pendingEntranceUseNewestColOnly = windowShift;
            } else if (windowShift) {
                pendingEntranceUseNewestColOnly = true;
            }
            pendingEntranceAnimation = true;
        } else if (newColCount < prevColCount || newColCount == 0) {
            cancelEntranceAnimation();
        }

        ViewTreeObserver vto = getViewTreeObserver();
        if (vto == null || !vto.isAlive()) {
            return;
        }
        pendingPreDrawListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (selectedHistoryIndex >= 0 || getVisibility() != View.VISIBLE) {
                    clearPendingPreDrawListener();
                    pendingEntranceAnimation = false;
                    pendingEntranceUseNewestColOnly = false;
                    return true;
                }
                int barWidth = getWidth();
                int containerWidth = contentLayout.getWidth();
                int newChildrenWidth = getChildrenTotalWidth();
                if (barWidth <= 0 || containerWidth <= 0 || newChildrenWidth <= 0) {
                    return true;
                }
                clearPendingPreDrawListener();

                cancelScrollAnimation();
                int targetScrollX = Math.max(0, containerWidth - barWidth);
                scrollTo(targetScrollX, 0);

                if (pendingEntranceAnimation) {
                    int prevWidth = pendingEntrancePrevWidth;
                    boolean useNewestOnly = pendingEntranceUseNewestColOnly;
                    pendingEntranceAnimation = false;
                    pendingEntrancePrevWidth = 0;
                    pendingEntranceUseNewestColOnly = false;

                    float currentTranslation = contentLayout.getTranslationX();
                    if (activeEntranceAnimator != null) {
                        activeEntranceAnimator.cancel();
                        activeEntranceAnimator = null;
                    }
                    float startTranslation = useNewestOnly
                            ? Math.min((float) barWidth, Math.max(0f, getNewestColumnSpanWidth() + Math.max(0f, currentTranslation)))
                            : computeEntranceTranslationX(prevWidth, currentTranslation, newChildrenWidth, barWidth);
                    if (startTranslation > 1f) {
                        contentLayout.setTranslationX(startTranslation);
                        activeEntranceAnimator = ValueAnimator.ofFloat(startTranslation, 0f);
                        activeEntranceAnimator.setDuration(ENTRANCE_ANIM_DURATION_MS);
                        activeEntranceAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
                        activeEntranceAnimator.addUpdateListener(anim ->
                                contentLayout.setTranslationX((Float) anim.getAnimatedValue()));
                        activeEntranceAnimator.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                contentLayout.setTranslationX(0f);
                                activeEntranceAnimator = null;
                            }

                            @Override
                            public void onAnimationCancel(Animator animation) {
                                activeEntranceAnimator = null;
                            }
                        });
                        activeEntranceAnimator.start();
                    } else {
                        contentLayout.setTranslationX(0f);
                    }
                } else if (activeEntranceAnimator == null) {
                    contentLayout.setTranslationX(0f);
                }
                return true;
            }
        };
        vto.addOnPreDrawListener(pendingPreDrawListener);
    }

    @Override
    public void setVisibility(int visibility) {
        if (visibility != View.VISIBLE && getVisibility() == View.VISIBLE) {
            displayedColumnWords.clear();
            cancelEntranceAnimation();
            cancelScrollAnimation();
        }
        super.setVisibility(visibility);
    }

    public void selectHistoryWord(int historyIndex) {
        this.selectedHistoryIndex = historyIndex;
        for (int i = 0; i < historyWheels.size(); i++) {
            VerticalWordWheelView wheel = historyWheels.get(i);
            wheel.setIsActiveWord(i == historyIndex);
        }
        if (currentWheelView != null) {
            currentWheelView.setIsActiveWord(historyIndex < 0);
        }
        if (historyIndex >= 0) {
            centerOnColumn(historyIndex);
        }
    }

    public void centerOnColumn(int columnIndex) {
        cancelEntranceAnimation();
        post(() -> {
            int barWidth = getWidth();
            if (barWidth <= 0) return;

            View target = null;
            if (columnIndex >= 0 && columnIndex < historyWheels.size()) {
                target = historyWheels.get(columnIndex);
            } else if (currentWheelView != null && currentWheelView.getParent() == contentLayout) {
                target = currentWheelView;
            } else if (!historyWheels.isEmpty()) {
                target = historyWheels.get(historyWheels.size() - 1);
            }
            if (target == null) return;
            if (target.getWidth() == 0 && target.getLeft() == 0) {
                post(() -> centerOnColumn(columnIndex));
                return;
            }

            int targetCenter = contentLayout.getLeft() + (target.getLeft() + target.getRight()) / 2;
            int maxScrollX = Math.max(0, contentLayout.getWidth() - barWidth);
            int scrollX = Math.max(0, Math.min(maxScrollX, targetCenter - barWidth / 2));
            animateScrollTo(scrollX);
        });
    }

    private void cancelScrollAnimation() {
        if (activeScrollAnimator != null) {
            activeScrollAnimator.cancel();
            activeScrollAnimator = null;
        }
    }

    private void animateScrollTo(int targetScrollX) {
        int startScrollX = getScrollX();
        if (Math.abs(targetScrollX - startScrollX) <= 1) {
            cancelScrollAnimation();
            scrollTo(targetScrollX, 0);
            return;
        }
        cancelScrollAnimation();
        ValueAnimator animator = ValueAnimator.ofInt(startScrollX, targetScrollX);
        animator.setDuration(SCROLL_ANIM_DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(anim -> scrollTo((Integer) anim.getAnimatedValue(), 0));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (activeScrollAnimator == animation) {
                    activeScrollAnimator = null;
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (activeScrollAnimator == animation) {
                    activeScrollAnimator = null;
                }
            }
        });
        activeScrollAnimator = animator;
        animator.start();
    }

    public void clearHistorySelection() {
        this.selectedHistoryIndex = -1;
        for (VerticalWordWheelView wheel : historyWheels) {
            wheel.setIsActiveWord(false);
        }
        if (currentWheelView != null) {
            currentWheelView.setIsActiveWord(true);
        }
    }

    public int getSelectedHistoryIndex() {
        return selectedHistoryIndex;
    }

    public int getHistoryWheelCount() {
        return historyWheels.size();
    }

    public void clearCurrent() {
        currentWheelView.setAlternatives(null, 0);
    }

    public void setOnWordCursorNavigateCallback(OnWordCursorNavigateCallback callback) {
        this.wordCursorNavigateCallback = callback;
    }

    private void handleNavActionDown(MotionEvent ev) {
        navDownX = ev.getX();
        navDownY = ev.getY();
        navLastStepX = navDownX;
        navDownTimeMs = ev.getEventTime();
        isHorizontalNavigating = false;
        isMultiWordSelecting = false;
        boolean withinDoubleTapWindow = multiWordSelectEnabled
                && lastTapUpTimeMs > 0L
                && (navDownTimeMs - lastTapUpTimeMs) <= doubleTapTimeoutMs
                && Math.hypot(navDownX - lastTapUpX, navDownY - lastTapUpY) <= doubleTapSlopPx;
        isPendingDoubleTapDrag = withinDoubleTapWindow;
        if (withinDoubleTapWindow) {
            lastTapUpTimeMs = 0L;
        }
    }

    private void beginHorizontalNav() {
        isHorizontalNavigating = true;
        isMultiWordSelecting = isPendingDoubleTapDrag && multiWordSelectEnabled;
        navLastStepX = navDownX;
        lastTapUpTimeMs = 0L;
        if (isMultiWordSelecting && wordCursorNavigateCallback != null) {
            wordCursorNavigateCallback.onMultiWordSelectStart();
        }
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    private void handleNavActionUpOrCancel(MotionEvent ev, boolean isCancel) {
        if (!isCancel
                && !isHorizontalNavigating
                && !isPendingDoubleTapDrag
                && multiWordSelectEnabled
                && (ev.getEventTime() - navDownTimeMs) <= tapTimeoutMs
                && Math.hypot(ev.getX() - navDownX, ev.getY() - navDownY) <= navTouchSlopPx) {
            lastTapUpTimeMs = ev.getEventTime();
            lastTapUpX = ev.getX();
            lastTapUpY = ev.getY();
        } else if (isCancel || isHorizontalNavigating) {
            lastTapUpTimeMs = 0L;
        }
        if (isHorizontalNavigating) {
            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(false);
            }
        }
        isHorizontalNavigating = false;
        isMultiWordSelecting = false;
        isPendingDoubleTapDrag = false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (wordCursorNavigateCallback == null) {
            return super.onInterceptTouchEvent(ev);
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleNavActionDown(ev);
                return false;

            case MotionEvent.ACTION_MOVE:
                if (isHorizontalNavigating) {
                    return true;
                }
                float dX = ev.getX() - navDownX;
                float dY = ev.getY() - navDownY;
                if (Math.abs(dX) > navTouchSlopPx && Math.abs(dX) > Math.abs(dY)) {
                    beginHorizontalNav();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
                handleNavActionUpOrCancel(ev, false);
                return false;

            case MotionEvent.ACTION_CANCEL:
                handleNavActionUpOrCancel(ev, true);
                return false;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (wordCursorNavigateCallback == null) {
            return super.onTouchEvent(ev);
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleNavActionDown(ev);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!isHorizontalNavigating) {
                    float dX = ev.getX() - navDownX;
                    float dY = ev.getY() - navDownY;
                    if (Math.abs(dX) > navTouchSlopPx && Math.abs(dX) > Math.abs(dY)) {
                        beginHorizontalNav();
                    }
                }
                if (isHorizontalNavigating) {
                    float delta = ev.getX() - navLastStepX;
                    while (delta <= -navStepThresholdPx) {
                        if (isMultiWordSelecting) {
                            wordCursorNavigateCallback.onMultiWordSelectStep(-1);
                        } else {
                            wordCursorNavigateCallback.onNavigateWord(-1);
                        }
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        navLastStepX -= navStepThresholdPx;
                        delta = ev.getX() - navLastStepX;
                    }
                    while (delta >= navStepThresholdPx) {
                        if (isMultiWordSelecting) {
                            wordCursorNavigateCallback.onMultiWordSelectStep(1);
                        } else {
                            wordCursorNavigateCallback.onNavigateWord(1);
                        }
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        navLastStepX += navStepThresholdPx;
                        delta = ev.getX() - navLastStepX;
                    }
                    return true;
                }
                return true;

            case MotionEvent.ACTION_UP:
                handleNavActionUpOrCancel(ev, false);
                return true;

            case MotionEvent.ACTION_CANCEL:
                handleNavActionUpOrCancel(ev, true);
                return true;
        }
        return true;
    }
}
