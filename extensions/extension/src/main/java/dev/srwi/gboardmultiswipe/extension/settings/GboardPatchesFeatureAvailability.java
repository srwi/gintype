package dev.srwi.gboardmultiswipe.extension.settings;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

public final class GboardPatchesFeatureAvailability {
    public static final String FEATURE_KEYPRESS_HAPTICS =
            "dev.srwi.gboardmultiswipe.feature.keypress_haptics";
    public static final String FEATURE_EDITING_ACCESS_POINTS =
            "dev.srwi.gboardmultiswipe.feature.editing_access_points";
    public static final String FEATURE_CLIPBOARD_ENHANCEMENTS =
            "dev.srwi.gboardmultiswipe.feature.clipboard_enhancements";
    public static final String FEATURE_CLIPBOARD_CONTENT_LIMIT =
            "dev.srwi.gboardmultiswipe.feature.clipboard_content_limit";
    public static final String FEATURE_WEB_CLIPBOARD =
            "dev.srwi.gboardmultiswipe.feature.web_clipboard";
    public static final String FEATURE_LAN_FTP =
            "dev.srwi.gboardmultiswipe.feature.lan_ftp_server";
    public static final String FEATURE_DEVICE_INTELLIGENCE =
            "dev.srwi.gboardmultiswipe.feature.device_intelligence";
    public static final String FEATURE_GRAMMAR_CHECKER =
            "dev.srwi.gboardmultiswipe.feature.grammar_checker";
    public static final String FEATURE_INLINE_SUGGESTIONS =
            "dev.srwi.gboardmultiswipe.feature.inline_suggestions";
    public static final String FEATURE_KEY_SHAPE_SELECTION =
            "dev.srwi.gboardmultiswipe.feature.key_shape_selection";
    public static final String FEATURE_CUSTOM_SYMBOLS =
            "dev.srwi.gboardmultiswipe.feature.custom_symbols";
    public static final String FEATURE_SETTINGS_HOMEPAGE =
            "dev.srwi.gboardmultiswipe.feature.settings_homepage";
    public static final String FEATURE_SYMBOL_FOOTER_ORDER =
            "dev.srwi.gboardmultiswipe.feature.symbol_footer_order";
    public static final String FEATURE_LATIN_GLOBE_KEY_IGNORE_INTERVAL =
            "dev.srwi.gboardmultiswipe.feature.latin_globe_key_ignore_interval";
    public static final String FEATURE_ENGLISH_UPPERCASE_TOGGLE =
            "dev.srwi.gboardmultiswipe.feature.english_uppercase_toggle";
    public static final String FEATURE_ZHUYIN_BOTTOM_ROW_WEIGHT =
            "dev.srwi.gboardmultiswipe.feature.zhuyin_bottom_row_weight";
    public static final String FEATURE_AI_WRITING_TOOLS =
            "dev.srwi.gboardmultiswipe.feature.ai_writing_tools";
    public static final String FEATURE_TOP_ROW_SWIPE =
            "dev.srwi.gboardmultiswipe.feature.top_row_swipe";
    public static final String FEATURE_DEVELOPER_OPTIONS =
            "dev.srwi.gboardmultiswipe.feature.developer_options";
    public static final String FEATURE_BACKUP_RESTORE =
            "dev.srwi.gboardmultiswipe.feature.backup_restore";
    public static final String FEATURE_LONG_PRESS_QUICK_ACTIONS =
            "dev.srwi.gboardmultiswipe.feature.long_press_quick_actions";
    public static final String FEATURE_ADVANCED_VOICE_TYPING =
            "dev.srwi.gboardmultiswipe.feature.advanced_voice_typing";
    public static final String FEATURE_BLUETOOTH_MICROPHONE =
            "dev.srwi.gboardmultiswipe.feature.bluetooth_microphone";
    public static final String FEATURE_OCR_SCAN_TEXT =
            "dev.srwi.gboardmultiswipe.feature.ocr_scan_text";
    public static final String FEATURE_MANUAL_INCOGNITO =
            "dev.srwi.gboardmultiswipe.feature.manual_incognito";
    public static final String FEATURE_EMOJI_SIZE =
            "dev.srwi.gboardmultiswipe.feature.emoji_size";
    public static final String FEATURE_CURSOR_TRACKPAD =
            "dev.srwi.gboardmultiswipe.feature.cursor_trackpad";
    public static final String FEATURE_ACCESS_POINTS_MENU_STYLE =
            "dev.srwi.gboardmultiswipe.feature.access_points_menu_style";
    public static final String FEATURE_SPLIT_KEYBOARD =
            "dev.srwi.gboardmultiswipe.feature.split_keyboard";
    public static final String FEATURE_ACCESSIBILITY_LAYOUT =
            "dev.srwi.gboardmultiswipe.feature.accessibility_layout";
    public static final String FEATURE_ROUNDED_KEYBOARD_PANEL =
            "dev.srwi.gboardmultiswipe.feature.rounded_keyboard_panel";
    public static final String FEATURE_SPACEBAR_LOGO =
            "dev.srwi.gboardmultiswipe.feature.spacebar_logo";
    public static final String FEATURE_CLOSE_PROACTIVE_SUGGESTIONS =
            "dev.srwi.gboardmultiswipe.feature.close_proactive_suggestions";
    public static final String FEATURE_QUICK_INSERT =
            "dev.srwi.gboardmultiswipe.feature.quick_insert";
    public static final String FEATURE_ACCESS_POINT_COUNT =
            "dev.srwi.gboardmultiswipe.feature.access_point_count";
    public static final String FEATURE_FLOATING_WEB_SEARCH =
            "dev.srwi.gboardmultiswipe.feature.floating_web_search";
    public static final String FEATURE_SIMPLE_CALCULATOR =
            "dev.srwi.gboardmultiswipe.feature.simple_calculator";
    public static final String FEATURE_CUSTOM_THEME =
            "dev.srwi.gboardmultiswipe.feature.custom_theme";
    public static final String FEATURE_FROSTED_GLASS =
            "dev.srwi.gboardmultiswipe.feature.frosted_glass";
    public static final String FEATURE_SWIPE_DISMISS =
            "dev.srwi.gboardmultiswipe.feature.swipe_dismiss";
    public static final String FEATURE_MATRIX_SUGGESTIONS =
            "dev.srwi.gboardmultiswipe.feature.matrix_suggestions";
    public static final String FEATURE_NINTYPE =
            "dev.srwi.gboardmultiswipe.feature.nintype";

    public static final String FEATURE_LANGUAGE_MODELS = "dev.srwi.gboardmultiswipe.feature.language_models";

    private static final String TAG = "GboardPatches";

    private GboardPatchesFeatureAvailability() {
    }

    public static boolean isNintypePatchPresent(Context context) {
        return hasFeature(context, FEATURE_NINTYPE);
    }

    public static boolean hasFeature(Context context, String featureKey) {
        if (context == null || featureKey == null || featureKey.isEmpty()) {
            return false;
        }
        return hasAnyFeature(context, featureKey);
    }

    public static boolean hasAnyFeature(Context context, String... featureKeys) {
        if (context == null || featureKeys == null || featureKeys.length == 0) {
            return false;
        }
        for (String featureKey : featureKeys) {
            if (featureKey == null || featureKey.isEmpty()) {
                return false;
            }
        }

        try {
            Context applicationContext = context.getApplicationContext();
            Context lookupContext = applicationContext != null ? applicationContext : context;
            PackageManager packageManager = lookupContext.getPackageManager();
            if (packageManager == null) {
                return false;
            }
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(
                    lookupContext.getPackageName(),
                    PackageManager.GET_META_DATA);
            Bundle metaData = applicationInfo.metaData;
            if (metaData == null) {
                return false;
            }
            for (String featureKey : featureKeys) {
                if (metaData.getBoolean(featureKey, false)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable throwable) {
            try {
                Log.w(TAG, "Failed to resolve feature marker: " + describeFeatures(featureKeys),
                        throwable);
            } catch (Throwable ignored) {
                // Feature detection still fails closed when logging is unavailable.
            }
            return false;
        }
    }

    private static String describeFeatures(String[] featureKeys) {
        if (featureKeys.length == 1) {
            return featureKeys[0];
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < featureKeys.length; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(featureKeys[index]);
        }
        return builder.toString();
    }
}
