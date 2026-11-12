package dev.jason.gboardpatches.extension.nintype;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.jason.gboardpatches.extension.nintype.NintypeGeometry.KeySpec;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.TouchPoint;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.WordCandidate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

        insertQuantized("hallo", 85000, false, "de");
        insertQuantized("halten", 40000, false, "de");
        insertQuantized("heute", 92000, false, "de");
        insertQuantized("Welt", 78000, true, "de");
        insertQuantized("müssen", 88000, false, "de");
        insertQuantized("schreiben", 75000, false, "de");
        insertQuantized("wir", 98000, false, "de");
        insertQuantized("als", 95000, false, "de");
        insertQuantized("las", 42000, false, "de");
        insertQuantized("as", 25000, false, "de");
        insertQuantized("sie", 97000, false, "de");
        insertQuantized("Hunde", 70000, true, "de");
        insertQuantized("hunde", 70000, false, "de");
        insertQuantized("wie", 96000, false, "de");
        insertQuantized("nochmal", 91000, false, "de");
        insertQuantized("Monica", 20000, true, "de");
        insertQuantized("sempre", 84000, false, "it");

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

    @Test
    public void quantizedCorpusFrequenciesPreventShortWordDomination() throws IOException {
        String corpus = ""
                + "ich\t5890279\t0\n"
                + "ist\t2900000\t0\n"
                + "hallo\t85000\t0\n"
                + "schreiben\t75000\t0\n"
                + "schreibt\t12000\t0\n";
        NintypeTrie loaded = NintypeDictionaryLoader.loadTrieFromStream(
                new ByteArrayInputStream(corpus.getBytes(StandardCharsets.UTF_8)), "de", false);

        int ichFreq = loaded.lookupFrequency("ich");
        int schreibenFreq = loaded.lookupFrequency("schreiben");
        assertTrue("Expected quantized frequency in [1,255], was " + ichFreq, ichFreq >= 1 && ichFreq <= 255);
        assertTrue("Expected quantized frequency in [1,255], was " + schreibenFreq,
                schreibenFreq >= 1 && schreibenFreq <= 255);
        assertTrue(ichFreq > schreibenFreq);

        NintypeInputToken leftSwipe = buildSwipeToken(0, "scre", 100L, 380L);
        NintypeInputToken rightSwipe = buildSwipeToken(1, "hibn", 160L, 460L);
        List<WordCandidate> results = decoder.decode(
                Arrays.asList(leftSwipe, rightSwipe), loaded, new NintypeBigramTable(), geometry, null);
        assertFalse(results.isEmpty());
        assertEquals("schreiben", results.get(0).word);
    }

    @Test
    public void streamPassDecodesInterleavedHandoverWithinAllowance() {
        // Right thumb swipes m->u->n (100..440ms), left thumb swipes e->s->e (190..380ms)
        // Right thumb finishes 'u' slightly around the time left starts 'e'; 1 cut (mu|n) decodes "müssen".
        NintypeInputToken rightSwipe = buildSwipeTokenWithContacts(1, 1, "mun", 100L, 440L);
        NintypeInputToken leftSwipe = buildSwipeTokenWithContacts(0, 0, "ese", 190L, 380L);

        List<WordCandidate> streamResults = decoder.streamPassOnly(
                Arrays.asList(rightSwipe, leftSwipe), trie, bigrams, geometry, null);
        assertFalse(streamResults.isEmpty());
        assertEquals("müssen", streamResults.get(0).word);

        // Also verify stream pass decodes interleaved swipe + double-handover taps ("nochmal": c, a + swipe "nohml")
        KeySpec cKey = geometry.keySpec('c');
        KeySpec aKey = geometry.keySpec('a');
        NintypeInputToken tapC = NintypeInputToken.tap(0, 0, 'c', cKey.centerX, cKey.centerY, 180L, 210L);
        NintypeInputToken tapA = NintypeInputToken.tap(0, 0, 'a', aKey.centerX, aKey.centerY, 320L, 350L);
        NintypeInputToken swipeNohml = buildSwipeTokenWithContacts(1, 1, "nohml", 100L, 440L);
        List<WordCandidate> mixedStream = decoder.streamPassOnly(
                Arrays.asList(swipeNohml, tapC, tapA), trie, bigrams, geometry, null);
        assertFalse(mixedStream.isEmpty());
        assertEquals("nochmal", mixedStream.get(0).word);
    }

    @Test
    public void runtimePointerTrackRejectsJitterAndDropsRestingFinger() {
        KeySpec kKey = geometry.keySpec('k');
        assertNotNull(kKey);
        float swipeThreshold = Math.max(22f, geometry.keyWidthPx * 0.24f);

        GboardNintypeRuntime.PointerTrack jitterTrack = new GboardNintypeRuntime.PointerTrack(
                0, kKey.centerX, kKey.centerY, 100L, 'k', true, 1, geometry);
        jitterTrack.overlapped = true;
        // Oscillate 6px back and forth: cumulative arc > swipeThreshold, net displacement ~6px
        for (int i = 1; i <= 10; i++) {
            float dx = (i % 2 == 0) ? 0f : 6f;
            jitterTrack.addPoint(kKey.centerX + dx, kKey.centerY, 100L + i * 50L, geometry);
        }
        assertFalse(jitterTrack.isSwipe(swipeThreshold));
        assertTrue(jitterTrack.isRestingFinger(swipeThreshold));
    }

    private void insertQuantized(String word, int rawCount, boolean isProperFlag, String lang) {
        int freqByte = NintypeTrie.freqByteFor(rawCount, 98_000L);
        NintypeDictionaryLoader.insertWordIntoTrie(trie, word, freqByte, isProperFlag, lang, false);
    }

    private NintypeInputToken buildSwipeTokenWithContacts(
            int pointerId,
            int streamId,
            String keySequence,
            long startTimeMs,
            long endTimeMs
    ) {
        NintypeInputToken base = buildSwipeToken(pointerId, keySequence, startTimeMs, endTimeMs);
        List<NintypeInputToken.KeyContact> contacts = new ArrayList<>(keySequence.length());
        long totalSpan = Math.max(1L, base.endTimeMs - startTimeMs);
        int n = keySequence.length();
        for (int i = 0; i < n; i++) {
            int code = NintypeAlphabet.charIndex(keySequence.charAt(i));
            if (code < 0) continue;
            long enter = startTimeMs + (totalSpan * i) / n;
            long exit = startTimeMs + (totalSpan * (i + 1)) / n;
            contacts.add(new NintypeInputToken.KeyContact(code, enter, exit));
        }
        return NintypeInputToken.swipe(
                pointerId,
                streamId,
                base.points,
                base.pathLengthPx,
                base.startTimeMs,
                base.endTimeMs,
                contacts,
                java.util.Collections.emptyList(),
                false,
                false
        );
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

    private NintypeInputToken buildTapToken(int pointerId, char ch, long startTimeMs, long endTimeMs) {
        KeySpec k = geometry.keySpec(ch);
        assertNotNull("Missing key: " + ch, k);
        return NintypeInputToken.tap(pointerId, ch, k.centerX, k.centerY, startTimeMs, endTimeMs);
    }

    @Test
    public void decodesRealGermanDictionaryFunktioniertAndUntersuchen() throws IOException {
        java.io.File wordlist = new java.io.File(
                "../../patches/src/main/resources/nintype-assets/de_wordlist.txt.gz");
        if (!wordlist.exists()) {
            wordlist = new java.io.File("patches/src/main/resources/nintype-assets/de_wordlist.txt.gz");
        }
        assertTrue("Expected de_wordlist.txt.gz at " + wordlist.getAbsolutePath(), wordlist.exists());

        NintypeTrie deTrie;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(wordlist)) {
            deTrie = NintypeDictionaryLoader.loadTrieFromGzipStream(fis, "de", true);
        }

        // Pattern A: "funktioniert" - Left thumb taps 'f', 'u'/'n' transitions, or dual-swipe
        // 1) Pure tap prefix 'f' + swipe "unktioniert" (single chord)
        List<NintypeInputToken> funkTapSwipe = Arrays.asList(
                buildTapToken(0, 'f', 100L, 140L),
                buildSwipeTokenWithContacts(1, 1, "unktioniert", 130L, 480L)
        );
        List<WordCandidate> r1 = decoder.decode(funkTapSwipe, deTrie, bigrams, geometry, null);
        assertFalse("r1 empty", r1.isEmpty());
        assertEquals("funktioniert (tap f + swipe unktioniert), got=" + r1, "funktioniert", r1.get(0).word);

        // 2) Tap 'f', tap 'u', dual-thumb "nkt" + "ioniert"
        List<NintypeInputToken> funkMulti = Arrays.asList(
                buildTapToken(0, 'f', 100L, 140L),
                buildTapToken(1, 'u', 150L, 190L),
                buildSwipeTokenWithContacts(0, 0, "nkt", 210L, 340L),
                buildSwipeTokenWithContacts(1, 1, "ioniert", 310L, 560L)
        );
        List<WordCandidate> r2 = decoder.decode(funkMulti, deTrie, bigrams, geometry, null);
        assertFalse("r2 empty", r2.isEmpty());
        assertEquals("funktioniert (multi), got=" + r2, "funktioniert", r2.get(0).word);

        // 3) Dual-swipe split around thumbs: "funkt" / "ioniert" or "fun" + "ktio" + "niert"
        List<NintypeInputToken> funkDual = Arrays.asList(
                buildSwipeTokenWithContacts(0, 0, "fun", 100L, 240L),
                buildSwipeTokenWithContacts(1, 1, "ktio", 200L, 360L),
                buildSwipeTokenWithContacts(0, 0, "niert", 330L, 520L)
        );
        List<WordCandidate> r3 = decoder.decode(funkDual, deTrie, bigrams, geometry, null);
        assertFalse("r3 empty", r3.isEmpty());
        assertEquals("funktioniert (dual), got=" + r3, "funktioniert", r3.get(0).word);

        // Pattern B: "untersuchen"
        // 1) Tap 'u' + tap 'n' + swipe "tersuchen"
        List<NintypeInputToken> untTapSwipe = Arrays.asList(
                buildTapToken(1, 'u', 100L, 140L),
                buildTapToken(0, 'n', 160L, 200L),
                buildSwipeTokenWithContacts(0, 0, "tersuchen", 230L, 560L)
        );
        List<WordCandidate> u1 = decoder.decode(untTapSwipe, deTrie, bigrams, geometry, null);
        assertFalse("u1 empty", u1.isEmpty());
        assertEquals("untersuchen (tap+swipe), got=" + u1, "untersuchen", u1.get(0).word);

        // 2) Dual-thumb: "unter" + "suchen"
        List<NintypeInputToken> untDual = Arrays.asList(
                buildSwipeTokenWithContacts(1, 1, "unter", 100L, 310L),
                buildSwipeTokenWithContacts(0, 0, "suchen", 270L, 510L)
        );
        List<WordCandidate> u2 = decoder.decode(untDual, deTrie, bigrams, geometry, null);
        assertFalse("u2 empty", u2.isEmpty());
        assertEquals("untersuchen (dual), got=" + u2, "untersuchen", u2.get(0).word);

        // 3) Near-miss prefix taps preserved (as when user aims near u/n before swiping):
        List<WordCandidate> nearMissF = decoder.decode(Arrays.asList(
                buildTapToken(0, 'f', 100L, 140L),
                buildTapToken(1, 'm', 160L, 200L),
                buildSwipeTokenWithContacts(0, 0, "ktio", 220L, 360L),
                buildSwipeTokenWithContacts(1, 1, "niert", 330L, 520L)
        ), deTrie, bigrams, geometry, null);
        assertFalse(nearMissF.isEmpty());
        assertEquals("funktioniert (fm near-miss prefix + swipe), got=" + nearMissF,
                "funktioniert", nearMissF.get(0).word);

        List<WordCandidate> fnSkipU = decoder.decode(Arrays.asList(
                buildTapToken(0, 'f', 100L, 140L),
                buildTapToken(1, 'n', 160L, 200L),
                buildSwipeTokenWithContacts(0, 0, "ktio", 220L, 360L),
                buildSwipeTokenWithContacts(1, 1, "niert", 330L, 520L)
        ), deTrie, bigrams, geometry, null);
        assertFalse(fnSkipU.isEmpty());
        assertEquals("funktioniert (fn prefix), got=" + wordsOf(fnSkipU),
                "funktioniert", fnSkipU.get(0).word);

        // Near-miss 'i' (left of u) + 'n' + "ter" + "suchen" -> "untersuchen"
        List<WordCandidate> nearMissIn = decoder.decode(Arrays.asList(
                buildTapToken(1, 'i', 100L, 140L),
                buildTapToken(0, 'n', 160L, 200L),
                buildSwipeTokenWithContacts(0, 0, "ter", 220L, 340L),
                buildSwipeTokenWithContacts(1, 1, "suchen", 300L, 520L)
        ), deTrie, bigrams, geometry, null);
        assertFalse(nearMissIn.isEmpty());
        assertEquals("untersuchen (in near-miss prefix + swipe), got=" + wordsOf(nearMissIn),
                "untersuchen", nearMissIn.get(0).word);

        // Near-miss 'u' + 'm' (right of n) + "ter" + "suchen" -> "untersuchen"
        List<WordCandidate> nearMissUm = decoder.decode(Arrays.asList(
                buildTapToken(1, 'u', 100L, 140L),
                buildTapToken(0, 'm', 160L, 200L),
                buildSwipeTokenWithContacts(0, 0, "ter", 220L, 340L),
                buildSwipeTokenWithContacts(1, 1, "suchen", 300L, 520L)
        ), deTrie, bigrams, geometry, null);
        assertFalse(nearMissUm.isEmpty());
        assertEquals("untersuchen (um near-miss prefix + swipe), got=" + wordsOf(nearMissUm),
                "untersuchen", nearMissUm.get(0).word);

        // Near-miss upper edge of 'j' (under 'u') + 'n' + "ter" + "suchen" -> "untersuchen"
        KeySpec uKey = geometry.keySpec('u');
        KeySpec jKey = geometry.keySpec('j');
        NintypeInputToken jNearU = NintypeInputToken.tap(
                1, 'j', (uKey.centerX + jKey.centerX) * 0.5f,
                jKey.centerY - (jKey.centerY - uKey.centerY) * 0.45f, 100L, 140L);
        List<WordCandidate> nearMissU = decoder.decode(Arrays.asList(
                jNearU,
                buildTapToken(0, 'n', 160L, 200L),
                buildSwipeTokenWithContacts(0, 0, "ter", 220L, 340L),
                buildSwipeTokenWithContacts(1, 1, "suchen", 300L, 520L)
        ), deTrie, bigrams, geometry, null);
        assertFalse(nearMissU.isEmpty());
        assertEquals("untersuchen (jn upper-edge near-miss prefix + swipe), got=" + wordsOf(nearMissU),
                "untersuchen", nearMissU.get(0).word);
    }

    private static List<String> wordsOf(List<WordCandidate> candidates) {
        List<String> out = new ArrayList<>(candidates.size());
        for (WordCandidate c : candidates) {
            out.add(c.word);
        }
        return out;
    }

    @Test
    public void passiveTapBufferPromotesTapPrefixAcrossLiftsAndRollsBackSlidingWindow() {
        GboardNintypeRuntime.PassiveTapBuffer buf = new GboardNintypeRuntime.PassiveTapBuffer();

        // Stroke 1: single tap 'f' -> Stock Gboard inserts "f"
        buf.recordStroke(
                Arrays.asList(buildTapToken(0, 'f', 100L, 130L)),
                false, false, false, "das ", 130L);
        assertEquals(1, buf.tokens.size());
        assertFalse(buf.hadMultiTouch);

        // Stroke 2: quick tap 'n' -> Stock Gboard now shows "das fn"
        buf.validateOnActionDown("das f", true, 220L);
        assertEquals(1, buf.tokens.size());
        buf.recordStroke(
                Arrays.asList(buildTapToken(1, 'n', 220L, 250L)),
                false, false, false, "das f", 250L);
        assertEquals(2, buf.tokens.size());

        // Stroke 3: Nintype swipe arrives within gap; baseline restores to "das " and deletes "fn"
        buf.validateOnActionDown("das fn", true, 340L);
        assertEquals(2, buf.tokens.size());
        List<NintypeInputToken> dest = new ArrayList<>();
        String restored = buf.promoteInto(dest, "das fn");
        assertEquals("das ", restored);
        assertEquals(2, dest.size());
        assertTrue(buf.tokens.isEmpty());
        assertEquals(2, GboardNintypeRuntime.computeExtraCharsAfterBaseline("das ", "das fn"));

        // Rollover multi-touch tap prefix enables subsequent swipe promotion
        buf.recordStroke(
                Arrays.asList(
                        buildTapToken(1, 'u', 100L, 140L),
                        buildTapToken(0, 'n', 120L, 160L)),
                false, false, true, "ich ", 160L);
        assertTrue(buf.canPromoteMultiTouchSwipe(
                Arrays.asList(buildSwipeToken(0, "tersuchen", 240L, 500L))));

        // Space, non-letter (Backspace), 1-thumb swipe, or >750ms pause clears buffer (Stock Gboard untouched)
        buf.validateOnActionDown("ich in ", false, 200L);
        assertTrue(buf.tokens.isEmpty());

        buf.recordStroke(
                Arrays.asList(buildTapToken(0, 'u', 100L, 130L)),
                false, true, false, "ich ", 130L);
        assertTrue(buf.tokens.isEmpty());

        buf.recordStroke(
                Arrays.asList(buildSwipeToken(0, "und", 100L, 250L)),
                true, false, false, "ich ", 250L);
        assertTrue(buf.tokens.isEmpty());

        buf.recordStroke(
                Arrays.asList(buildTapToken(0, 'f', 100L, 130L)),
                false, false, false, "ich ", 130L);
        buf.validateOnActionDown("ich f", true, 950L);
        assertTrue(buf.tokens.isEmpty());

        // Sliding-window >=256-char rollback detection
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            sb.append((char) ('a' + (i % 20)));
        }
        String longBaseline = sb.toString();
        String shiftedAfterFn = longBaseline.substring(2) + "fn";
        assertEquals(2, GboardNintypeRuntime.computeExtraCharsAfterBaseline(longBaseline, shiftedAfterFn));

        StringBuilder repeated = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            repeated.append('a');
        }
        assertEquals(0, GboardNintypeRuntime.computeExtraCharsAfterBaseline(
                repeated.toString(), repeated.toString()));
    }

    @Test
    public void traceLoggerEmitsGeometryOnceAndSerializesRawTouchPointsAndCandidates() {
        List<String> lines = new ArrayList<>();
        GboardNintypeTraceLogger.setTraceSinkForTesting((ctx, jsonLine) -> lines.add(jsonLine));
        try {
            NintypeInputToken tapH = buildTapToken(0, 'h', 1000L, 1040L);
            NintypeInputToken swipeEute = buildSwipeTokenWithContacts(1, 1, "eute", 1030L, 1250L);
            List<NintypeInputToken> tokens = Arrays.asList(tapH, swipeEute);
            List<WordCandidate> candidates = decoder.decode(tokens, trie, bigrams, geometry, "wir");
            assertFalse(candidates.isEmpty());

            GboardNintypeTraceLogger.logWordDecode(
                    null, geometry, true, 1080, 680, "de",
                    tokens, "heute", false, "wir", "wir ", 1, 2, 1, candidates);
            GboardNintypeTraceLogger.logSkippedGesture(
                    null, geometry, true, 1080, 680, "de",
                    "rollover_no_swipe", false, true, 2, 0, "ich ", Arrays.asList(tapH));
            GboardNintypeTraceLogger.logCommit(null, "heute", true, "wir");

            // Expected order: 1x geom (only once for same geometry), 1x word, 1x pass, 1x commit
            assertEquals(4, lines.size());
            String geomLine = lines.get(0);
            assertTrue(geomLine.contains("\"type\":\"geom\""));
            assertTrue(geomLine.contains("\"midX\":"));
            assertTrue(geomLine.contains("\"keys\":{"));

            String wordLine = lines.get(1);
            assertTrue(wordLine.contains("\"type\":\"word\""));
            assertTrue(wordLine.contains("\"best\":\"heute\""));
            assertTrue(wordLine.contains("\"passiveCount\":1"));
            assertTrue(wordLine.contains("\"rollback\":2"));
            assertTrue(wordLine.contains("\"restingDropped\":1"));
            assertTrue(wordLine.contains("\"kind\":\"TAP\""));
            assertTrue(wordLine.contains("\"kind\":\"SWIPE\""));
            assertTrue(wordLine.contains("\"pts\":["));
            assertTrue(wordLine.contains("\"contacts\":["));

            String passLine = lines.get(2);
            assertTrue(passLine.contains("\"type\":\"pass\""));
            assertTrue(passLine.contains("\"reason\":\"rollover_no_swipe\""));

            String commitLine = lines.get(3);
            assertTrue(commitLine.contains("\"type\":\"commit\""));
            assertTrue(commitLine.contains("\"word\":\"heute\""));
        } finally {
            GboardNintypeTraceLogger.setTraceSinkForTesting(null);
        }
    }
}
