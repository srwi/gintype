package dev.srwi.gboardmultiswipe.extension.multiswipe;

/**
 * Core alphabet and scoring constants for the MultiSwipe multi-finger swipe engine.
 */
public final class MultiSwipeAlphabet {

    public static final int ALPHABET_SIZE = 26;

    private MultiSwipeAlphabet() {}

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
