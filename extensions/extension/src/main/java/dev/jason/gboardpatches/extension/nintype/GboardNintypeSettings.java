package dev.jason.gboardpatches.extension.nintype;

import android.content.Context;
import android.content.SharedPreferences;
import dev.jason.gboardpatches.extension.flagsettings.GboardBooleanFlagSettings;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettings;

/**
 * Persistent preferences for Nintype multi-finger swipe & tap+swipe gesture typing.
 */
public final class GboardNintypeSettings {

    public static final String PREF_KEY_ENABLED = "pref_nintype_multi_touch_enabled";
    public static final String PREF_KEY_AUTO_SPACE = "pref_nintype_auto_space_enabled";
    public static final String PREF_KEY_TRACE_LOGGING = "pref_nintype_trace_logging_enabled";
    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_AUTO_SPACE = true;
    public static final boolean DEFAULT_TRACE_LOGGING = true;

    private GboardNintypeSettings() {}

    public static boolean readEnabled(Context context) {
        if (context == null) return DEFAULT_ENABLED;
        try {
            SharedPreferences preferences = GboardPatchesSettings.preferencesOrNull(context);
            if (preferences == null) {
                preferences = context.getSharedPreferences(
                        context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
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

    public static boolean isAutoSpaceEnabled(Context context) {
        if (context == null) return DEFAULT_AUTO_SPACE;
        try {
            SharedPreferences preferences = GboardPatchesSettings.preferencesOrNull(context);
            if (preferences == null) {
                preferences = context.getSharedPreferences(
                        context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            }
            return preferences.getBoolean(PREF_KEY_AUTO_SPACE, DEFAULT_AUTO_SPACE);
        } catch (Throwable ignored) {
            return DEFAULT_AUTO_SPACE;
        }
    }

    public static boolean isTraceLoggingEnabled(Context context) {
        if (context == null) return DEFAULT_TRACE_LOGGING;
        try {
            SharedPreferences preferences = GboardPatchesSettings.preferencesOrNull(context);
            if (preferences == null) {
                preferences = context.getSharedPreferences(
                        context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            }
            return preferences.getBoolean(PREF_KEY_TRACE_LOGGING, DEFAULT_TRACE_LOGGING);
        } catch (Throwable ignored) {
            return DEFAULT_TRACE_LOGGING;
        }
    }
}
