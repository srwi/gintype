package dev.jason.gboardpatches.extension.matrixsuggestions;

import android.content.Context;
import android.content.ContextWrapper;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.View;
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
        return (last == '.' || last == '!' || last == '?' || last == '\n' || last == '\r');
    }

    public static String getActiveWord(View view, List<GboardMatrixSuggestionsRuntime.CandidateItem> items) {
        InputConnection ic = getInputConnection(view);
        if (ic == null) return null;
        try {
            CharSequence cs = ic.getTextBeforeCursor(100, 0);
            return getActiveWord(cs, items);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getActiveWord(CharSequence cs, List<GboardMatrixSuggestionsRuntime.CandidateItem> items) {
        if (cs == null || cs.length() == 0) return null;
        String s = cs.toString();
        int len = s.length();
        if (len == 0) return null;

        int end = len;
        if (Character.isWhitespace(s.charAt(len - 1))) {
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
                if (c == '\n' || c == '.' || c == '!' || c == '?') {
                    if (i < text.length() - 1) {
                        sentStart = i + 1;
                        break;
                    }
                }
            }
            String currentSentence = text.substring(sentStart).trim();
            if (currentSentence.isEmpty()) {
                buffer.clear();
                return;
            }

            String[] rawWords = currentSentence.split("[\\s,\\;\\:\\-\\(\\)\"/]+");
            List<String> sentenceWords = new ArrayList<>();
            for (String w : rawWords) {
                String tw = w.trim();
                if (!tw.isEmpty()) {
                    sentenceWords.add(tw);
                }
            }

            if (sentenceWords.isEmpty()) {
                buffer.clear();
                return;
            }

            int historyWordCount = sentenceWords.size() - 1;
            if (currentWord == null || currentWord.isEmpty()) {
                historyWordCount = sentenceWords.size();
            }

            buffer.clear();
            for (int i = 0; i < historyWordCount; i++) {
                String w = sentenceWords.get(i);
                List<String> alts = alternativesCache != null ? alternativesCache.get(w.toLowerCase()) : null;
                int selIdx = 0;
                if (alts != null && !alts.isEmpty()) {
                    for (int a = 0; a < alts.size(); a++) {
                        if (alts.get(a).equalsIgnoreCase(w)) {
                            selIdx = a;
                            break;
                        }
                    }
                } else {
                    alts = Collections.singletonList(w);
                }
                buffer.addWord(w, alts, selIdx);
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
                String textAfter = text.substring(pos[1]);
                int deleteCount = text.length() - pos[0];
                ic.beginBatchEdit();
                ic.deleteSurroundingText(deleteCount, 0);
                ic.commitText(newWord + textAfter, 1);
                ic.endBatchEdit();
                Log.i(TAG, "replaceHistoryWord: replaced '" + oldWord + "' with '" + newWord + "' at [" + pos[0] + ", " + pos[1] + "]");
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
                if (currentWord.equalsIgnoreCase(oldWord)) {
                    return new int[]{start, end};
                }
            } else if (wordsSkipped < wordsAfter) {
                wordsSkipped++;
                end = start;
                continue;
            }

            end = start;
        }

        return findLastWordBoundary(text, oldWord);
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
