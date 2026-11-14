package dev.srwi.gboardmultiswipe.extension.matrixsuggestions;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import dev.srwi.gboardmultiswipe.extension.R;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesFeatureAvailability;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesIcons;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettings;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettingsContract;
import dev.srwi.gboardmultiswipe.extension.settings.GboardSettingsText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Settings screen and controls for Matrix Word Suggestions. */
public final class GboardMatrixSuggestionsSettingsFeature
        implements GboardPatchesSettingsContract.Feature {
    private static final float TOPBAR_SCALE_STEP = 0.05f;
    private static final int TOPBAR_SCALE_TOTAL_STEPS = Math.round(
            (GboardMatrixSuggestionsSettings.MAX_TOPBAR_SCALE
                    - GboardMatrixSuggestionsSettings.MIN_TOPBAR_SCALE) / TOPBAR_SCALE_STEP);

    private final String title;
    private final String summary;
    private final String sectionTitle;
    private final String enableTitle;
    private final String topbarScaleTitle;
    private final String topbarScaleSummary;
    private final String topbarScaleValueTemplate;
    private final String topbarScaleDefaultTemplate;
    private final String sliderDefaultLabel;
    private final String invertSwipeTitle;
    private final String invertSwipeSummary;
    private final String errorTitle;
    private final String errorSummary;

    public GboardMatrixSuggestionsSettingsFeature(Context context) {
        title = GboardSettingsText.get(context, R.string.gboard_patches_matrix_suggestions_title);
        summary = GboardSettingsText.get(
                context, R.string.gboard_patches_matrix_suggestions_summary);
        sectionTitle = GboardSettingsText.get(
                context, R.string.gboard_patches_matrix_suggestions_section);
        enableTitle = GboardSettingsText.get(
                context, R.string.gboard_patches_matrix_suggestions_enable_title);
        topbarScaleTitle = GboardSettingsText.get(
                context, R.string.gboard_patches_matrix_suggestions_topbar_scale_title);
        topbarScaleSummary = GboardSettingsText.get(
                context, R.string.gboard_patches_matrix_suggestions_topbar_scale_summary);
        topbarScaleValueTemplate = GboardSettingsText.get(
                context, R.string.gboard_patches_matrix_suggestions_topbar_scale_value);
        topbarScaleDefaultTemplate = GboardSettingsText.get(
                context, R.string.gboard_patches_matrix_suggestions_topbar_scale_value_default);
        sliderDefaultLabel = GboardSettingsText.get(
                context, R.string.gboard_patches_matrix_suggestions_slider_default);
        invertSwipeTitle = GboardSettingsText.get(
                context, R.string.gboard_patches_matrix_suggestions_invert_swipe_title);
        invertSwipeSummary = GboardSettingsText.get(
                context, R.string.gboard_patches_matrix_suggestions_invert_swipe_summary);
        errorTitle = GboardSettingsText.get(
                context, R.string.gboard_patches_error_header_title);
        errorSummary = GboardSettingsText.get(
                context, R.string.gboard_patches_error_header_summary);
    }

    @Override
    public String getEntryTitle() {
        return title;
    }

    @Override
    public String getEntrySummary() {
        return summary;
    }

    @Override
    public int getEntryIcon() {
        return GboardPatchesIcons.BOLT;
    }

    @Override
    public String getEntryTone() {
        return "blue";
    }

    @Override
    public boolean isAvailable(Context context) {
        return GboardPatchesFeatureAvailability.hasFeature(
                context, GboardPatchesFeatureAvailability.FEATURE_MATRIX_SUGGESTIONS);
    }

    @Override
    public GboardPatchesSettingsContract.Screen buildScreen(
            GboardPatchesSettingsContract.FeatureHost host) {
        try {
            Context context = host.getContext();
            SharedPreferences preferences = GboardPatchesSettings.preferences(context);
            GboardMatrixSuggestionsSettings.ensureDefaults(preferences);
            boolean enabled = GboardMatrixSuggestionsSettings.readEnabled(preferences);
            float topbarScale = GboardMatrixSuggestionsSettings.readTopbarScale(preferences);
            boolean invertSwipe = GboardMatrixSuggestionsSettings.readInvertSwipe(preferences);

            List<GboardPatchesSettingsContract.Row> rows = new ArrayList<>();
            rows.add(new GboardPatchesSettingsContract.ToggleRow(
                    enableTitle,
                    summary,
                    true,
                    enabled,
                    newValue -> {
                        GboardMatrixSuggestionsSettings.writeEnabled(context, newValue);
                        GboardMatrixSuggestionsRuntime.syncAllHeaderHeights(context);
                        GboardPatchesSettingsContract.refresh(host);
                    }));
            rows.add(new GboardPatchesSettingsContract.SelectorRow(
                    topbarScaleTitle,
                    topbarScaleSummary,
                    formatTopbarScale(topbarScale),
                    enabled,
                    () -> showTopbarScaleDialog(host, topbarScale)));
            rows.add(new GboardPatchesSettingsContract.ToggleRow(
                    invertSwipeTitle,
                    invertSwipeSummary,
                    enabled,
                    invertSwipe,
                    newValue -> {
                        GboardMatrixSuggestionsSettings.writeInvertSwipe(context, newValue);
                        GboardMatrixSuggestionsRuntime.syncAllHeaderHeights(context);
                        GboardPatchesSettingsContract.refresh(host);
                    }));

            return new GboardPatchesSettingsContract.Screen(
                    title,
                    null,
                    title,
                    summary,
                    Collections.emptyList(),
                    Collections.singletonList(
                            new GboardPatchesSettingsContract.Section(sectionTitle, rows)),
                    GboardPatchesSettingsContract.RefreshPolicy.none(),
                    GboardPatchesSettingsContract.PanelStyle.FLAT);
        } catch (Throwable ignored) {
            return buildErrorScreen();
        }
    }

    private void showTopbarScaleDialog(
            GboardPatchesSettingsContract.FeatureHost host,
            float currentValue) {
        GboardPatchesSettingsContract.showManagedDialog(host, onDismiss -> {
            Context context = host.getContext();
            if (context == null) {
                return false;
            }

            int initialProgress = scaleToStep(currentValue);
            int defaultProgress = scaleToStep(GboardMatrixSuggestionsSettings.DEFAULT_TOPBAR_SCALE);

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            int padH = dp(context, 24);
            int padV = dp(context, 16);
            content.setPadding(padH, padV, padH, dp(context, 8));

            TextView valueLabel = new TextView(context);
            valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            valueLabel.setTypeface(Typeface.DEFAULT_BOLD);
            valueLabel.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.bottomMargin = dp(context, 12);
            content.addView(valueLabel, labelParams);

            LinearLayout sliderRow = new LinearLayout(context);
            sliderRow.setOrientation(LinearLayout.HORIZONTAL);
            sliderRow.setGravity(Gravity.CENTER_VERTICAL);

            Button minusButton = new Button(context, null, android.R.attr.borderlessButtonStyle);
            minusButton.setText("-");
            minusButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            minusButton.setMinWidth(0);
            minusButton.setMinimumWidth(0);
            sliderRow.addView(minusButton, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));

            SeekBar seekBar = new SeekBar(context);
            seekBar.setMax(TOPBAR_SCALE_TOTAL_STEPS);
            seekBar.setProgress(initialProgress);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            barParams.leftMargin = dp(context, 4);
            barParams.rightMargin = dp(context, 4);
            sliderRow.addView(seekBar, barParams);

            Button plusButton = new Button(context, null, android.R.attr.borderlessButtonStyle);
            plusButton.setText("+");
            plusButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            plusButton.setMinWidth(0);
            plusButton.setMinimumWidth(0);
            sliderRow.addView(plusButton, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));

            content.addView(sliderRow);

            Runnable syncUi = () -> {
                int progress = seekBar.getProgress();
                valueLabel.setText(formatTopbarScale(stepToScale(progress)));
                minusButton.setEnabled(progress > 0);
                plusButton.setEnabled(progress < TOPBAR_SCALE_TOTAL_STEPS);
            };
            syncUi.run();

            minusButton.setOnClickListener(v -> {
                int progress = seekBar.getProgress();
                if (progress > 0) {
                    seekBar.setProgress(progress - 1);
                }
            });
            plusButton.setOnClickListener(v -> {
                int progress = seekBar.getProgress();
                if (progress < TOPBAR_SCALE_TOTAL_STEPS) {
                    seekBar.setProgress(progress + 1);
                }
            });
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    syncUi.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {}

                @Override
                public void onStopTrackingTouch(SeekBar bar) {}
            });

            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(topbarScaleTitle)
                    .setView(content)
                    .setNeutralButton(sliderDefaultLabel, null)
                    .setNegativeButton(
                            GboardSettingsText.get(context, R.string.gboard_patches_dialog_cancel),
                            null)
                    .setPositiveButton(
                            GboardSettingsText.get(context, R.string.gboard_patches_dialog_save),
                            (d, which) -> {
                                GboardMatrixSuggestionsSettings.writeTopbarScale(
                                        context, stepToScale(seekBar.getProgress()));
                                GboardMatrixSuggestionsRuntime.syncAllHeaderHeights(context);
                                GboardPatchesSettingsContract.refresh(host);
                            })
                    .create();
            dialog.setOnDismissListener(d -> onDismiss.run());
            dialog.show();

            Button defaultButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (defaultButton != null) {
                defaultButton.setOnClickListener(v -> seekBar.setProgress(defaultProgress));
            }
            return true;
        });
    }

    private String formatTopbarScale(float scale) {
        float sanitized = GboardMatrixSuggestionsSettings.sanitizeTopbarScale(scale);
        if (Math.abs(sanitized - GboardMatrixSuggestionsSettings.DEFAULT_TOPBAR_SCALE) < 0.001f) {
            return String.format(topbarScaleDefaultTemplate, sanitized);
        }
        return String.format(topbarScaleValueTemplate, sanitized);
    }

    private static int scaleToStep(float value) {
        float sanitized = GboardMatrixSuggestionsSettings.sanitizeTopbarScale(value);
        int step = Math.round(
                (sanitized - GboardMatrixSuggestionsSettings.MIN_TOPBAR_SCALE)
                        / TOPBAR_SCALE_STEP);
        return Math.max(0, Math.min(TOPBAR_SCALE_TOTAL_STEPS, step));
    }

    private static float stepToScale(int step) {
        int clamped = Math.max(0, Math.min(TOPBAR_SCALE_TOTAL_STEPS, step));
        return GboardMatrixSuggestionsSettings.sanitizeTopbarScale(
                GboardMatrixSuggestionsSettings.MIN_TOPBAR_SCALE + clamped * TOPBAR_SCALE_STEP);
    }

    private static int dp(Context context, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
    }

    private GboardPatchesSettingsContract.Screen buildErrorScreen() {
        return new GboardPatchesSettingsContract.Screen(
                title,
                null,
                title,
                summary,
                Collections.singletonList(new GboardPatchesSettingsContract.StatusBlock(
                        errorTitle,
                        errorSummary,
                        GboardPatchesSettingsContract.StatusTone.WARNING)),
                Collections.emptyList(),
                GboardPatchesSettingsContract.RefreshPolicy.none(),
                GboardPatchesSettingsContract.PanelStyle.FLAT);
    }
}
