package dev.jason.gboardpatches.extension.nintype;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.jason.gboardpatches.extension.nintype.NintypeGeometry.KeySpec;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.TouchPoint;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.WordCandidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class NintypeDecoderTest {

    private NintypeGeometry geometry;
    private NintypeTrie trie;
    private NintypeBigramTable bigrams;
    private NintypeDecoder decoder;

    @Before
    public void setUp() {
        geometry = NintypeGeometry.createStandardLayout(1080f, 680f, true);
        trie = new NintypeTrie(4096);
        bigrams = new NintypeBigramTable();

        NintypeDictionaryLoader.insertWordIntoTrie(trie, "hallo", 85000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "halten", 40000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "heute", 92000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "Welt", 78000, true, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "müssen", 88000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "schreiben", 75000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "wir", 98000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "als", 95000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "las", 42000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "as", 25000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "sie", 97000, false, "de", false);

        bigrams.put("wir", "müssen", 1200);
        bigrams.put("wir", "schreiben", 900);
        bigrams.freeze();

        decoder = new NintypeDecoder(trie, bigrams, geometry);
    }

    @Test
    public void decodesTwoFingerSwipeAndSwipe() {
        // Finger 0 swipes h -> a -> l, Finger 1 swipes l -> o
        NintypeInputToken leftSwipe = buildSwipeToken(0, "hal", 100L, 220L);
        NintypeInputToken rightSwipe = buildSwipeToken(1, "lo", 210L, 330L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(leftSwipe, rightSwipe), null, 5, null);
        assertFalse(results.isEmpty());
        assertEquals("hallo", results.get(0).word);
    }

    @Test
    public void decodesTapPlusSwipeCombination() {
        // Finger 0 taps 'h', Finger 1 swipes e -> u -> t -> e
        KeySpec hKey = geometry.keySpec('h');
        assertNotNull(hKey);
        NintypeInputToken tapH = NintypeInputToken.tap(
                0, 'h', hKey.centerX, hKey.centerY, 100L, 140L);
        NintypeInputToken swipeEute = buildSwipeToken(1, "eute", 130L, 320L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(tapH, swipeEute), null, 5, null);
        assertFalse(results.isEmpty());
        assertEquals("heute", results.get(0).word);
    }

    @Test
    public void decodesSwipePlusTapAndPreservesGermanNounCapitalization() {
        // Finger 0 swipes w -> e -> l, Finger 1 taps 't' -> "Welt"
        NintypeInputToken swipeWel = buildSwipeToken(0, "wel", 100L, 260L);
        KeySpec tKey = geometry.keySpec('t');
        assertNotNull(tKey);
        NintypeInputToken tapT = NintypeInputToken.tap(
                1, 't', tKey.centerX, tKey.centerY, 270L, 310L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(swipeWel, tapT), null, 5, null);
        assertFalse(results.isEmpty());
        assertEquals("Welt", results.get(0).word);
    }

    @Test
    public void decodesGermanUmlautAndDigraphWithBigramAndGboardCallbackBoost() {
        // Finger 0 swipes m -> u -> e -> s, Finger 1 swipes s -> e -> n -> "müssen"
        NintypeInputToken leftSwipe = buildSwipeToken(0, "mues", 100L, 240L);
        NintypeInputToken rightSwipe = buildSwipeToken(1, "sen", 230L, 360L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(leftSwipe, rightSwipe),
                "wir",
                5,
                words -> {
                    boolean[] valid = new boolean[words.size()];
                    for (int i = 0; i < words.size(); i++) {
                        valid[i] = "müssen".equals(words.get(i));
                    }
                    return valid;
                });

        assertFalse(results.isEmpty());
        assertEquals("müssen", results.get(0).word);
        assertTrue(results.get(0).bigramScore > 0.5f);
    }

    @Test
    public void decodesInterleavedShortSwipeAndTapLikeAlsEvenWhenTapTimestampIsAfterSwipeEnd() {
        // User scenario: left finger swipes a -> s (short 1-key neighbor swipe), right finger taps 'l'
        // Even if the short swipe a -> s reaches 's' at 165ms and 'l' is tapped at 185ms, "als" must win!
        NintypeInputToken swipeAs = buildSwipeToken(0, "as", 100L, 165L);
        KeySpec lKey = geometry.keySpec('l');
        assertNotNull(lKey);
        NintypeInputToken tapL = NintypeInputToken.tap(
                1, 'l', lKey.centerX, lKey.centerY, 185L, 215L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(swipeAs, tapL), null, 5, null);
        assertFalse(results.isEmpty());
        assertEquals("als", results.get(0).word);

        // Also test when 'l' is tapped concurrently during the swipe (at 130ms)
        NintypeInputToken tapLConcurrent = NintypeInputToken.tap(
                1, 'l', lKey.centerX, lKey.centerY, 130L, 155L);
        List<WordCandidate> concurrentResults = decoder.decode(
                Arrays.asList(swipeAs, tapLConcurrent), null, 5, null);
        assertFalse(concurrentResults.isEmpty());
        assertEquals("als", concurrentResults.get(0).word);
    }

    @Test
    public void decodesShortSwipeAndRightTapForSie() {
        // Left finger swipes s -> e, right finger taps 'i' -> "sie"
        NintypeInputToken swipeSe = buildSwipeToken(0, "se", 100L, 180L);
        KeySpec iKey = geometry.keySpec('i');
        assertNotNull(iKey);
        NintypeInputToken tapI = NintypeInputToken.tap(
                1, 'i', iKey.centerX, iKey.centerY, 195L, 225L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(swipeSe, tapI), null, 5, null);
        assertFalse(results.isEmpty());
        assertEquals("sie", results.get(0).word);
    }

    @Test
    public void decodesAlternatingDualThumbSwipeForSchreiben() {
        // User scenario: Left thumb swipes s -> c -> r -> e (stream 0)
        // Right thumb swipes h -> i -> b -> n (stream 1)
        // Combined word: "schreiben" (switches hands 4 times)
        NintypeInputToken leftSwipe = buildSwipeToken(0, "scre", 100L, 380L);
        NintypeInputToken rightSwipe = buildSwipeToken(1, "hibn", 160L, 460L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(leftSwipe, rightSwipe), "wir", 5, null);
        assertFalse(results.isEmpty());
        assertEquals("schreiben", results.get(0).word);
    }

    private NintypeInputToken buildSwipeToken(
            int pointerId,
            String keySequence,
            long startTimeMs,
            long endTimeMs
    ) {
        List<TouchPoint> pts = new ArrayList<>();
        int stepsPerSegment = 6;
        int totalSegments = Math.max(1, keySequence.length() - 1);
        int totalSteps = totalSegments * stepsPerSegment;
        float pathLen = 0f;
        float prevX = 0f;
        float prevY = 0f;

        for (int s = 0; s <= totalSteps; s++) {
            float progress = (float) s / (float) totalSteps;
            float exactIdx = progress * (keySequence.length() - 1);
            int idx0 = Math.min(keySequence.length() - 1, (int) Math.floor(exactIdx));
            int idx1 = Math.min(keySequence.length() - 1, idx0 + 1);
            float frac = exactIdx - idx0;

            KeySpec k0 = geometry.keySpec(keySequence.charAt(idx0));
            KeySpec k1 = geometry.keySpec(keySequence.charAt(idx1));
            float x = k0.centerX + (k1.centerX - k0.centerX) * frac;
            float y = k0.centerY + (k1.centerY - k0.centerY) * frac;
            long t = startTimeMs + (long) ((endTimeMs - startTimeMs) * progress);

            if (s > 0) {
                pathLen += (float) Math.hypot(x - prevX, y - prevY);
            }
            prevX = x;
            prevY = y;
            pts.add(new TouchPoint(x, y, t, pointerId));
        }

        int streamId = (pointerId == 0) ? 0 : 1;
        return NintypeInputToken.swipe(pointerId, streamId, pts, Math.max(60f, pathLen), startTimeMs, endTimeMs);
    }
}
