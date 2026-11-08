package dev.jason.gboardpatches.extension.nintype;

import android.content.Context;
import android.content.SharedPreferences;
import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.flagsettings.GboardBooleanFlagSettingsFeature;
import dev.jason.gboardpatches.extension.settings.GboardPatchesFeatureAvailability;
import dev.jason.gboardpatches.extension.settings.GboardPatchesIcons;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;
import java.util.Locale;

/**
 * Settings entry for Nintype multi-finger swipe & tap+swipe gesture typing.
 */
public final class GboardNintypeSettingsFeature extends GboardBooleanFlagSettingsFeature {

    public GboardNintypeSettingsFeature(Context context) {
        super(
                GboardPatchesFeatureAvailability.FEATURE_NINTYPE,
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
                        GboardNintypeSettings.ensureDefault(preferences);
                    }

                    @Override
                    public boolean readEnabled(SharedPreferences preferences) {
                        return GboardNintypeSettings.readEnabled(preferences);
                    }

                    @Override
                    public boolean writeEnabled(
                            SharedPreferences preferences,
                            boolean enabled) {
                        return GboardNintypeSettings.writeEnabled(preferences, enabled);
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
        if (isGerman()) return "Nintype Zwei-Finger-Swipe";
        return GboardSettingsText.get(context, R.string.gboard_patches_nintype_title);
    }

    private static String getSummary(Context context) {
        if (isGerman()) {
            return "Zwei-Finger-Swipe und Tap+Swipe-Eingabe nach dem Nintype-Algorithmus mit Gboard-Wörterbuch.";
        }
        return GboardSettingsText.get(context, R.string.gboard_patches_nintype_summary);
    }

    private static String getEnableTitle(Context context) {
        if (isGerman()) return "Nintype Zwei-Finger-Swipe aktivieren";
        return GboardSettingsText.get(context, R.string.gboard_patches_nintype_enabled_title);
    }

    private static String getSection(Context context) {
        if (isGerman()) return "Zwei-Finger-Gesteneingabe";
        return GboardSettingsText.get(context, R.string.gboard_patches_nintype_section_behavior);
    }
}
