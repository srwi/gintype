package dev.jason.gboardpatches.extension.nintype;

/**
 * Constants and mathematical scoring functions ported 1:1 from KineticaConstants.kt.
 */
public final class KineticaConstants {

    private KineticaConstants() {}

    public static final int RESAMPLE_N = 32;
    public static final int DTW_BAND_R = 4;
    public static final float DTW_ENDPOINT_WEIGHT = 2.0f;

    public static final float R_ENDPOINT_KW = 1.4f;
    public static final float R_INNER_KW = 1.8f;
    public static final float PASS_SPLIT_PROMINENCE_KW = 0.7f;
    public static final int MONOTONE_SLACK = 18;

    public static final float LEN_BAND_LO = 0.5f;
    public static final float LEN_BAND_HI = 1.5f;
    public static final float LEN_BAND_MARGIN_KW = 1.0f;

    public static final float FUZZY_TAP_RADIUS_KW = 1.0f;
    public static final float FUZZY_TAP_LAMBDA = 0.15f;
    public static final float DOUBLE_TAP_PEN_KW = 0.1f;
    public static final long HANDOVER_ALLOWANCE_MS = 200L;
    public static final float UNCONTACTED_LETTER_KEEP = 0.85f;

    public static final int BEAM_WIDTH = 128;
    public static final int BEAM_MAX_ATTEMPTS = 300;
    public static final float BEAM_FREQ_WEIGHT = 0.5f;
    public static final float BEAM_GATE_COST = 0.5f;
    public static final float BEAM_GATE_KEEP = 0.6f;
    public static final boolean BEAM_UNSATURATED = true;

    public static final float GEO_EXPONENT = 3.75f;
    public static final float GEO_SATURATION_KW = 0.5f;
    public static final float FREQ_WEIGHT_FLOOR = 0.25f;

    public static final int TOP_K = 10;
    public static final int MAX_WORD_LEN = 24;

    public static final float MIN_SWIPE_ARC_KW = 1.8f;
    public static final float KW_PER_LETTER = 1.5f;

    private static final float SATURATED_TERM = (float) (1.0 / Math.pow(1.0 + GEO_SATURATION_KW, GEO_EXPONENT));

    /**
     * Geometric factor of the score; 1.0 at a perfect fit, floored past saturation.
     */
    public static float geometricTerm(float dTotal) {
        float d = (dTotal < GEO_SATURATION_KW) ? dTotal : GEO_SATURATION_KW;
        return (float) (1.0 / Math.pow(1.0 + d, GEO_EXPONENT));
    }

    public static float geometricTermUnsaturated(float dTotal) {
        return (float) (1.0 / Math.pow(1.0 + dTotal, GEO_EXPONENT));
    }

    public static float boostWeight(float dGeometric) {
        float excess = dGeometric - GEO_SATURATION_KW;
        if (excess <= 0f) return 1.0f;
        float cur = (float) (1.0 / Math.pow(1.0 + Math.min(excess, GEO_SATURATION_KW), GEO_EXPONENT));
        return (cur - SATURATED_TERM) / (1.0f - SATURATED_TERM);
    }

    public static float appliedBoost(float rawBoost, float dGeometric) {
        return 1.0f + (rawBoost - 1.0f) * boostWeight(dGeometric);
    }
}
