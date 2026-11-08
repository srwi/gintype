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
    private KineticaDtwDecoder decoder;

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
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "Hunde", 70000, true, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "hunde", 70000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "wie", 96000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "nochmal", 91000, false, "de", false);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "Monica", 20000, true, "de", false);

        bigrams.put("wir", "müssen", 1200);
        bigrams.put("wir", "schreiben", 900);
        bigrams.freeze();

        decoder = new KineticaDtwDecoder();
    }

    @Test
    public void decodesSingleSwipeForHallo() {
        NintypeInputToken swipeHallo = buildSwipeToken(0, "halo", 100L, 350L);
        List<WordCandidate> results = decoder.decode(
                Arrays.asList(swipeHallo), trie, bigrams, geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("hallo", results.get(0).word);
    }

    @Test
    public void decodesTwoFingerSwipeAndSwipe() {
        // Finger 0 swipes h -> a -> l, Finger 1 swipes l -> o
        NintypeInputToken leftSwipe = buildSwipeToken(0, "hal", 100L, 220L);
        NintypeInputToken rightSwipe = buildSwipeToken(1, "lo", 210L, 330L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(leftSwipe, rightSwipe), trie, bigrams, geometry, null);
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
                Arrays.asList(tapH, swipeEute), trie, bigrams, geometry, null);
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
                1, 't', tKey.centerX, tKey.centerY, 380L, 420L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(swipeWel, tapT), trie, bigrams, geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("Welt", results.get(0).word);
    }

    @Test
    public void decodesGermanUmlautWithBigram() {
        NintypeInputToken swipeMussen = buildSwipeToken(0, "muesen", 100L, 360L);
        List<WordCandidate> results = decoder.decode(
                Arrays.asList(swipeMussen), trie, bigrams, geometry, "wir");

        assertFalse(results.isEmpty());
        assertEquals("müssen", results.get(0).word);
    }

    @Test
    public void decodesAlternatingDualThumbSwipeForSchreiben() {
        // User scenario: Left thumb swipes s -> c -> r -> e (stream 0)
        // Right thumb swipes h -> i -> b -> n (stream 1)
        NintypeInputToken leftSwipe = buildSwipeToken(0, "scre", 100L, 380L);
        NintypeInputToken rightSwipe = buildSwipeToken(1, "hibn", 160L, 460L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(leftSwipe, rightSwipe), trie, bigrams, geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("schreiben", results.get(0).word);
    }

    @Test
    public void decodesTapPlusTapPlusSwipeForNochmal() {
        // Scenario reported by user: tap 'c' and 'a' on left thumb, swipe "nohml" on right thumb
        KeySpec cKey = geometry.keySpec('c');
        KeySpec aKey = geometry.keySpec('a');
        assertNotNull(cKey);
        assertNotNull(aKey);

        NintypeInputToken tapC = NintypeInputToken.tap(0, 0, 'c', cKey.centerX, cKey.centerY, 100L, 140L);
        NintypeInputToken tapA = NintypeInputToken.tap(0, 0, 'a', aKey.centerX, aKey.centerY, 300L, 340L);
        NintypeInputToken swipeNohml = buildSwipeToken(1, "nohml", 120L, 420L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(tapC, tapA, swipeNohml), trie, bigrams, geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("nochmal", results.get(0).word);
    }

    @Test
    public void decodesSingleSwipeForWie() {
        NintypeInputToken swipeWie = buildSwipeToken(0, "wie", 100L, 300L);
        List<WordCandidate> results = decoder.decode(
                Arrays.asList(swipeWie), trie, bigrams, geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("wie", results.get(0).word);
    }

    @Test
    public void decodesSwipePlusTapForWie() {
        // User swipes w -> i and then taps 'e'
        NintypeInputToken swipeWi = buildSwipeToken(0, "wi", 100L, 250L);
        KeySpec eKey = geometry.keySpec('e');
        assertNotNull(eKey);
        NintypeInputToken tapE = NintypeInputToken.tap(1, 1, 'e', eKey.centerX, eKey.centerY, 300L, 340L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(swipeWi, tapE), trie, bigrams, geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("wie", results.get(0).word);
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
        long finalEnd = pts.get(pts.size() - 1).timeMs;
        return NintypeInputToken.swipe(pointerId, streamId, pts, Math.max(60f, pathLen), startTimeMs, finalEnd);
    }
}
