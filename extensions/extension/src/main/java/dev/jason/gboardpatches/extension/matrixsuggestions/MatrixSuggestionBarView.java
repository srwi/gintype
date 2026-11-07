package dev.jason.gboardpatches.extension.matrixsuggestions;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    private final LinearLayout contentLayout;
    private final VerticalWordWheelView currentWheelView;

    public MatrixSuggestionBarView(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);
        setClipChildren(false);
        setClipToPadding(false);

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
        contentLayout.removeAllViews();

        int divWidth = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1f, getResources().getDisplayMetrics());
        int divMargin = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4f, getResources().getDisplayMetrics());

        // 1. Add historical sentence columns (from older to newer, left of current word)
        if (history != null && !history.isEmpty()) {
            for (int i = 0; i < history.size(); i++) {
                final int historyColIndex = i;
                final MatrixSentenceBuffer.WordEntry entry = history.get(i);

                VerticalWordWheelView historyWheel = new VerticalWordWheelView(getContext());
                historyWheel.setAlternatives(entry.alternatives, entry.selectedIndex);
                historyWheel.setIsActiveWord(false);
                historyWheel.setAlpha(0.80f);

                // History wheels are interactive: flicking or tapping them swaps that word in the sentence!
                historyWheel.setOnWordActionListener((newIndex, newWord) -> {
                    String oldWord = entry.getSelectedWord();
                    entry.selectedIndex = newIndex;
                    entry.committedWord = newWord;
                    if (onHistoryReplace != null && oldWord != null && !oldWord.equals(newWord)) {
                        onHistoryReplace.onHistoryWordReplaced(historyColIndex, oldWord, newWord);
                    }
                });

                contentLayout.addView(historyWheel, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

                // Vertical separator line
                View divider = new View(getContext());
                divider.setBackgroundColor(Color.argb(45, 128, 128, 128));
                LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                        divWidth, ViewGroup.LayoutParams.MATCH_PARENT);
                divParams.setMargins(divMargin, 6, divMargin, 6);
                contentLayout.addView(divider, divParams);
            }
        }

        // 2. Add current active candidate wheel at the RIGHT edge
        if (currentAlternatives != null && !currentAlternatives.isEmpty()) {
            currentWheelView.setAlpha(1.0f);
            currentWheelView.setIsActiveWord(true);
            currentWheelView.setAlternatives(currentAlternatives, preferredSelected);

            // Discrete flick or tap on top/bottom swaps the active unconfirmed word WITHOUT space
            currentWheelView.setOnWordActionListener((newIndex, newWord) -> {
                if (onActiveSwap != null) {
                    onActiveSwap.onActiveWordSwapped(newIndex, newWord);
                }
            });

            // Tap on center pill commits/confirms the word WITH trailing space
            currentWheelView.setOnCenterClickListener((index, word) -> {
                if (onCommit != null) {
                    onCommit.onCommit(index, word);
                }
            });

            contentLayout.addView(currentWheelView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // Auto-scroll all the way to the right so active word is docked on the right edge
        post(() -> fullScroll(View.FOCUS_RIGHT));
        postDelayed(() -> fullScroll(View.FOCUS_RIGHT), 30);
    }

    public void updateHistoryOnly(List<MatrixSentenceBuffer.WordEntry> history,
                                  OnHistoryWordReplaceCallback onHistoryReplace) {
        contentLayout.removeAllViews();
        if (history == null || history.isEmpty()) {
            setVisibility(View.GONE);
            return;
        }

        int divWidth = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1f, getResources().getDisplayMetrics());
        int divMargin = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4f, getResources().getDisplayMetrics());

        for (int i = 0; i < history.size(); i++) {
            final int historyColIndex = i;
            final MatrixSentenceBuffer.WordEntry entry = history.get(i);

            VerticalWordWheelView historyWheel = new VerticalWordWheelView(getContext());
            historyWheel.setAlternatives(entry.alternatives, entry.selectedIndex);
            historyWheel.setIsActiveWord(false);
            historyWheel.setAlpha(0.85f);

            historyWheel.setOnWordActionListener((newIndex, newWord) -> {
                String oldWord = entry.getSelectedWord();
                entry.selectedIndex = newIndex;
                entry.committedWord = newWord;
                if (onHistoryReplace != null && oldWord != null && !oldWord.equals(newWord)) {
                    onHistoryReplace.onHistoryWordReplaced(historyColIndex, oldWord, newWord);
                }
            });

            contentLayout.addView(historyWheel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (i < history.size() - 1) {
                View divider = new View(getContext());
                divider.setBackgroundColor(Color.argb(45, 128, 128, 128));
                LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                        divWidth, ViewGroup.LayoutParams.MATCH_PARENT);
                divParams.setMargins(divMargin, 6, divMargin, 6);
                contentLayout.addView(divider, divParams);
            }
        }

        setVisibility(View.VISIBLE);
        post(() -> fullScroll(View.FOCUS_RIGHT));
        postDelayed(() -> fullScroll(View.FOCUS_RIGHT), 30);
    }

    public void clearCurrent() {
        currentWheelView.setAlternatives(null, 0);
    }
}
