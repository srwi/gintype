package dev.jason.gboardpatches.extension.nintype;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Pure-JVM tests for {@link GboardNintypeSettings} and {@link GboardNintypeSettingsFeature}
 * validation and preference persistence.
 */
public class GboardNintypeSettingsTest {

    @Test
    public void sanitizesAndPersistsAutoSpaceAndDelayPreferences() {
        InMemorySharedPreferences prefs = new InMemorySharedPreferences();

        assertEquals(GboardNintypeSettings.DEFAULT_ENABLED, GboardNintypeSettings.readEnabled(prefs));
        assertEquals(GboardNintypeSettings.DEFAULT_AUTO_SPACE, GboardNintypeSettings.readAutoSpaceEnabled(prefs));
        assertEquals(GboardNintypeSettings.DEFAULT_AUTO_SPACE_DELAY_MS,
                GboardNintypeSettings.readAutoSpaceDelayMs(prefs));

        GboardNintypeSettings.ensureDefaults(prefs);
        assertTrue(prefs.contains(GboardNintypeSettings.PREF_KEY_ENABLED));
        assertTrue(prefs.contains(GboardNintypeSettings.PREF_KEY_AUTO_SPACE));
        assertTrue(prefs.contains(GboardNintypeSettings.PREF_KEY_AUTO_SPACE_DELAY_MS));

        assertTrue(GboardNintypeSettings.writeAutoSpaceEnabled(prefs, false));
        assertFalse(GboardNintypeSettings.readAutoSpaceEnabled(prefs));

        assertTrue(GboardNintypeSettings.writeAutoSpaceDelayMs(prefs, 650));
        assertEquals(650, GboardNintypeSettings.readAutoSpaceDelayMs(prefs));

        assertTrue(GboardNintypeSettings.writeAutoSpaceDelayMs(prefs, -50));
        assertEquals(0, GboardNintypeSettings.readAutoSpaceDelayMs(prefs));

        assertTrue(GboardNintypeSettings.writeAutoSpaceDelayMs(prefs, 9999));
        assertEquals(GboardNintypeSettings.MAX_AUTO_SPACE_DELAY_MS,
                GboardNintypeSettings.readAutoSpaceDelayMs(prefs));

        assertEquals(GboardNintypeSettings.DEFAULT_SINGLE_TOUCH_COMBINE_DELAY_MS,
                GboardNintypeSettings.readSingleTouchCombineDelayMs(prefs));
        assertTrue(prefs.contains(GboardNintypeSettings.PREF_KEY_SINGLE_TOUCH_COMBINE_DELAY_MS));
        assertTrue(GboardNintypeSettings.writeSingleTouchCombineDelayMs(prefs, 0));
        assertEquals(0, GboardNintypeSettings.readSingleTouchCombineDelayMs(prefs));
        assertTrue(GboardNintypeSettings.writeSingleTouchCombineDelayMs(prefs, 900));
        assertEquals(GboardNintypeSettings.MAX_SINGLE_TOUCH_COMBINE_DELAY_MS,
                GboardNintypeSettings.readSingleTouchCombineDelayMs(prefs));
    }

    @Test
    public void parsesValidAndRejectsOutOfRangeDelayInputs() {
        assertEquals(0, GboardNintypeSettingsFeature.parseDelayMs("0", "err"));
        assertEquals(300, GboardNintypeSettingsFeature.parseDelayMs(" 300 ", "err"));
        assertEquals(2000, GboardNintypeSettingsFeature.parseDelayMs("2000", "err"));

        String[] invalid = {null, "", "   ", "-1", "2001", "abc", "12.5"};
        for (String s : invalid) {
            try {
                GboardNintypeSettingsFeature.parseDelayMs(s, "Invalid delay");
                fail("Expected IllegalArgumentException for input: " + s);
            } catch (IllegalArgumentException expected) {
                assertEquals("Invalid delay", expected.getMessage());
            }
        }
    }

    @After
    public void resetKineticaConstants() {
        KineticaConstants.applyRuntimeTuning(128, 0.25f, 3.75f, 0.50f);
    }

    @Test
    public void persistsAndSyncsDecoderTuningConstants() {
        InMemorySharedPreferences prefs = new InMemorySharedPreferences();

        assertEquals(GboardNintypeSettings.DEFAULT_BEAM_WIDTH,
                GboardNintypeSettings.readBeamWidth(prefs));
        assertEquals(GboardNintypeSettings.DEFAULT_FREQ_WEIGHT_FLOOR,
                GboardNintypeSettings.readFreqWeightFloor(prefs), 0.0001f);
        assertEquals(GboardNintypeSettings.DEFAULT_GEO_EXPONENT,
                GboardNintypeSettings.readGeoExponent(prefs), 0.0001f);
        assertEquals(GboardNintypeSettings.DEFAULT_BEAM_FREQ_WEIGHT,
                GboardNintypeSettings.readBeamFreqWeight(prefs), 0.0001f);

        GboardNintypeSettings.ensureDefaults(prefs);
        assertTrue(prefs.contains(GboardNintypeSettings.PREF_KEY_BEAM_WIDTH));
        assertTrue(prefs.contains(GboardNintypeSettings.PREF_KEY_FREQ_WEIGHT_FLOOR));
        assertTrue(prefs.contains(GboardNintypeSettings.PREF_KEY_GEO_EXPONENT));
        assertTrue(prefs.contains(GboardNintypeSettings.PREF_KEY_BEAM_FREQ_WEIGHT));

        assertTrue(GboardNintypeSettings.writeBeamWidth(prefs, 384));
        assertTrue(GboardNintypeSettings.writeFreqWeightFloor(prefs, 0.60f));
        assertTrue(GboardNintypeSettings.writeGeoExponent(prefs, 5.25f));
        assertTrue(GboardNintypeSettings.writeBeamFreqWeight(prefs, 0.20f));
        assertFalse(GboardNintypeSettings.writeGeoExponent(prefs, Float.NaN));

        assertEquals(384, GboardNintypeSettings.readBeamWidth(prefs));
        assertEquals(0.60f, GboardNintypeSettings.readFreqWeightFloor(prefs), 0.0001f);
        assertEquals(5.25f, GboardNintypeSettings.readGeoExponent(prefs), 0.0001f);
        assertEquals(0.20f, GboardNintypeSettings.readBeamFreqWeight(prefs), 0.0001f);

        GboardNintypeSettings.syncDecoderConstants(prefs);
        assertEquals(384, KineticaConstants.BEAM_WIDTH);
        assertEquals(0.60f, KineticaConstants.FREQ_WEIGHT_FLOOR, 0.0001f);
        assertEquals(5.25f, KineticaConstants.GEO_EXPONENT, 0.0001f);
        assertEquals(0.20f, KineticaConstants.BEAM_FREQ_WEIGHT, 0.0001f);
        assertEquals(1.0f, KineticaConstants.boostWeight(0.5f), 0.0001f);
        assertEquals(0.0f, KineticaConstants.boostWeight(1.5f), 0.0001f);
    }

    @Test
    public void parsesTuningIntAndFloatInputsAndRejectsInvalidValues() {
        assertEquals(256, GboardNintypeSettingsFeature.parseIntInRange("256", 32, 1024, "err"));
        assertEquals(3.75f, GboardNintypeSettingsFeature.parseFloatInRange("3,75", 1.0f, 8.0f, "err"), 0.0001f);
        assertEquals(0.50f, GboardNintypeSettingsFeature.parseFloatInRange(" 0.5 ", 0.0f, 2.0f, "err"), 0.0001f);

        String[] badFloats = {null, "", "  ", "NaN", "Infinity", "-0.1", "2.1", "abc"};
        for (String s : badFloats) {
            try {
                GboardNintypeSettingsFeature.parseFloatInRange(s, 0.0f, 2.0f, "Invalid");
                fail("Expected IllegalArgumentException for: " + s);
            } catch (IllegalArgumentException expected) {
                assertEquals("Invalid", expected.getMessage());
            }
        }
    }

    @Test
    public void detectsWhenLeadingSpaceIsNeededBeforeNintypeWord() {
        assertTrue(GboardNintypeRuntime.shouldPrependLeadingSpace("Hallo wie geht es", true));
        assertTrue(GboardNintypeRuntime.shouldPrependLeadingSpace("Mi r", true));
        assertTrue(GboardNintypeRuntime.shouldPrependLeadingSpace("Nummer 42", true));

        assertFalse(GboardNintypeRuntime.shouldPrependLeadingSpace("Hallo wie geht es ", true));
        assertFalse(GboardNintypeRuntime.shouldPrependLeadingSpace("Hallo\n", true));
        assertFalse(GboardNintypeRuntime.shouldPrependLeadingSpace("(", true));
        assertFalse(GboardNintypeRuntime.shouldPrependLeadingSpace("", true));
        assertFalse(GboardNintypeRuntime.shouldPrependLeadingSpace(null, true));
        assertFalse(GboardNintypeRuntime.shouldPrependLeadingSpace("Hallo wie geht es", false));
    }

    private static final class InMemorySharedPreferences implements SharedPreferences {
        private final Map<String, Object> data = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(data); }
        @Override public String getString(String key, String defValue) {
            Object v = data.get(key);
            return v == null ? defValue : (String) v;
        }
        @Override @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = data.get(key);
            return v == null ? defValues : (Set<String>) v;
        }
        @Override public int getInt(String key, int defValue) {
            Object v = data.get(key);
            return v == null ? defValue : (Integer) v;
        }
        @Override public long getLong(String key, long defValue) {
            Object v = data.get(key);
            return v == null ? defValue : (Long) v;
        }
        @Override public float getFloat(String key, float defValue) {
            Object v = data.get(key);
            return v == null ? defValue : (Float) v;
        }
        @Override public boolean getBoolean(String key, boolean defValue) {
            Object v = data.get(key);
            return v == null ? defValue : (Boolean) v;
        }
        @Override public boolean contains(String key) { return data.containsKey(key); }
        @Override public Editor edit() { return new InMemoryEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        private final class InMemoryEditor implements Editor {
            private final Map<String, Object> staged = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override public Editor putString(String key, String value) { staged.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> values) { staged.put(key, values); return this; }
            @Override public Editor putInt(String key, int value) { staged.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { staged.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { staged.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { staged.put(key, value); return this; }
            @Override public Editor remove(String key) { removals.add(key); return this; }
            @Override public Editor clear() { clear = true; return this; }

            @Override
            public boolean commit() {
                if (clear) data.clear();
                for (String k : removals) data.remove(k);
                data.putAll(staged);
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
