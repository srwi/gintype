package dev.srwi.gboardmultiswipe.extension.multiswipe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import dev.srwi.gboardmultiswipe.extension.R;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesFeatureAvailability;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettings;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettingsContract;
import dev.srwi.gboardmultiswipe.extension.settings.GboardSettingsText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Sub-page for tuning Kinetica decoder algorithm parameters and resetting them to defaults. */
public final class GboardKineticaSettingsFeature implements GboardPatchesSettingsContract.Feature {

    private static final String TAG = "GboardPatches";
    private static final int BEAM_WIDTH_STEP = 16;
    private static final float FREQ_WEIGHT_FLOOR_STEP = 0.01f;
    private static final float GEO_EXPONENT_STEP = 0.05f;
    private static final float BEAM_FREQ_WEIGHT_STEP = 0.05f;
    private static final float FLOAT_MATCH_EPS = 0.0005f;

    private interface IntSaver {
        boolean save(int value);
    }

    private interface FloatSaver {
        boolean save(float value);
    }

    private final String title;
    private final String summary;
    private final String headerSummary;
    private final String sectionParameters;
    private final String beamWidthTitle;
    private final String beamWidthSummary;
    private final String freqWeightFloorTitle;
    private final String freqWeightFloorSummary;
    private final String geoExponentTitle;
    private final String geoExponentSummary;
    private final String beamFreqWeightTitle;
    private final String beamFreqWeightSummary;
    private final String defaultValueTemplate;
    private final String resetTitle;
    private final String resetSummary;
    private final String resetConfirmTitle;
    private final String resetConfirmMessage;
    private final String resetDone;
    private final String errorTitle;
    private final String errorSummary;

    public GboardKineticaSettingsFeature(Context context) {
        title = text(context, R.string.gboard_patches_kinetica_title);
        summary = text(context, R.string.gboard_patches_kinetica_summary);
        headerSummary = text(context, R.string.gboard_patches_kinetica_header_summary);
        sectionParameters = text(context, R.string.gboard_patches_kinetica_section_parameters);
        beamWidthTitle = text(context, R.string.gboard_patches_multiswipe_beam_width_title);
        beamWidthSummary = text(context, R.string.gboard_patches_multiswipe_beam_width_summary);
        freqWeightFloorTitle = text(context, R.string.gboard_patches_multiswipe_freq_weight_floor_title);
        freqWeightFloorSummary =
                text(context, R.string.gboard_patches_multiswipe_freq_weight_floor_summary);
        geoExponentTitle = text(context, R.string.gboard_patches_multiswipe_geo_exponent_title);
        geoExponentSummary = text(context, R.string.gboard_patches_multiswipe_geo_exponent_summary);
        beamFreqWeightTitle = text(context, R.string.gboard_patches_multiswipe_beam_freq_weight_title);
        beamFreqWeightSummary =
                text(context, R.string.gboard_patches_multiswipe_beam_freq_weight_summary);
        defaultValueTemplate = text(context, R.string.gboard_patches_multiswipe_value_default);
        resetTitle = text(context, R.string.gboard_patches_kinetica_reset_title);
        resetSummary = text(context, R.string.gboard_patches_kinetica_reset_summary);
        resetConfirmTitle = text(context, R.string.gboard_patches_kinetica_reset_confirm_title);
        resetConfirmMessage = text(context, R.string.gboard_patches_kinetica_reset_confirm_message);
        resetDone = text(context, R.string.gboard_patches_kinetica_reset_done);
        errorTitle = text(context, R.string.gboard_patches_multiswipe_error_title);
        errorSummary = text(context, R.string.gboard_patches_multiswipe_error_summary);
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
    public boolean isAvailable(Context context) {
        return GboardPatchesFeatureAvailability.hasFeature(
                context, GboardPatchesFeatureAvailability.FEATURE_MULTISWIPE);
    }

    @Override
    public GboardPatchesSettingsContract.Screen buildScreen(
            GboardPatchesSettingsContract.FeatureHost host) {
        try {
            if (host == null || host.getContext() == null) {
                return buildErrorScreen();
            }
            SharedPreferences preferences = GboardPatchesSettings.preferences(host.getContext());
            GboardMultiSwipeSettings.ensureDefaults(preferences);
            GboardMultiSwipeSettings.syncDecoderConstants(preferences);

            boolean multiSwipeEnabled = GboardMultiSwipeSettings.readEnabled(preferences);
            int beamWidth = GboardMultiSwipeSettings.readBeamWidth(preferences);
            float freqWeightFloor = GboardMultiSwipeSettings.readFreqWeightFloor(preferences);
            float geoExponent = GboardMultiSwipeSettings.readGeoExponent(preferences);
            float beamFreqWeight = GboardMultiSwipeSettings.readBeamFreqWeight(preferences);

            List<GboardPatchesSettingsContract.Row> parameterRows = new ArrayList<>(4);
            parameterRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    beamWidthTitle,
                    beamWidthSummary,
                    formatBeamWidthLabel(beamWidth),
                    multiSwipeEnabled,
                    () -> showIntSliderDialog(
                            host,
                            beamWidthTitle,
                            beamWidthSummary,
                            GboardMultiSwipeSettings.MIN_BEAM_WIDTH,
                            GboardMultiSwipeSettings.MAX_BEAM_WIDTH,
                            BEAM_WIDTH_STEP,
                            GboardMultiSwipeSettings.DEFAULT_BEAM_WIDTH,
                            beamWidth,
                            v -> syncAfterSave(preferences,
                                    GboardMultiSwipeSettings.writeBeamWidth(preferences, v)))));
            parameterRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    freqWeightFloorTitle,
                    freqWeightFloorSummary,
                    formatFloatLabel(freqWeightFloor, GboardMultiSwipeSettings.DEFAULT_FREQ_WEIGHT_FLOOR),
                    multiSwipeEnabled,
                    () -> showFloatSliderDialog(
                            host,
                            freqWeightFloorTitle,
                            freqWeightFloorSummary,
                            GboardMultiSwipeSettings.MIN_FREQ_WEIGHT_FLOOR,
                            GboardMultiSwipeSettings.MAX_FREQ_WEIGHT_FLOOR,
                            FREQ_WEIGHT_FLOOR_STEP,
                            GboardMultiSwipeSettings.DEFAULT_FREQ_WEIGHT_FLOOR,
                            freqWeightFloor,
                            v -> syncAfterSave(preferences,
                                    GboardMultiSwipeSettings.writeFreqWeightFloor(preferences, v)))));
            parameterRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    geoExponentTitle,
                    geoExponentSummary,
                    formatFloatLabel(geoExponent, GboardMultiSwipeSettings.DEFAULT_GEO_EXPONENT),
                    multiSwipeEnabled,
                    () -> showFloatSliderDialog(
                            host,
                            geoExponentTitle,
                            geoExponentSummary,
                            GboardMultiSwipeSettings.MIN_GEO_EXPONENT,
                            GboardMultiSwipeSettings.MAX_GEO_EXPONENT,
                            GEO_EXPONENT_STEP,
                            GboardMultiSwipeSettings.DEFAULT_GEO_EXPONENT,
                            geoExponent,
                            v -> syncAfterSave(preferences,
                                    GboardMultiSwipeSettings.writeGeoExponent(preferences, v)))));
            parameterRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    beamFreqWeightTitle,
                    beamFreqWeightSummary,
                    formatFloatLabel(beamFreqWeight, GboardMultiSwipeSettings.DEFAULT_BEAM_FREQ_WEIGHT),
                    multiSwipeEnabled,
                    () -> showFloatSliderDialog(
                            host,
                            beamFreqWeightTitle,
                            beamFreqWeightSummary,
                            GboardMultiSwipeSettings.MIN_BEAM_FREQ_WEIGHT,
                            GboardMultiSwipeSettings.MAX_BEAM_FREQ_WEIGHT,
                            BEAM_FREQ_WEIGHT_STEP,
                            GboardMultiSwipeSettings.DEFAULT_BEAM_FREQ_WEIGHT,
                            beamFreqWeight,
                            v -> syncAfterSave(preferences,
                                    GboardMultiSwipeSettings.writeBeamFreqWeight(preferences, v)))));

            List<GboardPatchesSettingsContract.Row> resetRows = Collections.singletonList(
                    new GboardPatchesSettingsContract.DangerRow(
                            resetTitle,
                            resetSummary,
                            true,
                            () -> {
                                GboardMultiSwipeSettings.resetDecoderToDefaults(preferences);
                                GboardPatchesSettingsContract.refresh(host);
                                GboardPatchesSettingsContract.showMessage(host, resetDone);
                            },
                            resetConfirmTitle,
                            resetConfirmMessage));

            return new GboardPatchesSettingsContract.Screen(
                    title,
                    null,
                    title,
                    headerSummary,
                    Collections.emptyList(),
                    Arrays.asList(
                            new GboardPatchesSettingsContract.Section(
                                    sectionParameters, parameterRows),
                            new GboardPatchesSettingsContract.Section(
                                    null,
                                    null,
                                    GboardPatchesSettingsContract.SectionStyle.ADVANCED,
                                    resetRows)),
                    GboardPatchesSettingsContract.RefreshPolicy.none(),
                    GboardPatchesSettingsContract.PanelStyle.FLAT);
        } catch (Throwable throwable) {
            try {
                Log.w(TAG, "Failed to render Kinetica settings screen", throwable);
            } catch (Throwable ignored) {
                // Fallback error screen below handles recovery.
            }
            return buildErrorScreen();
        }
    }

    private static boolean syncAfterSave(SharedPreferences preferences, boolean ok) {
        if (ok) {
            GboardMultiSwipeSettings.syncDecoderConstants(preferences);
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
            IntSaver saver) {
        int totalSteps = Math.max(1, (max - min) / step);
        int initialProgress = GboardMultiSwipeSettingsFeature.sliderStepForInt(
                currentValue, min, max, step);
        int defaultProgress = GboardMultiSwipeSettingsFeature.sliderStepForInt(
                defaultValue, min, max, step);
        GboardPatchesSettingsContract.showSliderDialog(
                host,
                dialogTitle,
                description,
                totalSteps,
                initialProgress,
                defaultProgress,
                Integer.toString(min),
                Integer.toString(max),
                p -> formatBeamWidthLabel(
                        GboardMultiSwipeSettingsFeature.intValueForSliderStep(p, min, max, step)),
                p -> {
                    int value = GboardMultiSwipeSettingsFeature.intValueForSliderStep(
                            p, min, max, step);
                    if (!saver.save(value)) {
                        throw new IllegalStateException("Failed to persist " + dialogTitle);
                    }
                });
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
            FloatSaver saver) {
        int totalSteps = Math.max(1, Math.round((max - min) / step));
        int initialProgress = GboardMultiSwipeSettingsFeature.sliderStepForFloat(
                currentValue, min, max, step);
        int defaultProgress = GboardMultiSwipeSettingsFeature.sliderStepForFloat(
                defaultValue, min, max, step);
        GboardPatchesSettingsContract.showSliderDialog(
                host,
                dialogTitle,
                description,
                totalSteps,
                initialProgress,
                defaultProgress,
                String.format(Locale.US, "%.2f", min),
                String.format(Locale.US, "%.2f", max),
                p -> formatFloatLabel(
                        GboardMultiSwipeSettingsFeature.floatValueForSliderStep(
                                p, min, max, step),
                        defaultValue),
                p -> {
                    float value = GboardMultiSwipeSettingsFeature.floatValueForSliderStep(
                            p, min, max, step);
                    if (!saver.save(value)) {
                        throw new IllegalStateException("Failed to persist " + dialogTitle);
                    }
                });
    }

    private String formatBeamWidthLabel(int beamWidth) {
        String num = Integer.toString(beamWidth);
        if (beamWidth == GboardMultiSwipeSettings.DEFAULT_BEAM_WIDTH) {
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
                null,
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
