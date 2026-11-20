package dev.srwi.gboardmultiswipe.extension.multiswipe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeInputToken.WordCandidate;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SmartTapSwipeSplitJudgeTest {

    private static WordCandidate cand(String word, float score) {
        return new WordCandidate(word, score, 0f, 0f, 0f);
    }

    @Test
    public void oneLetterAllowlistRespectsLanguage() {
        assertTrue(SmartTapSwipeSplitJudge.canStandAlone("a", "en", 0));
        assertTrue(SmartTapSwipeSplitJudge.canStandAlone("I", "en-US", 0));
        assertFalse(SmartTapSwipeSplitJudge.canStandAlone("b", "en", 200));

        assertFalse(SmartTapSwipeSplitJudge.canStandAlone("a", "de", 200));
        assertFalse(SmartTapSwipeSplitJudge.canStandAlone("i", "de-DE", 200));

        assertTrue(SmartTapSwipeSplitJudge.canStandAlone("y", "es", 0));
        assertTrue(SmartTapSwipeSplitJudge.canStandAlone("é", "pt_BR", 0));
        assertTrue(SmartTapSwipeSplitJudge.canStandAlone("à", "fr", 0));
        assertTrue(SmartTapSwipeSplitJudge.canStandAlone("w", "pl", 0));
        assertTrue(SmartTapSwipeSplitJudge.canStandAlone("v", "cs", 0));
        assertTrue(SmartTapSwipeSplitJudge.canStandAlone("a", "hu", 0));
        assertTrue(SmartTapSwipeSplitJudge.canStandAlone("å", "nb", 0));
        assertTrue(SmartTapSwipeSplitJudge.canStandAlone("u", "nl", 0));
    }

    @Test
    public void contextFormCapitalizesEnglishPronounI() {
        assertEquals("I", SmartTapSwipeSplitJudge.contextForm("i", "en"));
        assertEquals("a", SmartTapSwipeSplitJudge.contextForm("a", "en"));
        assertEquals("i", SmartTapSwipeSplitJudge.contextForm("i", "it"));
    }

    @Test
    public void splitsShortGermanTapPrefixWhenCombinedFailsOrIsWeak() {
        List<WordCandidate> none = Collections.emptyList();
        List<WordCandidate> split = Collections.singletonList(cand("gut", 0.62f));

        assertTrue(SmartTapSwipeSplitJudge.shouldSplit(
                "so", "de", 140, 0.3f, none, split));
        assertFalse(SmartTapSwipeSplitJudge.shouldSplit(
                "xz", "de", 0, 0f, none, split));
        assertFalse(SmartTapSwipeSplitJudge.shouldSplit(
                "so", "de", 3, 0f, none, split));
    }

    @Test
    public void keepsRealCombinedWordsTogether() {
        // "so" + "fort" -> "sofort" (strong combined match)
        assertFalse(SmartTapSwipeSplitJudge.shouldSplit(
                "so",
                "de",
                140,
                0f,
                Collections.singletonList(cand("sofort", 0.55f)),
                Collections.singletonList(cand("fort", 0.58f))));

        // "a" + "bout" -> "about" (1-letter prefix requires high margin)
        assertFalse(SmartTapSwipeSplitJudge.shouldSplit(
                "a",
                "en",
                220,
                0f,
                Collections.singletonList(cand("about", 0.50f)),
                Collections.singletonList(cand("bout", 0.45f))));

        // "a" + "lot" -> "a lot" (no strong combined match)
        assertTrue(SmartTapSwipeSplitJudge.shouldSplit(
                "a",
                "en",
                220,
                0.2f,
                Collections.singletonList(cand("alot", 0.08f)),
                Collections.singletonList(cand("lot", 0.65f))));
    }

    @Test
    public void rejectsNonLetterOrOverlongPrefixes() {
        assertFalse(SmartTapSwipeSplitJudge.isEligibleTapPrefix(""));
        assertFalse(SmartTapSwipeSplitJudge.isEligibleTapPrefix("a1"));
        assertFalse(SmartTapSwipeSplitJudge.isEligibleTapPrefix("weil"));
        assertTrue(SmartTapSwipeSplitJudge.isEligibleTapPrefix("gut"));
    }
}
