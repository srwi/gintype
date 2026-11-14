package dev.srwi.gboardmultiswipe.extension.matrixsuggestions;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;

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

    @Test
    public void contextualBoundaryCharTests() {
        // Decimals and thousands separators between digits are NOT boundaries
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("3.14", 1));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("3,14", 1));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("1.500", 1));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("100,000", 3));

        // Dot or comma after a word or before space IS a boundary
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("Hallo.", 5));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("Hallo,", 5));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("Hallo. Welt", 5));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("Hallo, Welt", 5));

        // Contractions: apostrophe between letters is NOT a boundary
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("geht's", 4));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("don't", 3));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("it's", 2));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("c'est", 1));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("geht’s", 4)); // typographic apostrophe \u2019

        // Compound words: hyphen between letters/digits is NOT a boundary
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("E-Mail", 1));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("Wi-Fi", 2));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("Covid-19", 5));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("und/oder", 3));
        Assert.assertFalse(MatrixTextHelper.isBoundaryChar("my_var", 2));

        // Standalone hyphen / slash with spaces IS a boundary
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("Hallo - Welt", 6));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("a / b", 2));

        // Brackets, quotes, operators, currency, emojis are boundaries
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("(Hallo)", 0));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("(Hallo)", 6));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("[Wichtig]", 0));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("„Genial“", 0));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("50€", 2));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("$100", 0));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("1+2=3", 1));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("1+2=3", 3));
        Assert.assertTrue(MatrixTextHelper.isBoundaryChar("Super!😊", 5));
    }

    @Test
    public void tokenPartsParsingAllCases() {
        // 1. Plain words
        MatrixTextHelper.TokenParts p1 = MatrixTextHelper.parseTokenParts("Hallo");
        Assert.assertEquals("", p1.prefix);
        Assert.assertEquals("Hallo", p1.word);
        Assert.assertEquals("", p1.suffix);
        Assert.assertTrue(p1.hasWord());

        // 2. Trailing punctuation
        MatrixTextHelper.TokenParts p2 = MatrixTextHelper.parseTokenParts("Hallo,");
        Assert.assertEquals("", p2.prefix);
        Assert.assertEquals("Hallo", p2.word);
        Assert.assertEquals(",", p2.suffix);

        MatrixTextHelper.TokenParts p3 = MatrixTextHelper.parseTokenParts("dir.");
        Assert.assertEquals("", p3.prefix);
        Assert.assertEquals("dir", p3.word);
        Assert.assertEquals(".", p3.suffix);

        MatrixTextHelper.TokenParts p4 = MatrixTextHelper.parseTokenParts("toll!?!...");
        Assert.assertEquals("", p4.prefix);
        Assert.assertEquals("toll", p4.word);
        Assert.assertEquals("!?!...", p4.suffix);

        // 3. Brackets & Parentheses
        MatrixTextHelper.TokenParts p5 = MatrixTextHelper.parseTokenParts("(Hallo)");
        Assert.assertEquals("(", p5.prefix);
        Assert.assertEquals("Hallo", p5.word);
        Assert.assertEquals(")", p5.suffix);

        MatrixTextHelper.TokenParts p6 = MatrixTextHelper.parseTokenParts("[Wichtig]");
        Assert.assertEquals("[", p6.prefix);
        Assert.assertEquals("Wichtig", p6.word);
        Assert.assertEquals("]", p6.suffix);

        MatrixTextHelper.TokenParts p7 = MatrixTextHelper.parseTokenParts("{Config}");
        Assert.assertEquals("{", p7.prefix);
        Assert.assertEquals("Config", p7.word);
        Assert.assertEquals("}", p7.suffix);

        MatrixTextHelper.TokenParts p8 = MatrixTextHelper.parseTokenParts("<Tag>");
        Assert.assertEquals("<", p8.prefix);
        Assert.assertEquals("Tag", p8.word);
        Assert.assertEquals(">", p8.suffix);

        // 4. Quotation marks
        MatrixTextHelper.TokenParts p9 = MatrixTextHelper.parseTokenParts("„Genial!“");
        Assert.assertEquals("„", p9.prefix);
        Assert.assertEquals("Genial", p9.word);
        Assert.assertEquals("!“", p9.suffix);

        MatrixTextHelper.TokenParts p10 = MatrixTextHelper.parseTokenParts("\"Zitat\"");
        Assert.assertEquals("\"", p10.prefix);
        Assert.assertEquals("Zitat", p10.word);
        Assert.assertEquals("\"", p10.suffix);

        MatrixTextHelper.TokenParts p11 = MatrixTextHelper.parseTokenParts("«Französisch»");
        Assert.assertEquals("«", p11.prefix);
        Assert.assertEquals("Französisch", p11.word);
        Assert.assertEquals("»", p11.suffix);

        // 5. Contractions & Internal hyphens & Decimals
        MatrixTextHelper.TokenParts p12 = MatrixTextHelper.parseTokenParts("geht's");
        Assert.assertEquals("", p12.prefix);
        Assert.assertEquals("geht's", p12.word);
        Assert.assertEquals("", p12.suffix);

        MatrixTextHelper.TokenParts p13 = MatrixTextHelper.parseTokenParts("geht's?!");
        Assert.assertEquals("", p13.prefix);
        Assert.assertEquals("geht's", p13.word);
        Assert.assertEquals("?!", p13.suffix);

        MatrixTextHelper.TokenParts p14 = MatrixTextHelper.parseTokenParts("E-Mail");
        Assert.assertEquals("", p14.prefix);
        Assert.assertEquals("E-Mail", p14.word);
        Assert.assertEquals("", p14.suffix);

        MatrixTextHelper.TokenParts p15 = MatrixTextHelper.parseTokenParts("3,14");
        Assert.assertEquals("", p15.prefix);
        Assert.assertEquals("3,14", p15.word);
        Assert.assertEquals("", p15.suffix);

        MatrixTextHelper.TokenParts p16 = MatrixTextHelper.parseTokenParts("1.500");
        Assert.assertEquals("", p16.prefix);
        Assert.assertEquals("1.500", p16.word);
        Assert.assertEquals("", p16.suffix);

        // 6. Currency, Percentages, Hashtags, Mentions
        MatrixTextHelper.TokenParts p17 = MatrixTextHelper.parseTokenParts("$100");
        Assert.assertEquals("$", p17.prefix);
        Assert.assertEquals("100", p17.word);
        Assert.assertEquals("", p17.suffix);

        MatrixTextHelper.TokenParts p18 = MatrixTextHelper.parseTokenParts("50€");
        Assert.assertEquals("", p18.prefix);
        Assert.assertEquals("50", p18.word);
        Assert.assertEquals("€", p18.suffix);

        MatrixTextHelper.TokenParts p19 = MatrixTextHelper.parseTokenParts("100%");
        Assert.assertEquals("", p19.prefix);
        Assert.assertEquals("100", p19.word);
        Assert.assertEquals("%", p19.suffix);

        MatrixTextHelper.TokenParts p20 = MatrixTextHelper.parseTokenParts("#Gboard");
        Assert.assertEquals("#", p20.prefix);
        Assert.assertEquals("Gboard", p20.word);
        Assert.assertEquals("", p20.suffix);

        MatrixTextHelper.TokenParts p21 = MatrixTextHelper.parseTokenParts("@user");
        Assert.assertEquals("@", p21.prefix);
        Assert.assertEquals("user", p21.word);
        Assert.assertEquals("", p21.suffix);

        // 7. Emojis
        MatrixTextHelper.TokenParts p22 = MatrixTextHelper.parseTokenParts("Super!😊");
        Assert.assertEquals("", p22.prefix);
        Assert.assertEquals("Super", p22.word);
        Assert.assertEquals("!😊", p22.suffix);

        // 8. Standalone symbols (no word)
        MatrixTextHelper.TokenParts p23 = MatrixTextHelper.parseTokenParts("😊");
        Assert.assertFalse(p23.hasWord());

        MatrixTextHelper.TokenParts p24 = MatrixTextHelper.parseTokenParts("+");
        Assert.assertFalse(p24.hasWord());

        MatrixTextHelper.TokenParts p25 = MatrixTextHelper.parseTokenParts("...");
        Assert.assertFalse(p25.hasWord());
    }

    @Test
    public void sentenceTokenizationTests() {
        // Sentence with mixed brackets, quotes, currency, and emojis
        String s = "(Hallo) das ist ein \"Zitat\" mit $100 und 50€ Super!😊";
        List<MatrixTextHelper.TokenParts> tokens = MatrixTextHelper.tokenizeSentence(s);

        Assert.assertEquals(10, tokens.size());
        Assert.assertEquals("Hallo", tokens.get(0).word);
        Assert.assertEquals("(", tokens.get(0).prefix);
        Assert.assertEquals(")", tokens.get(0).suffix);

        Assert.assertEquals("das", tokens.get(1).word);
        Assert.assertEquals("ist", tokens.get(2).word);
        Assert.assertEquals("ein", tokens.get(3).word);

        Assert.assertEquals("Zitat", tokens.get(4).word);
        Assert.assertEquals("\"", tokens.get(4).prefix);
        Assert.assertEquals("\"", tokens.get(4).suffix);

        Assert.assertEquals("mit", tokens.get(5).word);

        Assert.assertEquals("100", tokens.get(6).word);
        Assert.assertEquals("$", tokens.get(6).prefix);

        Assert.assertEquals("und", tokens.get(7).word);

        Assert.assertEquals("50", tokens.get(8).word);
        Assert.assertEquals("€", tokens.get(8).suffix);

        Assert.assertEquals("Super", tokens.get(9).word);
        Assert.assertEquals("!😊", tokens.get(9).suffix);

        // Math operators test
        String math = "1 + 2 = 3";
        List<MatrixTextHelper.TokenParts> mathTokens = MatrixTextHelper.tokenizeSentence(math);
        Assert.assertEquals(5, mathTokens.size());
        Assert.assertTrue(mathTokens.get(0).hasWord()); // "1"
        Assert.assertFalse(mathTokens.get(1).hasWord()); // "+"
        Assert.assertTrue(mathTokens.get(2).hasWord()); // "2"
        Assert.assertFalse(mathTokens.get(3).hasWord()); // "="
        Assert.assertTrue(mathTokens.get(4).hasWord()); // "3"

        // Adjacent brackets without space: "(Hallo)(Welt)"
        List<MatrixTextHelper.TokenParts> adj = MatrixTextHelper.tokenizeSentence("(Hallo)(Welt)");
        Assert.assertEquals(2, adj.size());
        Assert.assertEquals("Hallo", adj.get(0).word);
        Assert.assertEquals("(", adj.get(0).prefix);
        Assert.assertEquals(")", adj.get(0).suffix);
        Assert.assertEquals("Welt", adj.get(1).word);
        Assert.assertEquals("(", adj.get(1).prefix);
        Assert.assertEquals(")", adj.get(1).suffix);
    }

    @Test
    public void findHistoryWordPositionInComplexSentences() {
        // 1. In brackets: "(Hallo) wie geht es dir" -> Target Col 0: "(Hallo)"
        String text1 = "(Hallo) wie geht es dir";
        MatrixSentenceBuffer buf1 = new MatrixSentenceBuffer();
        buf1.addWord("(Hallo)", Collections.singletonList("(Hallo)"), 0);
        buf1.addWord("wie", Collections.singletonList("wie"), 0);
        buf1.addWord("geht", Collections.singletonList("geht"), 0);
        buf1.addWord("es", Collections.singletonList("es"), 0);
        buf1.addWord("dir", Collections.singletonList("dir"), 0);

        int[] posHallo = MatrixTextHelper.findHistoryWordPosition(text1, buf1.getEntries(), 0, "(Hallo)");
        Assert.assertNotNull(posHallo);
        Assert.assertEquals(1, posHallo[0]);
        Assert.assertEquals(6, posHallo[1]);
        Assert.assertEquals("Hallo", text1.substring(posHallo[0], posHallo[1]));

        // 2. In quotes: "Das ist „Genial!“ wirklich" -> Target Col 2: "„Genial!“"
        String text2 = "Das ist „Genial!“ wirklich";
        MatrixSentenceBuffer buf2 = new MatrixSentenceBuffer();
        buf2.addWord("Das", Collections.singletonList("Das"), 0);
        buf2.addWord("ist", Collections.singletonList("ist"), 0);
        buf2.addWord("„Genial!“", Collections.singletonList("„Genial!“"), 0);
        buf2.addWord("wirklich", Collections.singletonList("wirklich"), 0);

        int[] posGenial = MatrixTextHelper.findHistoryWordPosition(text2, buf2.getEntries(), 2, "„Genial!“");
        Assert.assertNotNull(posGenial);
        Assert.assertEquals("Genial", text2.substring(posGenial[0], posGenial[1]));

        // 3. With decimals and currency: "Kostet $100 oder 3,14€ bar"
        String text3 = "Kostet $100 oder 3,14€ bar";
        MatrixSentenceBuffer buf3 = new MatrixSentenceBuffer();
        buf3.addWord("Kostet", Collections.singletonList("Kostet"), 0);
        buf3.addWord("$100", Collections.singletonList("$100"), 0);
        buf3.addWord("oder", Collections.singletonList("oder"), 0);
        buf3.addWord("3,14€", Collections.singletonList("3,14€"), 0);
        buf3.addWord("bar", Collections.singletonList("bar"), 0);

        int[] pos100 = MatrixTextHelper.findHistoryWordPosition(text3, buf3.getEntries(), 1, "$100");
        Assert.assertNotNull(pos100);
        Assert.assertEquals("100", text3.substring(pos100[0], pos100[1]));

        int[] posPi = MatrixTextHelper.findHistoryWordPosition(text3, buf3.getEntries(), 3, "3,14€");
        Assert.assertNotNull(posPi);
        Assert.assertEquals("3,14", text3.substring(posPi[0], posPi[1]));

        // 4. With contractions and hyphens: "Wie geht's per E-Mail"
        String text4 = "Wie geht's per E-Mail";
        MatrixSentenceBuffer buf4 = new MatrixSentenceBuffer();
        buf4.addWord("Wie", Collections.singletonList("Wie"), 0);
        buf4.addWord("geht's", Collections.singletonList("geht's"), 0);
        buf4.addWord("per", Collections.singletonList("per"), 0);
        buf4.addWord("E-Mail", Collections.singletonList("E-Mail"), 0);

        int[] posGehts = MatrixTextHelper.findHistoryWordPosition(text4, buf4.getEntries(), 1, "geht's");
        Assert.assertNotNull(posGehts);
        Assert.assertEquals("geht's", text4.substring(posGehts[0], posGehts[1]));

        int[] posEmail = MatrixTextHelper.findHistoryWordPosition(text4, buf4.getEntries(), 3, "E-Mail");
        Assert.assertNotNull(posEmail);
        Assert.assertEquals("E-Mail", text4.substring(posEmail[0], posEmail[1]));

        // 5. With attached emoji: "Das ist Super!😊 danke"
        String text5 = "Das ist Super!😊 danke";
        MatrixSentenceBuffer buf5 = new MatrixSentenceBuffer();
        buf5.addWord("Das", Collections.singletonList("Das"), 0);
        buf5.addWord("ist", Collections.singletonList("ist"), 0);
        buf5.addWord("Super!😊", Collections.singletonList("Super!😊"), 0);
        buf5.addWord("danke", Collections.singletonList("danke"), 0);

        int[] posSuper = MatrixTextHelper.findHistoryWordPosition(text5, buf5.getEntries(), 2, "Super!😊");
        Assert.assertNotNull(posSuper);
        Assert.assertEquals("Super", text5.substring(posSuper[0], posSuper[1]));

        // 6. Sentence with standalone math operators: "1 + 2 = 3"
        // Buffer only stores the real words ("1", "2", "3")
        String text6 = "1 + 2 = 3";
        MatrixSentenceBuffer buf6 = new MatrixSentenceBuffer();
        buf6.addWord("1", Collections.singletonList("1"), 0);
        buf6.addWord("2", Collections.singletonList("2"), 0);
        buf6.addWord("3", Collections.singletonList("3"), 0);

        int[] pos2 = MatrixTextHelper.findHistoryWordPosition(text6, buf6.getEntries(), 1, "2");
        Assert.assertNotNull(pos2);
        Assert.assertEquals("2", text6.substring(pos2[0], pos2[1]));
    }

    @Test
    public void activeWordResolutionWithSymbols() {
        // Active typing inside parentheses
        Assert.assertEquals("Hallo", MatrixTextHelper.getActiveWord("(Hallo", Collections.emptyList()));

        // Active typing with currency
        Assert.assertEquals("100", MatrixTextHelper.getActiveWord("$100", Collections.emptyList()));

        // Active typing compound words
        Assert.assertEquals("E-Mail", MatrixTextHelper.getActiveWord("E-Mail", Collections.emptyList()));
        Assert.assertEquals("geht's", MatrixTextHelper.getActiveWord("geht's", Collections.emptyList()));

        // Active typing decimal
        Assert.assertEquals("3,14", MatrixTextHelper.getActiveWord("3,14", Collections.emptyList()));
    }

    @Test
    public void replaceHistoryWordNormalCursorAtEnd() {
        // Normal case: user was typing at the end of the text field
        String text = "Hallo wie geht es dir";
        MatrixSentenceBuffer buffer = createSentenceBuffer("Hallo", "wie", "geht", "es", "dir");
        TestInputConnection ic = new TestInputConnection(text, 21, 21);

        boolean ok = MatrixTextHelper.replaceHistoryWord(ic, buffer, 1, "wie", "warum");
        Assert.assertTrue(ok);
        Assert.assertEquals("Hallo warum geht es dir", ic.getText());
        // Cursor restored to end of text field so user can continue typing
        Assert.assertEquals(23, ic.getSelectionStart());
        Assert.assertEquals(23, ic.getSelectionEnd());
    }

    @Test
    public void replaceHistoryWordTargetWordIsSelected() {
        // Untypical case: user tapped on history word in topbar -> word is currently selected in text field
        String text = "Hallo wie geht es dir";
        MatrixSentenceBuffer buffer = createSentenceBuffer("Hallo", "wie", "geht", "es", "dir");
        // "wie" is at [6, 9]
        TestInputConnection ic = new TestInputConnection(text, 6, 9);

        boolean ok = MatrixTextHelper.replaceHistoryWord(ic, buffer, 1, "wie", "warum");
        Assert.assertTrue(ok);
        Assert.assertEquals("Hallo warum geht es dir", ic.getText());
        // Target word was selected -> new word stays selected so user can flick again or retype
        Assert.assertEquals(6, ic.getSelectionStart());
        Assert.assertEquals(11, ic.getSelectionEnd());
        Assert.assertEquals("warum", ic.getSelectedText(0));
    }

    @Test
    public void replaceHistoryWordCursorAtStart() {
        // Untypical case: cursor placed at index 0 (very beginning of text field)
        String text = "Hallo wie geht es dir";
        MatrixSentenceBuffer buffer = createSentenceBuffer("Hallo", "wie", "geht", "es", "dir");
        TestInputConnection ic = new TestInputConnection(text, 0, 0);

        boolean ok = MatrixTextHelper.replaceHistoryWord(ic, buffer, 4, "dir", "euch");
        Assert.assertTrue(ok);
        Assert.assertEquals("Hallo wie geht es euch", ic.getText());
    }

    @Test
    public void replaceHistoryWordCursorInMiddle() {
        // Untypical case: cursor placed in the middle of sentence (between "geht" and " es dir")
        String text = "Hallo wie geht es dir";
        MatrixSentenceBuffer buffer = createSentenceBuffer("Hallo", "wie", "geht", "es", "dir");
        // Offset 14 is right after "geht"
        TestInputConnection ic = new TestInputConnection(text, 14, 14);

        boolean ok = MatrixTextHelper.replaceHistoryWord(ic, buffer, 0, "Hallo", "Guten Tag");
        Assert.assertTrue(ok);
        Assert.assertEquals("Guten Tag wie geht es dir", ic.getText());
    }

    @Test
    public void replaceHistoryWordCursorInsideTargetWord() {
        // Untypical case: cursor placed inside the word to be replaced (e.g. "w|ie" at offset 7)
        String text = "Hallo wie geht es dir";
        MatrixSentenceBuffer buffer = createSentenceBuffer("Hallo", "wie", "geht", "es", "dir");
        TestInputConnection ic = new TestInputConnection(text, 7, 7);

        boolean ok = MatrixTextHelper.replaceHistoryWord(ic, buffer, 1, "wie", "warum");
        Assert.assertTrue(ok);
        Assert.assertEquals("Hallo warum geht es dir", ic.getText());
    }

    @Test
    public void replaceHistoryWordWithDuplicatesAndUntypicalCursor() {
        // Untypical case: sentence with duplicate words, cursor placed after the second instance
        String text = "gut ist gut und bleibt gut";
        MatrixSentenceBuffer buffer = createSentenceBuffer("gut", "ist", "gut", "und", "bleibt", "gut");
        TestInputConnection ic = new TestInputConnection(text, 11, 11);

        // Replace first "gut" (Col 0) with "toll"
        boolean ok1 = MatrixTextHelper.replaceHistoryWord(ic, buffer, 0, "gut", "toll");
        Assert.assertTrue(ok1);
        Assert.assertEquals("toll ist gut und bleibt gut", ic.getText());

        // Replace middle "gut" (Col 2) with "super"
        boolean ok2 = MatrixTextHelper.replaceHistoryWord(ic, buffer, 2, "gut", "super");
        Assert.assertTrue(ok2);
        Assert.assertEquals("toll ist super und bleibt gut", ic.getText());

        // Replace last "gut" (Col 5) with "prima"
        boolean ok3 = MatrixTextHelper.replaceHistoryWord(ic, buffer, 5, "gut", "prima");
        Assert.assertTrue(ok3);
        Assert.assertEquals("toll ist super und bleibt prima", ic.getText());
    }

    @Test
    public void replaceHistoryWordWithBracketsAndUntypicalCursor() {
        // Untypical case: word in parentheses, cursor inside parentheses "(H|allo)"
        String text = "(Hallo) wie geht es dir";
        MatrixSentenceBuffer buffer = createSentenceBuffer("(Hallo)", "wie", "geht", "es", "dir");
        TestInputConnection ic = new TestInputConnection(text, 2, 2);

        boolean ok = MatrixTextHelper.replaceHistoryWord(ic, buffer, 0, "(Hallo)", "(Hi)");
        Assert.assertTrue(ok);
        // Parentheses are preserved!
        Assert.assertEquals("(Hi) wie geht es dir", ic.getText());
    }

    @Test
    public void replaceHistoryWordWithQuotesAndUntypicalCursor() {
        // Untypical case: German quotes with trailing exclamation mark, cursor at 0
        String text = "Das ist „Genial!“ wirklich";
        MatrixSentenceBuffer buffer = createSentenceBuffer("Das", "ist", "„Genial!“", "wirklich");
        TestInputConnection ic = new TestInputConnection(text, 0, 0);

        boolean ok = MatrixTextHelper.replaceHistoryWord(ic, buffer, 2, "„Genial!“", "„Klasse!“");
        Assert.assertTrue(ok);
        Assert.assertEquals("Das ist „Klasse!“ wirklich", ic.getText());
    }

    @Test
    public void replaceActiveWordWithUntypicalCursorAndSelection() {
        // Untypical case 1: Active word is currently selected in text field
        String text1 = "Hallo Welt";
        TestInputConnection ic1 = new TestInputConnection(text1, 6, 10); // "Welt" selected
        boolean ok1 = MatrixTextHelper.replaceActiveWordInTextField(ic1, "Erde");
        Assert.assertTrue(ok1);
        Assert.assertEquals("Hallo Erde", ic1.getText());

        // Untypical case 2: Cursor placed directly at end
        String text2 = "Hallo Welt";
        TestInputConnection ic2 = new TestInputConnection(text2, 10, 10);
        boolean ok2 = MatrixTextHelper.replaceActiveWordInTextField(ic2, "Erde");
        Assert.assertTrue(ok2);
        Assert.assertEquals("Hallo Erde", ic2.getText());
    }

    @Test
    public void selectHistoryWordAndJumpToEndWithUntypicalCursor() {
        String text = "Hallo wie geht es dir";
        MatrixSentenceBuffer buffer = createSentenceBuffer("Hallo", "wie", "geht", "es", "dir");
        TestInputConnection ic = new TestInputConnection(text, 0, 0);

        // Select Col 1 ("wie") from start of field
        boolean selOk = MatrixTextHelper.selectHistoryWordInTextField(ic, buffer, 1, "wie");
        Assert.assertTrue(selOk);
        Assert.assertEquals(6, ic.getSelectionStart());
        Assert.assertEquals(9, ic.getSelectionEnd());
        Assert.assertEquals("wie", ic.getSelectedText(0));

        // Jump to end
        MatrixTextHelper.jumpToEndOfTextField(ic);
        Assert.assertEquals(21, ic.getSelectionStart());
        Assert.assertEquals(21, ic.getSelectionEnd());
    }

    @Test
    public void findHistoryWordPosition_ignoresTrailingActiveWord() {
        String text = "Hallo Welt Hallo";
        MatrixSentenceBuffer buffer = createSentenceBuffer("Hallo", "Welt");

        int[] pos = MatrixTextHelper.findHistoryWordPosition(text, buffer.getEntries(), 0, "Hallo");
        Assert.assertNotNull(pos);
        Assert.assertEquals(0, pos[0]);
        Assert.assertEquals(5, pos[1]);
    }

    @Test
    public void findHistoryWordPositionInParagraph_isolatesCurrentParagraph() {
        String text = "Hallo Welt\nHallo Test";
        MatrixSentenceBuffer buffer = createSentenceBuffer("Hallo", "Test");

        // Cursor in second paragraph (index 21)
        int[] pos = MatrixTextHelper.findHistoryWordPositionInParagraph(text, 21, buffer.getEntries(), 0, "Hallo");
        Assert.assertNotNull(pos);
        Assert.assertEquals(11, pos[0]);
        Assert.assertEquals(16, pos[1]);
    }

    @Test
    public void replaceHistoryWord_preservesMiddleCursorPosition() {
        // Cursor is at offset 15 (after "wie "), NOT at the end of the text field
        TestInputConnection ic = new TestInputConnection("Hallo Welt wie geht", 15, 15);
        MatrixSentenceBuffer buffer = createSentenceBuffer("Hallo", "Welt", "wie", "geht");

        boolean replaced = MatrixTextHelper.replaceHistoryWord(ic, buffer, 0, "Hallo", "Servus");
        Assert.assertTrue(replaced);
        Assert.assertEquals("Servus Welt wie geht", ic.getText());
        // "Servus" (6) is 1 char longer than "Hallo" (5), so cursor should shift 15 -> 16
        Assert.assertEquals(16, ic.getSelectionStart());
        Assert.assertEquals(16, ic.getSelectionEnd());
    }

    @Test
    public void replaceActiveWordInTextField_inMiddleOfWord() {
        // Cursor is between "hal" and "lo"
        TestInputConnection ic = new TestInputConnection("hallo welt", 3, 3);

        boolean replaced = MatrixTextHelper.replaceActiveWordInTextField(ic, "super");
        Assert.assertTrue(replaced);
        Assert.assertEquals("super welt", ic.getText());
        Assert.assertEquals(5, ic.getSelectionStart());
        Assert.assertEquals(5, ic.getSelectionEnd());
    }

    @Test
    public void findCandidateByWord_matchesExactTextEvenWhenTrimmed() {
        GboardMatrixSuggestionsRuntime.CandidateItem a = new GboardMatrixSuggestionsRuntime.CandidateItem("Alpha", 0, false, false, false, "", null, null);
        GboardMatrixSuggestionsRuntime.CandidateItem b = new GboardMatrixSuggestionsRuntime.CandidateItem("Beta", 1, false, false, false, "", null, null);
        GboardMatrixSuggestionsRuntime.CandidateItem c = new GboardMatrixSuggestionsRuntime.CandidateItem("Gamma", 2, false, false, false, "", null, null);
        GboardMatrixSuggestionsRuntime.CandidateItem d = new GboardMatrixSuggestionsRuntime.CandidateItem("Delta", 3, false, false, false, "", null, null);

        List<GboardMatrixSuggestionsRuntime.CandidateItem> items = Arrays.asList(a, b, c, d);
        GboardMatrixSuggestionsRuntime.CandidateItem found = GboardMatrixSuggestionsRuntime.findCandidateByWord(items, "Delta");
        Assert.assertSame(d, found);
    }

    @Test
    public void moveCursorByWord_navigatesLeftAndRightAcrossWords() {
        String text = "Hallo Welt, wie geht's?";
        TestInputConnection ic = new TestInputConnection(text, text.length(), text.length());

        // Step 1 left from end -> selects "geht's" [16, 22]
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, -1));
        Assert.assertEquals(16, ic.getSelectionStart());
        Assert.assertEquals(22, ic.getSelectionEnd());

        // Step 2 left -> selects "wie" [12, 15]
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, -1));
        Assert.assertEquals(12, ic.getSelectionStart());
        Assert.assertEquals(15, ic.getSelectionEnd());

        // Step 3 left -> selects "Welt" [6, 10]
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, -1));
        Assert.assertEquals(6, ic.getSelectionStart());
        Assert.assertEquals(10, ic.getSelectionEnd());

        // Step 4 left -> selects "Hallo" [0, 5]
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, -1));
        Assert.assertEquals(0, ic.getSelectionStart());
        Assert.assertEquals(5, ic.getSelectionEnd());

        // Step 5 left from first word "Hallo" -> collapses to cursor at start [0, 0]
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, -1));
        Assert.assertEquals(0, ic.getSelectionStart());
        Assert.assertEquals(0, ic.getSelectionEnd());

        // Step 6 left at cursor [0, 0] -> returns false, stays at [0, 0]
        Assert.assertFalse(MatrixTextHelper.moveCursorByWord(ic, -1));
        Assert.assertEquals(0, ic.getSelectionStart());
        Assert.assertEquals(0, ic.getSelectionEnd());

        // Step 1 right from [0, 0] -> selects "Hallo" [0, 5]
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, 1));
        Assert.assertEquals(0, ic.getSelectionStart());
        Assert.assertEquals(5, ic.getSelectionEnd());

        // Step 2 right -> selects "Welt" [6, 10]
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, 1));
        Assert.assertEquals(6, ic.getSelectionStart());
        Assert.assertEquals(10, ic.getSelectionEnd());

        // Step 3 right -> selects "wie" [12, 15]
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, 1));
        Assert.assertEquals(12, ic.getSelectionStart());
        Assert.assertEquals(15, ic.getSelectionEnd());

        // Step 4 right -> selects "geht's" [16, 22]
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, 1));
        Assert.assertEquals(16, ic.getSelectionStart());
        Assert.assertEquals(22, ic.getSelectionEnd());

        // Step 5 right past last word -> moves cursor to end [23, 23]
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, 1));
        Assert.assertEquals(23, ic.getSelectionStart());
        Assert.assertEquals(23, ic.getSelectionEnd());

        // Also test right end without trailing punctuation: "Hallo Welt" with "Welt" selected
        TestInputConnection icNoPunct = new TestInputConnection("Hallo Welt", 6, 10);
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(icNoPunct, 1));
        Assert.assertEquals(10, icNoPunct.getSelectionStart());
        Assert.assertEquals(10, icNoPunct.getSelectionEnd());
    }

    @Test
    public void getWordIndexAtCursor_tracksNavigatedWord() {
        String text = "Eins Zwei Drei Vier";
        MatrixSentenceBuffer buffer = createSentenceBuffer("Eins", "Zwei", "Drei", "Vier");
        TestInputConnection ic = new TestInputConnection(text, text.length(), text.length());

        // Move left to "Vier" (col 3)
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, -1));
        Assert.assertEquals(3, MatrixTextHelper.getWordIndexAtCursor(ic, buffer));

        // Move left to "Drei" (col 2)
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, -1));
        Assert.assertEquals(2, MatrixTextHelper.getWordIndexAtCursor(ic, buffer));

        // Move left to "Zwei" (col 1)
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, -1));
        Assert.assertEquals(1, MatrixTextHelper.getWordIndexAtCursor(ic, buffer));

        // Move left to "Eins" (col 0)
        Assert.assertTrue(MatrixTextHelper.moveCursorByWord(ic, -1));
        Assert.assertEquals(0, MatrixTextHelper.getWordIndexAtCursor(ic, buffer));
    }

    @Test
    public void isInputEmptyAndSentenceEnded_falseWhenFirstWordSelected() {
        // First word selected at [0, 5]: before is empty, sel is "Hallo", after is " Welt"
        TestInputConnection icStart = new TestInputConnection("Hallo Welt", 0, 5);
        Assert.assertFalse(MatrixTextHelper.isInputEmpty(icStart));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded(icStart));

        // First word of second paragraph selected at [6, 11]: before ends with "\n"
        TestInputConnection icPara = new TestInputConnection("Eins.\nHallo Welt", 6, 11);
        Assert.assertFalse(MatrixTextHelper.isInputEmpty(icPara));
        Assert.assertFalse(MatrixTextHelper.isSentenceEnded(icPara));

        // Truly empty input
        TestInputConnection icEmpty = new TestInputConnection("", 0, 0);
        Assert.assertTrue(MatrixTextHelper.isInputEmpty(icEmpty));
    }

    private static MatrixSentenceBuffer createSentenceBuffer(String... words) {
        MatrixSentenceBuffer buf = new MatrixSentenceBuffer();
        for (String w : words) {
            buf.addWord(w, Collections.singletonList(w), 0);
        }
        return buf;
    }

    /**
     * Test implementation of InputConnection to simulate any cursor position,
     * selection, and document editing in pure unit tests.
     */
    private static class TestInputConnection implements InputConnection {
        private final StringBuilder text;
        private int selStart;
        private int selEnd;

        public TestInputConnection(String initialText, int selStart, int selEnd) {
            this.text = new StringBuilder(initialText != null ? initialText : "");
            this.selStart = Math.max(0, Math.min(this.text.length(), selStart));
            this.selEnd = Math.max(0, Math.min(this.text.length(), selEnd));
        }

        public String getText() {
            return text.toString();
        }

        public int getSelectionStart() {
            return selStart;
        }

        public int getSelectionEnd() {
            return selEnd;
        }

        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            int start = Math.max(0, selStart - n);
            return text.substring(start, selStart);
        }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) {
            int end = Math.min(text.length(), selEnd + n);
            return text.substring(selEnd, end);
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            if (selStart == selEnd) return null;
            int s = Math.min(selStart, selEnd);
            int e = Math.max(selStart, selEnd);
            return text.substring(s, e);
        }

        @Override
        public int getCursorCapsMode(int reqModes) {
            return 0;
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            ExtractedText et = new ExtractedText();
            et.text = text.toString();
            et.selectionStart = selStart;
            et.selectionEnd = selEnd;
            et.startOffset = 0;
            return et;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (beforeLength > 0) {
                int delStart = Math.max(0, selStart - beforeLength);
                this.text.delete(delStart, selStart);
                this.selStart = delStart;
                this.selEnd = delStart;
            }
            if (afterLength > 0) {
                int delEnd = Math.min(text.length(), selEnd + afterLength);
                this.text.delete(selEnd, delEnd);
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            return deleteSurroundingText(beforeLength, afterLength);
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            return commitText(text, newCursorPosition);
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            return true;
        }

        @Override
        public boolean finishComposingText() {
            return true;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            int s = Math.min(selStart, selEnd);
            int e = Math.max(selStart, selEnd);
            this.text.replace(s, e, text.toString());
            int newCursor = s + text.length();
            this.selStart = newCursor;
            this.selEnd = newCursor;
            return true;
        }

        @Override
        public boolean commitCompletion(CompletionInfo text) {
            return false;
        }

        @Override
        public boolean commitCorrection(CorrectionInfo correctionInfo) {
            return false;
        }

        @Override
        public boolean setSelection(int start, int end) {
            this.selStart = Math.max(0, Math.min(text.length(), start));
            this.selEnd = Math.max(0, Math.min(text.length(), end));
            return true;
        }

        @Override
        public boolean performEditorAction(int editorAction) {
            return false;
        }

        @Override
        public boolean performContextMenuAction(int id) {
            return false;
        }

        @Override
        public boolean beginBatchEdit() {
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            return false;
        }

        @Override
        public boolean clearMetaKeyStates(int states) {
            return false;
        }

        @Override
        public boolean reportFullscreenMode(boolean enabled) {
            return false;
        }

        @Override
        public boolean performPrivateCommand(String action, Bundle data) {
            return false;
        }

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode) {
            return false;
        }

        @Override
        public Handler getHandler() {
            return null;
        }

        @Override
        public void closeConnection() {
        }

        @Override
        public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
            return false;
        }
    }
}
