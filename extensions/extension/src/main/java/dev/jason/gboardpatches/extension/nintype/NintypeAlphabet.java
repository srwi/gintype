package dev.jason.gboardpatches.extension.nintype;

/**
 * Core alphabet and scoring constants for the Nintype multi-finger swipe engine.
 */
public final class NintypeAlphabet {

    public static final int ALPHABET_SIZE = 26;

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
