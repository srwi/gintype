package dev.srwi.gboardmultiswipe.extension.matrixsuggestions;

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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.List;

/**
 * 2D Matrix Suggestion Bar:
 * Displays previous words of the sentence as interactive columns on the left,
 * and the active candidate word wheel with Material You pill focus on the right.
 * Aligned to the RIGHT so the current/active word is always anchored on the right edge,
 * pushing older words to the left as typing progresses.
 */
public class MatrixSuggestionBarView extends HorizontalScrollView {

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
    }

    private final LinearLayout contentLayout;
    private final VerticalWordWheelView currentWheelView;
    private final List<VerticalWordWheelView> historyWheels = new java.util.ArrayList<>();
    private int selectedHistoryIndex = -1;

    private OnWordCursorNavigateCallback wordCursorNavigateCallback;
    private float navDownX = 0f;
    private float navDownY = 0f;
    private float navLastStepX = 0f;
    private boolean isHorizontalNavigating = false;
    private final float navTouchSlopPx;
    private final float navStepThresholdPx;

    public MatrixSuggestionBarView(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);
        setClipChildren(false);
        setClipToPadding(false);

        navTouchSlopPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, getResources().getDisplayMetrics());
        navStepThresholdPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, getResources().getDisplayMetrics());

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

    public void updateMatrix(List<MatrixSentenceBuffer.WordEntry> history,
                             List<String> currentAlternatives,
                             int preferredSelected,
                             OnCommitCallback onCommit,
                             OnActiveWordSwapCallback onActiveSwap,
                             OnHistoryWordReplaceCallback onHistoryReplace) {
        updateMatrix(history, currentAlternatives, preferredSelected, onCommit, onActiveSwap, onHistoryReplace, null);
    }

    public void updateMatrix(List<MatrixSentenceBuffer.WordEntry> history,
                             List<String> currentAlternatives,
                             int preferredSelected,
                             OnCommitCallback onCommit,
                             OnActiveWordSwapCallback onActiveSwap,
                             OnHistoryWordReplaceCallback onHistoryReplace,
                             OnHistoryWordClickCallback onHistoryClick) {
        contentLayout.removeAllViews();
        historyWheels.clear();
        selectedHistoryIndex = -1;

        int divWidth = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1f, getResources().getDisplayMetrics());
        int divMargin = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 2f, getResources().getDisplayMetrics());
        int divMarginV = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4f, getResources().getDisplayMetrics());
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int dividerColor = dark ? Color.argb(45, 255, 255, 255) : Color.argb(35, 0, 0, 0);

        // 1. Add historical sentence columns (from older to newer, left of current word)
        if (history != null && !history.isEmpty()) {
            for (int i = 0; i < history.size(); i++) {
                final int historyColIndex = i;
                final MatrixSentenceBuffer.WordEntry entry = history.get(i);

                VerticalWordWheelView historyWheel = new VerticalWordWheelView(getContext());
                historyWheel.setAlternatives(entry.alternatives, entry.selectedIndex);
                historyWheel.setIsActiveWord(false);
                historyWheel.setAlpha(1.0f);

                // Tapping anywhere in the history column marks/selects that word in text field
                historyWheel.setOnCenterClickListener((idx, word) -> {
                    selectHistoryWord(historyColIndex);
                    if (onHistoryClick != null) {
                        onHistoryClick.onHistoryWordClicked(historyColIndex, word);
                    }
                });

                // Vertical flick swaps that word's alternative
                historyWheel.setOnWordActionListener((newIndex, newWord) -> {
                    String oldWord = entry.getSelectedWord();
                    entry.selectedIndex = newIndex;
                    entry.committedWord = newWord;
                    if (onHistoryReplace != null && oldWord != null && !oldWord.equals(newWord)) {
                        onHistoryReplace.onHistoryWordReplaced(historyColIndex, oldWord, newWord);
                    }
                });

                historyWheels.add(historyWheel);
                contentLayout.addView(historyWheel, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

                // Vertical separator line
                View divider = new View(getContext());
                divider.setBackgroundColor(dividerColor);
                LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                        divWidth, ViewGroup.LayoutParams.MATCH_PARENT);
                divParams.setMargins(divMargin, divMarginV, divMargin, divMarginV);
                contentLayout.addView(divider, divParams);
            }
        }

        // 2. Add current active candidate wheel at the RIGHT edge
        if (currentAlternatives != null && !currentAlternatives.isEmpty()) {
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
        }

        // Auto-scroll all the way to the right so active word is docked on the right edge
        post(() -> {
            if (selectedHistoryIndex < 0) {
                fullScroll(View.FOCUS_RIGHT);
            }
        });
        postDelayed(() -> {
            if (selectedHistoryIndex < 0) {
                fullScroll(View.FOCUS_RIGHT);
            }
        }, 30);
    }

    public void updateHistoryOnly(List<MatrixSentenceBuffer.WordEntry> history,
                                  OnHistoryWordReplaceCallback onHistoryReplace) {
        updateHistoryOnly(history, onHistoryReplace, null);
    }

    public void updateHistoryOnly(List<MatrixSentenceBuffer.WordEntry> history,
                                  OnHistoryWordReplaceCallback onHistoryReplace,
                                  OnHistoryWordClickCallback onHistoryClick) {
        contentLayout.removeAllViews();
        historyWheels.clear();
        selectedHistoryIndex = -1;

        if (history == null || history.isEmpty()) {
            setVisibility(View.GONE);
            return;
        }

        int divWidth = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1f, getResources().getDisplayMetrics());
        int divMargin = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 2f, getResources().getDisplayMetrics());
        int divMarginV = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4f, getResources().getDisplayMetrics());
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int dividerColor = dark ? Color.argb(45, 255, 255, 255) : Color.argb(35, 0, 0, 0);

        for (int i = 0; i < history.size(); i++) {
            final int historyColIndex = i;
            final MatrixSentenceBuffer.WordEntry entry = history.get(i);

            VerticalWordWheelView historyWheel = new VerticalWordWheelView(getContext());
            historyWheel.setAlternatives(entry.alternatives, entry.selectedIndex);
            historyWheel.setIsActiveWord(false);
            historyWheel.setAlpha(1.0f);

            historyWheel.setOnCenterClickListener((idx, word) -> {
                selectHistoryWord(historyColIndex);
                if (onHistoryClick != null) {
                    onHistoryClick.onHistoryWordClicked(historyColIndex, word);
                }
            });

            historyWheel.setOnWordActionListener((newIndex, newWord) -> {
                String oldWord = entry.getSelectedWord();
                entry.selectedIndex = newIndex;
                entry.committedWord = newWord;
                if (onHistoryReplace != null && oldWord != null && !oldWord.equals(newWord)) {
                    onHistoryReplace.onHistoryWordReplaced(historyColIndex, oldWord, newWord);
                }
            });

            historyWheels.add(historyWheel);
            contentLayout.addView(historyWheel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (i < history.size() - 1) {
                View divider = new View(getContext());
                divider.setBackgroundColor(dividerColor);
                LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                        divWidth, ViewGroup.LayoutParams.MATCH_PARENT);
                divParams.setMargins(divMargin, divMarginV, divMargin, divMarginV);
                contentLayout.addView(divider, divParams);
            }
        }

        setVisibility(View.VISIBLE);
        post(() -> {
            if (selectedHistoryIndex < 0) {
                fullScroll(View.FOCUS_RIGHT);
            }
        });
        postDelayed(() -> {
            if (selectedHistoryIndex < 0) {
                fullScroll(View.FOCUS_RIGHT);
            }
        }, 30);
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
            int scrollX = Math.max(0, targetCenter - barWidth / 2);
            smoothScrollTo(scrollX, 0);
        });
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

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (wordCursorNavigateCallback == null) {
            return super.onInterceptTouchEvent(ev);
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                navDownX = ev.getX();
                navDownY = ev.getY();
                navLastStepX = navDownX;
                isHorizontalNavigating = false;
                return false;

            case MotionEvent.ACTION_MOVE:
                if (isHorizontalNavigating) {
                    return true;
                }
                float dX = ev.getX() - navDownX;
                float dY = ev.getY() - navDownY;
                if (Math.abs(dX) > navTouchSlopPx && Math.abs(dX) > Math.abs(dY)) {
                    isHorizontalNavigating = true;
                    navLastStepX = navDownX;
                    ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isHorizontalNavigating = false;
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
                navDownX = ev.getX();
                navDownY = ev.getY();
                navLastStepX = navDownX;
                isHorizontalNavigating = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!isHorizontalNavigating) {
                    float dX = ev.getX() - navDownX;
                    float dY = ev.getY() - navDownY;
                    if (Math.abs(dX) > navTouchSlopPx && Math.abs(dX) > Math.abs(dY)) {
                        isHorizontalNavigating = true;
                        navLastStepX = navDownX;
                        ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                if (isHorizontalNavigating) {
                    float delta = ev.getX() - navLastStepX;
                    while (delta <= -navStepThresholdPx) {
                        wordCursorNavigateCallback.onNavigateWord(-1);
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        navLastStepX -= navStepThresholdPx;
                        delta = ev.getX() - navLastStepX;
                    }
                    while (delta >= navStepThresholdPx) {
                        wordCursorNavigateCallback.onNavigateWord(1);
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        navLastStepX += navStepThresholdPx;
                        delta = ev.getX() - navLastStepX;
                    }
                    return true;
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isHorizontalNavigating) {
                    ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
                isHorizontalNavigating = false;
                return true;
        }
        return true;
    }
}
