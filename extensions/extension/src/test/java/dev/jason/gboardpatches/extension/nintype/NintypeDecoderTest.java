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
        NintypeDictionaryLoader.insertWordIntoTrie(trie, "sempre", 84000, false, "it", false);

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

    @Test
    public void dynamicMidlineIgnoresAsymmetricUmlautColumn() {
        NintypeGeometry geo = NintypeGeometry.createStandardLayout(1080f, 680f, true);
        // Add German 11th-column umlauts on the far right (should NOT shift t/z & g/h midline)
        List<KeySpec> specs = new ArrayList<>(30);
        for (int i = 0; i < NintypeAlphabet.ALPHABET_SIZE; i++) {
            KeySpec ks = geo.keySpecByIdx(i);
            if (ks != null) specs.add(ks);
        }
        specs.add(new KeySpec('ü', 1140f, geo.keySpec('p').centerY, 98f, 140f));
        specs.add(new KeySpec('ö', 1140f, geo.keySpec('l').centerY, 98f, 140f));
        specs.add(new KeySpec('ä', 1190f, geo.keySpec('l').centerY, 98f, 140f));
        geo.setKeys(specs, 98f, 140f, true);

        float tZMid = (geo.keySpec('t').centerX + geo.keySpec('z').centerX) * 0.5f;
        float gHMid = (geo.keySpec('g').centerX + geo.keySpec('h').centerX) * 0.5f;
        float expectedMid = (tZMid + gHMid) * 0.5f;
        assertEquals(expectedMid, geo.midlinePx(), 0.5f);
        assertTrue(geo.hasDedicatedUmlauts());
    }

    static final class FakeSoftKeyDef {
        final List<CharSequence> i; // labels
        final String m; // contentDescription

        FakeSoftKeyDef(String label) {
            this.i = Arrays.asList((CharSequence) label);
            this.m = label;
        }
    }

    static final class FakeSoftKeyView {
        final FakeSoftKeyDef e;

        FakeSoftKeyView(String label) {
            this.e = new FakeSoftKeyDef(label);
        }
    }

    @Test
    public void extractsNativeLayoutKeysWithoutTextViewsIncludingUmlauts() {
        String letters = "qwertzuiopasdfghjklyxcvbnmüöä";
        int n = letters.length();
        Object[] keyObjs = new Object[n];
        float[] cx = new float[n];
        float[] cy = new float[n];
        int[] w = new int[n];
        int[] h = new int[n];
        for (int idx = 0; idx < n; idx++) {
            char ch = letters.charAt(idx);
            keyObjs[idx] = new FakeSoftKeyView(String.valueOf(ch));
            cx[idx] = 50f + (idx % 11) * 98f;
            cy[idx] = 220f + (idx / 11) * 145f; // realistic offset from suggestion bar
            w[idx] = 96;
            h[idx] = 140;
        }
        boolean[] hasUmlaut = new boolean[] {false};
        List<KeySpec> extracted = GboardNintypeRuntime.extractKeysFromNativeLayoutArrays(
                keyObjs, cx, cy, w, h, hasUmlaut);

        assertEquals(n, extracted.size());
        assertTrue(hasUmlaut[0]);

        NintypeGeometry geo = new NintypeGeometry();
        geo.setKeys(extracted, 96f, 140f, hasUmlaut[0]);
        assertTrue(geo.isValid());
        // Verify row Y offset from suggestion bar is preserved
        assertEquals(220f, geo.keySpec('q').centerY, 0.1f);
        assertEquals(geo.keySpec('q').centerX, extracted.get(0).centerX, 0.1f);
    }

    @Test
    public void decodesSimultaneousDualThumbEvenWhenArrivalOrderSwapped() {
        // Right thumb ("lo") arrives slightly earlier than left thumb ("hal") inside ORDER_AMBIG_MS (120ms)
        NintypeInputToken rightEarly = buildSwipeToken(1, "lo", 100L, 260L);
        NintypeInputToken leftSlightlyLater = buildSwipeToken(0, "hal", 145L, 290L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(rightEarly, leftSlightlyLater), trie, bigrams, geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("hallo", results.get(0).word);
    }

    @Test
    public void decodesTapInterruptingSwipeMidGesture() {
        // Right thumb swipes h -> l -> o while left thumb interrupts with tap 'a'
        NintypeInputToken rightSwipe = buildSwipeToken(1, "hlo", 100L, 420L);
        KeySpec aKey = geometry.keySpec('a');
        assertNotNull(aKey);
        NintypeInputToken leftTapA = NintypeInputToken.tap(
                0, 0, 'a', aKey.centerX, aKey.centerY, 220L, 250L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(rightSwipe, leftTapA), trie, bigrams, geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("hallo", results.get(0).word);
    }

    @Test
    public void decodesSwipeAroundSwipeInterleaveForSempre() {
        // Left thumb swipes s->e->r->e around right thumb swiping m->p
        NintypeInputToken leftOuter = buildSwipeToken(0, "sere", 100L, 520L);
        NintypeInputToken rightInner = buildSwipeToken(1, "mp", 240L, 380L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(leftOuter, rightInner), trie, bigrams, geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("sempre", results.get(0).word);
    }

    @Test
    public void pointerTrackExtractsStickyKeyContactsAndDwellRuns() {
        KeySpec hKey = geometry.keySpec('h');
        KeySpec aKey = geometry.keySpec('a');
        KeySpec lKey = geometry.keySpec('l');
        KeySpec oKey = geometry.keySpec('o');
        assertNotNull(hKey);
        assertNotNull(aKey);
        assertNotNull(lKey);
        assertNotNull(oKey);

        GboardNintypeRuntime.PointerTrack track = new GboardNintypeRuntime.PointerTrack(
                0, hKey.centerX, hKey.centerY, 100L, 'h', true, 0, geometry);
        // Dwell inside DWELL_RADIUS_KW for >= 150ms without large displacement
        track.addPoint(hKey.centerX + 0.5f, hKey.centerY, 180L, geometry);
        track.addPoint(hKey.centerX + 0.8f, hKey.centerY, 270L, geometry);
        // Move to 'a', linger slightly inside STICKY_INFLATE_KW, then 'l' -> 'o'
        track.addPoint(aKey.centerX, aKey.centerY, 330L, geometry);
        track.addPoint(aKey.centerX + geometry.keyWidthPx * 0.55f, aKey.centerY, 360L, geometry);
        track.addPoint(lKey.centerX, lKey.centerY, 410L, geometry);
        track.addPoint(oKey.centerX, oKey.centerY, 470L, geometry);

        NintypeInputToken tok = track.toToken(geometry.keyWidthPx * 0.38f);
        assertNotNull(tok);
        assertEquals(NintypeInputToken.Kind.SWIPE, tok.kind);
        assertFalse("Expected stationary dwell run to be recorded", tok.dwells.isEmpty());
        assertTrue("Dwell must span >= 150ms",
                tok.dwells.get(0).tExit - tok.dwells.get(0).tEnter >= KineticaConstants.DWELL_MIN_MS);
        assertTrue("Expected key contacts along h-a-l-o", tok.keyContacts.size() >= 4);
    }

    @Test
    public void rescuePassFindsWordWhenEndpointStartsFarOutsideRadius() {
        // Build swipe for "hallo" whose first point starts 1.9 kw away above 'q' instead of 'h'
        KeySpec qKey = geometry.keySpec('q');
        KeySpec hKey = geometry.keySpec('h');
        KeySpec aKey = geometry.keySpec('a');
        KeySpec lKey = geometry.keySpec('l');
        KeySpec oKey = geometry.keySpec('o');
        List<TouchPoint> pts = Arrays.asList(
                new TouchPoint(qKey.centerX, qKey.centerY - geometry.keyWidthPx * 1.1f, 100L, 0),
                new TouchPoint(hKey.centerX, hKey.centerY, 160L, 0),
                new TouchPoint(aKey.centerX, aKey.centerY, 240L, 0),
                new TouchPoint(lKey.centerX, lKey.centerY, 310L, 0),
                new TouchPoint(oKey.centerX, oKey.centerY, 380L, 0)
        );
        NintypeInputToken noisy = NintypeInputToken.swipe(0, 0, pts, 600f, 100L, 380L);

        List<WordCandidate> results = decoder.decode(
                Arrays.asList(noisy), trie, bigrams, geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("hallo", results.get(0).word);
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
