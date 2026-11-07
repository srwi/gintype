package dev.jason.gboardpatches.extension.matrixsuggestions;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class MatrixTextHelperTest {

    @Test
    public void boundaryCharDetection() {
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar(' '));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar('.'));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar(','));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar('!'));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar('?'));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar('\n'));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar('a'));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar('Z'));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar('9'));
    }

    @Test
    public void sentenceEndDetection() {
        // Dot, exclamation, and question marks do NOT end the sentence buffer (kept in topbar per UX requirements)
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded("Hallo."));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded("Hallo. "));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded("Hallo,"));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded("Hallo!"));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded("Hallo?  "));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded("Hallo"));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded("Hallo "));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded(""));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded((CharSequence) null));

        // Only newline (\n or \r) ends the sentence / paragraph
        Assert.assertTrue(MatrixTextHelper.isSentenceEnded("Hallo\n"));
        Assert.assertTrue(MatrixTextHelper.isSentenceEnded("Hallo\r"));
        Assert.assertTrue(MatrixTextHelper.isSentenceEnded("Hallo\n   "));
    }

    @Test
    public void punctuationSplittingTests() {
        Assert.assertEquals("Hallo", MatrixTextHelper.stripTrailingPunctuation("Hallo,"));
        Assert.assertEquals(",", MatrixTextHelper.getTrailingPunctuation("Hallo,"));

        Assert.assertEquals("dir", MatrixTextHelper.stripTrailingPunctuation("dir."));
        Assert.assertEquals(".", MatrixTextHelper.getTrailingPunctuation("dir."));

        Assert.assertEquals("toll", MatrixTextHelper.stripTrailingPunctuation("toll!"));
        Assert.assertEquals("!", MatrixTextHelper.getTrailingPunctuation("toll!"));

        Assert.assertEquals("wirklich", MatrixTextHelper.stripTrailingPunctuation("wirklich?!"));
        Assert.assertEquals("?!", MatrixTextHelper.getTrailingPunctuation("wirklich?!"));

        Assert.assertEquals("wort", MatrixTextHelper.stripTrailingPunctuation("wort..."));
        Assert.assertEquals("...", MatrixTextHelper.getTrailingPunctuation("wort..."));

        Assert.assertEquals("wort", MatrixTextHelper.stripTrailingPunctuation("wort"));
        Assert.assertEquals("", MatrixTextHelper.getTrailingPunctuation("wort"));
    }

    @Test
    public void activeWordResolution() {
        // 1. In-progress typing (no trailing whitespace)
        Assert.assertEquals("toggles", MatrixTextHelper.getActiveWord("toggles", Collections.emptyList()));
        Assert.assertEquals("Welt", MatrixTextHelper.getActiveWord("Hallo Welt", Collections.emptyList()));

        // 2. Swiped word with single auto-space
        Assert.assertEquals("toggles", MatrixTextHelper.getActiveWord("toggles ", Collections.emptyList()));
        Assert.assertEquals("Welt", MatrixTextHelper.getActiveWord("Hallo Welt ", Collections.emptyList()));

        // 3. Double space (suppressed active word, moving to next word)
        Assert.assertNull(MatrixTextHelper.getActiveWord("toggles  ", Collections.emptyList()));

        // 4. Prediction items (suppressed next-word suggestions per user UX requirement)
        GboardMatrixSuggestionsRuntime.CandidateItem pred1 = new GboardMatrixSuggestionsRuntime.CandidateItem(
                "und", 0, false, false, true, "PREDICTION", null, null);
        GboardMatrixSuggestionsRuntime.CandidateItem pred2 = new GboardMatrixSuggestionsRuntime.CandidateItem(
                "ist", 1, false, false, true, "PREDICTION", null, null);
        List<GboardMatrixSuggestionsRuntime.CandidateItem> predictionItems = Arrays.asList(pred1, pred2);

        Assert.assertNull(MatrixTextHelper.getActiveWord("toggles ", predictionItems));

        // 5. Swipe candidate alternatives (not all prediction -> active word preserved)
        GboardMatrixSuggestionsRuntime.CandidateItem alt1 = new GboardMatrixSuggestionsRuntime.CandidateItem(
                "toggles", 0, true, true, false, "GLIDE", null, null);
        List<GboardMatrixSuggestionsRuntime.CandidateItem> glideItems = Collections.singletonList(alt1);
        Assert.assertEquals("toggles", MatrixTextHelper.getActiveWord("toggles ", glideItems));

        // 6. When candidates are cleared (allowTrailingSpace = false, e.g. after swipe-delete or manual space):
        // Trailing space means word was committed, not currently composing
        Assert.assertNull(MatrixTextHelper.getActiveWord("toggles ", Collections.emptyList(), false));
        Assert.assertNull(MatrixTextHelper.getActiveWord("Hallo Welt ", Collections.emptyList(), false));
        Assert.assertEquals("toggles", MatrixTextHelper.getActiveWord("toggles", Collections.emptyList(), false));
    }

    @Test
    public void findHistoryWordPositionWithDuplicates() {
        // Text with three identical words: "toggles toggles toggles"
        String text = "toggles toggles toggles";
        MatrixSentenceBuffer buffer = new MatrixSentenceBuffer();
        buffer.addWord("toggles", Collections.singletonList("toggles"), 0);
        buffer.addWord("toggles", Collections.singletonList("toggles"), 0);
        buffer.addWord("toggles", Collections.singletonList("toggles"), 0);

        List<MatrixSentenceBuffer.WordEntry> entries = buffer.getEntries();
        Assert.assertEquals(3, entries.size());

        // Col 0 is the first "toggles" (index 0..7)
        int[] pos0 = MatrixTextHelper.findHistoryWordPosition(text, entries, 0, "toggles");
        Assert.assertNotNull(pos0);
        Assert.assertEquals(0, pos0[0]);
        Assert.assertEquals(7, pos0[1]);

        // Col 1 is the middle "toggles" (index 8..15)
        int[] pos1 = MatrixTextHelper.findHistoryWordPosition(text, entries, 1, "toggles");
        Assert.assertNotNull(pos1);
        Assert.assertEquals(8, pos1[0]);
        Assert.assertEquals(15, pos1[1]);

        // Col 2 is the last "toggles" (index 16..23)
        int[] pos2 = MatrixTextHelper.findHistoryWordPosition(text, entries, 2, "toggles");
        Assert.assertNotNull(pos2);
        Assert.assertEquals(16, pos2[0]);
        Assert.assertEquals(23, pos2[1]);
    }

    @Test
    public void findHistoryWordPositionInRealSentence() {
        String text = "Hallo wie geht es dir";
        MatrixSentenceBuffer buffer = new MatrixSentenceBuffer();
        buffer.addWord("Hallo", Collections.singletonList("Hallo"), 0);
        buffer.addWord("wie", Collections.singletonList("wie"), 0);
        buffer.addWord("geht", Collections.singletonList("geht"), 0);
        buffer.addWord("es", Collections.singletonList("es"), 0);
        buffer.addWord("dir", Collections.singletonList("dir"), 0);

        List<MatrixSentenceBuffer.WordEntry> entries = buffer.getEntries();

        // Check each word's exact position
        int[] posHallo = MatrixTextHelper.findHistoryWordPosition(text, entries, 0, "Hallo");
        Assert.assertNotNull(posHallo);
        Assert.assertEquals("Hallo", text.substring(posHallo[0], posHallo[1]));

        int[] posWie = MatrixTextHelper.findHistoryWordPosition(text, entries, 1, "wie");
        Assert.assertNotNull(posWie);
        Assert.assertEquals("wie", text.substring(posWie[0], posWie[1]));

        int[] posGeht = MatrixTextHelper.findHistoryWordPosition(text, entries, 2, "geht");
        Assert.assertNotNull(posGeht);
        Assert.assertEquals("geht", text.substring(posGeht[0], posGeht[1]));

        int[] posEs = MatrixTextHelper.findHistoryWordPosition(text, entries, 3, "es");
        Assert.assertNotNull(posEs);
        Assert.assertEquals("es", text.substring(posEs[0], posEs[1]));

        int[] posDir = MatrixTextHelper.findHistoryWordPosition(text, entries, 4, "dir");
        Assert.assertNotNull(posDir);
        Assert.assertEquals("dir", text.substring(posDir[0], posDir[1]));
    }

    @Test
    public void findHistoryWordPositionWithPunctuation() {
        String text = "Hallo, wie geht es dir.";
        MatrixSentenceBuffer buffer = new MatrixSentenceBuffer();
        buffer.addWord("Hallo,", Collections.singletonList("Hallo,"), 0);
        buffer.addWord("wie", Collections.singletonList("wie"), 0);
        buffer.addWord("geht", Collections.singletonList("geht"), 0);
        buffer.addWord("es", Collections.singletonList("es"), 0);
        buffer.addWord("dir.", Collections.singletonList("dir."), 0);

        List<MatrixSentenceBuffer.WordEntry> entries = buffer.getEntries();

        // Target Col 0: "Hallo," -> base word "Hallo" at [0, 5]
        int[] posHallo = MatrixTextHelper.findHistoryWordPosition(text, entries, 0, "Hallo,");
        Assert.assertNotNull(posHallo);
        Assert.assertEquals(0, posHallo[0]);
        Assert.assertEquals(5, posHallo[1]);
        Assert.assertEquals("Hallo", text.substring(posHallo[0], posHallo[1]));

        // Target Col 4: "dir." -> base word "dir" at [19, 22]
        int[] posDir = MatrixTextHelper.findHistoryWordPosition(text, entries, 4, "dir.");
        Assert.assertNotNull(posDir);
        Assert.assertEquals(19, posDir[0]);
        Assert.assertEquals(22, posDir[1]);
        Assert.assertEquals("dir", text.substring(posDir[0], posDir[1]));
    }

    @Test
    public void findLastWordBoundaryTests() {
        String text = "Das ist ein Test und noch ein Test";
        int[] pos = MatrixTextHelper.findLastWordBoundary(text, "Test");
        Assert.assertNotNull(pos);
        Assert.assertEquals(30, pos[0]);
        Assert.assertEquals(34, pos[1]);
        Assert.assertEquals("Test", text.substring(pos[0], pos[1]));

        int[] posFirst = MatrixTextHelper.findLastWordBoundary(text, "Das");
        Assert.assertNotNull(posFirst);
        Assert.assertEquals(0, posFirst[0]);
        Assert.assertEquals(3, posFirst[1]);

        Assert.assertNull(MatrixTextHelper.findLastWordBoundary(text, "NichtVorhanden"));
        Assert.assertNull(MatrixTextHelper.findLastWordBoundary(null, "Test"));
        Assert.assertNull(MatrixTextHelper.findLastWordBoundary(text, null));
    }
}
