package dev.srwi.gboardmultiswipe.extension.matrixsuggestions;

import android.content.Context;
import android.content.SharedPreferences;

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

    private static String getTitle(Context context) {
        return GboardSettingsText.get(context, R.string.gboard_patches_matrix_suggestions_title);
    }

    private static String getSummary(Context context) {
        return GboardSettingsText.get(context, R.string.gboard_patches_matrix_suggestions_summary);
    }

    private static String getEnableTitle(Context context) {
        return GboardSettingsText.get(context, R.string.gboard_patches_matrix_suggestions_enable_title);
    }

    private static String getSection(Context context) {
        return GboardSettingsText.get(context, R.string.gboard_patches_matrix_suggestions_section);
    }
}
