package dev.jason.gboardpatches.extension.matrixsuggestions;

import android.content.Context;
import android.content.ContextWrapper;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Text and cursor manipulation utilities for Matrix Word Suggestions.
 * Isolates word boundary detection, sentence splitting, active word resolution,
 * and text replacement via InputConnection.
 */
public final class MatrixTextHelper {
    private static final String TAG = "MatrixTextHelper";

    private MatrixTextHelper() {}

    public static final class TokenParts {
        public final String prefix;
        public final String word;
        public final String suffix;
        public final String fullToken;

        public TokenParts(String prefix, String word, String suffix, String fullToken) {
            this.prefix = prefix != null ? prefix : "";
            this.word = word != null ? word : "";
            this.suffix = suffix != null ? suffix : "";
            this.fullToken = fullToken != null ? fullToken : "";
        }

        public boolean hasWord() {
            return !word.isEmpty();
        }

        @Override
        public String toString() {
            return "TokenParts{prefix='" + prefix + "', word='" + word + "', suffix='" + suffix + "', fullToken='" + fullToken + "'}";
        }
    }

    public static boolean isBoundaryChar(char c) {
        if (Character.isWhitespace(c)) return true;
        if (Character.isLetterOrDigit(c)) return false;
        int type = Character.getType(c);
        if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) {
            return false;
        }
        return true;
    }

    public static boolean isBoundaryChar(CharSequence text, int index) {
        if (text == null || index < 0 || index >= text.length()) return true;
        char c = text.charAt(index);
        if (Character.isWhitespace(c)) return true;

        // Contextual exceptions for internal connectors within words or numbers:
        if (index > 0 && index + 1 < text.length()) {
            char prev = text.charAt(index - 1);
            char next = text.charAt(index + 1);

            // 1. Decimal or thousands separator between digits: "3,14", "1.500"
            if ((c == '.' || c == ',') && Character.isDigit(prev) && Character.isDigit(next)) {
                return false;
            }

            // 2. Contraction apostrophe between letters: "geht's", "don't", "c'est", "geht’s"
            if ((c == '\'' || c == '’' || c == '`') && Character.isLetter(prev) && Character.isLetter(next)) {
                return false;
            }

            // 3. Hyphen, underscore, or slash in compound words: "E-Mail", "Wi-Fi", "Covid-19", "und/oder"
            if ((c == '-' || c == '_' || c == '/') && Character.isLetterOrDigit(prev) && Character.isLetterOrDigit(next)) {
                return false;
            }
        }

        return isBoundaryChar(c);
    }

    public static boolean isLeadingSymbol(char c) {
        return c == '(' || c == '[' || c == '{' || c == '<'
                || c == '"' || c == '\'' || c == '„' || c == '“' || c == '‘' || c == '«'
                || c == '$' || c == '€' || c == '£' || c == '¥' || c == '₹' || c == '₽' || c == '₩'
                || c == '@' || c == '#' || c == '¿' || c == '¡';
    }

    public static boolean isTrailingSymbol(char c) {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' || c == '…'
                || c == ')' || c == ']' || c == '}' || c == '>'
                || c == '"' || c == '\'' || c == '”' || c == '’' || c == '»'
                || c == '%' || c == '‰' || c == '°'
                || c == '€' || c == '$' || c == '£';
    }

    public static boolean isTrailingPunctuationOnly(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isTrailingSymbol(c)) {
                return false;
            }
        }
        return true;
    }

    public static TokenParts parseTokenParts(String token) {
        if (token == null || token.isEmpty()) {
            return new TokenParts("", "", "", "");
        }
        int len = token.length();
        int start = -1;
        for (int i = 0; i < len; i++) {
            if (!isBoundaryChar(token, i)) {
                start = i;
                break;
            }
        }
        if (start == -1) {
            return new TokenParts(token, "", "", token);
        }

        int end = -1;
        for (int i = len - 1; i >= start; i--) {
            if (!isBoundaryChar(token, i)) {
                end = i + 1;
                break;
            }
        }

        String prefix = token.substring(0, start);
        String word = token.substring(start, end);
        String suffix = token.substring(end);
        return new TokenParts(prefix, word, suffix, token);
    }

    public static List<TokenParts> splitChunkIntoTokenParts(String chunk) {
        List<TokenParts> list = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) return list;

        int len = chunk.length();
        int i = 0;
        while (i < len) {
            int prefixStart = i;
            while (i < len && isBoundaryChar(chunk, i)) {
                i++;
            }
            if (i < len) {
                int wordStart = i;
                while (i < len && !isBoundaryChar(chunk, i)) {
                    i++;
                }
                int wordEnd = i;
                int suffixStart = i;
                while (i < len && isBoundaryChar(chunk, i)) {
                    if (i + 1 < len && !isBoundaryChar(chunk, i + 1)) {
                        char c = chunk.charAt(i);
                        if (isLeadingSymbol(c)) {
                            break;
                        }
                    }
                    i++;
                }
                int suffixEnd = i;
                String prefix = chunk.substring(prefixStart, wordStart);
                String word = chunk.substring(wordStart, wordEnd);
                String suffix = chunk.substring(suffixStart, suffixEnd);
                list.add(new TokenParts(prefix, word, suffix, prefix + word + suffix));
            } else {
                if (prefixStart < len) {
                    String symbols = chunk.substring(prefixStart);
                    list.add(new TokenParts(symbols, "", "", symbols));
                }
            }
        }
        return list;
    }

    public static List<TokenParts> tokenizeSentence(String sentence) {
        List<TokenParts> tokens = new ArrayList<>();
        if (sentence == null || sentence.isEmpty()) {
            return tokens;
        }
        String[] chunks = sentence.split("\\s+");
        for (String chunk : chunks) {
            String tr = chunk.trim();
            if (tr.isEmpty()) continue;
            tokens.addAll(splitChunkIntoTokenParts(tr));
        }
        return tokens;
    }

    public static boolean isInputEmpty(View view) {
        InputConnection ic = getInputConnection(view);
        return isInputEmpty(ic);
    }

    public static boolean isInputEmpty(InputConnection ic) {
        if (ic == null) return true;
        try {
            CharSequence cs = ic.getTextBeforeCursor(10, 0);
            return cs == null || cs.length() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isSentenceEnded(View view) {
        InputConnection ic = getInputConnection(view);
        return isSentenceEnded(ic);
    }

    public static boolean isSentenceEnded(InputConnection ic) {
        if (ic == null) return false;
        try {
            CharSequence cs = ic.getTextBeforeCursor(20, 0);
            return isSentenceEnded(cs);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isSentenceEnded(CharSequence cs) {
        if (cs == null || cs.length() == 0) return false;
        String s = cs.toString();
        int idx = s.length() - 1;
        while (idx >= 0 && Character.isWhitespace(s.charAt(idx)) && s.charAt(idx) != '\n' && s.charAt(idx) != '\r') {
            idx--;
        }
        if (idx < 0) return false;
        char last = s.charAt(idx);
        return (last == '\n' || last == '\r');
    }

    public static String getActiveWord(View view, List<GboardMatrixSuggestionsRuntime.CandidateItem> items) {
        return getActiveWord(view, items, true);
    }

    public static String getActiveWord(View view, List<GboardMatrixSuggestionsRuntime.CandidateItem> items, boolean allowTrailingSpace) {
        InputConnection ic = getInputConnection(view);
        if (ic == null) return null;
        try {
            CharSequence cs = ic.getTextBeforeCursor(100, 0);
            return getActiveWord(cs, items, allowTrailingSpace);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getActiveWord(CharSequence cs, List<GboardMatrixSuggestionsRuntime.CandidateItem> items) {
        return getActiveWord(cs, items, true);
    }

    public static String getActiveWord(CharSequence cs, List<GboardMatrixSuggestionsRuntime.CandidateItem> items, boolean allowTrailingSpace) {
        if (cs == null || cs.length() == 0) return null;
        String s = cs.toString();
        int len = s.length();
        if (len == 0) return null;

        int end = len;
        if (Character.isWhitespace(s.charAt(len - 1))) {
            if (!allowTrailingSpace) {
                return null;
            }
            // If there are multiple trailing whitespaces, user pressed multiple spaces -> no active word.
            if (len >= 2 && Character.isWhitespace(s.charAt(len - 2))) {
                return null;
            }

            // If items is non-empty and EVERY item is a next-word prediction:
            // then the word was completed and user moved on. Suppress next-word predictions per user request.
            if (items != null && !items.isEmpty() && allPredictions(items)) {
                return null;
            }

            // Otherwise (swipe typing auto-space, or word without predictions like 'toggles'):
            // the word right before the single trailing space is our active word!
            end--;
            if (end <= 0 || Character.isWhitespace(s.charAt(end - 1))) {
                return null;
            }
        }

        int start = end - 1;
        while (start >= 0 && !isBoundaryChar(s, start)) {
            start--;
        }
        if (start < end - 1) {
            String word = s.substring(start + 1, end).trim();
            if (!word.isEmpty()) {
                return word;
            }
        }
        return null;
    }

    public static boolean allPredictions(List<GboardMatrixSuggestionsRuntime.CandidateItem> items) {
        if (items == null || items.isEmpty()) return false;
        for (GboardMatrixSuggestionsRuntime.CandidateItem ci : items) {
            if (!ci.isPrediction) return false;
        }
        return true;
    }

    public static boolean isPunctuationChar(char c) {
        return isBoundaryChar(c);
    }

    public static String stripTrailingPunctuation(String word) {
        if (word == null || word.isEmpty()) return "";
        TokenParts parts = parseTokenParts(word);
        return parts.prefix + parts.word;
    }

    public static String getTrailingPunctuation(String word) {
        if (word == null || word.isEmpty()) return "";
        TokenParts parts = parseTokenParts(word);
        return parts.suffix;
    }

    public static String getCoreWord(String word) {
        if (word == null || word.isEmpty()) return "";
        TokenParts parts = parseTokenParts(word);
        return parts.hasWord() ? parts.word : word;
    }

    public static void syncSentenceBufferFromText(
            View view,
            String currentWord,
            MatrixSentenceBuffer buffer,
            Map<String, List<String>> alternativesCache
    ) {
        try {
            InputConnection ic = getInputConnection(view);
            if (ic == null) return;

            CharSequence cs = ic.getTextBeforeCursor(1000, 0);
            if (cs == null || cs.length() == 0) {
                buffer.clear();
                return;
            }

            String text = cs.toString();
            int sentStart = 0;
            for (int i = text.length() - 1; i >= 0; i--) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r') {
                    sentStart = i + 1;
                    break;
                }
            }
            String currentSentence = text.substring(sentStart).trim();
            if (currentSentence.isEmpty()) {
                buffer.clear();
                return;
            }

            List<TokenParts> allTokens = tokenizeSentence(currentSentence);
            List<TokenParts> validTokens = new ArrayList<>();

            for (TokenParts parts : allTokens) {
                if (parts.hasWord()) {
                    validTokens.add(parts);
                } else if (!validTokens.isEmpty() && isTrailingPunctuationOnly(parts.prefix)) {
                    // Pure trailing punctuation mark typed after space (e.g. "Hallo .", "Hallo ,", "Hallo !")
                    int lastIdx = validTokens.size() - 1;
                    TokenParts prev = validTokens.get(lastIdx);
                    String combinedSuffix = prev.suffix + parts.prefix;
                    validTokens.set(lastIdx, new TokenParts(prev.prefix, prev.word, combinedSuffix, prev.prefix + prev.word + combinedSuffix));
                }
            }

            if (validTokens.isEmpty()) {
                buffer.clear();
                return;
            }

            int historyWordCount = validTokens.size() - 1;
            if (currentWord == null || currentWord.isEmpty()) {
                historyWordCount = validTokens.size();
            }

            buffer.clear();
            for (int i = 0; i < historyWordCount; i++) {
                TokenParts parts = validTokens.get(i);
                String baseWord = parts.word;
                String prefix = parts.prefix;
                String suffix = parts.suffix;

                List<String> rawAlts = alternativesCache != null ? alternativesCache.get(baseWord.toLowerCase()) : null;
                List<String> formattedAlts = new ArrayList<>();
                int selIdx = 0;

                if (rawAlts != null && !rawAlts.isEmpty()) {
                    for (int a = 0; a < rawAlts.size(); a++) {
                        String rawAlt = rawAlts.get(a);
                        formattedAlts.add(prefix + rawAlt + suffix);
                        if (rawAlt.equalsIgnoreCase(baseWord)) {
                            selIdx = a;
                        }
                    }
                } else {
                    formattedAlts.add(parts.fullToken);
                }

                buffer.addWord(parts.fullToken, formattedAlts, selIdx);
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncSentenceBufferFromText error: " + t.getMessage());
        }
    }

    public static void replaceActiveWordInTextField(View view, String newWord) {
        if (newWord == null || newWord.isEmpty()) return;
        try {
            InputConnection ic = getInputConnection(view);
            if (ic == null) return;

            ic.finishComposingText();

            CharSequence cs = ic.getTextBeforeCursor(200, 0);
            if (cs == null || cs.length() == 0) {
                ic.commitText(newWord, 1);
                return;
            }

            String text = cs.toString();
            int end = text.length();
            while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            if (end <= 0) {
                ic.commitText(newWord, 1);
                return;
            }

            int start = end;
            while (start > 0 && !isBoundaryChar(text, start - 1)) {
                start--;
            }

            String oldWord = text.substring(start, end);
            if (oldWord.equals(newWord)) {
                return;
            }

            int deleteLen = text.length() - start;
            if (deleteLen > 0 && deleteLen <= text.length()) {
                ic.beginBatchEdit();
                ic.deleteSurroundingText(deleteLen, 0);
                ic.commitText(newWord, 1);
                ic.endBatchEdit();
                Log.i(TAG, "replaceActiveWordInTextField: cleanly replaced '" + oldWord + "' with '" + newWord + "' (no space)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "replaceActiveWordInTextField failed", t);
        }
    }

    public static boolean isCursorAtEnd(View view) {
        if (view == null) return true;
        InputConnection ic = getInputConnection(view);
        if (ic == null) return true;
        try {
            CharSequence sel = ic.getSelectedText(0);
            if (sel != null && sel.length() > 0) {
                return false;
            }
            CharSequence after = ic.getTextAfterCursor(1, 0);
            return after == null || after.length() == 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean selectHistoryWordInTextField(View view, MatrixSentenceBuffer buffer, int historyColIndex, String word) {
        if (view == null || buffer == null || word == null) return false;
        try {
            InputConnection ic = getInputConnection(view);
            if (ic == null) return false;

            CharSequence csBefore = ic.getTextBeforeCursor(2000, 0);
            CharSequence csSel = ic.getSelectedText(0);
            CharSequence csAfter = ic.getTextAfterCursor(2000, 0);

            String before = csBefore != null ? csBefore.toString() : "";
            String sel = csSel != null ? csSel.toString() : "";
            String after = csAfter != null ? csAfter.toString() : "";

            String text = before + sel + after;
            if (text.isEmpty()) return false;

            List<MatrixSentenceBuffer.WordEntry> entries = buffer.getEntries();
            int[] pos = findHistoryWordPosition(text, entries, historyColIndex, word);

            if (pos != null && pos[0] >= 0 && pos[1] <= text.length() && pos[0] < pos[1]) {
                int docOffset = 0;
                try {
                    ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
                    if (et != null && et.selectionStart >= 0) {
                        docOffset = et.selectionStart - before.length();
                    }
                } catch (Throwable ignored) {
                }

                int selStart = Math.max(0, docOffset + pos[0]);
                int selEnd = Math.max(0, docOffset + pos[1]);

                ic.setSelection(selStart, selEnd);
                Log.i(TAG, "selectHistoryWordInTextField: selected '" + word + "' at [" + selStart + ", " + selEnd + "]");
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "selectHistoryWordInTextField failed", t);
        }
        return false;
    }

    public static void jumpToEndOfTextField(View view) {
        if (view == null) return;
        try {
            InputConnection ic = getInputConnection(view);
            if (ic == null) return;

            ic.finishComposingText();

            int textLength = -1;
            try {
                ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
                if (et != null && et.text != null) {
                    textLength = et.text.length();
                }
            } catch (Throwable ignored) {
            }

            if (textLength < 0) {
                CharSequence before = ic.getTextBeforeCursor(5000, 0);
                CharSequence sel = ic.getSelectedText(0);
                CharSequence after = ic.getTextAfterCursor(5000, 0);
                int bLen = before != null ? before.length() : 0;
                int sLen = sel != null ? sel.length() : 0;
                int aLen = after != null ? after.length() : 0;
                textLength = bLen + sLen + aLen;
            }

            if (textLength >= 0) {
                ic.setSelection(textLength, textLength);
                Log.i(TAG, "jumpToEndOfTextField: moved cursor to end offset " + textLength);
            }
        } catch (Throwable t) {
            Log.e(TAG, "jumpToEndOfTextField error", t);
        }
    }

    public static void replaceHistoryWord(View anchorView, MatrixSentenceBuffer buffer, int historyColIndex, String oldWord, String newWord) {
        try {
            InputConnection ic = getInputConnection(anchorView);
            if (ic == null) return;

            ic.finishComposingText();

            CharSequence textBefore = ic.getTextBeforeCursor(2000, 0);
            if (textBefore == null) return;

            String text = textBefore.toString();
            List<MatrixSentenceBuffer.WordEntry> entries = buffer.getEntries();
            int[] pos = findHistoryWordPosition(text, entries, historyColIndex, oldWord);

            if (pos != null && pos[0] >= 0 && pos[1] <= text.length() && pos[0] < pos[1]) {
                TokenParts newParts = parseTokenParts(newWord);
                String cleanNewWord = newParts.hasWord() ? newParts.word : newWord;
                String textAfter = text.substring(pos[1]);
                int deleteCount = text.length() - pos[0];
                ic.beginBatchEdit();
                ic.deleteSurroundingText(deleteCount, 0);
                ic.commitText(cleanNewWord + textAfter, 1);
                ic.endBatchEdit();
                Log.i(TAG, "replaceHistoryWord: replaced '" + oldWord + "' with '" + cleanNewWord + "' at [" + pos[0] + ", " + pos[1] + "]");
            } else {
                Log.w(TAG, "replaceHistoryWord: could not find boundary position for '" + oldWord + "' (col=" + historyColIndex + ")");
            }
        } catch (Throwable t) {
            Log.e(TAG, "replaceHistoryWord failed", t);
        }
    }

    public static int[] findHistoryWordPosition(String text, List<MatrixSentenceBuffer.WordEntry> history, int targetColIndex, String oldWord) {
        if (text == null || oldWord == null || text.isEmpty() || oldWord.isEmpty()) {
            return null;
        }

        TokenParts oldParts = parseTokenParts(oldWord);
        String baseOldWord = oldParts.hasWord() ? oldParts.word : oldWord;

        int wordsAfter = (history != null && targetColIndex >= 0 && targetColIndex < history.size())
                ? (history.size() - 1 - targetColIndex)
                : 0;

        int end = text.length();
        int wordsSkipped = 0;

        while (end > 0) {
            while (end > 0 && isBoundaryChar(text, end - 1)) {
                end--;
            }
            if (end <= 0) break;

            int start = end;
            while (start > 0 && !isBoundaryChar(text, start - 1)) {
                start--;
            }

            String currentWord = text.substring(start, end);

            if (wordsSkipped == wordsAfter) {
                if (currentWord.equalsIgnoreCase(baseOldWord) || currentWord.equalsIgnoreCase(oldWord)) {
                    return new int[]{start, end};
                }
            } else if (wordsSkipped < wordsAfter) {
                wordsSkipped++;
                end = start;
                continue;
            }

            end = start;
        }

        return findLastWordBoundary(text, baseOldWord);
    }

    public static int[] findLastWordBoundary(String text, String word) {
        if (text == null || word == null || text.isEmpty() || word.isEmpty()) {
            return null;
        }
        TokenParts parts = parseTokenParts(word);
        String target = parts.hasWord() ? parts.word : word;
        int wLen = target.length();
        int tLen = text.length();

        for (int i = tLen - wLen; i >= 0; i--) {
            if (text.regionMatches(true, i, target, 0, wLen)) {
                boolean beforeOk = (i == 0) || isBoundaryChar(text, i - 1);
                boolean afterOk = (i + wLen == tLen) || isBoundaryChar(text, i + wLen);
                if (beforeOk && afterOk) {
                    return new int[]{i, i + wLen};
                }
            }
        }
        return null;
    }

    public static InputConnection getInputConnection(View view) {
        if (view == null) return null;

        // 1. Direct Context unwrapping to InputMethodService (covers Gboard LatinIME)
        Context ctx = view.getContext();
        while (ctx instanceof ContextWrapper && !(ctx instanceof InputMethodService)) {
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        if (ctx instanceof InputMethodService ims) {
            InputConnection ic = ims.getCurrentInputConnection();
            if (ic != null) return ic;
        }

        // 2. Fallback: via Gboard Reflector
        return MatrixGboardReflector.resolveInputConnectionViaHierarchy(view);
    }
}
