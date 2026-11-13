package dev.jason.gboardpatches.extension.nintype;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.settings.GboardPatchesFeatureAvailability;
import dev.jason.gboardpatches.extension.settings.GboardPatchesIcons;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettings;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Settings screen for Nintype multi-finger swipe, auto-space, and Kinetica decoder tuning.
 */
public final class GboardNintypeSettingsFeature implements GboardPatchesSettingsContract.Feature {

    private static final String TAG = "GboardPatches";
    private static final int[] PRESET_DELAYS_MS = {150, 200, 300, 450, 600, 800};
    private static final int[] PRESET_COMBINE_DELAYS_MS = {0, 80, 120, 150, 200, 300};
    private static final int[] PRESET_BEAM_WIDTHS = {64, 128, 192, 256, 384, 512};
    private static final float[] PRESET_FREQ_WEIGHT_FLOORS = {0.10f, 0.15f, 0.25f, 0.40f, 0.60f, 0.80f, 1.00f};
    private static final float[] PRESET_GEO_EXPONENTS = {2.50f, 3.00f, 3.75f, 4.50f, 5.50f, 6.50f};
    private static final float[] PRESET_BEAM_FREQ_WEIGHTS = {0.00f, 0.25f, 0.50f, 0.75f, 1.00f, 1.50f};
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
    private final String delayDialogHint;
    private final String delayDialogError;
    private final String combineDelayTitle;
    private final String combineDelaySummary;
    private final String combineDelayHint;
    private final String combineDelayError;
    private final String combineDelayOffLabel;
    private final String defaultValueTemplate;
    private final String beamWidthTitle;
    private final String beamWidthSummary;
    private final String beamWidthHint;
    private final String beamWidthError;
    private final String freqWeightFloorTitle;
    private final String freqWeightFloorSummary;
    private final String freqWeightFloorHint;
    private final String freqWeightFloorError;
    private final String geoExponentTitle;
    private final String geoExponentSummary;
    private final String geoExponentHint;
    private final String geoExponentError;
    private final String beamFreqWeightTitle;
    private final String beamFreqWeightSummary;
    private final String beamFreqWeightHint;
    private final String beamFreqWeightError;
    private final String errorTitle;
    private final String errorSummary;

    public GboardNintypeSettingsFeature(Context context) {
        boolean de = isGerman();
        title = de ? "Nintype Zwei-Finger-Swipe"
                : text(context, R.string.gboard_patches_nintype_title);
        summary = de
                ? "Zwei-Finger-Swipe und Tap+Swipe-Eingabe nach dem Nintype-Algorithmus mit Gboard-Wörterbuch."
                : text(context, R.string.gboard_patches_nintype_summary);
        headerBadge = text(context, R.string.gboard_patches_header_badge);
        sectionBehavior = de ? "Zwei-Finger-Gesteneingabe"
                : text(context, R.string.gboard_patches_nintype_section_behavior);
        sectionTuning = de ? "Decoder-Abstimmung (Kinetica)"
                : text(context, R.string.gboard_patches_nintype_section_tuning);
        enabledTitle = de ? "Nintype Zwei-Finger-Swipe aktivieren"
                : text(context, R.string.gboard_patches_nintype_enabled_title);
        enabledSummary = de
                ? "Übernimmt automatisch bei Zwei-Finger-Swipes oder Tap+Swipe auf der Tastatur; Einzel-Taps und normales Swipen bleiben bei Gboard."
                : text(context, R.string.gboard_patches_nintype_enabled_summary);
        autoSpaceTitle = de ? "Automatisches Leerzeichen"
                : text(context, R.string.gboard_patches_nintype_auto_space_title);
        autoSpaceSummary = de
                ? "Vor und nach Nintype-Wörtern automatisch ein Leerzeichen einfügen."
                : text(context, R.string.gboard_patches_nintype_auto_space_summary);
        delayTitle = de ? "Autospace-Verzögerung"
                : text(context, R.string.gboard_patches_nintype_auto_space_delay_title);
        delaySummary = de
                ? "Wartezeit nach dem Abheben aller Finger, bevor das Wort bestätigt und ein Leerzeichen eingefügt wird."
                : text(context, R.string.gboard_patches_nintype_auto_space_delay_summary);
        delayValueTemplate = de ? "%d ms"
                : text(context, R.string.gboard_patches_nintype_auto_space_delay_value);
        delayValueDefaultTemplate = de ? "%d ms (Standard)"
                : text(context, R.string.gboard_patches_nintype_auto_space_delay_value_default);
        delayCustomLabel = de ? "Benutzerdefiniert…"
                : text(context, R.string.gboard_patches_nintype_auto_space_delay_custom);
        delayDialogHint = de ? "0–2000 ms"
                : text(context, R.string.gboard_patches_nintype_auto_space_delay_dialog_hint);
        delayDialogError = de ? "Bitte eine Verzögerung zwischen 0 und 2000 ms eingeben."
                : text(context, R.string.gboard_patches_nintype_auto_space_delay_dialog_error);
        combineDelayTitle = de ? "Single-Touch-Kombinationszeit"
                : text(context, R.string.gboard_patches_nintype_combine_delay_title);
        combineDelaySummary = de
                ? "Folgt nach einem Gboard-Einzelfinger-Swipe/Tap innerhalb dieser Zeit ein weiterer Swipe oder Tap, kombiniert Nintype beide rückwirkend (0 = aus)."
                : text(context, R.string.gboard_patches_nintype_combine_delay_summary);
        combineDelayHint = text(context, R.string.gboard_patches_nintype_combine_delay_hint);
        combineDelayError = de ? "Bitte eine Zeit zwischen 0 und 500 ms eingeben."
                : text(context, R.string.gboard_patches_nintype_combine_delay_error);
        combineDelayOffLabel = de ? "0 ms (Aus)"
                : text(context, R.string.gboard_patches_nintype_combine_delay_off);
        defaultValueTemplate = de ? "%s (Standard)"
                : text(context, R.string.gboard_patches_nintype_value_default);
        beamWidthTitle = de ? "Beam-Breite (BEAM_WIDTH)"
                : text(context, R.string.gboard_patches_nintype_beam_width_title);
        beamWidthSummary = de
                ? "Wie viele Wort-Anfänge pro Buchstabe bei parallelen Swipes behalten werden (höher = bessere Treffer im großen deutschen Wörterbuch)."
                : text(context, R.string.gboard_patches_nintype_beam_width_summary);
        beamWidthHint = text(context, R.string.gboard_patches_nintype_beam_width_hint);
        beamWidthError = de ? "Bitte eine Beam-Breite zwischen 32 und 1024 eingeben."
                : text(context, R.string.gboard_patches_nintype_beam_width_error);
        freqWeightFloorTitle = de ? "Häufigkeits-Untergrenze (FREQ_WEIGHT_FLOOR)"
                : text(context, R.string.gboard_patches_nintype_freq_weight_floor_title);
        freqWeightFloorSummary = de
                ? "Mindestfaktor für seltene Wörter (kleiner = häufige/bekannte Wörter bevorzugen; größer = geometrisch exakten Match bevorzugen)."
                : text(context, R.string.gboard_patches_nintype_freq_weight_floor_summary);
        freqWeightFloorHint = text(context, R.string.gboard_patches_nintype_freq_weight_floor_hint);
        freqWeightFloorError = de ? "Bitte einen Wert zwischen 0.05 und 1.00 eingeben."
                : text(context, R.string.gboard_patches_nintype_freq_weight_floor_error);
        geoExponentTitle = de ? "Geometrie-Exponent (GEO_EXPONENT)"
                : text(context, R.string.gboard_patches_nintype_geo_exponent_title);
        geoExponentSummary = de
                ? "Wie stark Formabweichungen bestraft werden (höher = exakterer Match schlägt Worthäufigkeit/Bigramm)."
                : text(context, R.string.gboard_patches_nintype_geo_exponent_summary);
        geoExponentHint = text(context, R.string.gboard_patches_nintype_geo_exponent_hint);
        geoExponentError = de ? "Bitte einen Wert zwischen 1.00 und 8.00 eingeben."
                : text(context, R.string.gboard_patches_nintype_geo_exponent_error);
        beamFreqWeightTitle = de ? "Beam-Frequenzgewicht (BEAM_FREQ_WEIGHT)"
                : text(context, R.string.gboard_patches_nintype_beam_freq_weight_title);
        beamFreqWeightSummary = de
                ? "Wie stark bekannte Wörter beim Beam-Pruning bevorzugt werden (0.0 = rein geometrisch, höher = bekannte Wörter bleiben länger erhalten)."
                : text(context, R.string.gboard_patches_nintype_beam_freq_weight_summary);
        beamFreqWeightHint = text(context, R.string.gboard_patches_nintype_beam_freq_weight_hint);
        beamFreqWeightError = de ? "Bitte einen Wert zwischen 0.00 und 2.00 eingeben."
                : text(context, R.string.gboard_patches_nintype_beam_freq_weight_error);
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
                    () -> showIntSelector(
                            host, delayTitle, delayDialogHint, PRESET_DELAYS_MS,
                            GboardNintypeSettings.MIN_AUTO_SPACE_DELAY_MS,
                            GboardNintypeSettings.MAX_AUTO_SPACE_DELAY_MS,
                            autoSpaceDelayMs, this::formatDelayLabel, delayDialogError,
                            v -> GboardNintypeSettings.writeAutoSpaceDelayMs(preferences, v))));
            behaviorRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    combineDelayTitle,
                    combineDelaySummary,
                    formatCombineDelayLabel(combineDelayMs),
                    enabled,
                    () -> showIntSelector(
                            host, combineDelayTitle, combineDelayHint, PRESET_COMBINE_DELAYS_MS,
                            GboardNintypeSettings.MIN_SINGLE_TOUCH_COMBINE_DELAY_MS,
                            GboardNintypeSettings.MAX_SINGLE_TOUCH_COMBINE_DELAY_MS,
                            combineDelayMs, this::formatCombineDelayLabel, combineDelayError,
                            v -> GboardNintypeSettings.writeSingleTouchCombineDelayMs(preferences, v))));

            List<GboardPatchesSettingsContract.Row> tuningRows = new ArrayList<>(4);
            tuningRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    beamWidthTitle,
                    beamWidthSummary,
                    formatBeamWidthLabel(beamWidth),
                    enabled,
                    () -> showIntSelector(
                            host, beamWidthTitle, beamWidthHint, PRESET_BEAM_WIDTHS,
                            GboardNintypeSettings.MIN_BEAM_WIDTH,
                            GboardNintypeSettings.MAX_BEAM_WIDTH,
                            beamWidth, this::formatBeamWidthLabel, beamWidthError,
                            v -> {
                                boolean ok = GboardNintypeSettings.writeBeamWidth(preferences, v);
                                if (ok) GboardNintypeSettings.syncDecoderConstants(preferences);
                                return ok;
                            })));
            tuningRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    freqWeightFloorTitle,
                    freqWeightFloorSummary,
                    formatFloatLabel(freqWeightFloor, GboardNintypeSettings.DEFAULT_FREQ_WEIGHT_FLOOR),
                    enabled,
                    () -> showFloatSelector(
                            host, freqWeightFloorTitle, freqWeightFloorHint, PRESET_FREQ_WEIGHT_FLOORS,
                            GboardNintypeSettings.MIN_FREQ_WEIGHT_FLOOR,
                            GboardNintypeSettings.MAX_FREQ_WEIGHT_FLOOR,
                            GboardNintypeSettings.DEFAULT_FREQ_WEIGHT_FLOOR,
                            freqWeightFloor, freqWeightFloorError,
                            v -> {
                                boolean ok = GboardNintypeSettings.writeFreqWeightFloor(preferences, v);
                                if (ok) GboardNintypeSettings.syncDecoderConstants(preferences);
                                return ok;
                            })));
            tuningRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    geoExponentTitle,
                    geoExponentSummary,
                    formatFloatLabel(geoExponent, GboardNintypeSettings.DEFAULT_GEO_EXPONENT),
                    enabled,
                    () -> showFloatSelector(
                            host, geoExponentTitle, geoExponentHint, PRESET_GEO_EXPONENTS,
                            GboardNintypeSettings.MIN_GEO_EXPONENT,
                            GboardNintypeSettings.MAX_GEO_EXPONENT,
                            GboardNintypeSettings.DEFAULT_GEO_EXPONENT,
                            geoExponent, geoExponentError,
                            v -> {
                                boolean ok = GboardNintypeSettings.writeGeoExponent(preferences, v);
                                if (ok) GboardNintypeSettings.syncDecoderConstants(preferences);
                                return ok;
                            })));
            tuningRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    beamFreqWeightTitle,
                    beamFreqWeightSummary,
                    formatFloatLabel(beamFreqWeight, GboardNintypeSettings.DEFAULT_BEAM_FREQ_WEIGHT),
                    enabled,
                    () -> showFloatSelector(
                            host, beamFreqWeightTitle, beamFreqWeightHint, PRESET_BEAM_FREQ_WEIGHTS,
                            GboardNintypeSettings.MIN_BEAM_FREQ_WEIGHT,
                            GboardNintypeSettings.MAX_BEAM_FREQ_WEIGHT,
                            GboardNintypeSettings.DEFAULT_BEAM_FREQ_WEIGHT,
                            beamFreqWeight, beamFreqWeightError,
                            v -> {
                                boolean ok = GboardNintypeSettings.writeBeamFreqWeight(preferences, v);
                                if (ok) GboardNintypeSettings.syncDecoderConstants(preferences);
                                return ok;
                            })));

            return new GboardPatchesSettingsContract.Screen(
                    title,
                    headerBadge,
                    title,
                    summary,
                    Collections.emptyList(),
                    Arrays.asList(
                            new GboardPatchesSettingsContract.Section(sectionBehavior, behaviorRows),
                            new GboardPatchesSettingsContract.Section(sectionTuning, tuningRows)),
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

    private void showIntSelector(
            GboardPatchesSettingsContract.FeatureHost host,
            String dialogTitle,
            String hint,
            int[] presets,
            int min,
            int max,
            int currentValue,
            IntFormatter formatter,
            String errorMessage,
            IntSaver saver
    ) {
        String[] labels = new String[presets.length + 1];
        String[] values = new String[presets.length + 1];
        String selectedValue = VALUE_CUSTOM;
        for (int i = 0; i < presets.length; i++) {
            int preset = presets[i];
            labels[i] = formatter.format(preset);
            values[i] = Integer.toString(preset);
            if (preset == currentValue) {
                selectedValue = values[i];
            }
        }
        labels[presets.length] = delayCustomLabel;
        values[presets.length] = VALUE_CUSTOM;

        GboardPatchesSettingsContract.showChoiceDialog(
                host,
                dialogTitle,
                labels,
                values,
                selectedValue,
                VALUE_CUSTOM,
                () -> GboardPatchesSettingsContract.showTextInputDialog(
                        host,
                        dialogTitle,
                        hint,
                        Integer.toString(currentValue),
                        raw -> {
                            int parsed = parseIntInRange(raw, min, max, errorMessage);
                            if (!saver.save(parsed)) {
                                throw new IllegalStateException("Failed to persist " + dialogTitle);
                            }
                        }),
                choice -> {
                    int parsed = parseIntInRange(choice, min, max, errorMessage);
                    if (!saver.save(parsed)) {
                        throw new IllegalStateException("Failed to persist " + dialogTitle);
                    }
                });
    }

    private void showFloatSelector(
            GboardPatchesSettingsContract.FeatureHost host,
            String dialogTitle,
            String hint,
            float[] presets,
            float min,
            float max,
            float defaultValue,
            float currentValue,
            String errorMessage,
            FloatSaver saver
    ) {
        String[] labels = new String[presets.length + 1];
        String[] values = new String[presets.length + 1];
        String selectedValue = VALUE_CUSTOM;
        for (int i = 0; i < presets.length; i++) {
            float preset = presets[i];
            labels[i] = formatFloatLabel(preset, defaultValue);
            values[i] = String.format(Locale.US, "%.2f", preset);
            if (Math.abs(preset - currentValue) < FLOAT_MATCH_EPS) {
                selectedValue = values[i];
            }
        }
        labels[presets.length] = delayCustomLabel;
        values[presets.length] = VALUE_CUSTOM;

        GboardPatchesSettingsContract.showChoiceDialog(
                host,
                dialogTitle,
                labels,
                values,
                selectedValue,
                VALUE_CUSTOM,
                () -> GboardPatchesSettingsContract.showTextInputDialog(
                        host,
                        dialogTitle,
                        hint,
                        String.format(Locale.US, "%.2f", currentValue),
                        raw -> {
                            float parsed = parseFloatInRange(raw, min, max, errorMessage);
                            if (!saver.save(parsed)) {
                                throw new IllegalStateException("Failed to persist " + dialogTitle);
                            }
                        }),
                choice -> {
                    float parsed = parseFloatInRange(choice, min, max, errorMessage);
                    if (!saver.save(parsed)) {
                        throw new IllegalStateException("Failed to persist " + dialogTitle);
                    }
                });
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

    private static boolean isGerman() {
        try {
            return "de".equalsIgnoreCase(Locale.getDefault().getLanguage());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String text(Context context, int id) {
        return GboardSettingsText.get(context, id);
    }
}
