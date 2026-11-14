package dev.srwi.gboardmultiswipe.extension.matrixsuggestions;

import android.content.Context;
import android.content.SharedPreferences;

import dev.srwi.gboardmultiswipe.extension.flagsettings.GboardBooleanFlagSettings;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettings;

public final class GboardMatrixSuggestionsSettings {
    public static final String PREF_KEY_ENABLED = "pref_matrix_word_suggestions_enabled";
    public static final boolean DEFAULT_ENABLED = true;

    public static final String PREF_KEY_TOPBAR_SCALE = "pref_matrix_topbar_scale";
    public static final float MIN_TOPBAR_SCALE = 1.0f;
    public static final float MAX_TOPBAR_SCALE = 2.0f;
    public static final float DEFAULT_TOPBAR_SCALE = 1.0f;

    public static final String PREF_KEY_INVERT_SWIPE = "pref_matrix_invert_vertical_swipe";
    public static final boolean DEFAULT_INVERT_SWIPE = false;

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
        ensureDefaults(preferences);
    }

    public static void ensureDefaults(SharedPreferences preferences) {
        GboardBooleanFlagSettings.ensureDefault(
                preferences, PREF_KEY_ENABLED, DEFAULT_ENABLED);
        GboardBooleanFlagSettings.ensureDefault(
                preferences, PREF_KEY_INVERT_SWIPE, DEFAULT_INVERT_SWIPE);
        if (preferences != null && !preferences.contains(PREF_KEY_TOPBAR_SCALE)) {
            writeTopbarScale(preferences, DEFAULT_TOPBAR_SCALE);
        }
    }

    public static boolean readInvertSwipe(Context context) {
        if (context == null) return DEFAULT_INVERT_SWIPE;
        try {
            SharedPreferences preferences = GboardPatchesSettings.preferencesOrNull(context);
            if (preferences == null) {
                preferences = context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            }
            return readInvertSwipe(preferences);
        } catch (Throwable ignored) {
            return DEFAULT_INVERT_SWIPE;
        }
    }

    public static boolean readInvertSwipe(SharedPreferences preferences) {
        return GboardBooleanFlagSettings.readEnabled(
                preferences, PREF_KEY_INVERT_SWIPE, DEFAULT_INVERT_SWIPE);
    }

    public static boolean writeInvertSwipe(SharedPreferences preferences, boolean invert) {
        return GboardBooleanFlagSettings.writeEnabled(preferences, PREF_KEY_INVERT_SWIPE, invert);
    }

    public static boolean writeInvertSwipe(Context context, boolean invert) {
        if (context == null) return false;
        try {
            return writeInvertSwipe(GboardPatchesSettings.preferences(context), invert);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static float readTopbarScale(Context context) {
        return readTopbarScale(GboardPatchesSettings.preferencesOrNull(context));
    }

    public static float readTopbarScale(SharedPreferences preferences) {
        if (preferences == null) {
            return DEFAULT_TOPBAR_SCALE;
        }
        try {
            return sanitizeTopbarScale(
                    preferences.getFloat(PREF_KEY_TOPBAR_SCALE, DEFAULT_TOPBAR_SCALE));
        } catch (ClassCastException ignored) {
            return DEFAULT_TOPBAR_SCALE;
        }
    }

    public static void writeTopbarScale(Context context, float value) {
        if (context == null) {
            return;
        }
        writeTopbarScale(GboardPatchesSettings.preferences(context), value);
    }

    public static void writeTopbarScale(SharedPreferences preferences, float value) {
        if (preferences == null) {
            return;
        }
        preferences.edit()
                .putFloat(PREF_KEY_TOPBAR_SCALE, sanitizeTopbarScale(value))
                .apply();
    }

    public static float sanitizeTopbarScale(float value) {
        if (!Float.isFinite(value)) {
            return DEFAULT_TOPBAR_SCALE;
        }
        return Math.max(MIN_TOPBAR_SCALE, Math.min(MAX_TOPBAR_SCALE, value));
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
