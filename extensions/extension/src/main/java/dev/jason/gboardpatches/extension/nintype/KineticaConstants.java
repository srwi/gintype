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
    public static final int MAX_CANDIDATES = 800;
    public static final int MAX_EMIT_ATTEMPTS = 4000;
    public static final int STREAM_SEARCH_STEP_BUDGET = 100_000;
    public static final int RESCUE_STEP_BUDGET = 15_000;

    public static final float TRANSPOSE_PENALTY = 0.15f;
    public static final long ORDER_AMBIG_MS = 120L;
    public static final long SPLIT_MARGIN_MS = 80L;
    public static final float MIN_SPLIT_HALF_ARC_KW = 0.5f;
    public static final float SPLIT_HEAD_TRIM_KW = 0.6f;
    public static final float SPLIT_RESUME_TAIL_KW = 1.2f;
    public static final int MAX_ALT_SEQUENCES = 14;
    public static final int MAX_SPLIT_ANCHORS = 3;
    public static final float HANDOVER_CONTACT_FRACTION = 1.0f;

    public static final float RESCUE_MIN_LEAD_D = 1.0f;
    public static final float RESCUE_GATE_KEEP = 0.6f;
    public static final int RESCUE_MAX_ATTEMPTS = 200;
    public static final int BEAM_MAX_NEW = 2;

    public static final int MAX_SEARCH_CUTS = 2;
    public static final int STREAM_SEARCH_GATE = 1;
    public static final int STREAM_SEARCH_MAX_CUTS = 3;
    public static final int MAX_SEARCH_CUT_TIMES = 8;
    public static final boolean STREAM_SEARCH_OWN_CUTS = true;
    public static final boolean STREAM_SEARCH_OWN_CUT_CLOSES_ON_KEY = true;
    public static final boolean STREAM_SEARCH_CLOSE_INSIDE_LAST_KEY = true;

    public static final long RESTING_FINGER_MIN_MS = 400L;
    public static final int PERSONAL_MERGE_MIN_COUNT = 2;

    public static final float STICKY_INFLATE_KW = 0.2f;
    public static final long DWELL_MIN_MS = 150L;
    public static final float DWELL_RADIUS_KW = 0.3f;
    public static final int MAX_DWELL_SEGMENTS = 4;

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

    /**
     * Exact inverse of geometricTerm for DTW early-abandon.
     */
    public static float maxDTotalForScore(float numerator, float minScore) {
        if (minScore <= 0f) return Float.POSITIVE_INFINITY;
        float ratio = numerator / minScore;
        if (ratio <= 1f) return 0f;
        float d = (float) Math.pow(ratio, 1.0 / GEO_EXPONENT) - 1.0f;
        return (d >= GEO_SATURATION_KW) ? Float.POSITIVE_INFINITY : d;
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
