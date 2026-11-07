package dev.jason.gboardpatches.extension.matrixsuggestions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Maintains the sequence of typed words and their alternatives for 2D matrix display.
 */
public final class MatrixSentenceBuffer {

    public static final class WordEntry {
        public String committedWord;
        public final List<String> alternatives;
        public int selectedIndex;

        public WordEntry(String committedWord, List<String> alternatives, int selectedIndex) {
            this.committedWord = committedWord;
            this.alternatives = alternatives != null
                    ? Collections.unmodifiableList(new ArrayList<>(alternatives))
                    : Collections.emptyList();
            this.selectedIndex = selectedIndex;
        }

        public String getSelectedWord() {
            if (selectedIndex >= 0 && selectedIndex < alternatives.size()) {
                return alternatives.get(selectedIndex);
            }
            return committedWord;
        }
    }

    private final List<WordEntry> entries = new ArrayList<>();

    public synchronized void addWord(String word, List<String> alternatives, int selectedIndex) {
        if (word == null || word.trim().isEmpty()) {
            return;
        }

        entries.add(new WordEntry(word, alternatives, selectedIndex));
        // Keep at most last 15 words to avoid memory and layout bloat
        if (entries.size() > 15) {
            entries.remove(0);
        }
    }

    public synchronized List<WordEntry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized WordEntry getLastEntry() {
        if (entries.isEmpty()) {
            return null;
        }
        return entries.get(entries.size() - 1);
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }
}
