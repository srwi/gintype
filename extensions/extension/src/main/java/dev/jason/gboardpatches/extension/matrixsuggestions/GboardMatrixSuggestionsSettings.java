package dev.jason.gboardpatches.extension.matrixsuggestions;

import android.content.Context;
import android.content.SharedPreferences;

import dev.jason.gboardpatches.extension.flagsettings.GboardBooleanFlagSettings;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettings;

public final class GboardMatrixSuggestionsSettings {
    public static final String PREF_KEY_ENABLED = "pref_matrix_word_suggestions_enabled";
    public static final boolean DEFAULT_ENABLED = true;

    private GboardMatrixSuggestionsSettings() {
    }

    public static boolean readEnabled(Context context) {
        if (context == null) return DEFAULT_ENABLED;
        try {
            SharedPreferences preferences = GboardPatchesSettings.preferencesOrNull(context);
            if (preferences == null) {
                preferences = context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            }
            return readEnabled(preferences);
        } catch (Throwable ignored) {
            return DEFAULT_ENABLED;
        }
    }

    public static boolean readEnabled(SharedPreferences preferences) {
        return GboardBooleanFlagSettings.readEnabled(
                preferences, PREF_KEY_ENABLED, DEFAULT_ENABLED);
    }

    public static void ensureDefault(SharedPreferences preferences) {
        GboardBooleanFlagSettings.ensureDefault(
                preferences, PREF_KEY_ENABLED, DEFAULT_ENABLED);
    }

    public static boolean writeEnabled(SharedPreferences preferences, boolean enabled) {
        return GboardBooleanFlagSettings.writeEnabled(preferences, PREF_KEY_ENABLED, enabled);
    }

    public static boolean writeEnabled(Context context, boolean enabled) {
        if (context == null) return false;
        try {
            SharedPreferences preferences = GboardPatchesSettings.preferences(context);
            return writeEnabled(preferences, enabled);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
