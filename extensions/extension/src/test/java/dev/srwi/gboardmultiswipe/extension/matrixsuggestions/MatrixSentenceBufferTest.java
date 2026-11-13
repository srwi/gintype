package dev.srwi.gboardmultiswipe.extension.matrixsuggestions;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class MatrixSentenceBufferTest {

    @Test
    public void addsAndRetrievesWordsCorrectly() {
        MatrixSentenceBuffer buffer = new MatrixSentenceBuffer();
        Assert.assertTrue(buffer.isEmpty());

        List<String> alts1 = Arrays.asList("Hallo", "hallo", "Hellas");
        buffer.addWord("Hallo", alts1, 0);

        Assert.assertFalse(buffer.isEmpty());
        Assert.assertEquals(1, buffer.getEntries().size());

        MatrixSentenceBuffer.WordEntry entry = buffer.getEntries().get(0);
        Assert.assertEquals("Hallo", entry.committedWord);
        Assert.assertEquals(3, entry.alternatives.size());
        Assert.assertEquals(0, entry.selectedIndex);

        List<String> alts2 = Arrays.asList("wie", "wir", "wieder");
        buffer.addWord("wie", alts2, 0);
        Assert.assertEquals(2, buffer.getEntries().size());
        Assert.assertEquals("wie", buffer.getEntries().get(1).committedWord);
    }

    @Test
    public void clearEmptiesBuffer() {
        MatrixSentenceBuffer buffer = new MatrixSentenceBuffer();
        buffer.addWord("Test", Arrays.asList("Test", "Text"), 0);
        Assert.assertFalse(buffer.isEmpty());

        buffer.clear();
        Assert.assertTrue(buffer.isEmpty());
        Assert.assertEquals(0, buffer.getEntries().size());
    }

    @Test
    public void skipsBlankWords() {
        MatrixSentenceBuffer buffer = new MatrixSentenceBuffer();
        buffer.addWord("", Arrays.asList(""), 0);
        buffer.addWord("   ", Arrays.asList("   "), 0);
        buffer.addWord(null, null, 0);
        Assert.assertTrue(buffer.isEmpty());
    }

    @Test
    public void allowsConsecutiveDuplicateWords() {
        MatrixSentenceBuffer buffer = new MatrixSentenceBuffer();
        buffer.addWord("toggles", Arrays.asList("toggles"), 0);
        buffer.addWord("toggles", Arrays.asList("toggles"), 0);
        buffer.addWord("toggles", Arrays.asList("toggles"), 0);
        Assert.assertEquals(3, buffer.getEntries().size());
        Assert.assertEquals("toggles", buffer.getEntries().get(0).committedWord);
        Assert.assertEquals("toggles", buffer.getEntries().get(1).committedWord);
        Assert.assertEquals("toggles", buffer.getEntries().get(2).committedWord);
    }
}
