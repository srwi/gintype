package dev.srwi.gboardmultiswipe.extension.multiswipe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
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
 * Settings screen for MultiSwipe multi-finger swipe, auto-space, and navigation to
 * Kinetica algorithm and diagnostics sub-pages.
 */
public final class GboardMultiSwipeSettingsFeature implements GboardPatchesSettingsContract.Feature {

    private static final String TAG = "GboardPatches";
    private static final int AUTO_SPACE_DELAY_STEP_MS = 25;
    private static final int COMBINE_DELAY_STEP_MS = 10;
    private static final int TAP_TO_MULTISWIPE_DELAY_STEP_MS = 25;
    private static final String SPACE_PLACEMENT_BEFORE = "before";
    private static final String SPACE_PLACEMENT_AFTER = "after";

    private interface IntFormatter {
        String format(int value);
    }

    private interface IntSaver {
        boolean save(int value);
    }

    private final GboardKineticaSettingsFeature kineticaFeature;
    private final GboardMultiSwipeDiagnosticsFeature diagnosticsFeature;
    private final String title;
    private final String summary;
    private final String sectionGestures;
    private final String sectionAutoSpace;
    private final String sectionAdvanced;
    private final String enabledTitle;
    private final String enabledSummary;
    private final String autoSpaceTitle;
    private final String autoSpaceSummary;
    private final String spacePlacementTitle;
    private final String spacePlacementSummary;
    private final String spacePlacementBeforeLabel;
    private final String spacePlacementAfterLabel;
    private final String delayTitle;
    private final String delaySummary;
    private final String delayValueTemplate;
    private final String delayValueDefaultTemplate;
    private final String combineEnabledTitle;
    private final String combineEnabledSummary;
    private final String combineDelayTitle;
    private final String combineDelaySummary;
    private final String tapToMultiSwipeEnabledTitle;
    private final String tapToMultiSwipeEnabledSummary;
    private final String tapToMultiSwipeDelayTitle;
    private final String tapToMultiSwipeDelaySummary;
    private final String spacebarTimerIndicatorTitle;
    private final String spacebarTimerIndicatorSummary;
    private final String backspaceModeTitle;
    private final String backspaceModeSummary;
    private final String backspaceModeWholeWordLabel;
    private final String backspaceModeLastInputLabel;
    private final String errorTitle;
    private final String errorSummary;

    public GboardMultiSwipeSettingsFeature(Context context) {
        kineticaFeature = new GboardKineticaSettingsFeature(context);
        diagnosticsFeature = new GboardMultiSwipeDiagnosticsFeature(context);
        title = text(context, R.string.gboard_patches_multiswipe_title);
        summary = text(context, R.string.gboard_patches_multiswipe_summary);
        sectionGestures = text(context, R.string.gboard_patches_multiswipe_section_gestures);
        sectionAutoSpace = text(context, R.string.gboard_patches_multiswipe_section_auto_space);
        sectionAdvanced = text(context, R.string.gboard_patches_multiswipe_section_advanced);
        enabledTitle = text(context, R.string.gboard_patches_multiswipe_enabled_title);
        enabledSummary = text(context, R.string.gboard_patches_multiswipe_enabled_summary);
        autoSpaceTitle = text(context, R.string.gboard_patches_multiswipe_auto_space_title);
        autoSpaceSummary = text(context, R.string.gboard_patches_multiswipe_auto_space_summary);
        spacePlacementTitle =
                text(context, R.string.gboard_patches_multiswipe_space_placement_title);
        spacePlacementSummary =
                text(context, R.string.gboard_patches_multiswipe_space_placement_summary);
        spacePlacementBeforeLabel =
                text(context, R.string.gboard_patches_multiswipe_space_placement_before);
        spacePlacementAfterLabel =
                text(context, R.string.gboard_patches_multiswipe_space_placement_after);
        delayTitle = text(context, R.string.gboard_patches_multiswipe_auto_space_delay_title);
        delaySummary = text(context, R.string.gboard_patches_multiswipe_auto_space_delay_summary);
        delayValueTemplate =
                text(context, R.string.gboard_patches_multiswipe_auto_space_delay_value);
        delayValueDefaultTemplate =
                text(context, R.string.gboard_patches_multiswipe_auto_space_delay_value_default);
        combineEnabledTitle =
                text(context, R.string.gboard_patches_multiswipe_combine_enabled_title);
        combineEnabledSummary =
                text(context, R.string.gboard_patches_multiswipe_combine_enabled_summary);
        combineDelayTitle = text(context, R.string.gboard_patches_multiswipe_combine_delay_title);
        combineDelaySummary =
                text(context, R.string.gboard_patches_multiswipe_combine_delay_summary);
        tapToMultiSwipeEnabledTitle =
                text(context, R.string.gboard_patches_multiswipe_tap_to_multiswipe_enabled_title);
        tapToMultiSwipeEnabledSummary =
                text(context, R.string.gboard_patches_multiswipe_tap_to_multiswipe_enabled_summary);
        tapToMultiSwipeDelayTitle =
                text(context, R.string.gboard_patches_multiswipe_tap_to_multiswipe_delay_title);
        tapToMultiSwipeDelaySummary =
                text(context, R.string.gboard_patches_multiswipe_tap_to_multiswipe_delay_summary);
        spacebarTimerIndicatorTitle =
                text(context, R.string.gboard_patches_multiswipe_spacebar_timer_indicator_title);
        spacebarTimerIndicatorSummary =
                text(context, R.string.gboard_patches_multiswipe_spacebar_timer_indicator_summary);
        backspaceModeTitle = text(context, R.string.gboard_patches_multiswipe_backspace_mode_title);
        backspaceModeSummary =
                text(context, R.string.gboard_patches_multiswipe_backspace_mode_summary);
        backspaceModeWholeWordLabel =
                text(context, R.string.gboard_patches_multiswipe_backspace_mode_whole_word);
        backspaceModeLastInputLabel =
                text(context, R.string.gboard_patches_multiswipe_backspace_mode_last_input);
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
                context, GboardPatchesFeatureAvailability.FEATURE_MULTISWIPE);
    }

    @Override
    public List<GboardPatchesSettingsContract.Feature> getNavigationChildren() {
        return Arrays.asList(kineticaFeature, diagnosticsFeature);
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

            boolean enabled = GboardMultiSwipeSettings.readEnabled(preferences);
            boolean autoSpaceEnabled = GboardMultiSwipeSettings.readAutoSpaceEnabled(preferences);
            boolean autoSpaceBeforeWord =
                    GboardMultiSwipeSettings.readAutoSpaceBeforeWord(preferences);
            int autoSpaceDelayMs = GboardMultiSwipeSettings.readAutoSpaceDelayMs(preferences);
            boolean combineEnabled =
                    GboardMultiSwipeSettings.readSingleTouchCombineEnabled(preferences);
            int combineDelayMs = GboardMultiSwipeSettings.readSingleTouchCombineDelayMs(preferences);
            boolean tapToMultiSwipeEnabled =
                    GboardMultiSwipeSettings.readTapToMultiSwipeEnabled(preferences);
            int tapToMultiSwipeDelayMs =
                    GboardMultiSwipeSettings.readTapToMultiSwipeDelayMs(preferences);
            boolean spacebarTimerIndicatorEnabled =
                    GboardMultiSwipeSettings.readSpacebarTimerIndicatorEnabled(preferences);
            String backspaceMode = GboardMultiSwipeSettings.readBackspaceMode(preferences);

            List<GboardPatchesSettingsContract.Row> gestureRows = buildGestureRows(
                    enabledTitle,
                    enabledSummary,
                    enabled,
                    value -> {
                        GboardMultiSwipeSettings.writeEnabled(preferences, value);
                        GboardPatchesSettingsContract.refresh(host);
                    },
                    combineEnabledTitle,
                    combineEnabledSummary,
                    combineEnabled,
                    value -> {
                        GboardMultiSwipeSettings.writeSingleTouchCombineEnabled(preferences, value);
                        GboardPatchesSettingsContract.refresh(host);
                    },
                    combineDelayTitle,
                    combineDelaySummary,
                    formatCombineDelayLabel(combineDelayMs),
                    () -> showIntSliderDialog(
                            host,
                            combineDelayTitle,
                            combineDelaySummary,
                            GboardMultiSwipeSettings.MIN_SINGLE_TOUCH_COMBINE_DELAY_MS,
                            GboardMultiSwipeSettings.MAX_SINGLE_TOUCH_COMBINE_DELAY_MS,
                            COMBINE_DELAY_STEP_MS,
                            GboardMultiSwipeSettings.DEFAULT_SINGLE_TOUCH_COMBINE_DELAY_MS,
                            combineDelayMs,
                            this::formatCombineDelayLabel,
                            v -> GboardMultiSwipeSettings.writeSingleTouchCombineDelayMs(
                                    preferences, v)),
                    tapToMultiSwipeEnabledTitle,
                    tapToMultiSwipeEnabledSummary,
                    tapToMultiSwipeEnabled,
                    value -> {
                        GboardMultiSwipeSettings.writeTapToMultiSwipeEnabled(preferences, value);
                        GboardPatchesSettingsContract.refresh(host);
                    },
                    tapToMultiSwipeDelayTitle,
                    tapToMultiSwipeDelaySummary,
                    formatTapToMultiSwipeDelayLabel(tapToMultiSwipeDelayMs),
                    () -> showIntSliderDialog(
                            host,
                            tapToMultiSwipeDelayTitle,
                            tapToMultiSwipeDelaySummary,
                            GboardMultiSwipeSettings.MIN_TAP_TO_MULTISWIPE_DELAY_MS,
                            GboardMultiSwipeSettings.MAX_TAP_TO_MULTISWIPE_DELAY_MS,
                            TAP_TO_MULTISWIPE_DELAY_STEP_MS,
                            GboardMultiSwipeSettings.DEFAULT_TAP_TO_MULTISWIPE_DELAY_MS,
                            tapToMultiSwipeDelayMs,
                            this::formatTapToMultiSwipeDelayLabel,
                            v -> GboardMultiSwipeSettings.writeTapToMultiSwipeDelayMs(
                                    preferences, v)),
                    backspaceModeTitle,
                    backspaceModeSummary,
                    formatBackspaceModeLabel(backspaceMode),
                    () -> showBackspaceModeSelector(host, preferences, backspaceMode));

            List<GboardPatchesSettingsContract.Row> autoSpaceRows = buildAutoSpaceRows(
                    enabled,
                    autoSpaceTitle,
                    autoSpaceSummary,
                    autoSpaceEnabled,
                    value -> {
                        GboardMultiSwipeSettings.writeAutoSpaceEnabled(preferences, value);
                        GboardPatchesSettingsContract.refresh(host);
                    },
                    spacePlacementTitle,
                    spacePlacementSummary,
                    formatSpacePlacementLabel(autoSpaceBeforeWord),
                    () -> showSpacePlacementSelector(host, preferences, autoSpaceBeforeWord),
                    delayTitle,
                    delaySummary,
                    formatDelayLabel(autoSpaceDelayMs),
                    () -> showIntSliderDialog(
                            host,
                            delayTitle,
                            delaySummary,
                            GboardMultiSwipeSettings.MIN_AUTO_SPACE_DELAY_MS,
                            GboardMultiSwipeSettings.MAX_AUTO_SPACE_DELAY_MS,
                            AUTO_SPACE_DELAY_STEP_MS,
                            GboardMultiSwipeSettings.DEFAULT_AUTO_SPACE_DELAY_MS,
                            autoSpaceDelayMs,
                            this::formatDelayLabel,
                            v -> GboardMultiSwipeSettings.writeAutoSpaceDelayMs(preferences, v)),
                    spacebarTimerIndicatorTitle,
                    spacebarTimerIndicatorSummary,
                    spacebarTimerIndicatorEnabled,
                    value -> GboardMultiSwipeSettings.writeSpacebarTimerIndicatorEnabled(
                            preferences, value));

            List<GboardPatchesSettingsContract.Row> advancedRows = Arrays.asList(
                    new GboardPatchesSettingsContract.NavigationRow(
                            kineticaFeature.getEntryTitle(),
                            kineticaFeature.getEntrySummary(),
                            enabled,
                            () -> GboardPatchesSettingsContract.openFeature(
                                    host, kineticaFeature)),
                    new GboardPatchesSettingsContract.NavigationRow(
                            diagnosticsFeature.getEntryTitle(),
                            diagnosticsFeature.getEntrySummary(),
                            true,
                            () -> GboardPatchesSettingsContract.openFeature(
                                    host, diagnosticsFeature)));

            return new GboardPatchesSettingsContract.Screen(
                    title,
                    null,
                    title,
                    summary,
                    Collections.emptyList(),
                    Arrays.asList(
                            new GboardPatchesSettingsContract.Section(sectionGestures, gestureRows),
                            new GboardPatchesSettingsContract.Section(
                                    sectionAutoSpace, autoSpaceRows),
                            new GboardPatchesSettingsContract.Section(
                                    sectionAdvanced, advancedRows)),
                    GboardPatchesSettingsContract.RefreshPolicy.none(),
                    GboardPatchesSettingsContract.PanelStyle.FLAT);
        } catch (Throwable throwable) {
            try {
                Log.w(TAG, "Failed to render MultiSwipe settings screen", throwable);
            } catch (Throwable ignored) {
                // Fallback error screen below handles recovery.
            }
            return buildErrorScreen();
        }
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
            IntSaver saver) {
        int totalSteps = Math.max(1, (max - min) / step);
        int initialProgress = sliderStepForInt(currentValue, min, max, step);
        int defaultProgress = sliderStepForInt(defaultValue, min, max, step);
        GboardPatchesSettingsContract.showSliderDialog(
                host,
                dialogTitle,
                description,
                totalSteps,
                initialProgress,
                defaultProgress,
                formatter.format(min),
                formatter.format(max),
                p -> formatter.format(intValueForSliderStep(p, min, max, step)),
                p -> {
                    int value = intValueForSliderStep(p, min, max, step);
                    if (!saver.save(value)) {
                        throw new IllegalStateException("Failed to persist " + dialogTitle);
                    }
                });
    }

    private void showSpacePlacementSelector(
            GboardPatchesSettingsContract.FeatureHost host,
            SharedPreferences preferences,
            boolean autoSpaceBeforeWord) {
        String[] labels = {spacePlacementBeforeLabel, spacePlacementAfterLabel};
        String[] values = {SPACE_PLACEMENT_BEFORE, SPACE_PLACEMENT_AFTER};
        String current = autoSpaceBeforeWord ? SPACE_PLACEMENT_BEFORE : SPACE_PLACEMENT_AFTER;
        GboardPatchesSettingsContract.showSingleChoiceDialog(
                host,
                spacePlacementTitle,
                labels,
                values,
                current,
                choice -> {
                    GboardMultiSwipeSettings.writeAutoSpaceBeforeWord(
                            preferences, SPACE_PLACEMENT_BEFORE.equals(choice));
                    GboardPatchesSettingsContract.refresh(host);
                });
    }

    private String formatSpacePlacementLabel(boolean autoSpaceBeforeWord) {
        return autoSpaceBeforeWord ? spacePlacementBeforeLabel : spacePlacementAfterLabel;
    }

    private void showBackspaceModeSelector(
            GboardPatchesSettingsContract.FeatureHost host,
            SharedPreferences preferences,
            String currentMode) {
        String[] labels = {backspaceModeWholeWordLabel, backspaceModeLastInputLabel};
        String[] values = {
                GboardMultiSwipeSettings.BACKSPACE_MODE_WHOLE_WORD,
                GboardMultiSwipeSettings.BACKSPACE_MODE_LAST_INPUT
        };
        GboardPatchesSettingsContract.showSingleChoiceDialog(
                host,
                backspaceModeTitle,
                labels,
                values,
                GboardMultiSwipeSettings.sanitizeBackspaceMode(currentMode),
                choice -> {
                    GboardMultiSwipeSettings.writeBackspaceMode(preferences, choice);
                    GboardPatchesSettingsContract.refresh(host);
                });
    }

    private String formatBackspaceModeLabel(String mode) {
        if (GboardMultiSwipeSettings.BACKSPACE_MODE_LAST_INPUT.equals(mode)) {
            return backspaceModeLastInputLabel;
        }
        return backspaceModeWholeWordLabel;
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
                GboardMultiSwipeSettings.MIN_AUTO_SPACE_DELAY_MS,
                GboardMultiSwipeSettings.MAX_AUTO_SPACE_DELAY_MS,
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
        if (delayMs == GboardMultiSwipeSettings.DEFAULT_AUTO_SPACE_DELAY_MS) {
            return String.format(Locale.US, delayValueDefaultTemplate, delayMs);
        }
        return String.format(Locale.US, delayValueTemplate, delayMs);
    }

    private String formatCombineDelayLabel(int delayMs) {
        if (delayMs == GboardMultiSwipeSettings.DEFAULT_SINGLE_TOUCH_COMBINE_DELAY_MS) {
            return String.format(Locale.US, delayValueDefaultTemplate, delayMs);
        }
        return String.format(Locale.US, delayValueTemplate, delayMs);
    }

    private String formatTapToMultiSwipeDelayLabel(int delayMs) {
        if (delayMs == GboardMultiSwipeSettings.DEFAULT_TAP_TO_MULTISWIPE_DELAY_MS) {
            return String.format(Locale.US, delayValueDefaultTemplate, delayMs);
        }
        return String.format(Locale.US, delayValueTemplate, delayMs);
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

    static List<GboardPatchesSettingsContract.Row> buildGestureRows(
            String enabledTitle,
            String enabledSummary,
            boolean enabled,
            GboardPatchesSettingsContract.ToggleAction onToggleEnabled,
            String combineEnabledTitle,
            String combineEnabledSummary,
            boolean combineEnabled,
            GboardPatchesSettingsContract.ToggleAction onToggleCombineEnabled,
            String combineDelayTitle,
            String combineDelaySummary,
            String combineValueLabel,
            Runnable onSelectCombineDelay,
            String tapToMultiSwipeEnabledTitle,
            String tapToMultiSwipeEnabledSummary,
            boolean tapToMultiSwipeEnabled,
            GboardPatchesSettingsContract.ToggleAction onToggleTapToMultiSwipeEnabled,
            String tapToMultiSwipeDelayTitle,
            String tapToMultiSwipeDelaySummary,
            String tapToMultiSwipeValueLabel,
            Runnable onSelectTapToMultiSwipeDelay,
            String backspaceModeTitle,
            String backspaceModeSummary,
            String backspaceModeValueLabel,
            Runnable onSelectBackspaceMode) {
        List<GboardPatchesSettingsContract.Row> rows = new ArrayList<>(6);
        rows.add(new GboardPatchesSettingsContract.ToggleRow(
                enabledTitle,
                enabledSummary,
                true,
                enabled,
                onToggleEnabled));
        rows.add(new GboardPatchesSettingsContract.ToggleRow(
                combineEnabledTitle,
                combineEnabledSummary,
                enabled,
                combineEnabled,
                onToggleCombineEnabled));
        rows.add(new GboardPatchesSettingsContract.SelectorRow(
                combineDelayTitle,
                combineDelaySummary,
                combineValueLabel,
                enabled && combineEnabled,
                onSelectCombineDelay));
        rows.add(new GboardPatchesSettingsContract.ToggleRow(
                tapToMultiSwipeEnabledTitle,
                tapToMultiSwipeEnabledSummary,
                enabled,
                tapToMultiSwipeEnabled,
                onToggleTapToMultiSwipeEnabled));
        rows.add(new GboardPatchesSettingsContract.SelectorRow(
                tapToMultiSwipeDelayTitle,
                tapToMultiSwipeDelaySummary,
                tapToMultiSwipeValueLabel,
                enabled && tapToMultiSwipeEnabled,
                onSelectTapToMultiSwipeDelay));
        rows.add(new GboardPatchesSettingsContract.SelectorRow(
                backspaceModeTitle,
                backspaceModeSummary,
                backspaceModeValueLabel,
                enabled,
                onSelectBackspaceMode));
        return rows;
    }

    static List<GboardPatchesSettingsContract.Row> buildAutoSpaceRows(
            boolean multiSwipeEnabled,
            String autoSpaceTitle,
            String autoSpaceSummary,
            boolean autoSpaceEnabled,
            GboardPatchesSettingsContract.ToggleAction onToggleAutoSpace,
            String spacePlacementTitle,
            String spacePlacementSummary,
            String spacePlacementValueLabel,
            Runnable onSelectSpacePlacement,
            String delayTitle,
            String delaySummary,
            String delayValueLabel,
            Runnable onSelectDelay,
            String spacebarTimerTitle,
            String spacebarTimerSummary,
            boolean spacebarTimerEnabled,
            GboardPatchesSettingsContract.ToggleAction onToggleSpacebarTimer) {
        boolean autoSpaceControlsEnabled = multiSwipeEnabled && autoSpaceEnabled;
        List<GboardPatchesSettingsContract.Row> rows = new ArrayList<>(4);
        rows.add(new GboardPatchesSettingsContract.ToggleRow(
                autoSpaceTitle,
                autoSpaceSummary,
                multiSwipeEnabled,
                autoSpaceEnabled,
                onToggleAutoSpace));
        rows.add(new GboardPatchesSettingsContract.SelectorRow(
                spacePlacementTitle,
                spacePlacementSummary,
                spacePlacementValueLabel,
                autoSpaceControlsEnabled,
                onSelectSpacePlacement));
        rows.add(new GboardPatchesSettingsContract.SelectorRow(
                delayTitle,
                delaySummary,
                delayValueLabel,
                autoSpaceControlsEnabled,
                onSelectDelay));
        rows.add(new GboardPatchesSettingsContract.ToggleRow(
                spacebarTimerTitle,
                spacebarTimerSummary,
                autoSpaceControlsEnabled,
                spacebarTimerEnabled,
                onToggleSpacebarTimer));
        return rows;
    }

    private static String text(Context context, int id) {
        return GboardSettingsText.get(context, id);
    }
}
