package dev.srwi.gboardmultiswipe.extension.customholdpopups;

import android.app.Activity;
import android.app.AlertDialog;
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
 * Settings screen for Custom Hold Popups: master switch, hold-to-edit duration slider, list of all
 * modified/added hold popup bindings (each tappable to edit, delete, or reset), and reset-all.
 */
public final class GboardCustomHoldPopupsSettingsFeature
        implements GboardPatchesSettingsContract.Feature {

    private static final String TAG = "GboardPatches";

    private final String title;
    private final String summary;
    private final String sectionGeneral;
    private final String sectionCustomizations;
    private final String enabledTitle;
    private final String enabledSummary;
    private final String holdDurationTitle;
    private final String holdDurationSummary;
    private final String emptyTitle;
    private final String emptySummary;
    private final String entryModifiedTitleTemplate;
    private final String entryModifiedSummaryTemplate;
    private final String entryAddedTitleTemplate;
    private final String entryAddedSummary;
    private final String defaultValueTemplate;
    private final String resetTitle;
    private final String resetSummary;
    private final String resetConfirmTitle;
    private final String resetConfirmMessage;
    private final String resetDone;
    private final String errorTitle;
    private final String errorSummary;

    public GboardCustomHoldPopupsSettingsFeature(Context context) {
        title = text(context, R.string.gboard_patches_custom_hold_popups_title);
        summary = text(context, R.string.gboard_patches_custom_hold_popups_summary);
        sectionGeneral = text(context, R.string.gboard_patches_custom_hold_popups_section_general);
        sectionCustomizations =
                text(context, R.string.gboard_patches_custom_hold_popups_section_customizations);
        enabledTitle = text(context, R.string.gboard_patches_custom_hold_popups_enabled_title);
        enabledSummary = text(context, R.string.gboard_patches_custom_hold_popups_enabled_summary);
        holdDurationTitle =
                text(context, R.string.gboard_patches_custom_hold_popups_hold_duration_title);
        holdDurationSummary =
                text(context, R.string.gboard_patches_custom_hold_popups_hold_duration_summary);
        emptyTitle = text(context, R.string.gboard_patches_custom_hold_popups_empty_title);
        emptySummary = text(context, R.string.gboard_patches_custom_hold_popups_empty_summary);
        entryModifiedTitleTemplate =
                text(context, R.string.gboard_patches_custom_hold_popups_entry_modified_title);
        entryModifiedSummaryTemplate =
                text(context, R.string.gboard_patches_custom_hold_popups_entry_modified_summary);
        entryAddedTitleTemplate =
                text(context, R.string.gboard_patches_custom_hold_popups_entry_added_title);
        entryAddedSummary =
                text(context, R.string.gboard_patches_custom_hold_popups_entry_added_summary);
        defaultValueTemplate = text(context, R.string.gboard_patches_multiswipe_value_default);
        resetTitle = text(context, R.string.gboard_patches_custom_hold_popups_reset_title);
        resetSummary = text(context, R.string.gboard_patches_custom_hold_popups_reset_summary);
        resetConfirmTitle =
                text(context, R.string.gboard_patches_custom_hold_popups_reset_confirm_title);
        resetConfirmMessage =
                text(context, R.string.gboard_patches_custom_hold_popups_reset_confirm_message);
        resetDone = text(context, R.string.gboard_patches_custom_hold_popups_reset_done);
        errorTitle = text(context, R.string.gboard_patches_custom_hold_popups_error_title);
        errorSummary = text(context, R.string.gboard_patches_custom_hold_popups_error_summary);
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
        return GboardPatchesIcons.EDIT;
    }

    @Override
    public String getEntryTone() {
        return "teal";
    }

    @Override
    public boolean updatesDuringKeyboardPreview() {
        return true;
    }

    @Override
    public boolean isAvailable(Context context) {
        return GboardPatchesFeatureAvailability.hasFeature(
                context, GboardPatchesFeatureAvailability.FEATURE_CUSTOM_HOLD_POPUPS);
    }

    @Override
    public GboardPatchesSettingsContract.Screen buildScreen(
            GboardPatchesSettingsContract.FeatureHost host) {
        try {
            if (host == null || host.getContext() == null) {
                return buildErrorScreen();
            }
            SharedPreferences preferences = GboardPatchesSettings.preferences(host.getContext());
            GboardCustomHoldPopupsSettings.ensureDefaults(preferences);
            boolean masterEnabled = GboardCustomHoldPopupsSettings.readEnabled(preferences);
            int holdDurationMs = GboardCustomHoldPopupsSettings.readHoldDurationMs(preferences);
            List<GboardCustomHoldPopupsSettings.CustomEntry> entries =
                    GboardCustomHoldPopupsSettings.listAllCustomEntries(preferences);

            // 1. General section (Master switch + hold duration slider)
            List<GboardPatchesSettingsContract.Row> generalRows =
                    new ArrayList<GboardPatchesSettingsContract.Row>(2);
            generalRows.add(new GboardPatchesSettingsContract.SwitchRow(
                    enabledTitle,
                    enabledSummary,
                    true,
                    masterEnabled,
                    checked -> saveBoolAndRefresh(host,
                            GboardCustomHoldPopupsSettings.writeEnabled(preferences, checked))));
            generalRows.add(new GboardPatchesSettingsContract.SelectorRow(
                    holdDurationTitle,
                    holdDurationSummary,
                    formatDurationMs(holdDurationMs),
                    masterEnabled,
                    () -> showHoldDurationSliderDialog(host, preferences, holdDurationMs)));

            // 2. Custom Hold Bindings list section
            List<GboardPatchesSettingsContract.Row> customRows =
                    new ArrayList<GboardPatchesSettingsContract.Row>();
            if (entries.isEmpty()) {
                customRows.add(new GboardPatchesSettingsContract.DetailRow(
                        emptyTitle,
                        emptySummary,
                        masterEnabled));
            } else {
                for (GboardCustomHoldPopupsSettings.CustomEntry entry : entries) {
                    String displayKey = formatDisplayKey(entry.baseKey);
                    String rowTitle = entry.userAdded
                            ? String.format(Locale.US, entryAddedTitleTemplate,
                                    displayKey, entry.replacementSymbol)
                            : String.format(Locale.US, entryModifiedTitleTemplate,
                                    displayKey, entry.replacementSymbol);
                    String rowSummary = entry.userAdded
                            ? entryAddedSummary
                            : String.format(Locale.US, entryModifiedSummaryTemplate,
                                    entry.originalSymbol);
                    customRows.add(new GboardPatchesSettingsContract.CommandRow(
                            rowTitle,
                            rowSummary,
                            masterEnabled,
                            () -> showEntryEditorDialog(host, entry)));
                }
            }

            // 3. Reset all custom bindings
            List<GboardPatchesSettingsContract.Row> resetRows = Collections.singletonList(
                    new GboardPatchesSettingsContract.DangerRow(
                            resetTitle,
                            resetSummary,
                            !entries.isEmpty(),
                            () -> {
                                GboardCustomHoldPopupsSettings.resetAllCustomBindings(preferences);
                                GboardPatchesSettingsContract.refresh(host);
                                GboardPatchesSettingsContract.showMessage(host, resetDone);
                            },
                            resetConfirmTitle,
                            resetConfirmMessage));

            return new GboardPatchesSettingsContract.Screen(
                    title,
                    null,
                    title,
                    summary,
                    Collections.emptyList(),
                    Arrays.asList(
                            new GboardPatchesSettingsContract.Section(sectionGeneral, generalRows),
                            new GboardPatchesSettingsContract.Section(
                                    sectionCustomizations, customRows),
                            new GboardPatchesSettingsContract.Section(
                                    null,
                                    null,
                                    GboardPatchesSettingsContract.SectionStyle.ADVANCED,
                                    resetRows)),
                    GboardPatchesSettingsContract.RefreshPolicy.none(),
                    GboardPatchesSettingsContract.PanelStyle.FLAT);
        } catch (Throwable throwable) {
            try {
                Log.w(TAG, "Failed to render Custom Hold Popups settings screen", throwable);
            } catch (Throwable ignored) {
            }
            return buildErrorScreen();
        }
    }

    private void showEntryEditorDialog(
            GboardPatchesSettingsContract.FeatureHost host,
            GboardCustomHoldPopupsSettings.CustomEntry entry) {
        if (host == null || entry == null) return;
        Context context = host.getContext();
        if (!(context instanceof Activity)) return;
        Activity activity = (Activity) context;
        GboardPatchesSettingsContract.showManagedDialog(host, onDismiss -> {
            AlertDialog dialog = GboardCustomHoldPopupsEditorDialog.show(
                    activity,
                    GboardCustomHoldPopupsEditorDialog.MODE_EDIT,
                    entry.baseKey,
                    entry.replacementSymbol,
                    entry.originalSymbol,
                    entry.userAdded,
                    entry.addedIndex,
                    () -> GboardPatchesSettingsContract.refresh(host),
                    onDismiss);
            return dialog != null;
        });
    }

    private void showHoldDurationSliderDialog(
            GboardPatchesSettingsContract.FeatureHost host,
            SharedPreferences preferences,
            int currentValueMs) {
        int min = GboardCustomHoldPopupsSettings.MIN_HOLD_DURATION_MS;
        int max = GboardCustomHoldPopupsSettings.MAX_HOLD_DURATION_MS;
        int step = GboardCustomHoldPopupsSettings.HOLD_DURATION_STEP_MS;
        int def = GboardCustomHoldPopupsSettings.DEFAULT_HOLD_DURATION_MS;
        int totalSteps = Math.max(1, (max - min) / step);
        int initialProgress = Math.max(0, Math.min(totalSteps, Math.round((currentValueMs - min) / (float) step)));
        int defaultProgress = Math.max(0, Math.min(totalSteps, Math.round((def - min) / (float) step)));
        GboardPatchesSettingsContract.showSliderDialog(
                host,
                holdDurationTitle,
                holdDurationSummary,
                totalSteps,
                initialProgress,
                defaultProgress,
                min + " ms",
                max + " ms",
                progress -> formatDurationMs(min + progress * step),
                progress -> {
                    int val = min + progress * step;
                    if (!GboardCustomHoldPopupsSettings.writeHoldDurationMs(preferences, val)) {
                        throw new IllegalStateException("Unable to persist Custom Hold Popups setting");
                    }
                    GboardPatchesSettingsContract.refresh(host);
                });
    }

    private String formatDurationMs(int valueMs) {
        String formatted = valueMs + " ms";
        if (valueMs == GboardCustomHoldPopupsSettings.DEFAULT_HOLD_DURATION_MS) {
            return String.format(Locale.US, defaultValueTemplate, formatted);
        }
        return formatted;
    }

    private static String formatDisplayKey(String normalizedBaseKey) {
        if (normalizedBaseKey == null || normalizedBaseKey.isEmpty()) {
            return "?";
        }
        if (normalizedBaseKey.length() == 1 && Character.isLetter(normalizedBaseKey.charAt(0))) {
            return normalizedBaseKey.toUpperCase(Locale.ROOT);
        }
        return normalizedBaseKey;
    }

    private void saveBoolAndRefresh(
            GboardPatchesSettingsContract.FeatureHost host,
            boolean saved) {
        if (!saved) {
            throw new IllegalStateException("Unable to persist Custom Hold Popups setting");
        }
        GboardPatchesSettingsContract.refresh(host);
    }

    private GboardPatchesSettingsContract.Screen buildErrorScreen() {
        List<GboardPatchesSettingsContract.StatusBlock> statusBlocks = Collections.singletonList(
                new GboardPatchesSettingsContract.StatusBlock(
                        errorTitle,
                        errorSummary,
                        GboardPatchesSettingsContract.StatusTone.WARNING));
        return new GboardPatchesSettingsContract.Screen(
                title,
                null,
                errorTitle,
                errorSummary,
                statusBlocks,
                Collections.emptyList());
    }

    private static String text(Context context, int resourceId) {
        return GboardSettingsText.get(context, resourceId);
    }
}
