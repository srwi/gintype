package dev.srwi.gboardmultiswipe.extension.multiswipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeGeometry.KeySpec;
import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeInputToken.WordCandidate;
import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeWordSession.DecodePort;
import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeWordSession.EditorAdapter;
import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeWordSession.LifecycleCallbacks;
import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeWordSession.SettingsSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure-JVM behavioral test suite verifying MultiSwipe's Gboard integration specification
 * (MS-01 through MS-19) in {@code docs/multiswipe-integration.md}.
 */
public class MultiSwipeIntegrationTest {

    private MultiSwipeWordSession session;
    private FakeEditor editor;
    private RecordingCallbacks callbacks;

    // Coordinates derived from standard QWERTZ geometry (1080x800 -> keyW=108, rowH=200)
    private float letterAX;
    private float letterAY;
    private float letterSX;
    private float letterSY;
    private float letterLX;
    private float letterLY;
    private float spaceX;
    private float spaceY;
    private float backspaceX;
    private float backspaceY;
    private float shiftX;
    private float shiftY;
    private float periodX;
    private float periodY;

    @Before
    public void setUp() {
        session = new MultiSwipeWordSession();
        MultiSwipeGeometry base = MultiSwipeGeometry.createStandardLayout(1080f, 800f, true);
        List<KeySpec> specs = new ArrayList<>(base.allKeysSnapshot());
        // Add explicit non-letter keys along row 2 & bottom row (row 3: y=700)
        specs.add(new KeySpec('\0', 54f, 500f, 108f, 200f));    // Shift (left of y)
        specs.add(new KeySpec('\0', 1026f, 500f, 108f, 200f));  // Backspace (right of m)
        specs.add(new KeySpec('.', 810f, 700f, 108f, 200f));    // Period
        specs.add(new KeySpec('\0', 540f, 700f, 432f, 200f));   // Space (>= 2.5 * 108)
        session.geometry.setKeys(specs, base.keyWidthPx, base.keyHeightPx, false);

        KeySpec a = key('a');
        KeySpec s = key('s');
        KeySpec l = key('l');
        letterAX = a.centerX;
        letterAY = a.centerY;
        letterSX = s.centerX;
        letterSY = s.centerY;
        letterLX = l.centerX;
        letterLY = l.centerY;
        shiftX = 54f;
        shiftY = 500f;
        backspaceX = 1026f;
        backspaceY = 500f;
        periodX = 810f;
        periodY = 700f;
        spaceX = 540f;
        spaceY = 700f;

        editor = new FakeEditor();
        callbacks = new RecordingCallbacks();
    }

    private KeySpec key(char c) {
        KeySpec k = session.geometry.nearestLetterKey(0, 0);
        for (KeySpec candidate : session.geometry.allKeysSnapshot()) {
            if (candidate.character == c) {
                return candidate;
            }
        }
        return k;
    }

    @Test
    public void disabledFeatureBypassesAllHooksAndWordSession() {
        session.sawMultiTouch = true;
        session.multiswipeTakeoverActive = true;
        session.multiswipeWordActive = true;

        assertFalse(session.shouldStartGesture(false, false, false));
        assertTrue(session.shouldStartGesture(false, true, false));
        assertFalse(session.resolveLetterStarted(false, false));
        assertTrue(session.resolveLetterStarted(false, true));
        assertFalse(session.shouldReceiveEventsDuringOtherOwner(false));
        assertFalse(session.shouldSuppressBasicHandler(false, false));
        assertFalse(session.shouldSuppressRolloverCommit(
                false, true, true, 100L, letterLX, letterLY, true, letterAX, letterAY));
    }

    @Test
    public void singleFingerSwipeAndSpaceStayOnStockGboard() {
        SettingsSnapshot settings = new SettingsSnapshot(true, true, 300, 150);
        DecodePort neverCalled = (tokens, prevWord) -> {
            throw new AssertionError("Decoder must not run on single-finger swipe");
        };

        // Single-finger swipe across a -> s -> l
        session.onActionDown(0, letterAX, letterAY, 1000L, settings, editor, callbacks);
        session.activePointers.get(0).addPoint(letterSX, letterSY, 1020L, session.geometry);
        session.activePointers.get(0).addPoint(letterLX, letterLY, 1050L, session.geometry);
        session.onActionUp(1060L, settings, editor, neverCalled, callbacks);

        assertFalse(session.shouldInterceptGestureDispatch());
        assertFalse(session.multiswipeTakeoverActive);
        assertFalse(session.multiswipeWordActive);
        assertEquals(Boolean.FALSE, callbacks.lastTrailAccent);
        assertEquals("single_touch_swipe", callbacks.lastSkippedReason);

        // Space tap stays on stock Gboard
        editor.stockType("als ");
        session.onActionDown(0, spaceX, spaceY, 1300L, settings, editor, callbacks);
        session.onActionUp(1340L, settings, editor, neverCalled, callbacks);
        assertFalse(session.shouldInterceptGestureDispatch());
    }

    @Test
    public void rolloverMultiTouchWithoutSwipeStaysOnStockGboard() {
        SettingsSnapshot settings = new SettingsSnapshot(true, true, 300, 150);
        DecodePort neverCalled = (tokens, prevWord) -> {
            throw new AssertionError("Decoder must not run on tap-only rollover");
        };

        // Tap 0 on 'a', pointer 1 overlaps on 'l', both lift without exceeding swipe threshold
        session.onActionDown(0, letterAX, letterAY, 1000L, settings, editor, callbacks);
        session.onPointerDown(1, letterLX, letterLY, 1040L);
        session.onPointerUp(0, letterAX + 2f, letterAY, 1080L, true, callbacks);
        session.onActionUp(1110L, settings, editor, neverCalled, callbacks);

        assertFalse(session.shouldInterceptGestureDispatch());
        assertFalse(session.multiswipeWordActive);
        assertEquals("rollover_no_swipe", callbacks.lastSkippedReason);
    }

    @Test
    public void singleTouchCombineLeavesStockTextIntactWhenZeroCandidates() {
        SettingsSnapshot settings = new SettingsSnapshot(true, true, 300, 150);
        DecodePort emptyDecoder = (tokens, prevWord) -> Collections.emptyList();

        // Stroke 1: single-finger swipe "Hallo" -> stock commits "Hallo"
        session.onActionDown(0, letterAX, letterAY, 1000L, settings, editor, callbacks);
        session.activePointers.get(0).addPoint(letterLX, letterLY, 1040L, session.geometry);
        session.onActionUp(1050L, settings, editor, emptyDecoder, callbacks);
        editor.stockType("Hallo");

        // Stroke 2 within timeout: single-finger swipe yielding 0 candidates -> stock must remain untouched
        session.onActionDown(0, letterSX, letterSY, 1120L, settings, editor, callbacks);
        session.activePointers.get(0).addPoint(letterLX, letterLY, 1160L, session.geometry);
        session.onActionUp(1170L, settings, editor, emptyDecoder, callbacks);

        assertFalse(session.shouldInterceptGestureDispatch());
        assertFalse(session.multiswipeTakeoverActive);
        assertFalse(session.multiswipeWordActive);
        assertEquals("Hallo", editor.text());
    }

    @Test
    public void autoSpaceOffRetainsBestMatchAndTokensWhenAdditionalSwipeYieldsZeroCandidates() {
        SettingsSnapshot autoSpaceOff = new SettingsSnapshot(true, false, 300, 150);
        List<Integer> decodeTokenCounts = new ArrayList<>();
        DecodePort conditionalDecoder = (tokens, prevWord) -> {
            decodeTokenCounts.add(tokens.size());
            if (tokens.size() >= 3) {
                return Collections.singletonList(cand("Hallowelt", 1f));
            }
            return Collections.emptyList();
        };

        // 1) Stock 1-finger swipe "Hallo"
        session.onActionDown(0, letterAX, letterAY, 1000L, autoSpaceOff, editor, callbacks);
        session.activePointers.get(0).addPoint(letterLX, letterLY, 1040L, session.geometry);
        session.onActionUp(1050L, autoSpaceOff, editor, conditionalDecoder, callbacks);
        editor.stockType("Hallo");

        // 2) Second 1-finger swipe yields 0 candidates -> with Auto-Space OFF, MultiSwipe takes over,
        // suppresses stock dispatch (preventing "Hallo <word2>"), keeps "Hallo", and retains both tokens.
        session.onActionDown(0, letterSX, letterSY, 3000L, autoSpaceOff, editor, callbacks);
        session.activePointers.get(0).addPoint(letterLX, letterLY, 3050L, session.geometry);
        session.onActionUp(3060L, autoSpaceOff, editor, conditionalDecoder, callbacks);

        assertTrue(session.shouldInterceptGestureDispatch());
        assertTrue(session.multiswipeWordActive);
        assertEquals("Hallo", editor.text());
        assertEquals(2, session.wordTokens.size());
        assertEquals("Hallo", session.currentComposingWord);

        // 3) Third swipe appends onto the retained 2 tokens and resolves when candidates appear.
        session.onActionDown(0, letterAX, letterAY, 5000L, autoSpaceOff, editor, callbacks);
        session.activePointers.get(0).addPoint(letterSX, letterSY, 5050L, session.geometry);
        session.onActionUp(5060L, autoSpaceOff, editor, conditionalDecoder, callbacks);

        assertEquals(3, (int) decodeTokenCounts.get(decodeTokenCounts.size() - 1));
        assertEquals("Hallowelt", editor.text());

        // 4) Subsequent 4th swipe returning 0 candidates keeps "Hallowelt" and retains all 4 tokens.
        DecodePort emptyDecoder = (tokens, prevWord) -> Collections.emptyList();
        session.onActionDown(0, letterSX, letterSY, 7000L, autoSpaceOff, editor, callbacks);
        session.activePointers.get(0).addPoint(letterLX, letterLY, 7050L, session.geometry);
        session.onActionUp(7060L, autoSpaceOff, editor, emptyDecoder, callbacks);

        assertTrue(session.shouldInterceptGestureDispatch());
        assertEquals("Hallowelt", editor.text());
        assertEquals(4, session.wordTokens.size());
    }

    @Test
    public void pureTapsWithoutSwipeStayOnStockEvenWithAutoSpaceOff() {
        SettingsSnapshot autoSpaceOff = new SettingsSnapshot(true, false, 300, 150);
        DecodePort neverCalled = (tokens, prevWord) -> {
            throw new AssertionError("Pure tap sequence without swipes must not invoke Kinetica");
        };

        long ts = 1000L;
        char[] taps = new char[] { 'h', 'a', 'l', 'l', 'o' };
        for (char c : taps) {
            KeySpec k = key(c);
            session.onActionDown(0, k.centerX, k.centerY, ts, autoSpaceOff, editor, callbacks);
            session.onActionUp(ts + 30L, autoSpaceOff, editor, neverCalled, callbacks);
            editor.stockType(String.valueOf(c));
            ts += 2000L; // Long pause > any timeout
        }

        assertFalse(session.shouldInterceptGestureDispatch());
        assertFalse(session.multiswipeWordActive);
        assertEquals("hallo", editor.text());
    }

    @Test
    public void motionHandlerHooksProtectMultiTouchAndKeepFreshActionDown() {
        // Fresh single tap: ACTION_DOWN is never suppressed on BasicMotionEventHandler
        assertFalse(session.shouldSuppressBasicHandler(true, true));
        assertFalse(session.shouldInterceptGestureDispatch());
        assertFalse(session.shouldInterceptGestureReset());

        // Simulating simultaneous two-thumb tap on letters within 220ms -> suppresses premature rollover commit
        SettingsSnapshot settings = new SettingsSnapshot(true, true, 300, 150);
        session.onActionDown(0, letterAX, letterAY, 1000L, settings, editor, callbacks);
        assertTrue(session.shouldSuppressRolloverCommit(
                true, true, true, 1120L, letterLX, letterLY, true, letterAX, letterAY));
        // Outside 220ms window or on non-letter key -> do not suppress rollover commit
        assertFalse(session.shouldSuppressRolloverCommit(
                true, true, true, 1300L, letterLX, letterLY, true, letterAX, letterAY));
        assertFalse(session.shouldSuppressRolloverCommit(
                true, true, true, 1120L, spaceX, spaceY, true, letterAX, letterAY));

        // Once finger 1 joins and swipes: hooks activate takeover, protect B(), block dispatch & BasicHandler moves
        session.onPointerDown(1, letterLX, letterLY, 1050L);
        assertTrue(session.resolveLetterStarted(true, false));
        assertTrue(session.shouldReceiveEventsDuringOtherOwner(true));
        session.activePointers.get(1).addPoint(letterSX, letterSY, 1080L, session.geometry);
        assertTrue(session.shouldStartGesture(true, false, false));
        assertTrue(session.shouldInterceptGestureDispatch());
        assertTrue(session.shouldInterceptGestureReset());
        assertTrue(session.shouldSuppressBasicHandler(true, false));
        // Fresh ACTION_DOWN remains unsuppressed even while takeover flag was set prior to reset
        assertFalse(session.shouldSuppressBasicHandler(true, true));
    }

    @Test
    public void autoSpaceOffCombinesPassiveTapsAndSwipeAcrossLongPauses() {
        SettingsSnapshot autoSpaceOff = new SettingsSnapshot(true, false, 300, 150);
        List<Integer> decodedTokenCounts = new ArrayList<>();
        DecodePort decoder = (tokens, prevWord) -> {
            decodedTokenCounts.add(tokens.size());
            return Collections.singletonList(cand("hallo", 1f));
        };

        // Tap 'h' and 'a' separately with a 2500ms pause (well above MAX_PASSIVE_TAP_GAP_MS=750ms)
        KeySpec h = key('h');
        KeySpec a = key('a');
        session.onActionDown(0, h.centerX, h.centerY, 1000L, autoSpaceOff, editor, callbacks);
        session.onActionUp(1030L, autoSpaceOff, editor, decoder, callbacks);
        editor.stockType("h");

        session.onActionDown(0, a.centerX, a.centerY, 3530L, autoSpaceOff, editor, callbacks);
        session.onActionUp(3560L, autoSpaceOff, editor, decoder, callbacks);
        editor.stockType("a");

        assertTrue(decodedTokenCounts.isEmpty());
        assertEquals("ha", editor.text());

        // Swipe 'llo' with 2 fingers 4 seconds later -> rolls back stock "ha" and decodes full word (4 tokens)
        session.onActionDown(0, letterLX, letterLY, 7560L, autoSpaceOff, editor, callbacks);
        session.onPointerDown(1, letterSX, letterSY, 7580L);
        session.activePointers.get(0).addPoint(key('o').centerX, key('o').centerY, 7620L, session.geometry);
        session.onPointerUp(0, key('o').centerX, key('o').centerY, 7630L, true, callbacks);
        session.onActionUp(7650L, autoSpaceOff, editor, decoder, callbacks);

        assertEquals(1, decodedTokenCounts.size());
        assertEquals(4, (int) decodedTokenCounts.get(0));
        assertEquals("Hallo", editor.text()); // Start of field -> capitalized
        assertFalse(session.autospaceTimerPending);
    }

    @Test
    public void autoSpaceOffCombinesSequentialStrokesAcrossLongPausesUntilSpaceOrNavigation() {
        // Even with combineDelayMs=0 stored, Auto-Space OFF ignores timeouts and combines until Space/Nav.
        SettingsSnapshot autoSpaceOff = new SettingsSnapshot(true, false, 300, 0);
        List<Integer> tokenCounts = new ArrayList<>();
        DecodePort decoder = (tokens, prevWord) -> {
            tokenCounts.add(tokens.size());
            return Collections.singletonList(cand("hallowelt", 1f));
        };

        // 1) First 1-finger swipe -> handled by stock Gboard ("Hallo")
        session.onActionDown(0, key('h').centerX, key('h').centerY, 1000L, autoSpaceOff, editor, callbacks);
        session.activePointers.get(0).addPoint(key('o').centerX, key('o').centerY, 1080L, session.geometry);
        session.onActionUp(1090L, autoSpaceOff, editor, decoder, callbacks);
        assertFalse(session.shouldInterceptGestureDispatch());
        editor.stockType("Hallo");

        // 2) Wait 5 seconds (long pause), then 2nd 1-finger swipe -> combines via Kinetica
        session.onActionDown(0, key('w').centerX, key('w').centerY, 6100L, autoSpaceOff, editor, callbacks);
        session.activePointers.get(0).addPoint(key('t').centerX, key('t').centerY, 6160L, session.geometry);
        session.onActionUp(6180L, autoSpaceOff, editor, decoder, callbacks);

        assertTrue(session.shouldInterceptGestureDispatch());
        assertEquals(1, tokenCounts.size());
        assertEquals(2, (int) tokenCounts.get(0));
        assertEquals("Hallowelt", editor.text());
        assertFalse(session.autospaceTimerPending);

        // 3) Pressing Space ends the word boundary; subsequent 1-finger swipe returns to stock Gboard!
        session.onActionDown(0, spaceX, spaceY, 7000L, autoSpaceOff, editor, callbacks);
        session.onActionUp(7030L, autoSpaceOff, editor, decoder, callbacks);
        editor.finishComposingAndCommitSpace();

        tokenCounts.clear();
        session.onActionDown(0, key('n').centerX, key('n').centerY, 7200L, autoSpaceOff, editor, callbacks);
        session.activePointers.get(0).addPoint(key('u').centerX, key('u').centerY, 7260L, session.geometry);
        session.onActionUp(7280L, autoSpaceOff, editor, decoder, callbacks);
        assertFalse(session.shouldInterceptGestureDispatch());
        assertTrue(tokenCounts.isEmpty());

        // 4) Cursor navigation also clears combination state when Auto-Space is OFF
        editor.simulateCursorMoveTo("Moved here");
        session.onActionDown(0, key('w').centerX, key('w').centerY, 9000L, autoSpaceOff, editor, callbacks);
        session.activePointers.get(0).addPoint(key('t').centerX, key('t').centerY, 9050L, session.geometry);
        session.onActionUp(9070L, autoSpaceOff, editor, decoder, callbacks);
        assertFalse(session.shouldInterceptGestureDispatch());
    }

    @Test
    public void wordContinuationRespectsDelayWhenAutoSpaceOnAndHasNoTimeoutWhenOff() {
        DecodePort decoder = (tokens, prevWord) -> Collections.singletonList(
                cand("w" + tokens.size(), 1f));

        // Auto-Space ON (delay 200ms -> cutoff 280ms): second stroke after 600ms starts a new word
        SettingsSnapshot onSettings = new SettingsSnapshot(true, true, 200, 150);
        performTwoFingerSwipe(1000L, onSettings, decoder);
        assertTrue(session.multiswipeWordActive);
        assertTrue(session.autospaceTimerPending);
        session.finalizeAutospace(true, editor, callbacks); // simulates timer expiry at 1200ms
        assertFalse(session.multiswipeWordActive);
        assertEquals("W2 ", editor.text());

        // Without timer expiry, gap > 200 + 80ms also breaks continuation when Auto-Space ON
        editor.reset();
        performTwoFingerSwipe(2000L, onSettings, decoder);
        session.autospaceTimerPending = false; // timer already cleared or not pending
        boolean continuedAfterExpiry = session.onActionDown(
                0, letterAX, letterAY, 2500L, onSettings, editor, callbacks);
        assertFalse(continuedAfterExpiry);

        // Auto-Space OFF: continuation stays open across a 10-second pause until explicit boundary
        editor.reset();
        SettingsSnapshot offSettings = new SettingsSnapshot(true, false, 200, 150);
        performTwoFingerSwipe(1000L, offSettings, decoder);
        assertTrue(session.multiswipeWordActive);
        boolean continuedLongPause = session.onActionDown(
                0, letterLX, letterLY, 11_000L, offSettings, editor, callbacks);
        assertTrue(continuedLongPause);
        session.activePointers.get(0).addPoint(letterAX, letterAY, 11_050L, session.geometry);
        session.onActionUp(11_060L, offSettings, editor, decoder, callbacks);
        assertEquals("W3", editor.text());

        // Tapping outside letter area breaks continuation
        boolean continuedOnSpace = session.onActionDown(
                0, spaceX, spaceY, 11_200L, offSettings, editor, callbacks);
        assertFalse(continuedOnSpace);
    }

    @Test
    public void prependsLeadingSpaceAfterStockWordWhenAutoSpaceOn() {
        SettingsSnapshot onSettings = new SettingsSnapshot(true, true, 300, 150);
        editor.stockType("es");
        DecodePort decoder = (tokens, prevWord) ->
                Collections.singletonList(cand("dir", 1f));

        performTwoFingerSwipe(1000L, onSettings, decoder);
        assertEquals("es dir", editor.text());

        // Auto-Space OFF: does NOT auto-insert a leading space
        editor.reset();
        editor.stockType("es");
        SettingsSnapshot offSettings = new SettingsSnapshot(true, false, 300, 150);
        performTwoFingerSwipe(2000L, offSettings, decoder);
        assertEquals("esdir", editor.text());
    }

    @Test
    public void autospaceCommitsSpaceAndRetractsBeforeHuggingPunctuation() {
        SettingsSnapshot onSettings = new SettingsSnapshot(true, true, 300, 150);
        DecodePort decoder = (tokens, prevWord) ->
                Collections.singletonList(cand("hallo", 1f));

        performTwoFingerSwipe(1000L, onSettings, decoder);
        assertTrue(session.autospaceTimerPending);
        assertTrue(callbacks.autospaceScheduled);

        session.finalizeAutospace(true, editor, callbacks);
        assertEquals("Hallo ", editor.text());
        assertTrue(session.autospaceInserted);

        // Next touch hits hugging punctuation '.' -> retracts trailing space!
        session.onActionDown(0, periodX, periodY, 1500L, onSettings, editor, callbacks);
        assertFalse(session.autospaceInserted);
        assertEquals("Hallo", editor.text());
    }

    @Test
    public void backspaceCompoundAttachmentWorksInShortAndLongTextsAndIgnoresCursorMoves() {
        SettingsSnapshot onSettings = new SettingsSnapshot(true, true, 300, 150);
        DecodePort seitenDecoder = (tokens, prevWord) ->
                Collections.singletonList(cand("Seiten", 1f));
        DecodePort leisteDecoder = (tokens, prevWord) ->
                Collections.singletonList(cand("Leiste", 1f));

        // 1) Short text: swipe "Seiten" -> autospace -> Backspace -> Shift -> MultiSwipe "Leiste" = "Seitenleiste"
        performTwoFingerSwipe(1000L, onSettings, seitenDecoder);
        session.finalizeAutospace(true, editor, callbacks);
        assertEquals("Seiten ", editor.text());

        session.onActionDown(0, backspaceX, backspaceY, 1400L, onSettings, editor, callbacks);
        session.onActionUp(1430L, onSettings, editor, seitenDecoder, callbacks);
        editor.simulateBackspace();
        assertEquals("Seiten", editor.text());

        // Optional Shift tap between Backspace and next word keeps Backspace attachment armed
        session.onActionDown(0, shiftX, shiftY, 1460L, onSettings, editor, callbacks);
        session.onActionUp(1490L, onSettings, editor, seitenDecoder, callbacks);

        performTwoFingerSwipe(1550L, onSettings, leisteDecoder);
        assertEquals("Seitenleiste", editor.text());
        assertEquals("leiste", callbacks.lastAlternatives.get(0));

        // 2) Long text (>= 256 chars sliding window): Backspace shrinks within fixed 256-char window
        StringBuilder longPrefix = new StringBuilder();
        for (int i = 0; i < 270; i++) {
            longPrefix.append((char) ('a' + (i % 26)));
        }
        String prefix = longPrefix.toString();
        editor.simulateCursorMoveTo(prefix + "Seiten ");
        // Tap Backspace once while snapshot is saturated at 256 chars
        session.onActionDown(0, backspaceX, backspaceY, 2000L, onSettings, editor, callbacks);
        session.onActionUp(2030L, onSettings, editor, leisteDecoder, callbacks);
        editor.simulateBackspace();

        performTwoFingerSwipe(2100L, onSettings, leisteDecoder);
        assertEquals(prefix + "Seitenleiste", editor.text());

        // 3) Negative: cursor repositioning in textbox WITHOUT Backspace must NOT suppress space
        editor.reset();
        editor.stockType("Hallo ");
        // Cursor jumps after "Seiten" without Backspace key press
        editor.simulateCursorMoveTo("Hallo Seiten");
        performTwoFingerSwipe(3000L, onSettings, leisteDecoder);
        assertEquals("Hallo Seiten Leiste", editor.text());

        // 4) Negative: Spacebar cursor swipe over "Seiten " -> "Seiten" must NOT suppress space
        editor.reset();
        editor.stockType("Seiten ");
        session.onActionDown(0, spaceX, spaceY, 4000L, onSettings, editor, callbacks);
        session.onActionUp(4050L, onSettings, editor, leisteDecoder, callbacks);
        editor.simulateCursorMoveTo("Seiten");
        performTwoFingerSwipe(4100L, onSettings, leisteDecoder);
        assertEquals("Seiten Leiste", editor.text());

        // 5) Negative: Auto-Space OFF placed after existing word WITHOUT Backspace must NOT force lowercase
        editor.reset();
        editor.simulateCursorMoveTo("Seiten");
        SettingsSnapshot offSettings = new SettingsSnapshot(true, false, 300, 150);
        performTwoFingerSwipe(5000L, offSettings, leisteDecoder);
        assertEquals("SeitenLeiste", editor.text());
    }

    @Test
    public void capitalizesSentenceStartsAndNewlinesUnlessAttachingAfterBackspace() {
        assertTrue(MultiSwipeWordSession.shouldCapitalizeFirstLetter(""));
        assertTrue(MultiSwipeWordSession.shouldCapitalizeFirstLetter("Hallo. "));
        assertTrue(MultiSwipeWordSession.shouldCapitalizeFirstLetter("Erste Zeile\n"));
        assertTrue(MultiSwipeWordSession.shouldCapitalizeFirstLetter("Frage?  "));
        assertTrue(MultiSwipeWordSession.shouldCapitalizeFirstLetter("Achtung! "));
        assertFalse(MultiSwipeWordSession.shouldCapitalizeFirstLetter("Hallo "));

        assertEquals("Hallo", MultiSwipeWordSession.capitalizeFirstChar("hallo"));
        assertEquals("leiste", MultiSwipeWordSession.lowercaseFirstChar("Leiste"));
        assertFalse(MultiSwipeWordSession.isAttachingAfterDeletion("Seiten", true, true));
        assertFalse(MultiSwipeWordSession.isAttachingAfterDeletion("Seiten", false, false));
        assertTrue(MultiSwipeWordSession.isAttachingAfterDeletion("Seiten", false, true));
    }

    @Test
    public void notifiesThiccTopBarListenerOnPreviewAndCommit() {
        SettingsSnapshot onSettings = new SettingsSnapshot(true, true, 300, 150);
        DecodePort decoder = (tokens, prevWord) -> {
            List<WordCandidate> out = new ArrayList<>();
            out.add(cand("seite", 1f));
            out.add(cand("saite", 0.9f));
            return out;
        };

        performTwoFingerSwipe(1000L, onSettings, decoder);
        assertEquals(1, callbacks.decodedWords.size());
        assertEquals("Seite", callbacks.decodedWords.get(0));
        assertEquals(2, callbacks.lastAlternatives.size());
        assertEquals("Saite", callbacks.lastAlternatives.get(1));

        session.finalizeAutospace(true, editor, callbacks);
        assertEquals(1, callbacks.committedWords.size());
        assertEquals("Seite", callbacks.committedWords.get(0));
        assertTrue(callbacks.lastCommitAutoSpace);
    }

    @Test
    public void spacebarTimerIndicatorNotifiesBufferAndAutospaceTimers() {
        SettingsSnapshot passiveOnlySettings = new SettingsSnapshot(true, true, 300, 0);
        SettingsSnapshot onSettings = new SettingsSnapshot(true, true, 300, 150);
        SettingsSnapshot offSettings = new SettingsSnapshot(true, false, 300, 150);
        DecodePort decoder = (tokens, prevWord) -> Collections.singletonList(cand("Hallo", 10.0f));

        // 1. Single-touch tap (with combineDelay=0) starts passive tap prefix window (750 ms)
        session.onActionDown(0, letterAX, letterAY, 1000L, passiveOnlySettings, editor, callbacks);
        assertEquals(0L, callbacks.lastBufferTimerMs);
        session.onPointerUp(0, letterAX, letterAY, 1020L, false, callbacks);
        session.onActionUp(1020L, passiveOnlySettings, editor, decoder, callbacks);
        assertEquals(750L, callbacks.lastBufferTimerMs);

        // 2. Next ACTION_DOWN cancels active buffer timer; single-touch swipe starts combine window (150 ms)
        session.onActionDown(0, letterAX, letterAY, 1100L, onSettings, editor, callbacks);
        assertEquals(0L, callbacks.lastBufferTimerMs);
        session.activePointers.get(0).addPoint(letterSX, letterSY, 1130L, session.geometry);
        session.onPointerUp(0, letterSX, letterSY, 1150L, true, callbacks);
        session.onActionUp(1150L, onSettings, editor, decoder, callbacks);
        assertEquals(150L, callbacks.lastBufferTimerMs);

        // 3. MultiSwipe word schedules autospace timer and commits with autospaceInserted = true
        session.onActionCancel(callbacks);
        assertEquals(0L, callbacks.lastBufferTimerMs);
        performTwoFingerSwipe(2000L, onSettings, decoder);
        assertTrue(callbacks.autospaceScheduled);
        assertEquals(300L, callbacks.lastAutospaceDelayMs);
        session.finalizeAutospace(true, editor, callbacks);
        assertTrue(callbacks.lastCommitAutoSpace);

        // 4. With Auto-Space disabled, no buffer timer or autospace timer is started
        callbacks.autospaceScheduled = false;
        session.onActionCancel(callbacks);
        session.onActionDown(0, letterAX, letterAY, 3000L, offSettings, editor, callbacks);
        session.onPointerUp(0, letterAX, letterAY, 3020L, false, callbacks);
        session.onActionUp(3020L, offSettings, editor, decoder, callbacks);
        assertEquals(0L, callbacks.lastBufferTimerMs);
        performTwoFingerSwipe(3200L, offSettings, decoder);
        assertFalse(callbacks.autospaceScheduled);
    }

    @Test
    public void singleFingerStockSwipeAppendsAutospaceOnlyWhenAutoSpaceEnabled() {
        SettingsSnapshot onSettings = new SettingsSnapshot(true, true, 300, 150);
        SettingsSnapshot offSettings = new SettingsSnapshot(true, false, 300, 150);
        DecodePort decoder = (tokens, prevWord) -> Collections.emptyList();

        // 1. Pure tap does NOT schedule stock-swipe autospace
        editor.reset();
        session.onActionDown(0, letterAX, letterAY, 1000L, onSettings, editor, callbacks);
        editor.stockType("a");
        session.onPointerUp(0, letterAX, letterAY, 1020L, false, callbacks);
        session.onActionUp(1020L, onSettings, editor, decoder, callbacks);
        assertFalse(callbacks.stockSwipeAutospaceScheduled);
        session.finalizeStockSwipeAutospace(true, editor);
        assertEquals("a", editor.text());

        // 2. Single-finger swipe schedules stock-swipe autospace and appends " " + supports eatBefore
        editor.reset();
        session.onActionCancel(callbacks);
        session.onActionDown(0, letterAX, letterAY, 2000L, onSettings, editor, callbacks);
        session.activePointers.get(0).addPoint(letterSX, letterSY, 2030L, session.geometry);
        editor.stockType("Hallo");
        session.onPointerUp(0, letterSX, letterSY, 2050L, true, callbacks);
        session.onActionUp(2050L, onSettings, editor, decoder, callbacks);
        assertTrue(callbacks.stockSwipeAutospaceScheduled);
        assertEquals(150L, callbacks.lastStockSwipeAutospaceDelayMs);
        session.finalizeStockSwipeAutospace(true, editor);
        assertEquals("Hallo ", editor.text());
        assertTrue(session.autospaceInserted);

        // Punctuation eatBefore retracts the appended space
        session.onActionDown(0, periodX, periodY, 2250L, onSettings, editor, callbacks);
        assertEquals("Hallo", editor.text());

        // Spacebar after autospace preserves the trailing space (allowing multiple spaces)
        // and converts a fast double-tap into ". " only when Gboard's double-space period is enabled
        editor.reset();
        session.onActionCancel(callbacks);
        session.onActionDown(0, letterAX, letterAY, 2500L, onSettings, editor, callbacks);
        session.activePointers.get(0).addPoint(letterSX, letterSY, 2530L, session.geometry);
        editor.stockType("Hallo");
        session.onPointerUp(0, letterSX, letterSY, 2550L, true, callbacks);
        session.onActionUp(2550L, onSettings, editor, decoder, callbacks);
        session.finalizeStockSwipeAutospace(true, editor);
        assertEquals("Hallo ", editor.text());
        assertTrue(session.autospaceInserted);
        // 1st Space tap (simulating BasicMotionEventHandler claiming tap -> onActionCancel)
        // keeps existing space and adds another -> "Hallo  "
        session.onActionDown(0, spaceX, spaceY, 2750L, onSettings, editor, callbacks);
        session.onActionCancel(callbacks);
        editor.finishComposingAndCommitSpace();
        assertEquals("Hallo  ", editor.text());
        assertFalse(session.autospaceInserted);
        // Fast 2nd Space tap within 500ms replaces "  " with "." on ACTION_DOWN before Gboard commits " " -> "Hallo. "
        session.onActionDown(0, spaceX, spaceY, 2900L, onSettings, editor, callbacks);
        session.onActionCancel(callbacks);
        editor.finishComposingAndCommitSpace();
        assertEquals("Hallo. ", editor.text());

        // When Gboard's double-space-period setting is disabled, fast double-tap stays spaces
        SettingsSnapshot noDoublePeriodSettings = new SettingsSnapshot(true, true, 300, 150, false);
        editor.reset();
        session.onActionCancel(callbacks);
        session.onActionDown(0, letterAX, letterAY, 5000L, noDoublePeriodSettings, editor, callbacks);
        session.activePointers.get(0).addPoint(letterSX, letterSY, 5030L, session.geometry);
        editor.stockType("Hallo");
        session.onPointerUp(0, letterSX, letterSY, 5050L, true, callbacks);
        session.onActionUp(5050L, noDoublePeriodSettings, editor, decoder, callbacks);
        session.finalizeStockSwipeAutospace(true, editor);
        session.onActionDown(0, spaceX, spaceY, 5200L, noDoublePeriodSettings, editor, callbacks);
        session.onPointerUp(0, spaceX, spaceY, 5230L, true, callbacks);
        session.onActionUp(5230L, noDoublePeriodSettings, editor, decoder, callbacks);
        editor.finishComposingAndCommitSpace();
        session.onActionDown(0, spaceX, spaceY, 5350L, noDoublePeriodSettings, editor, callbacks);
        session.onPointerUp(0, spaceX, spaceY, 5380L, true, callbacks);
        session.onActionUp(5380L, noDoublePeriodSettings, editor, decoder, callbacks);
        editor.finishComposingAndCommitSpace();
        assertEquals("Hallo   ", editor.text());

        // 3. Guard: if stock Gboard already ended with space, do not append a second space
        editor.reset();
        session.onActionCancel(callbacks);
        session.onActionDown(0, letterAX, letterAY, 3000L, onSettings, editor, callbacks);
        session.activePointers.get(0).addPoint(letterSX, letterSY, 3030L, session.geometry);
        editor.stockType("Hallo ");
        session.onPointerUp(0, letterSX, letterSY, 3050L, true, callbacks);
        session.onActionUp(3050L, onSettings, editor, decoder, callbacks);
        session.finalizeStockSwipeAutospace(true, editor);
        assertEquals("Hallo ", editor.text());

        // 4. With Auto-Space disabled, single-finger swipe does NOT schedule or append space
        editor.reset();
        callbacks.stockSwipeAutospaceScheduled = false;
        session.onActionCancel(callbacks);
        session.onActionDown(0, letterAX, letterAY, 4000L, offSettings, editor, callbacks);
        session.activePointers.get(0).addPoint(letterSX, letterSY, 4030L, session.geometry);
        editor.stockType("Hallo");
        session.onPointerUp(0, letterSX, letterSY, 4050L, true, callbacks);
        session.onActionUp(4050L, offSettings, editor, decoder, callbacks);
        assertFalse(callbacks.stockSwipeAutospaceScheduled);
        session.finalizeStockSwipeAutospace(false, editor);
        assertEquals("Hallo", editor.text());
    }

    @Test
    public void colonAfterLayoutSwitchRetractsAutospace() {
        SettingsSnapshot onSettings = new SettingsSnapshot(true, true, 300, 150);
        DecodePort decoder = (tokens, prev) -> Collections.emptyList();

        // Swipe "Hallo" -> autospace appends " "
        session.onActionDown(0, letterAX, letterAY, 1000L, onSettings, editor, callbacks);
        session.activePointers.get(0).addPoint(letterSX, letterSY, 1030L, session.geometry);
        editor.stockType("Hallo");
        session.onPointerUp(0, letterSX, letterSY, 1050L, true, callbacks);
        session.onActionUp(1050L, onSettings, editor, decoder, callbacks);
        session.finalizeStockSwipeAutospace(true, editor);
        assertEquals("Hallo ", editor.text());
        assertTrue(session.autospaceInserted);

        // Tap layout-switch / modifier key (?123 at bottom-left, x=100, y=700) without changing editor text
        List<KeySpec> withSymbolsKey = new ArrayList<>(session.geometry.allKeysSnapshot());
        withSymbolsKey.add(new KeySpec('\0', 100f, 700f, 160f, 200f));
        session.geometry.setKeys(withSymbolsKey, session.geometry.keyWidthPx, session.geometry.keyHeightPx, false);
        session.onActionDown(0, 100f, 700f, 1200L, onSettings, editor, callbacks);
        session.onActionCancel(callbacks);
        assertTrue(session.autospaceInserted);

        // Symbols layout exposes ':' key -> tapping ':' retracts trailing space before ':'
        List<KeySpec> symbolSpecs = new ArrayList<>();
        symbolSpecs.add(new KeySpec(':', 702f, 500f, 108f, 200f));
        symbolSpecs.add(new KeySpec('\0', 540f, 700f, 432f, 200f));
        session.geometry.setKeys(symbolSpecs, session.geometry.keyWidthPx, session.geometry.keyHeightPx, false);
        session.onActionDown(0, 702f, 500f, 1350L, onSettings, editor, callbacks);
        assertEquals("Hallo", editor.text());
        assertFalse(session.autospaceInserted);
    }

    @Test
    public void basicHandlerActionDownRetractsAutospaceBeforeColonOnSymbolsLayout() {
        SettingsSnapshot onSettings = new SettingsSnapshot(true, true, 300, 150);
        DecodePort decoder = (tokens, prev) -> Collections.emptyList();

        session.onActionDown(0, letterAX, letterAY, 2000L, onSettings, editor, callbacks);
        session.activePointers.get(0).addPoint(letterSX, letterSY, 2030L, session.geometry);
        editor.stockType("Hallo");
        session.onPointerUp(0, letterSX, letterSY, 2050L, true, callbacks);
        session.onActionUp(2050L, onSettings, editor, decoder, callbacks);
        session.finalizeStockSwipeAutospace(true, editor);
        assertEquals("Hallo ", editor.text());

        // Control key (?123) preserves autospaceInserted
        session.onBasicHandlerActionDown('\0', editor);
        assertTrue(session.autospaceInserted);

        // Tapping ':' on symbols layout retracts the trailing space immediately before ':' is committed
        session.onBasicHandlerActionDown(':', editor);
        assertEquals("Hallo", editor.text());
        assertFalse(session.autospaceInserted);
    }

    private static WordCandidate cand(String word, float score) {
        return new WordCandidate(word, score, 0f, 0f, 0f);
    }

    private void performTwoFingerSwipe(
            long startMs,
            SettingsSnapshot settings,
            DecodePort decoder
    ) {
        session.onActionDown(0, letterAX, letterAY, startMs, settings, editor, callbacks);
        session.onPointerDown(1, letterLX, letterLY, startMs + 20L);
        session.activePointers.get(0).addPoint(letterSX, letterSY, startMs + 40L, session.geometry);
        session.onPointerUp(0, letterSX, letterSY, startMs + 50L, true, callbacks);
        session.onActionUp(startMs + 70L, settings, editor, decoder, callbacks);
    }

    private static final class FakeEditor implements EditorAdapter {
        private final StringBuilder buf = new StringBuilder();
        private int composingStart = -1;

        void reset() {
            buf.setLength(0);
            composingStart = -1;
        }

        void stockType(String s) {
            finishComposing();
            buf.append(s);
        }

        void finishComposingAndCommitSpace() {
            finishComposing();
            buf.append(' ');
        }

        void simulateBackspace() {
            composingStart = -1;
            if (buf.length() > 0) {
                buf.deleteCharAt(buf.length() - 1);
            }
        }

        void simulateCursorMoveTo(String textAtCursor) {
            buf.setLength(0);
            buf.append(textAtCursor);
            composingStart = -1;
        }

        private void finishComposing() {
            composingStart = -1;
        }

        String text() {
            return buf.toString();
        }

        @Override
        public String readTextBeforeCursorSnapshot() {
            int n = Math.min(MultiSwipeWordSession.TEXT_SNAPSHOT_CHARS, buf.length());
            return buf.substring(buf.length() - n);
        }

        @Override
        public String readCharBeforeCursor() {
            return buf.length() > 0 ? String.valueOf(buf.charAt(buf.length() - 1)) : "";
        }

        @Override
        public void deleteCharsBeforeCursor(int count) {
            int del = Math.min(count, buf.length());
            if (del > 0) {
                buf.delete(buf.length() - del, buf.length());
            }
            composingStart = -1;
        }

        @Override
        public int previewWord(String word, String baselineBeforeText, boolean prependLeadingSpace) {
            int extraChars = 0;
            if (baselineBeforeText != null) {
                extraChars = MultiSwipeWordSession.computeExtraCharsAfterBaseline(
                        baselineBeforeText, readTextBeforeCursorSnapshot());
                if (extraChars > 0) {
                    finishComposing();
                    deleteCharsBeforeCursor(extraChars);
                }
            }
            if (prependLeadingSpace) {
                finishComposing();
                buf.append(' ');
            }
            if (composingStart >= 0) {
                buf.replace(composingStart, buf.length(), word);
            } else {
                composingStart = buf.length();
                buf.append(word);
            }
            return extraChars;
        }

        @Override
        public void commitFinishedWord(String word, boolean appendSpace) {
            finishComposing();
            if (appendSpace) {
                buf.append(' ');
            }
        }

        @Override
        public void commitText(String text) {
            if (text != null && !text.isEmpty()) {
                buf.append(text);
            }
        }
    }

    private static final class RecordingCallbacks implements LifecycleCallbacks {
        Boolean lastTrailAccent;
        String lastSkippedReason;
        boolean autospaceScheduled;
        long lastAutospaceDelayMs;
        boolean stockSwipeAutospaceScheduled;
        long lastStockSwipeAutospaceDelayMs;
        long lastBufferTimerMs;
        final List<String> decodedWords = new ArrayList<>();
        List<String> lastAlternatives = Collections.emptyList();
        final List<String> committedWords = new ArrayList<>();
        boolean lastCommitAutoSpace;

        @Override public void onTrailAccentChanged(boolean active) { lastTrailAccent = active; }
        @Override public void onTakeoverActivatedOnFinish() {}
        @Override public void onPointerFinished(int pointerId, boolean isSwipe, boolean inMultiSwipeGesture) {}
        @Override public void onClearTrailManagerActive() {}
        @Override public void onScheduleAutospaceTimer(long delayMs) {
            autospaceScheduled = true;
            lastAutospaceDelayMs = delayMs;
        }
        @Override public void onCancelAutospaceTimer() { autospaceScheduled = false; }
        @Override public void onScheduleStockSwipeAutospaceTimer(long delayMs) {
            stockSwipeAutospaceScheduled = true;
            lastStockSwipeAutospaceDelayMs = delayMs;
        }
        @Override public void onCancelStockSwipeAutospaceTimer() { stockSwipeAutospaceScheduled = false; }
        @Override public void onBufferTimerUpdated(long durationMs) { lastBufferTimerMs = durationMs; }

        @Override
        public void onWordDecoded(
                String word,
                List<String> alternatives,
                List<MultiSwipeInputToken> tokens,
                boolean capitalizeFirst,
                String prevWord,
                String baseline,
                int promotedPassiveCount,
                int rollbackDeleted,
                int restingDropped,
                List<WordCandidate> candidates
        ) {
            if (word != null) {
                decodedWords.add(word);
                lastAlternatives = alternatives;
            }
        }

        @Override
        public void onWordCommitted(
                String word,
                boolean autospaceInserted,
                String prevWord,
                List<String> alternatives
        ) {
            committedWords.add(word);
            lastCommitAutoSpace = autospaceInserted;
        }

        @Override
        public void onSkippedGesture(
                String reason,
                boolean hasSwipe,
                boolean strokeHadMultiTouch,
                int passiveBufferSize,
                int restingDropped,
                String baseline,
                List<MultiSwipeInputToken> completedTokens
        ) {
            lastSkippedReason = reason;
        }
    }
}
