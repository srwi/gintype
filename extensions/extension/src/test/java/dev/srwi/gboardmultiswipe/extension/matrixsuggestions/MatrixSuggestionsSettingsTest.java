package dev.srwi.gboardmultiswipe.extension.matrixsuggestions;

import android.content.SharedPreferences;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class MatrixSuggestionsSettingsTest {

    @Test
    public void defaultStateIsEnabled() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        Assert.assertTrue(GboardMatrixSuggestionsSettings.readEnabled(prefs));
    }

    @Test
    public void readsBooleanAndStringValues() {
        TestSharedPreferences prefs = new TestSharedPreferences();

        prefs.values.put(GboardMatrixSuggestionsSettings.PREF_KEY_ENABLED, Boolean.FALSE);
        Assert.assertFalse(GboardMatrixSuggestionsSettings.readEnabled(prefs));

        prefs.values.put(GboardMatrixSuggestionsSettings.PREF_KEY_ENABLED, Boolean.TRUE);
        Assert.assertTrue(GboardMatrixSuggestionsSettings.readEnabled(prefs));

        prefs.values.put(GboardMatrixSuggestionsSettings.PREF_KEY_ENABLED, "false");
        Assert.assertFalse(GboardMatrixSuggestionsSettings.readEnabled(prefs));

        prefs.values.put(GboardMatrixSuggestionsSettings.PREF_KEY_ENABLED, "true");
        Assert.assertTrue(GboardMatrixSuggestionsSettings.readEnabled(prefs));
    }

    @Test
    public void writeAndEnsureDefault() {
        TestSharedPreferences prefs = new TestSharedPreferences();

        GboardMatrixSuggestionsSettings.ensureDefault(prefs);
        Assert.assertEquals(Boolean.TRUE, prefs.values.get(GboardMatrixSuggestionsSettings.PREF_KEY_ENABLED));

        GboardMatrixSuggestionsSettings.writeEnabled(prefs, false);
        Assert.assertFalse(GboardMatrixSuggestionsSettings.readEnabled(prefs));

        GboardMatrixSuggestionsSettings.writeEnabled(prefs, true);
        Assert.assertTrue(GboardMatrixSuggestionsSettings.readEnabled(prefs));
    }

    @Test
    public void chooseNormalAccentPrefersStockTrailThenPrimaryThenGoogleBlue() {
        Assert.assertEquals(0xFFECC09F,
                MatrixGboardReflector.chooseNormalAccentColor(0xECC09F, 0xA8C7FA, true));
        Assert.assertEquals(0xFFA8C7FA,
                MatrixGboardReflector.chooseNormalAccentColor(-1, 0xA8C7FA, true));
        Assert.assertEquals(0xFF8AB4F8,
                MatrixGboardReflector.chooseNormalAccentColor(-1, -1, true));
        Assert.assertEquals(0xFF1A73E8,
                MatrixGboardReflector.chooseNormalAccentColor(-1, -1, false));
    }

    @Test
    public void recordStockTrailAccentUpdatesResolvedColorWhenDarkPolarityMatches() {
        MatrixGboardReflector.recordStockTrailAccent(null, 0xECC09F);
        Assert.assertEquals(0xFFECC09F, MatrixGboardReflector.resolveNormalAccentColor(null, true));
        Assert.assertTrue(MatrixGboardReflector.perceivedLuminance(0xFFECC09F) >= 140f);
        Assert.assertTrue(MatrixGboardReflector.perceivedLuminance(0xFF845B38) < 140f);
    }

    @Test
    public void topbarScaleDefaultsAndPersistence() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        Assert.assertEquals(
                GboardMatrixSuggestionsSettings.DEFAULT_TOPBAR_SCALE,
                GboardMatrixSuggestionsSettings.readTopbarScale(prefs),
                0.0001f);

        GboardMatrixSuggestionsSettings.ensureDefaults(prefs);
        Assert.assertEquals(
                GboardMatrixSuggestionsSettings.DEFAULT_TOPBAR_SCALE,
                GboardMatrixSuggestionsSettings.readTopbarScale(prefs),
                0.0001f);

        GboardMatrixSuggestionsSettings.writeTopbarScale(prefs, 1.50f);
        Assert.assertEquals(1.50f, GboardMatrixSuggestionsSettings.readTopbarScale(prefs), 0.0001f);

        // Re-running ensureDefaults must preserve explicit user values.
        GboardMatrixSuggestionsSettings.ensureDefaults(prefs);
        Assert.assertEquals(1.50f, GboardMatrixSuggestionsSettings.readTopbarScale(prefs), 0.0001f);
    }

    @Test
    public void topbarScaleSanitizesAndClampsOutOfRangeValues() {
        TestSharedPreferences prefs = new TestSharedPreferences();

        Assert.assertEquals(
                GboardMatrixSuggestionsSettings.DEFAULT_TOPBAR_SCALE,
                GboardMatrixSuggestionsSettings.sanitizeTopbarScale(Float.NaN),
                0.0001f);
        Assert.assertEquals(
                GboardMatrixSuggestionsSettings.DEFAULT_TOPBAR_SCALE,
                GboardMatrixSuggestionsSettings.sanitizeTopbarScale(Float.POSITIVE_INFINITY),
                0.0001f);
        Assert.assertEquals(
                GboardMatrixSuggestionsSettings.MIN_TOPBAR_SCALE,
                GboardMatrixSuggestionsSettings.sanitizeTopbarScale(0.5f),
                0.0001f);
        Assert.assertEquals(
                GboardMatrixSuggestionsSettings.MAX_TOPBAR_SCALE,
                GboardMatrixSuggestionsSettings.sanitizeTopbarScale(3.5f),
                0.0001f);

        prefs.values.put(GboardMatrixSuggestionsSettings.PREF_KEY_TOPBAR_SCALE, 2.75f);
        Assert.assertEquals(
                GboardMatrixSuggestionsSettings.MAX_TOPBAR_SCALE,
                GboardMatrixSuggestionsSettings.readTopbarScale(prefs),
                0.0001f);

        prefs.values.put(GboardMatrixSuggestionsSettings.PREF_KEY_TOPBAR_SCALE, "invalid");
        Assert.assertEquals(
                GboardMatrixSuggestionsSettings.DEFAULT_TOPBAR_SCALE,
                GboardMatrixSuggestionsSettings.readTopbarScale(prefs),
                0.0001f);
    }

    private static final class TestSharedPreferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return values;
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = values.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = values.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = values.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new TestEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        final class TestEditor implements Editor {
            final Map<String, Object> pending = new HashMap<>();

            @Override
            public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override
            public Editor putStringSet(String key, Set<String> values) { return this; }
            @Override
            public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override
            public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override
            public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override
            public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override
            public Editor remove(String key) { pending.remove(key); values.remove(key); return this; }
            @Override
            public Editor clear() { pending.clear(); values.clear(); return this; }
            @Override
            public boolean commit() { values.putAll(pending); return true; }
            @Override
            public void apply() { commit(); }
        }
    }
}
