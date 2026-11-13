package dev.srwi.gboardmultiswipe.extension.nintype;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Settings screen for Nintype multi-finger swipe, auto-space, Kinetica decoder tuning,
 * and debug trace diagnostics.
 */
public final class GboardNintypeSettingsFeature implements GboardPatchesSettingsContract.Feature {

    private static final String TAG = "GboardPatches";
    private static final int AUTO_SPACE_DELAY_STEP_MS = 25;
    private static final int COMBINE_DELAY_STEP_MS = 10;
    private static final int BEAM_WIDTH_STEP = 16;
    private static final float FREQ_WEIGHT_FLOOR_STEP = 0.01f;
    private static final float GEO_EXPONENT_STEP = 0.05f;
    private static final float BEAM_FREQ_WEIGHT_STEP = 0.05f;
    private static final int[] PRESET_EXPORT_MINUTES = {5, 15, 30, 60, 180, 0};
    private static final String VALUE_CUSTOM = "custom";
    private static final float FLOAT_MATCH_EPS = 0.0005f;

    private interface IntFormatter {
        String format(int value);
    }

    private interface IntSaver {
        boolean save(int value);
    }

    private interface FloatSaver {
        boolean save(float value);
    }

    private final String title;
    private final String summary;
    private final String headerBadge;
    private final String sectionBehavior;
    private final String sectionTuning;
    private final String enabledTitle;
    private final String enabledSummary;
    private final String autoSpaceTitle;
    private final String autoSpaceSummary;
    private final String delayTitle;
    private final String delaySummary;
    private final String delayValueTemplate;
    private final String delayValueDefaultTemplate;
    private final String delayCustomLabel;
    private final String combineDelayTitle;
    private final String combineDelaySummary;
    private final String combineDelayOffLabel;
    private final String defaultValueTemplate;
    private final String beamWidthTitle;
    private final String beamWidthSummary;
    private final String freqWeightFloorTitle;
    private final String freqWeightFloorSummary;
    private final String geoExponentTitle;
    private final String geoExponentSummary;
    private final String beamFreqWeightTitle;
    private final String beamFreqWeightSummary;
    private final String sliderDefaultLabel;
    private final String sectionDiagnostics;
    private final String traceLoggingTitle;
    private final String traceLoggingSummary;
    private final String traceExportTitle;
    private final String traceExportSummary;
    private final String traceExportDialogTitle;
    private final String traceExportMinutesTemplate;
    private final String traceExportHoursTemplate;
    private final String traceExportAllLabel;
    private final String traceExportHint;
    private final String traceExportError;
    private final String traceExportEmpty;
    private final String traceExportDone;
    private final String traceClearTitle;
    private final String traceClearSummary;
    private final String traceClearConfirmTitle;
    private final String traceClearConfirmMessage;
    private final String traceClearDone;
    private final String sectionReset;
    private final String resetTitle;
    private final String resetSummary;
    private final String resetConfirmTitle;
    private final String resetConfirmMessage;
    private final String resetDone;
    private final String errorTitle;
    private final String errorSummary;

    public GboardNintypeSettingsFeature(Context context) {
        title = text(context, R.string.gboard_patches_nintype_title);
        summary = text(context, R.string.gboard_patches_nintype_summary);
        headerBadge = text(context, R.string.gboard_patches_header_badge);
        sectionBehavior = text(context, R.string.gboard_patches_nintype_section_behavior);
        sectionTuning = text(context, R.string.gboard_patches_nintype_section_tuning);
        enabledTitle = text(context, R.string.gboard_patches_nintype_enabled_title);
        enabledSummary = text(context, R.string.gboard_patches_nintype_enabled_summary);
        autoSpaceTitle = text(context, R.string.gboard_patches_nintype_auto_space_title);
        autoSpaceSummary = text(context, R.string.gboard_patches_nintype_auto_space_summary);
        delayTitle = text(context, R.string.gboard_patches_nintype_auto_space_delay_title);
        delaySummary = text(context, R.string.gboard_patches_nintype_auto_space_delay_summary);
        delayValueTemplate = text(context, R.string.gboard_patches_nintype_auto_space_delay_value);
        delayValueDefaultTemplate = text(context, R.string.gboard_patches_nintype_auto_space_delay_value_default);
        delayCustomLabel = text(context, R.string.gboard_patches_nintype_auto_space_delay_custom);
        combineDelayTitle = text(context, R.string.gboard_patches_nintype_combine_delay_title);
        combineDelaySummary = text(context, R.string.gboard_patches_nintype_combine_delay_summary);
        combineDelayOffLabel = text(context, R.string.gboard_patches_nintype_combine_delay_off);
        defaultValueTemplate = text(context, R.string.gboard_patches_nintype_value_default);
        beamWidthTitle = text(context, R.string.gboard_patches_nintype_beam_width_title);
        beamWidthSummary = text(context, R.string.gboard_patches_nintype_beam_width_summary);
        freqWeightFloorTitle = text(context, R.string.gboard_patches_nintype_freq_weight_floor_title);
        freqWeightFloorSummary = text(context, R.string.gboard_patches_nintype_freq_weight_floor_summary);
        geoExponentTitle = text(context, R.string.gboard_patches_nintype_geo_exponent_title);
        geoExponentSummary = text(context, R.string.gboard_patches_nintype_geo_exponent_summary);
        beamFreqWeightTitle = text(context, R.string.gboard_patches_nintype_beam_freq_weight_title);
        beamFreqWeightSummary = text(context, R.string.gboard_patches_nintype_beam_freq_weight_summary);
        sliderDefaultLabel = text(context, R.string.gboard_patches_nintype_slider_default);
        sectionDiagnostics = text(context, R.string.gboard_patches_nintype_section_diagnostics);
        traceLoggingTitle = text(context, R.string.gboard_patches_nintype_trace_logging_title);
        traceLoggingSummary = text(context, R.string.gboard_patches_nintype_trace_logging_summary);
        traceExportTitle = text(context, R.string.gboard_patches_nintype_trace_export_title);
        traceExportSummary = text(context, R.string.gboard_patches_nintype_trace_export_summary);
        traceExportDialogTitle = text(context, R.string.gboard_patches_nintype_trace_export_dialog_title);
        traceExportMinutesTemplate = text(context, R.string.gboard_patches_nintype_trace_export_minutes_item);
        traceExportHoursTemplate = text(context, R.string.gboard_patches_nintype_trace_export_hours_item);
        traceExportAllLabel = text(context, R.string.gboard_patches_nintype_trace_export_all);
        traceExportHint = text(context, R.string.gboard_patches_nintype_trace_export_hint);
        traceExportError = text(context, R.string.gboard_patches_nintype_trace_export_error);
        traceExportEmpty = text(context, R.string.gboard_patches_nintype_trace_export_empty);
        traceExportDone = text(context, R.string.gboard_patches_nintype_trace_export_done);
        traceClearTitle = text(context, R.string.gboard_patches_nintype_trace_clear_title);
        traceClearSummary = text(context, R.string.gboard_patches_nintype_trace_clear_summary);
        traceClearConfirmTitle = text(context, R.string.gboard_patches_nintype_trace_clear_confirm_title);
        traceClearConfirmMessage = text(context, R.string.gboard_patches_nintype_trace_clear_confirm_message);
        traceClearDone = text(context, R.string.gboard_patches_nintype_trace_clear_done);
        sectionReset = text(context, R.string.gboard_patches_nintype_section_reset);
        resetTitle = text(context, R.string.gboard_patches_nintype_reset_title);
        resetSummary = text(context, R.string.gboard_patches_nintype_reset_summary);
        resetConfirmTitle = text(context, R.string.gboard_patches_nintype_reset_confirm_title);
        resetConfirmMessage = text(context, R.string.gboard_patches_nintype_reset_confirm_message);
        resetDone = text(context, R.string.gboard_patches_nintype_reset_done);
        errorTitle = text(context, R.string.gboard_patches_nintype_error_title);
        errorSummary = text(context, R.string.gboard_patches_nintype_error_summary);
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
                context, GboardPatchesFeatureAvailability.FEATURE_NINTYPE);
    }

    @Override
    public GboardPatchesSettingsContract.Screen buildScreen(
            GboardPatchesSettingsContract.FeatureHost host) {
        try {
            if (host == null || host.getContext() == null) {
                return buildErrorScreen();
            }
            SharedPreferences preferences = GboardPatchesSettings.preferences(host.getContext());
            GboardNintypeSettings.ensureDefaults(preferences);
            GboardNintypeSettings.syncDecoderConstants(preferences);

            boolean enabled = GboardNintypeSettings.readEnabled(preferences);
            boolean autoSpaceEnabled = GboardNintypeSettings.readAutoSpaceEnabled(preferences);
            int autoSpaceDelayMs = GboardNintypeSettings.readAutoSpaceDelayMs(preferences);
            int combineDelayMs = GboardNintypeSettings.readSingleTouchCombineDelayMs(preferences);
            int beamWidth = GboardNintypeSettings.readBeamWidth(preferences);
            float freqWeightFloor = GboardNintypeSettings.readFreqWeightFloor(preferences);
            float geoExponent = GboardNintypeSettings.readGeoExponent(preferences);
            float beamFreqWeight = GboardNintypeSettings.readBeamFreqWeight(preferences);
            boolean traceLoggingEnabled = GboardNintypeSettings.readTraceLoggingEnabled(preferences);

            List<GboardPatchesSettingsContract.Row> behaviorRows = new ArrayList<>(4);
            behaviorRows.add(new GboardPatchesSettingsContract.ToggleRow(
                    enabledTitle,
                    enabledSummary,
                    true,
                    enabled,
                    value -> {
                        GboardNintypeSettings.writeEnabled(preferences, value);
                        GboardPatchesSettingsContract.refresh(host);
                    }));
            behaviorRows.add(new GboardPatchesSettingsContract.ToggleRow(
                    autoSpaceTitle,
                    autoSpaceSummary,
                    enabled,
                    autoSpaceEnabled,
                    value -> {
                        GboardNintypeSettings.writeAutoSpaceEnabled(preferences, value);
                        GboardPatchesSettingsContract.refresh(host);
                    }));
            behaviorRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    delayTitle,
                    delaySummary,
                    formatDelayLabel(autoSpaceDelayMs),
                    enabled,
                    () -> showIntSliderDialog(
                            host, delayTitle, delaySummary,
                            GboardNintypeSettings.MIN_AUTO_SPACE_DELAY_MS,
                            GboardNintypeSettings.MAX_AUTO_SPACE_DELAY_MS,
                            AUTO_SPACE_DELAY_STEP_MS,
                            GboardNintypeSettings.DEFAULT_AUTO_SPACE_DELAY_MS,
                            autoSpaceDelayMs, this::formatDelayLabel,
                            v -> GboardNintypeSettings.writeAutoSpaceDelayMs(preferences, v))));
            behaviorRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    combineDelayTitle,
                    combineDelaySummary,
                    formatCombineDelayLabel(combineDelayMs),
                    enabled,
                    () -> showIntSliderDialog(
                            host, combineDelayTitle, combineDelaySummary,
                            GboardNintypeSettings.MIN_SINGLE_TOUCH_COMBINE_DELAY_MS,
                            GboardNintypeSettings.MAX_SINGLE_TOUCH_COMBINE_DELAY_MS,
                            COMBINE_DELAY_STEP_MS,
                            GboardNintypeSettings.DEFAULT_SINGLE_TOUCH_COMBINE_DELAY_MS,
                            combineDelayMs, this::formatCombineDelayLabel,
                            v -> GboardNintypeSettings.writeSingleTouchCombineDelayMs(preferences, v))));

            List<GboardPatchesSettingsContract.Row> tuningRows = new ArrayList<>(4);
            tuningRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    beamWidthTitle,
                    beamWidthSummary,
                    formatBeamWidthLabel(beamWidth),
                    enabled,
                    () -> showIntSliderDialog(
                            host, beamWidthTitle, beamWidthSummary,
                            GboardNintypeSettings.MIN_BEAM_WIDTH,
                            GboardNintypeSettings.MAX_BEAM_WIDTH,
                            BEAM_WIDTH_STEP,
                            GboardNintypeSettings.DEFAULT_BEAM_WIDTH,
                            beamWidth, this::formatBeamWidthLabel,
                            v -> syncAfterSave(preferences,
                                    GboardNintypeSettings.writeBeamWidth(preferences, v)))));
            tuningRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    freqWeightFloorTitle,
                    freqWeightFloorSummary,
                    formatFloatLabel(freqWeightFloor, GboardNintypeSettings.DEFAULT_FREQ_WEIGHT_FLOOR),
                    enabled,
                    () -> showFloatSliderDialog(
                            host, freqWeightFloorTitle, freqWeightFloorSummary,
                            GboardNintypeSettings.MIN_FREQ_WEIGHT_FLOOR,
                            GboardNintypeSettings.MAX_FREQ_WEIGHT_FLOOR,
                            FREQ_WEIGHT_FLOOR_STEP,
                            GboardNintypeSettings.DEFAULT_FREQ_WEIGHT_FLOOR,
                            freqWeightFloor,
                            v -> syncAfterSave(preferences,
                                    GboardNintypeSettings.writeFreqWeightFloor(preferences, v)))));
            tuningRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    geoExponentTitle,
                    geoExponentSummary,
                    formatFloatLabel(geoExponent, GboardNintypeSettings.DEFAULT_GEO_EXPONENT),
                    enabled,
                    () -> showFloatSliderDialog(
                            host, geoExponentTitle, geoExponentSummary,
                            GboardNintypeSettings.MIN_GEO_EXPONENT,
                            GboardNintypeSettings.MAX_GEO_EXPONENT,
                            GEO_EXPONENT_STEP,
                            GboardNintypeSettings.DEFAULT_GEO_EXPONENT,
                            geoExponent,
                            v -> syncAfterSave(preferences,
                                    GboardNintypeSettings.writeGeoExponent(preferences, v)))));
            tuningRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    beamFreqWeightTitle,
                    beamFreqWeightSummary,
                    formatFloatLabel(beamFreqWeight, GboardNintypeSettings.DEFAULT_BEAM_FREQ_WEIGHT),
                    enabled,
                    () -> showFloatSliderDialog(
                            host, beamFreqWeightTitle, beamFreqWeightSummary,
                            GboardNintypeSettings.MIN_BEAM_FREQ_WEIGHT,
                            GboardNintypeSettings.MAX_BEAM_FREQ_WEIGHT,
                            BEAM_FREQ_WEIGHT_STEP,
                            GboardNintypeSettings.DEFAULT_BEAM_FREQ_WEIGHT,
                            beamFreqWeight,
                            v -> syncAfterSave(preferences,
                                    GboardNintypeSettings.writeBeamFreqWeight(preferences, v)))));

            List<GboardPatchesSettingsContract.Row> diagnosticsRows = new ArrayList<>(3);
            diagnosticsRows.add(new GboardPatchesSettingsContract.ToggleRow(
                    traceLoggingTitle,
                    traceLoggingSummary,
                    true,
                    traceLoggingEnabled,
                    value -> {
                        GboardNintypeSettings.writeTraceLoggingEnabled(preferences, value);
                        GboardPatchesSettingsContract.refresh(host);
                    }));
            diagnosticsRows.add(new GboardPatchesSettingsContract.CommandRow(
                    traceExportTitle,
                    traceExportSummary,
                    true,
                    () -> showTraceExportMinutesSelector(host)));
            diagnosticsRows.add(new GboardPatchesSettingsContract.DangerRow(
                    traceClearTitle,
                    traceClearSummary,
                    true,
                    () -> {
                        GboardNintypeTraceLogger.clearTraceLogs(host.getContext());
                        GboardPatchesSettingsContract.showMessage(host, traceClearDone);
                    },
                    traceClearConfirmTitle,
                    traceClearConfirmMessage));

            List<GboardPatchesSettingsContract.Row> resetRows = Collections.singletonList(
                    new GboardPatchesSettingsContract.DangerRow(
                            resetTitle,
                            resetSummary,
                            true,
                            () -> {
                                GboardNintypeSettings.resetToDefaults(preferences);
                                GboardPatchesSettingsContract.refresh(host);
                                GboardPatchesSettingsContract.showMessage(host, resetDone);
                            },
                            resetConfirmTitle,
                            resetConfirmMessage));

            return new GboardPatchesSettingsContract.Screen(
                    title,
                    headerBadge,
                    title,
                    summary,
                    Collections.emptyList(),
                    Arrays.asList(
                            new GboardPatchesSettingsContract.Section(sectionBehavior, behaviorRows),
                            new GboardPatchesSettingsContract.Section(sectionTuning, tuningRows),
                            new GboardPatchesSettingsContract.Section(sectionDiagnostics, diagnosticsRows),
                            new GboardPatchesSettingsContract.Section(
                                    sectionReset,
                                    null,
                                    GboardPatchesSettingsContract.SectionStyle.ADVANCED,
                                    resetRows)),
                    GboardPatchesSettingsContract.RefreshPolicy.none(),
                    GboardPatchesSettingsContract.PanelStyle.FLAT);
        } catch (Throwable throwable) {
            try {
                Log.w(TAG, "Failed to render Nintype settings screen", throwable);
            } catch (Throwable ignored) {
                // Fallback error screen below handles recovery.
            }
            return buildErrorScreen();
        }
    }

    private static boolean syncAfterSave(SharedPreferences preferences, boolean ok) {
        if (ok) {
            GboardNintypeSettings.syncDecoderConstants(preferences);
        }
        return ok;
    }

    private void showIntSliderDialog(
            GboardPatchesSettingsContract.FeatureHost host,
            String dialogTitle,
            String description,
            int min,
            int max,
            int step,
            int defaultValue,
            int currentValue,
            IntFormatter formatter,
            IntSaver saver
    ) {
        int totalSteps = Math.max(1, (max - min) / step);
        int initialProgress = sliderStepForInt(currentValue, min, max, step);
        int defaultProgress = sliderStepForInt(defaultValue, min, max, step);
        showStepSliderDialog(
                host,
                dialogTitle,
                description,
                totalSteps,
                initialProgress,
                defaultProgress,
                formatter.format(min),
                formatter.format(max),
                p -> formatter.format(intValueForSliderStep(p, min, max, step)),
                p -> saver.save(intValueForSliderStep(p, min, max, step)));
    }

    private void showFloatSliderDialog(
            GboardPatchesSettingsContract.FeatureHost host,
            String dialogTitle,
            String description,
            float min,
            float max,
            float step,
            float defaultValue,
            float currentValue,
            FloatSaver saver
    ) {
        int totalSteps = Math.max(1, Math.round((max - min) / step));
        int initialProgress = sliderStepForFloat(currentValue, min, max, step);
        int defaultProgress = sliderStepForFloat(defaultValue, min, max, step);
        showStepSliderDialog(
                host,
                dialogTitle,
                description,
                totalSteps,
                initialProgress,
                defaultProgress,
                String.format(Locale.US, "%.2f", min),
                String.format(Locale.US, "%.2f", max),
                p -> formatFloatLabel(floatValueForSliderStep(p, min, max, step), defaultValue),
                p -> saver.save(floatValueForSliderStep(p, min, max, step)));
    }

    private interface StepFormatter {
        String format(int step);
    }

    private void showStepSliderDialog(
            GboardPatchesSettingsContract.FeatureHost host,
            String dialogTitle,
            String description,
            int totalSteps,
            int initialProgress,
            int defaultProgress,
            String minLabel,
            String maxLabel,
            StepFormatter formatter,
            IntSaver stepSaver
    ) {
        GboardPatchesSettingsContract.showManagedDialog(host, onDismiss -> {
            Context context = host.getContext();
            if (context == null) return false;

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 8));

            if (description != null && !description.isEmpty()) {
                TextView descView = new TextView(context);
                descView.setText(description);
                descView.setTextSize(13f);
                descView.setPadding(0, 0, 0, dp(context, 12));
                content.addView(descView);
            }

            TextView valueView = new TextView(context);
            valueView.setTextSize(20f);
            valueView.setTypeface(Typeface.DEFAULT_BOLD);
            valueView.setGravity(Gravity.CENTER_HORIZONTAL);
            valueView.setPadding(0, dp(context, 4), 0, dp(context, 12));
            content.addView(valueView);

            LinearLayout sliderRow = new LinearLayout(context);
            sliderRow.setOrientation(LinearLayout.HORIZONTAL);
            sliderRow.setGravity(Gravity.CENTER_VERTICAL);

            Button minusBtn = new Button(context, null, android.R.attr.borderlessButtonStyle);
            minusBtn.setText("−");
            minusBtn.setTextSize(18f);
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    dp(context, 44), dp(context, 44));
            sliderRow.addView(minusBtn, btnParams);

            SeekBar seekBar = new SeekBar(context);
            seekBar.setMax(totalSteps);
            seekBar.setProgress(initialProgress);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            sliderRow.addView(seekBar, barParams);

            Button plusBtn = new Button(context, null, android.R.attr.borderlessButtonStyle);
            plusBtn.setText("+");
            plusBtn.setTextSize(18f);
            sliderRow.addView(plusBtn, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));

            content.addView(sliderRow);

            LinearLayout boundsRow = new LinearLayout(context);
            boundsRow.setOrientation(LinearLayout.HORIZONTAL);
            boundsRow.setPadding(dp(context, 44), dp(context, 2), dp(context, 44), 0);

            TextView minView = new TextView(context);
            minView.setText(minLabel);
            minView.setTextSize(12f);
            boundsRow.addView(minView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView maxView = new TextView(context);
            maxView.setText(maxLabel);
            maxView.setTextSize(12f);
            maxView.setGravity(Gravity.END);
            boundsRow.addView(maxView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            content.addView(boundsRow);

            Runnable syncUi = () -> {
                int p = seekBar.getProgress();
                valueView.setText(formatter.format(p));
                minusBtn.setEnabled(p > 0);
                plusBtn.setEnabled(p < totalSteps);
            };
            syncUi.run();

            minusBtn.setOnClickListener(v -> {
                int p = seekBar.getProgress();
                if (p > 0) seekBar.setProgress(p - 1);
            });
            plusBtn.setOnClickListener(v -> {
                int p = seekBar.getProgress();
                if (p < totalSteps) seekBar.setProgress(p + 1);
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
                    .setTitle(dialogTitle)
                    .setView(content)
                    .setNeutralButton(sliderDefaultLabel, null)
                    .setNegativeButton(text(context, R.string.gboard_patches_dialog_cancel), null)
                    .setPositiveButton(text(context, R.string.gboard_patches_dialog_save),
                            (d, which) -> {
                                if (!stepSaver.save(seekBar.getProgress())) {
                                    throw new IllegalStateException("Failed to persist " + dialogTitle);
                                }
                                GboardPatchesSettingsContract.refresh(host);
                            })
                    .create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> seekBar.setProgress(defaultProgress)));
            dialog.setOnDismissListener(ignored -> {
                if (onDismiss != null) onDismiss.run();
            });
            dialog.show();
            return true;
        });
    }

    private void showTraceExportMinutesSelector(GboardPatchesSettingsContract.FeatureHost host) {
        String[] labels = new String[PRESET_EXPORT_MINUTES.length + 1];
        String[] values = new String[PRESET_EXPORT_MINUTES.length + 1];
        for (int i = 0; i < PRESET_EXPORT_MINUTES.length; i++) {
            int mins = PRESET_EXPORT_MINUTES[i];
            labels[i] = formatExportWindowLabel(mins);
            values[i] = Integer.toString(mins);
        }
        labels[PRESET_EXPORT_MINUTES.length] = delayCustomLabel;
        values[PRESET_EXPORT_MINUTES.length] = VALUE_CUSTOM;

        GboardPatchesSettingsContract.showChoiceDialog(
                host,
                traceExportDialogTitle,
                labels,
                values,
                Integer.toString(15),
                VALUE_CUSTOM,
                () -> GboardPatchesSettingsContract.showTextInputDialog(
                        host,
                        traceExportDialogTitle,
                        traceExportHint,
                        "15",
                        raw -> {
                            int mins = parseIntInRange(
                                    raw,
                                    GboardNintypeSettings.MIN_TRACE_EXPORT_MINUTES,
                                    GboardNintypeSettings.MAX_TRACE_EXPORT_MINUTES,
                                    traceExportError);
                            exportTraceForMinutes(host, mins);
                        }),
                choice -> {
                    int mins = Integer.parseInt(choice);
                    exportTraceForMinutes(host, mins);
                });
    }

    private void exportTraceForMinutes(GboardPatchesSettingsContract.FeatureHost host, int minutes) {
        GboardNintypeTraceLogger.TraceExportResult result =
                GboardNintypeTraceLogger.readTraceForExport(host.getContext(), minutes);
        if (result.lineCount == 0 || result.content.isEmpty()) {
            GboardPatchesSettingsContract.showMessage(host, traceExportEmpty);
            return;
        }
        String fileName = minutes > 0
                ? "nintype_trace_" + minutes + "m.jsonl"
                : "nintype_trace_all.jsonl";
        GboardPatchesSettingsContract.createTextDocument(
                host,
                fileName,
                "text/plain",
                result.content,
                () -> GboardPatchesSettingsContract.showMessage(host, traceExportDone));
    }

    private String formatExportWindowLabel(int minutes) {
        if (minutes <= 0) {
            return traceExportAllLabel;
        }
        if (minutes >= 60 && minutes % 60 == 0) {
            return String.format(Locale.US, traceExportHoursTemplate, minutes / 60);
        }
        return String.format(Locale.US, traceExportMinutesTemplate, minutes);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int sliderStepForInt(int value, int min, int max, int step) {
        int totalSteps = Math.max(1, (max - min) / step);
        return Math.max(0, Math.min(totalSteps, Math.round((value - min) / (float) step)));
    }

    static int intValueForSliderStep(int progress, int min, int max, int step) {
        return Math.max(min, Math.min(max, min + progress * step));
    }

    static int sliderStepForFloat(float value, float min, float max, float step) {
        int totalSteps = Math.max(1, Math.round((max - min) / step));
        return Math.max(0, Math.min(totalSteps, Math.round((value - min) / step)));
    }

    static float floatValueForSliderStep(int progress, float min, float max, float step) {
        float raw = min + progress * step;
        float clamped = Math.max(min, Math.min(max, raw));
        return Math.round(clamped * 100f) / 100f;
    }

    static int parseDelayMs(String value, String errorMessage) {
        return parseIntInRange(
                value,
                GboardNintypeSettings.MIN_AUTO_SPACE_DELAY_MS,
                GboardNintypeSettings.MAX_AUTO_SPACE_DELAY_MS,
                errorMessage);
    }

    static int parseIntInRange(String value, int min, int max, String errorMessage) {
        if (value == null) {
            throw new IllegalArgumentException(errorMessage);
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(errorMessage);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(errorMessage);
        }
        return parsed;
    }

    static float parseFloatInRange(String value, float min, float max, String errorMessage) {
        if (value == null) {
            throw new IllegalArgumentException(errorMessage);
        }
        String normalized = value.trim().replace(',', '.');
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
        final float parsed;
        try {
            parsed = Float.parseFloat(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(errorMessage);
        }
        if (!Float.isFinite(parsed) || parsed < min || parsed > max) {
            throw new IllegalArgumentException(errorMessage);
        }
        return parsed;
    }

    private String formatDelayLabel(int delayMs) {
        if (delayMs == GboardNintypeSettings.DEFAULT_AUTO_SPACE_DELAY_MS) {
            return String.format(Locale.US, delayValueDefaultTemplate, delayMs);
        }
        return String.format(Locale.US, delayValueTemplate, delayMs);
    }

    private String formatCombineDelayLabel(int delayMs) {
        if (delayMs == 0) {
            return combineDelayOffLabel;
        }
        if (delayMs == GboardNintypeSettings.DEFAULT_SINGLE_TOUCH_COMBINE_DELAY_MS) {
            return String.format(Locale.US, delayValueDefaultTemplate, delayMs);
        }
        return String.format(Locale.US, delayValueTemplate, delayMs);
    }

    private String formatBeamWidthLabel(int beamWidth) {
        String num = Integer.toString(beamWidth);
        if (beamWidth == GboardNintypeSettings.DEFAULT_BEAM_WIDTH) {
            return String.format(Locale.US, defaultValueTemplate, num);
        }
        return num;
    }

    private String formatFloatLabel(float value, float defaultValue) {
        String num = String.format(Locale.US, "%.2f", value);
        if (Math.abs(value - defaultValue) < FLOAT_MATCH_EPS) {
            return String.format(Locale.US, defaultValueTemplate, num);
        }
        return num;
    }

    private GboardPatchesSettingsContract.Screen buildErrorScreen() {
        return new GboardPatchesSettingsContract.Screen(
                title,
                headerBadge,
                title,
                summary,
                Collections.singletonList(new GboardPatchesSettingsContract.StatusBlock(
                        errorTitle,
                        errorSummary,
                        GboardPatchesSettingsContract.StatusTone.WARNING)),
                Collections.emptyList());
    }

    private static String text(Context context, int id) {
        return GboardSettingsText.get(context, id);
    }
}
