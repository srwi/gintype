package dev.srwi.gboardmultiswipe.extension.matrixsuggestions;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

import dev.srwi.gboardmultiswipe.extension.R;
import dev.srwi.gboardmultiswipe.extension.flagsettings.GboardBooleanFlagSettingsFeature;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesFeatureAvailability;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesIcons;
import dev.srwi.gboardmultiswipe.extension.settings.GboardSettingsText;

public final class GboardMatrixSuggestionsSettingsFeature
        extends GboardBooleanFlagSettingsFeature {

    public GboardMatrixSuggestionsSettingsFeature(Context context) {
        super(
                GboardPatchesFeatureAvailability.FEATURE_MATRIX_SUGGESTIONS,
                getTitle(context),
                getSummary(context),
                getEnableTitle(context),
                GboardSettingsText.get(context, R.string.gboard_patches_header_badge),
                GboardSettingsText.get(context, R.string.gboard_patches_flag_patch_error_title),
                GboardSettingsText.get(context, R.string.gboard_patches_flag_patch_error_summary),
                getSection(context),
                null,
                new SettingsStore() {
                    @Override
                    public void ensureDefault(SharedPreferences preferences) {
                        GboardMatrixSuggestionsSettings.ensureDefault(preferences);
                    }

                    @Override
                    public boolean readEnabled(SharedPreferences preferences) {
                        return GboardMatrixSuggestionsSettings.readEnabled(preferences);
                    }

                    @Override
                    public boolean writeEnabled(
                            SharedPreferences preferences,
                            boolean enabled) {
                        return GboardMatrixSuggestionsSettings.writeEnabled(preferences, enabled);
                    }
                });
    }

    @Override
    public int getEntryIcon() {
        return GboardPatchesIcons.BOLT;
    }

    @Override
    public String getEntryTone() {
        return "blue";
    }

    private static boolean isGerman() {
        try {
            return "de".equalsIgnoreCase(Locale.getDefault().getLanguage());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String getTitle(Context context) {
        if (isGerman()) return "Matrix-Wortvorschläge";
        return GboardSettingsText.get(context, R.string.gboard_patches_matrix_suggestions_title);
    }

    private static String getSummary(Context context) {
        if (isGerman()) return "2D-Vorschläge mit vertikalem Wischen und Satzverlauf in der Topbar.";
        return GboardSettingsText.get(context, R.string.gboard_patches_matrix_suggestions_summary);
    }

    private static String getEnableTitle(Context context) {
        if (isGerman()) return "Matrix-Wortvorschläge aktivieren";
        return GboardSettingsText.get(context, R.string.gboard_patches_matrix_suggestions_enable_title);
    }

    private static String getSection(Context context) {
        if (isGerman()) return "Vorschläge & Satzverlauf";
        return GboardSettingsText.get(context, R.string.gboard_patches_matrix_suggestions_section);
    }
}
