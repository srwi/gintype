package dev.jason.gboardpatches.extension.matrixsuggestions;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A vertical 3-row column view displaying word alternatives:
 * - Top: Previous alternative (smaller, muted)
 * - Middle: Current Word (larger, bold, highlighted with Material pill when active)
 * - Bottom: Next alternative (smaller, muted)
 *
 * Supports discrete vertical flick gestures (one word swap per flick, no endless spinning),
 * and direct tap on top/bottom rows to swap alternatives in place, or center row to commit.
 */
public class VerticalWordWheelView extends LinearLayout {

    public interface OnWordCommitListener {
        void onWordCommitted(int index, String word);
    }

    public interface OnWordChangeListener {
        void onWordChanged(int index, String word);
    }

    public interface OnWordActionListener {
        void onWordSwapped(int newIndex, String newWord);
    }

    public interface OnCenterClickListener {
        void onCenterClicked(int index, String word);
    }

    private final TextView topView;
    private final TextView centerView;
    private final TextView bottomView;

    private List<String> alternatives = Collections.emptyList();
    private int selectedIndex = 0;
    private boolean isActiveWord = false;

    private float downX = 0f;
    private float downY = 0f;
    private float touchSlop = 12f;
    private boolean hasFlicked = false;
    private boolean isFlickGesture = false;

    private OnWordCommitListener commitListener;
    private OnWordChangeListener changeListener;
    private OnWordActionListener wordActionListener;
    private OnCenterClickListener centerClickListener;

    public VerticalWordWheelView(Context context) {
        super(context);
        setOrientation(LinearLayout.VERTICAL);
        setGravity(Gravity.CENTER);
        setClickable(true);
        setFocusable(true);
        setClipChildren(false);
        setClipToPadding(false);

        int padH = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 6f, getResources().getDisplayMetrics());
        setPadding(padH, 0, padH, 0);

        touchSlop = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 12f, getResources().getDisplayMetrics());

        setWeightSum(3.25f);

        topView = createWordView(context, 11.0f, 0.88f, false);
        centerView = createWordView(context, 13.5f, 1.0f, true);
        bottomView = createWordView(context, 11.0f, 0.88f, false);

        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1.0f);
        topLp.gravity = Gravity.CENTER_HORIZONTAL;

        LinearLayout.LayoutParams centerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1.25f);
        centerLp.gravity = Gravity.CENTER_HORIZONTAL;

        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1.0f);
        bottomLp.gravity = Gravity.CENTER_HORIZONTAL;

        addView(topView, topLp);
        addView(centerView, centerLp);
        addView(bottomView, bottomLp);

        updateColors();
        updateDisplay();
    }

    public void setIsActiveWord(boolean active) {
        if (this.isActiveWord != active) {
            this.isActiveWord = active;
            updateColors();
            updateDisplay();
        }
    }

    public boolean isActiveWord() {
        return isActiveWord;
    }

    public void setOnWordCommitListener(OnWordCommitListener listener) {
        this.commitListener = listener;
    }

    public void setOnWordChangeListener(OnWordChangeListener listener) {
        this.changeListener = listener;
    }

    public void setOnWordActionListener(OnWordActionListener listener) {
        this.wordActionListener = listener;
    }

    public void setOnCenterClickListener(OnCenterClickListener listener) {
        this.centerClickListener = listener;
    }

    public void setAlternatives(List<String> words) {
        setAlternatives(words, 0);
    }

    public void setAlternatives(List<String> words, int preferredSelected) {
        if (words == null || words.isEmpty()) {
            this.alternatives = Collections.emptyList();
            this.selectedIndex = 0;
            setVisibility(View.GONE);
            return;
        }

        this.alternatives = new ArrayList<>(words);
        if (preferredSelected >= 0 && preferredSelected < words.size()) {
            this.selectedIndex = preferredSelected;
        } else {
            this.selectedIndex = 0;
        }

        setVisibility(View.VISIBLE);
        updateDisplay();
    }

    public List<String> getAlternatives() {
        return alternatives;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public String getSelectedWord() {
        if (selectedIndex >= 0 && selectedIndex < alternatives.size()) {
            return alternatives.get(selectedIndex);
        }
        return null;
    }

    private TextView createWordView(Context context, float spSize, float alpha, boolean bold) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, spSize);
        tv.setAlpha(alpha);
        tv.setSingleLine(true);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    public void updateColors() {
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int primaryColor = dark ? Color.WHITE : 0xff202124;
        int accentColor = dark ? 0xff8ab4f8 : 0xff1a73e8;

        if (isActiveWord) {
            topView.setTextColor(primaryColor);
            topView.setAlpha(0.88f);
            topView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.0f);

            centerView.setTextColor(accentColor);
            centerView.setAlpha(1.0f);
            centerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            centerView.setTypeface(Typeface.DEFAULT_BOLD);

            // Active pill background: Material You accent rounded pill with clean vertical padding
            int fillColor = dark ? 0x2e8ab4f8 : 0x1f1a73e8;
            int strokeColor = dark ? 0x4d8ab4f8 : 0x331a73e8;
            centerView.setBackground(createPillDrawable(fillColor, strokeColor, 10f));
            int padH = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 8f, getResources().getDisplayMetrics());
            int padV = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 2f, getResources().getDisplayMetrics());
            centerView.setPadding(padH, padV, padH, padV);

            bottomView.setTextColor(primaryColor);
            bottomView.setAlpha(0.88f);
            bottomView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.0f);
        } else {
            topView.setTextColor(primaryColor);
            topView.setAlpha(0.85f);
            topView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.0f);

            centerView.setTextColor(primaryColor);
            centerView.setAlpha(1.0f);
            centerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.0f);
            centerView.setTypeface(Typeface.DEFAULT_BOLD);
            centerView.setBackground(null);
            int padH = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 6f, getResources().getDisplayMetrics());
            int padV = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 2f, getResources().getDisplayMetrics());
            centerView.setPadding(padH, padV, padH, padV);

            bottomView.setTextColor(primaryColor);
            bottomView.setAlpha(0.85f);
            bottomView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.0f);
        }
    }

    private Drawable createPillDrawable(int fillColor, int strokeColor, float cornerRadiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        float radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, cornerRadiusDp, getResources().getDisplayMetrics());
        gd.setCornerRadius(radiusPx);
        if (strokeColor != 0) {
            int strokeWidthPx = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 1f, getResources().getDisplayMetrics());
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    public void updateDisplay() {
        int size = alternatives.size();
        if (size == 0) {
            topView.setText("");
            centerView.setText("");
            bottomView.setText("");
            return;
        }

        if (size == 1) {
            topView.setText("");
            topView.setVisibility(View.INVISIBLE);
            centerView.setText(alternatives.get(0));
            centerView.setVisibility(View.VISIBLE);
            bottomView.setText("");
            bottomView.setVisibility(View.INVISIBLE);
            return;
        }

        if (size == 2) {
            int other = (selectedIndex == 0) ? 1 : 0;
            topView.setText(alternatives.get(other));
            topView.setVisibility(View.VISIBLE);
            centerView.setText(alternatives.get(selectedIndex));
            centerView.setVisibility(View.VISIBLE);
            bottomView.setText(alternatives.get(other));
            bottomView.setVisibility(View.VISIBLE);
            return;
        }

        // 3 or more alternatives
        int prev = (selectedIndex - 1 + size) % size;
        int next = (selectedIndex + 1) % size;

        topView.setText(alternatives.get(prev));
        topView.setVisibility(View.VISIBLE);

        centerView.setText(alternatives.get(selectedIndex));
        centerView.setVisibility(View.VISIBLE);

        bottomView.setText(alternatives.get(next));
        bottomView.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (alternatives.isEmpty()) {
            return super.onTouchEvent(event);
        }

        int size = alternatives.size();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                hasFlicked = false;
                isFlickGesture = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (hasFlicked) {
                    // Discrete flick already completed for this stroke - do not rotate continuously!
                    return true;
                }
                float dY = event.getY() - downY;
                float dX = event.getX() - downX;
                if (Math.abs(dY) > touchSlop && Math.abs(dY) > Math.abs(dX) * 1.1f) {
                    // Vertical flick gesture detected
                    getParent().requestDisallowInterceptTouchEvent(true);
                    hasFlicked = true;
                    isFlickGesture = true;

                    if (dY < -touchSlop) {
                        // Flick UP -> select top/previous item
                        selectedIndex = (selectedIndex - 1 + size) % size;
                    } else if (dY > touchSlop) {
                        // Flick DOWN -> select bottom/next item
                        selectedIndex = (selectedIndex + 1) % size;
                    }

                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    updateDisplay();

                    String newWord = getSelectedWord();
                    if (wordActionListener != null && newWord != null) {
                        wordActionListener.onWordSwapped(selectedIndex, newWord);
                    }
                    if (changeListener != null && newWord != null) {
                        changeListener.onWordChanged(selectedIndex, newWord);
                    }
                    return true;
                } else if (Math.abs(dX) > touchSlop && Math.abs(dX) > Math.abs(dY)) {
                    // Horizontal scroll gesture - allow parent HorizontalScrollView to scroll
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;

            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (!isFlickGesture) {
                    // Tap gesture
                    float upY = event.getY();
                    float totalHeight = getHeight();
                    if (totalHeight > 0) {
                        if (upY < totalHeight * 0.32f && size > 1) {
                            // Tapped top row: swap to top alternative
                            selectedIndex = (selectedIndex - 1 + size) % size;
                            updateDisplay();
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            String newWord = getSelectedWord();
                            if (wordActionListener != null && newWord != null) {
                                wordActionListener.onWordSwapped(selectedIndex, newWord);
                            }
                            if (changeListener != null && newWord != null) {
                                changeListener.onWordChanged(selectedIndex, newWord);
                            }
                        } else if (upY > totalHeight * 0.68f && size > 1) {
                            // Tapped bottom row: swap to bottom alternative
                            selectedIndex = (selectedIndex + 1) % size;
                            updateDisplay();
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            String newWord = getSelectedWord();
                            if (wordActionListener != null && newWord != null) {
                                wordActionListener.onWordSwapped(selectedIndex, newWord);
                            }
                            if (changeListener != null && newWord != null) {
                                changeListener.onWordChanged(selectedIndex, newWord);
                            }
                        } else {
                            // Tapped center row: commit/confirm action
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            String centerWord = getSelectedWord();
                            if (centerClickListener != null && centerWord != null) {
                                centerClickListener.onCenterClicked(selectedIndex, centerWord);
                            }
                            if (commitListener != null && centerWord != null) {
                                commitListener.onWordCommitted(selectedIndex, centerWord);
                            }
                        }
                    }
                }
                hasFlicked = false;
                isFlickGesture = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                hasFlicked = false;
                isFlickGesture = false;
                return true;
        }

        return super.onTouchEvent(event);
    }
}
