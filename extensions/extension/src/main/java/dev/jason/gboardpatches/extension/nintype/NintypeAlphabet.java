package dev.jason.gboardpatches.extension.nintype;

/**
 * Core alphabet and scoring constants for the Nintype multi-finger swipe engine.
 */
public final class NintypeAlphabet {

    public static final int ALPHABET_SIZE = 26;

    // Scoring weights for final candidate ranking (ported from Kinetica)
    public static final float WEIGHT_SPATIAL = 0.50f;
    public static final float WEIGHT_FREQUENCY = 0.30f;
    public static final float WEIGHT_BIGRAM = 0.20f;

    public static final float FREQ_LOG_SCALE = 11.512925f; // ln(100_000)

    private NintypeAlphabet() {}

    public static int charIndex(char c) {
        char lower = Character.toLowerCase(c);
        if (lower >= 'a' && lower <= 'z') {
            return lower - 'a';
        }
        return -1;
    }

    public static char indexToChar(int idx) {
        return (char) ('a' + idx);
    }
}
