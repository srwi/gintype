package dev.srwi.gboardmultiswipe.extension.multiswipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.SharedPreferences;
import dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettingsContract;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Pure-JVM tests for {@link GboardMultiSwipeSettings} and {@link GboardMultiSwipeSettingsFeature}
 * validation and preference persistence.
 */
public class GboardMultiSwipeSettingsTest {

    @Test
    public void sanitizesAndPersistsAutoSpaceAndDelayPreferences() {
        InMemorySharedPreferences prefs = new InMemorySharedPreferences();

        assertEquals(GboardMultiSwipeSettings.DEFAULT_ENABLED, GboardMultiSwipeSettings.readEnabled(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_AUTO_SPACE, GboardMultiSwipeSettings.readAutoSpaceEnabled(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_AUTO_SPACE_DELAY_MS,
                GboardMultiSwipeSettings.readAutoSpaceDelayMs(prefs));

        GboardMultiSwipeSettings.ensureDefaults(prefs);
        assertTrue(prefs.contains(GboardMultiSwipeSettings.PREF_KEY_ENABLED));
        assertTrue(prefs.contains(GboardMultiSwipeSettings.PREF_KEY_AUTO_SPACE));
        assertTrue(prefs.contains(GboardMultiSwipeSettings.PREF_KEY_AUTO_SPACE_DELAY_MS));

        assertTrue(GboardMultiSwipeSettings.writeAutoSpaceEnabled(prefs, false));
        assertFalse(GboardMultiSwipeSettings.readAutoSpaceEnabled(prefs));

        assertTrue(GboardMultiSwipeSettings.writeAutoSpaceDelayMs(prefs, 650));
        assertEquals(650, GboardMultiSwipeSettings.readAutoSpaceDelayMs(prefs));

        assertTrue(GboardMultiSwipeSettings.writeAutoSpaceDelayMs(prefs, -50));
        assertEquals(0, GboardMultiSwipeSettings.readAutoSpaceDelayMs(prefs));

        assertTrue(GboardMultiSwipeSettings.writeAutoSpaceDelayMs(prefs, 9999));
        assertEquals(GboardMultiSwipeSettings.MAX_AUTO_SPACE_DELAY_MS,
                GboardMultiSwipeSettings.readAutoSpaceDelayMs(prefs));

        assertEquals(GboardMultiSwipeSettings.DEFAULT_SINGLE_TOUCH_COMBINE_DELAY_MS,
                GboardMultiSwipeSettings.readSingleTouchCombineDelayMs(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_TAP_TO_MULTISWIPE_DELAY_MS,
                GboardMultiSwipeSettings.readTapToMultiSwipeDelayMs(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_SPACEBAR_TIMER_INDICATOR,
                GboardMultiSwipeSettings.readSpacebarTimerIndicatorEnabled(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_WORD_READY_VIBRATION,
                GboardMultiSwipeSettings.readWordReadyVibrationEnabled(prefs));
        assertTrue(prefs.contains(GboardMultiSwipeSettings.PREF_KEY_SINGLE_TOUCH_COMBINE_DELAY_MS));
        assertTrue(GboardMultiSwipeSettings.writeSingleTouchCombineDelayMs(prefs, 0));
        assertEquals(GboardMultiSwipeSettings.MIN_SINGLE_TOUCH_COMBINE_DELAY_MS,
                GboardMultiSwipeSettings.readSingleTouchCombineDelayMs(prefs));
        assertTrue(GboardMultiSwipeSettings.writeSingleTouchCombineDelayMs(prefs, 900));
        assertEquals(GboardMultiSwipeSettings.MAX_SINGLE_TOUCH_COMBINE_DELAY_MS,
                GboardMultiSwipeSettings.readSingleTouchCombineDelayMs(prefs));

        assertEquals(GboardMultiSwipeSettings.DEFAULT_TAP_TO_MULTISWIPE_DELAY_MS,
                GboardMultiSwipeSettings.readTapToMultiSwipeDelayMs(prefs));
        assertTrue(prefs.contains(GboardMultiSwipeSettings.PREF_KEY_TAP_TO_MULTISWIPE_DELAY_MS));
        assertTrue(GboardMultiSwipeSettings.writeTapToMultiSwipeDelayMs(prefs, 0));
        assertEquals(GboardMultiSwipeSettings.MIN_TAP_TO_MULTISWIPE_DELAY_MS,
                GboardMultiSwipeSettings.readTapToMultiSwipeDelayMs(prefs));
        assertTrue(GboardMultiSwipeSettings.writeTapToMultiSwipeDelayMs(prefs, 5000));
        assertEquals(GboardMultiSwipeSettings.MAX_TAP_TO_MULTISWIPE_DELAY_MS,
                GboardMultiSwipeSettings.readTapToMultiSwipeDelayMs(prefs));
    }

    @Test
    public void parsesValidAndRejectsOutOfRangeDelayInputs() {
        assertEquals(0, GboardMultiSwipeSettingsFeature.parseDelayMs("0", "err"));
        assertEquals(300, GboardMultiSwipeSettingsFeature.parseDelayMs(" 300 ", "err"));
        assertEquals(2000, GboardMultiSwipeSettingsFeature.parseDelayMs("2000", "err"));

        String[] invalid = {null, "", "   ", "-1", "2001", "abc", "12.5"};
        for (String s : invalid) {
            try {
                GboardMultiSwipeSettingsFeature.parseDelayMs(s, "Invalid delay");
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

        assertEquals(GboardMultiSwipeSettings.DEFAULT_BEAM_WIDTH,
                GboardMultiSwipeSettings.readBeamWidth(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_FREQ_WEIGHT_FLOOR,
                GboardMultiSwipeSettings.readFreqWeightFloor(prefs), 0.0001f);
        assertEquals(GboardMultiSwipeSettings.DEFAULT_GEO_EXPONENT,
                GboardMultiSwipeSettings.readGeoExponent(prefs), 0.0001f);
        assertEquals(GboardMultiSwipeSettings.DEFAULT_BEAM_FREQ_WEIGHT,
                GboardMultiSwipeSettings.readBeamFreqWeight(prefs), 0.0001f);

        GboardMultiSwipeSettings.ensureDefaults(prefs);
        assertTrue(prefs.contains(GboardMultiSwipeSettings.PREF_KEY_BEAM_WIDTH));
        assertTrue(prefs.contains(GboardMultiSwipeSettings.PREF_KEY_FREQ_WEIGHT_FLOOR));
        assertTrue(prefs.contains(GboardMultiSwipeSettings.PREF_KEY_GEO_EXPONENT));
        assertTrue(prefs.contains(GboardMultiSwipeSettings.PREF_KEY_BEAM_FREQ_WEIGHT));

        assertTrue(GboardMultiSwipeSettings.writeBeamWidth(prefs, 384));
        assertTrue(GboardMultiSwipeSettings.writeFreqWeightFloor(prefs, 0.60f));
        assertTrue(GboardMultiSwipeSettings.writeGeoExponent(prefs, 5.25f));
        assertTrue(GboardMultiSwipeSettings.writeBeamFreqWeight(prefs, 0.20f));
        assertFalse(GboardMultiSwipeSettings.writeGeoExponent(prefs, Float.NaN));

        assertEquals(384, GboardMultiSwipeSettings.readBeamWidth(prefs));
        assertEquals(0.60f, GboardMultiSwipeSettings.readFreqWeightFloor(prefs), 0.0001f);
        assertEquals(5.25f, GboardMultiSwipeSettings.readGeoExponent(prefs), 0.0001f);
        assertEquals(0.20f, GboardMultiSwipeSettings.readBeamFreqWeight(prefs), 0.0001f);

        GboardMultiSwipeSettings.syncDecoderConstants(prefs);
        assertEquals(384, KineticaConstants.BEAM_WIDTH);
        assertEquals(900, KineticaConstants.BEAM_MAX_ATTEMPTS);
        assertEquals(0.60f, KineticaConstants.FREQ_WEIGHT_FLOOR, 0.0001f);
        assertEquals(5.25f, KineticaConstants.GEO_EXPONENT, 0.0001f);
        assertEquals(0.20f, KineticaConstants.BEAM_FREQ_WEIGHT, 0.0001f);
        assertEquals(1.0f, KineticaConstants.boostWeight(0.5f), 0.0001f);
        assertEquals(0.0f, KineticaConstants.boostWeight(1.5f), 0.0001f);

        assertEquals(300, KineticaConstants.beamMaxAttemptsForWidth(32));
        assertEquals(300, KineticaConstants.beamMaxAttemptsForWidth(64));
        assertEquals(300, KineticaConstants.beamMaxAttemptsForWidth(128));
        assertEquals(600, KineticaConstants.beamMaxAttemptsForWidth(256));
        assertEquals(900, KineticaConstants.beamMaxAttemptsForWidth(384));
        assertEquals(1200, KineticaConstants.beamMaxAttemptsForWidth(512));
    }

    @Test
    public void persistsAndResetsBackspaceModePreference() {
        InMemorySharedPreferences prefs = new InMemorySharedPreferences();

        assertEquals(GboardMultiSwipeSettings.BACKSPACE_MODE_WHOLE_WORD,
                GboardMultiSwipeSettings.readBackspaceMode(prefs));

        GboardMultiSwipeSettings.ensureDefaults(prefs);
        assertTrue(prefs.contains(GboardMultiSwipeSettings.PREF_KEY_BACKSPACE_MODE));
        assertEquals(GboardMultiSwipeSettings.BACKSPACE_MODE_WHOLE_WORD,
                GboardMultiSwipeSettings.readBackspaceMode(prefs));

        assertTrue(GboardMultiSwipeSettings.writeBackspaceMode(
                prefs, GboardMultiSwipeSettings.BACKSPACE_MODE_LAST_INPUT));
        assertEquals(GboardMultiSwipeSettings.BACKSPACE_MODE_LAST_INPUT,
                GboardMultiSwipeSettings.readBackspaceMode(prefs));

        assertTrue(GboardMultiSwipeSettings.writeBackspaceMode(prefs, "invalid_mode"));
        assertEquals(GboardMultiSwipeSettings.BACKSPACE_MODE_WHOLE_WORD,
                GboardMultiSwipeSettings.readBackspaceMode(prefs));

        List<GboardPatchesSettingsContract.Row> gestureRows =
                GboardMultiSwipeSettingsFeature.buildGestureRows(
                        "Enable MultiSwipe", "Summary", true, v -> {},
                        "Combine single-finger strokes", "Summary", false, v -> {},
                        "Combine", "Summary", "150 ms", () -> {},
                        "Merge preceding taps", "Summary", true, v -> {},
                        "TapDelay", "Summary", "750 ms", () -> {},
                        "Backspace behavior", "Summary", "Delete whole word (Default)", () -> {});
        assertEquals(6, gestureRows.size());
        assertFalse(gestureRows.get(2).isEnabled());
        assertTrue(gestureRows.get(4).isEnabled());
        assertEquals("Backspace behavior", gestureRows.get(5).getTitle());

        assertTrue(GboardMultiSwipeSettings.writeBackspaceMode(
                prefs, GboardMultiSwipeSettings.BACKSPACE_MODE_LAST_INPUT));
        assertTrue(GboardMultiSwipeSettings.resetToDefaults(prefs));
        assertEquals(GboardMultiSwipeSettings.BACKSPACE_MODE_WHOLE_WORD,
                GboardMultiSwipeSettings.readBackspaceMode(prefs));
        assertEquals(2400, KineticaConstants.beamMaxAttemptsForWidth(1024));
        assertEquals(KineticaConstants.MAX_EMIT_ATTEMPTS,
                KineticaConstants.beamMaxAttemptsForWidth(4096));

        KineticaConstants.applyRuntimeTuning(1024, 0.25f, 3.75f, 0.50f);
        assertEquals(2400, KineticaConstants.BEAM_MAX_ATTEMPTS);
        KineticaConstants.applyRuntimeTuning(128, 0.25f, 3.75f, 0.50f);
        assertEquals(300, KineticaConstants.BEAM_MAX_ATTEMPTS);
    }

    @Test
    public void parsesTuningIntAndFloatInputsAndRejectsInvalidValues() {
        assertEquals(256, GboardMultiSwipeSettingsFeature.parseIntInRange("256", 32, 1024, "err"));
        assertEquals(3.75f, GboardMultiSwipeSettingsFeature.parseFloatInRange("3,75", 1.0f, 8.0f, "err"), 0.0001f);
        assertEquals(0.50f, GboardMultiSwipeSettingsFeature.parseFloatInRange(" 0.5 ", 0.0f, 2.0f, "err"), 0.0001f);

        String[] badFloats = {null, "", "  ", "NaN", "Infinity", "-0.1", "2.1", "abc"};
        for (String s : badFloats) {
            try {
                GboardMultiSwipeSettingsFeature.parseFloatInRange(s, 0.0f, 2.0f, "Invalid");
                fail("Expected IllegalArgumentException for: " + s);
            } catch (IllegalArgumentException expected) {
                assertEquals("Invalid", expected.getMessage());
            }
        }
    }

    @Test
    public void detectsWhenLeadingSpaceIsNeededBeforeMultiSwipeWord() {
        assertTrue(GboardMultiSwipeRuntime.shouldPrependLeadingSpace("Hallo wie geht es", true));
        assertTrue(GboardMultiSwipeRuntime.shouldPrependLeadingSpace("Mi r", true));
        assertTrue(GboardMultiSwipeRuntime.shouldPrependLeadingSpace("Nummer 42", true));

        assertFalse(GboardMultiSwipeRuntime.shouldPrependLeadingSpace("Hallo wie geht es ", true));
        assertFalse(GboardMultiSwipeRuntime.shouldPrependLeadingSpace("Hallo\n", true));
        assertFalse(GboardMultiSwipeRuntime.shouldPrependLeadingSpace("(", true));
        assertFalse(GboardMultiSwipeRuntime.shouldPrependLeadingSpace("", true));
        assertFalse(GboardMultiSwipeRuntime.shouldPrependLeadingSpace(null, true));
        assertFalse(GboardMultiSwipeRuntime.shouldPrependLeadingSpace("Hallo wie geht es", false));
    }

    @Test
    public void defaultsTraceLoggingOffAndResetsAllSettingsToDefaults() {
        assertFalse(GboardMultiSwipeSettings.DEFAULT_TRACE_LOGGING);
        assertFalse(GboardMultiSwipeSettings.DEFAULT_SPACEBAR_TIMER_INDICATOR);

        InMemorySharedPreferences prefs = new InMemorySharedPreferences();
        GboardMultiSwipeSettings.ensureDefaults(prefs);
        assertFalse(GboardMultiSwipeSettings.readTraceLoggingEnabled(prefs));
        assertFalse(GboardMultiSwipeSettings.readSpacebarTimerIndicatorEnabled(prefs));
        assertFalse(GboardMultiSwipeSettings.readWordReadyVibrationEnabled(prefs));

        assertTrue(GboardMultiSwipeSettings.writeEnabled(prefs, false));
        assertTrue(GboardMultiSwipeSettings.writeAutoSpaceEnabled(prefs, false));
        assertTrue(GboardMultiSwipeSettings.writeAutoSpaceDelayMs(prefs, 725));
        assertTrue(GboardMultiSwipeSettings.writeSingleTouchCombineDelayMs(prefs, 260));
        assertTrue(GboardMultiSwipeSettings.writeSpacebarTimerIndicatorEnabled(prefs, true));
        assertTrue(GboardMultiSwipeSettings.readSpacebarTimerIndicatorEnabled(prefs));
        assertTrue(GboardMultiSwipeSettings.writeWordReadyVibrationEnabled(prefs, true));
        assertTrue(GboardMultiSwipeSettings.readWordReadyVibrationEnabled(prefs));
        assertTrue(GboardMultiSwipeSettings.writeBeamWidth(prefs, 512));
        assertTrue(GboardMultiSwipeSettings.writeFreqWeightFloor(prefs, 0.72f));
        assertTrue(GboardMultiSwipeSettings.writeGeoExponent(prefs, 6.25f));
        assertTrue(GboardMultiSwipeSettings.writeBeamFreqWeight(prefs, 1.35f));
        assertTrue(GboardMultiSwipeSettings.writeTraceLoggingEnabled(prefs, true));
        assertTrue(GboardMultiSwipeSettings.readTraceLoggingEnabled(prefs));

        assertTrue(GboardMultiSwipeSettings.resetToDefaults(prefs));

        assertEquals(GboardMultiSwipeSettings.DEFAULT_ENABLED, GboardMultiSwipeSettings.readEnabled(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_AUTO_SPACE,
                GboardMultiSwipeSettings.readAutoSpaceEnabled(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_AUTO_SPACE_DELAY_MS,
                GboardMultiSwipeSettings.readAutoSpaceDelayMs(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_SINGLE_TOUCH_COMBINE_DELAY_MS,
                GboardMultiSwipeSettings.readSingleTouchCombineDelayMs(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_TAP_TO_MULTISWIPE_DELAY_MS,
                GboardMultiSwipeSettings.readTapToMultiSwipeDelayMs(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_SPACEBAR_TIMER_INDICATOR,
                GboardMultiSwipeSettings.readSpacebarTimerIndicatorEnabled(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_WORD_READY_VIBRATION,
                GboardMultiSwipeSettings.readWordReadyVibrationEnabled(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_BEAM_WIDTH,
                GboardMultiSwipeSettings.readBeamWidth(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_FREQ_WEIGHT_FLOOR,
                GboardMultiSwipeSettings.readFreqWeightFloor(prefs), 0.0001f);
        assertEquals(GboardMultiSwipeSettings.DEFAULT_GEO_EXPONENT,
                GboardMultiSwipeSettings.readGeoExponent(prefs), 0.0001f);
        assertEquals(GboardMultiSwipeSettings.DEFAULT_BEAM_FREQ_WEIGHT,
                GboardMultiSwipeSettings.readBeamFreqWeight(prefs), 0.0001f);
        assertEquals(GboardMultiSwipeSettings.DEFAULT_TRACE_LOGGING,
                GboardMultiSwipeSettings.readTraceLoggingEnabled(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_BEAM_WIDTH, KineticaConstants.BEAM_WIDTH);
    }

    @Test
    public void convertsSliderStepsRoundTripOnIntAndFloatDefaults() {
        int delayStep = GboardMultiSwipeSettingsFeature.sliderStepForInt(
                GboardMultiSwipeSettings.DEFAULT_AUTO_SPACE_DELAY_MS, 0, 2000, 25);
        assertEquals(300, GboardMultiSwipeSettingsFeature.intValueForSliderStep(delayStep, 0, 2000, 25));

        int beamStep = GboardMultiSwipeSettingsFeature.sliderStepForInt(
                GboardMultiSwipeSettings.DEFAULT_BEAM_WIDTH, 32, 1024, 16);
        assertEquals(256, GboardMultiSwipeSettingsFeature.intValueForSliderStep(beamStep, 32, 1024, 16));

        int floorStep = GboardMultiSwipeSettingsFeature.sliderStepForFloat(
                GboardMultiSwipeSettings.DEFAULT_FREQ_WEIGHT_FLOOR, 0.05f, 1.00f, 0.01f);
        assertEquals(0.25f, GboardMultiSwipeSettingsFeature.floatValueForSliderStep(
                floorStep, 0.05f, 1.00f, 0.01f), 0.0001f);

        int geoStep = GboardMultiSwipeSettingsFeature.sliderStepForFloat(
                GboardMultiSwipeSettings.DEFAULT_GEO_EXPONENT, 1.00f, 8.00f, 0.05f);
        assertEquals(3.75f, GboardMultiSwipeSettingsFeature.floatValueForSliderStep(
                geoStep, 1.00f, 8.00f, 0.05f), 0.0001f);

        int freqStep = GboardMultiSwipeSettingsFeature.sliderStepForFloat(
                GboardMultiSwipeSettings.DEFAULT_BEAM_FREQ_WEIGHT, 0.00f, 2.00f, 0.05f);
        assertEquals(0.50f, GboardMultiSwipeSettingsFeature.floatValueForSliderStep(
                freqStep, 0.00f, 2.00f, 0.05f), 0.0001f);
    }

    @Test
    public void filtersTraceExportByPastMinutesAndIncludesReferencedGeometry() {
        long nowMs = 1_000_000_000L;
        String oldGeom = "{\"type\":\"geom\",\"ts\":" + (nowMs - 40 * 60_000L) + ",\"geom\":-42}";
        String oldWord = "{\"type\":\"word\",\"ts\":" + (nowMs - 30 * 60_000L) + ",\"geom\":-42,\"best\":\"alt\"}";
        String recentWord = "{\"type\":\"word\",\"ts\":" + (nowMs - 5 * 60_000L) + ",\"geom\":-42,\"best\":\"neu\"}";
        String recentCommit = "{\"type\":\"commit\",\"ts\":" + (nowMs - 4 * 60_000L) + ",\"word\":\"neu\"}";
        List<String> all = Arrays.asList(oldGeom, oldWord, recentWord, recentCommit);

        GboardMultiSwipeTraceLogger.TraceExportResult last10 =
                GboardMultiSwipeTraceLogger.buildTraceExport(all, 10, nowMs);
        assertEquals(3, last10.lineCount);
        assertEquals(oldGeom + "\n" + recentWord + "\n" + recentCommit, last10.content);

        GboardMultiSwipeTraceLogger.TraceExportResult last1 =
                GboardMultiSwipeTraceLogger.buildTraceExport(all, 1, nowMs);
        assertEquals(0, last1.lineCount);
        assertEquals("", last1.content);

        GboardMultiSwipeTraceLogger.TraceExportResult exportAll =
                GboardMultiSwipeTraceLogger.buildTraceExport(all, 0, nowMs);
        assertEquals(4, exportAll.lineCount);
    }

    @Test
    public void selectsDistinctMaterialDynamicAccentForMultiSwipeTrail() {
        // Material You Tertiary color distinct from stock Primary blue -> pick Tertiary directly
        assertEquals(0xF2B8B5,
                GboardMultiSwipeRuntime.chooseMultiSwipeTrailColor(0xA8C7FA, 0xF2B8B5, 0xCCC2DC, true));

        // When Tertiary matches stock too closely, fall back to distinct Secondary
        assertEquals(0x80CBC4,
                GboardMultiSwipeRuntime.chooseMultiSwipeTrailColor(0xA8C7FA, 0xA8C7FA, 0x80CBC4, true));

        // When both Tertiary and Secondary are too close to stock, rotate accent hue
        int tooCloseFallback =
                GboardMultiSwipeRuntime.chooseMultiSwipeTrailColor(0xA8C7FA, 0xA8C7FA, 0xCCC2DC, true);
        assertTrue(tooCloseFallback != 0xA8C7FA && tooCloseFallback != 0xCCC2DC);

        // On Android <12 (no dynamic system_accent* resources), returns a distinct fallback accent
        int rotated = GboardMultiSwipeRuntime.chooseMultiSwipeTrailColor(0x4285F4, -1, -1, false);
        assertTrue(rotated != 0x4285F4);
        assertEquals(0xEFB8C8, GboardMultiSwipeRuntime.chooseMultiSwipeTrailColor(0xEEEEEE, -1, -1, true));
    }

    @Test
    public void disablesDependentAutoSpaceRowsWhenAutoSpaceIsOff() {
        List<dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettingsContract.Row> withAutoSpace =
                GboardMultiSwipeSettingsFeature.buildAutoSpaceRows(
                        true,
                        "Auto-space", "", true, v -> {},
                        "Space placement", "", "Before next word (Gboard style)", () -> {},
                        "Auto-space delay", "", "300 ms", () -> {},
                        "Spacebar timer indicator", "", false, v -> {},
                        "Word-ready vibration", "", false, v -> {});
        assertEquals(5, withAutoSpace.size());
        for (dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettingsContract.Row row : withAutoSpace) {
            assertTrue(row.isEnabled());
        }

        List<dev.srwi.gboardmultiswipe.extension.settings.GboardPatchesSettingsContract.Row> withoutAutoSpace =
                GboardMultiSwipeSettingsFeature.buildAutoSpaceRows(
                        true,
                        "Auto-space", "", false, v -> {},
                        "Space placement", "", "Before next word (Gboard style)", () -> {},
                        "Auto-space delay", "", "300 ms", () -> {},
                        "Spacebar timer indicator", "", false, v -> {},
                        "Word-ready vibration", "", false, v -> {});
        assertEquals(5, withoutAutoSpace.size());
        assertTrue(withoutAutoSpace.get(0).isEnabled());
        assertFalse(withoutAutoSpace.get(1).isEnabled());
        assertFalse(withoutAutoSpace.get(2).isEnabled());
        assertFalse(withoutAutoSpace.get(3).isEnabled());
        assertFalse(withoutAutoSpace.get(4).isEnabled());
    }

    @Test
    public void resetDecoderToDefaultsResetsOnlyKineticaParameters() {
        InMemorySharedPreferences prefs = new InMemorySharedPreferences();
        GboardMultiSwipeSettings.ensureDefaults(prefs);

        assertTrue(GboardMultiSwipeSettings.writeAutoSpaceEnabled(prefs, false));
        assertTrue(GboardMultiSwipeSettings.writeAutoSpaceDelayMs(prefs, 500));
        assertTrue(GboardMultiSwipeSettings.writeBackspaceMode(
                prefs, GboardMultiSwipeSettings.BACKSPACE_MODE_LAST_INPUT));
        assertTrue(GboardMultiSwipeSettings.writeBeamWidth(prefs, 512));
        assertTrue(GboardMultiSwipeSettings.writeFreqWeightFloor(prefs, 0.50f));
        assertTrue(GboardMultiSwipeSettings.writeGeoExponent(prefs, 5.00f));
        assertTrue(GboardMultiSwipeSettings.writeBeamFreqWeight(prefs, 1.00f));

        assertTrue(GboardMultiSwipeSettings.resetDecoderToDefaults(prefs));

        assertEquals(GboardMultiSwipeSettings.DEFAULT_BEAM_WIDTH,
                GboardMultiSwipeSettings.readBeamWidth(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_FREQ_WEIGHT_FLOOR,
                GboardMultiSwipeSettings.readFreqWeightFloor(prefs), 0.0001f);
        assertEquals(GboardMultiSwipeSettings.DEFAULT_GEO_EXPONENT,
                GboardMultiSwipeSettings.readGeoExponent(prefs), 0.0001f);
        assertEquals(GboardMultiSwipeSettings.DEFAULT_BEAM_FREQ_WEIGHT,
                GboardMultiSwipeSettings.readBeamFreqWeight(prefs), 0.0001f);
        assertFalse(GboardMultiSwipeSettings.readAutoSpaceEnabled(prefs));
        assertEquals(500, GboardMultiSwipeSettings.readAutoSpaceDelayMs(prefs));
        assertEquals(GboardMultiSwipeSettings.BACKSPACE_MODE_LAST_INPUT,
                GboardMultiSwipeSettings.readBackspaceMode(prefs));
    }

    @Test
    public void migratesLegacyZeroTimeoutToDisabledToggle() {
        InMemorySharedPreferences prefs = new InMemorySharedPreferences();
        prefs.edit()
                .putInt(GboardMultiSwipeSettings.PREF_KEY_SINGLE_TOUCH_COMBINE_DELAY_MS, 0)
                .putInt(GboardMultiSwipeSettings.PREF_KEY_TAP_TO_MULTISWIPE_DELAY_MS, 0)
                .commit();

        assertFalse(GboardMultiSwipeSettings.readSingleTouchCombineEnabled(prefs));
        assertFalse(GboardMultiSwipeSettings.readTapToMultiSwipeEnabled(prefs));

        GboardMultiSwipeSettings.ensureDefaults(prefs);
        assertFalse(GboardMultiSwipeSettings.readSingleTouchCombineEnabled(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_SINGLE_TOUCH_COMBINE_DELAY_MS,
                GboardMultiSwipeSettings.readSingleTouchCombineDelayMs(prefs));
        assertFalse(GboardMultiSwipeSettings.readTapToMultiSwipeEnabled(prefs));
        assertEquals(GboardMultiSwipeSettings.DEFAULT_TAP_TO_MULTISWIPE_DELAY_MS,
                GboardMultiSwipeSettings.readTapToMultiSwipeDelayMs(prefs));
    }

    @Test
    public void sanitizesAndResetsAutoSpaceBeforeWordPreference() {
        InMemorySharedPreferences prefs = new InMemorySharedPreferences();
        GboardMultiSwipeSettings.ensureDefaults(prefs);
        assertTrue(GboardMultiSwipeSettings.readAutoSpaceBeforeWord(prefs));

        assertTrue(GboardMultiSwipeSettings.writeAutoSpaceBeforeWord(prefs, false));
        assertFalse(GboardMultiSwipeSettings.readAutoSpaceBeforeWord(prefs));

        assertTrue(GboardMultiSwipeSettings.resetToDefaults(prefs));
        assertTrue(GboardMultiSwipeSettings.readAutoSpaceBeforeWord(prefs));
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
