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
        Assert.assertTrue(MatrixTextHelper.isSentenceEnded("Hallo."));
        Assert.assertTrue(MatrixTextHelper.isSentenceEnded("Hallo. "));
        Assert.assertTrue(MatrixTextHelper.isSentenceEnded("Hallo!"));
        Assert.assertTrue(MatrixTextHelper.isSentenceEnded("Hallo?  "));
        Assert.assertTrue(MatrixTextHelper.isSentenceEnded("Hallo\n"));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded("Hallo"));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded("Hallo "));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded(""));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded((CharSequence) null));
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
}
