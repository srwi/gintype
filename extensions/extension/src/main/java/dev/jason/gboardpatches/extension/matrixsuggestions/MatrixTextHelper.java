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

    public static boolean isBoundaryChar(char c) {
        return Character.isWhitespace(c) || c == '.' || c == ',' || c == '!' || c == '?'
                || c == ';' || c == ':' || c == '"' || c == '\'' || c == '-' || c == '/'
                || c == '(' || c == ')';
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
        while (start >= 0 && !isBoundaryChar(s.charAt(start))) {
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
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' || c == '-' || c == '…' || c == '\"' || c == '\'';
    }

    public static String stripTrailingPunctuation(String word) {
        if (word == null || word.isEmpty()) return "";
        int end = word.length();
        while (end > 0 && isPunctuationChar(word.charAt(end - 1))) {
            end--;
        }
        return word.substring(0, end);
    }

    public static String getTrailingPunctuation(String word) {
        if (word == null || word.isEmpty()) return "";
        int end = word.length();
        while (end > 0 && isPunctuationChar(word.charAt(end - 1))) {
            end--;
        }
        return word.substring(end);
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

            List<String> rawTokens = new ArrayList<>();
            for (String t : currentSentence.split("\\s+")) {
                String tr = t.trim();
                if (!tr.isEmpty()) {
                    rawTokens.add(tr);
                }
            }

            List<String> wordsWithPunct = new ArrayList<>();
            for (String token : rawTokens) {
                String base = stripTrailingPunctuation(token);
                String punct = getTrailingPunctuation(token);
                if (!base.isEmpty()) {
                    wordsWithPunct.add(token);
                } else if (!punct.isEmpty() && !wordsWithPunct.isEmpty()) {
                    int lastIdx = wordsWithPunct.size() - 1;
                    wordsWithPunct.set(lastIdx, wordsWithPunct.get(lastIdx) + punct);
                }
            }

            if (wordsWithPunct.isEmpty()) {
                buffer.clear();
                return;
            }

            int historyWordCount = wordsWithPunct.size() - 1;
            if (currentWord == null || currentWord.isEmpty()) {
                historyWordCount = wordsWithPunct.size();
            }

            buffer.clear();
            for (int i = 0; i < historyWordCount; i++) {
                String token = wordsWithPunct.get(i);
                String baseWord = stripTrailingPunctuation(token);
                String punct = getTrailingPunctuation(token);

                List<String> rawAlts = alternativesCache != null ? alternativesCache.get(baseWord.toLowerCase()) : null;
                List<String> formattedAlts = new ArrayList<>();
                int selIdx = 0;

                if (rawAlts != null && !rawAlts.isEmpty()) {
                    for (int a = 0; a < rawAlts.size(); a++) {
                        String rawAlt = rawAlts.get(a);
                        formattedAlts.add(rawAlt + punct);
                        if (rawAlt.equalsIgnoreCase(baseWord)) {
                            selIdx = a;
                        }
                    }
                } else {
                    formattedAlts.add(token);
                }

                buffer.addWord(token, formattedAlts, selIdx);
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
            while (start > 0 && !isBoundaryChar(text.charAt(start - 1))) {
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
                String cleanNewWord = stripTrailingPunctuation(newWord);
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

        String baseOldWord = stripTrailingPunctuation(oldWord);
        if (baseOldWord.isEmpty()) {
            baseOldWord = oldWord;
        }

        int wordsAfter = (history != null && targetColIndex >= 0 && targetColIndex < history.size())
                ? (history.size() - 1 - targetColIndex)
                : 0;

        int end = text.length();
        int wordsSkipped = 0;

        while (end > 0) {
            while (end > 0 && isBoundaryChar(text.charAt(end - 1))) {
                end--;
            }
            if (end <= 0) break;

            int start = end;
            while (start > 0 && !isBoundaryChar(text.charAt(start - 1))) {
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
        int wLen = word.length();
        int tLen = text.length();

        for (int i = tLen - wLen; i >= 0; i--) {
            if (text.regionMatches(true, i, word, 0, wLen)) {
                boolean beforeOk = (i == 0) || isBoundaryChar(text.charAt(i - 1));
                boolean afterOk = (i + wLen == tLen) || isBoundaryChar(text.charAt(i + wLen));
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
