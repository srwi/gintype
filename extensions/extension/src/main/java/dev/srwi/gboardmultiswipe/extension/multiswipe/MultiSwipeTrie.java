package dev.srwi.gboardmultiswipe.extension.multiswipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Array-backed prefix Trie for O(1) per-character beam transitions during MultiSwipe decoding.
 * Ported from Kinetica's Trie.kt.
 */
public final class MultiSwipeTrie {

    public static final class WordForm implements Comparable<WordForm> {
        public final String displayWord;
        public final int frequency;
        public final boolean isProperNoun;

        public WordForm(String displayWord, int frequency, boolean isProperNoun) {
            this.displayWord = displayWord;
            this.frequency = frequency;
            this.isProperNoun = isProperNoun;
        }

        @Override
        public int compareTo(WordForm other) {
            return Integer.compare(other.frequency, this.frequency);
        }
    }

    public static final class WordMatch {
        public final String word;
        public final int frequency;
        public final boolean isProperNoun;

        public WordMatch(String word, int frequency, boolean isProperNoun) {
            this.word = word;
            this.frequency = frequency;
            this.isProperNoun = isProperNoun;
        }
    }

    private int capacity;
    private int[] children;
    private int[] frequency;
    private int[] maxSubtreeFreq;
    private int[] maxWordLen;
    private String[] wordRef;

    private final Map<Integer, List<WordForm>> wordForms = new HashMap<>();
    private final Map<String, Integer> wordFreqLookup = new HashMap<>();

    public int nodeCount = 1; // 0 is root
    public int wordCount = 0;

    public MultiSwipeTrie(int initialCapacity) {
        this.capacity = Math.max(1024, initialCapacity);
        this.children = new int[this.capacity * MultiSwipeAlphabet.ALPHABET_SIZE];
        Arrays.fill(this.children, -1);
        this.frequency = new int[this.capacity];
        this.maxSubtreeFreq = new int[this.capacity];
        this.maxWordLen = new int[this.capacity];
        this.wordRef = new String[this.capacity];
    }

    private void ensureCapacity(int needed) {
        if (needed <= capacity) return;
        int newCap = Math.max(capacity * 2, needed);
        int oldChildrenLen = capacity * MultiSwipeAlphabet.ALPHABET_SIZE;
        int newChildrenLen = newCap * MultiSwipeAlphabet.ALPHABET_SIZE;
        children = Arrays.copyOf(children, newChildrenLen);
        Arrays.fill(children, oldChildrenLen, newChildrenLen, -1);
        frequency = Arrays.copyOf(frequency, newCap);
        maxSubtreeFreq = Arrays.copyOf(maxSubtreeFreq, newCap);
        maxWordLen = Arrays.copyOf(maxWordLen, newCap);
        wordRef = Arrays.copyOf(wordRef, newCap);
        capacity = newCap;
    }

    private int allocNode() {
        int id = nodeCount++;
        ensureCapacity(nodeCount);
        return id;
    }

    /**
     * Log-quantizes a raw corpus frequency count into [1, 255], matching Kinetica's Trie.freqByteFor.
     */
    public static int freqByteFor(int count, long maxCount) {
        if (count <= 0) return 1;
        long effectiveMax = Math.max(1L, maxCount);
        double ratio = Math.log(1.0 + count) / Math.log(1.0 + effectiveMax);
        return Math.max(1, Math.min(255, (int) (255.0 * ratio)));
    }

    public void insert(String word, int freq) {
        insert(word, freq, null, false);
    }

    public void insert(String word, int freq, String displayWord, boolean isProperNoun) {
        int cur = 0;
        if (freq > maxSubtreeFreq[0]) {
            maxSubtreeFreq[0] = freq;
        }
        int letterLen = 0;
        for (int i = 0; i < word.length(); i++) {
            if (MultiSwipeAlphabet.charIndex(word.charAt(i)) >= 0) letterLen++;
        }
        if (letterLen > maxWordLen[0]) {
            maxWordLen[0] = letterLen;
        }

        for (int i = 0; i < word.length(); i++) {
            int idx = MultiSwipeAlphabet.charIndex(word.charAt(i));
            if (idx < 0) continue;

            int base = cur * MultiSwipeAlphabet.ALPHABET_SIZE + idx;
            int next = children[base];
            if (next == -1) {
                next = allocNode();
                children[base] = next;
            }
            cur = next;
            if (freq > maxSubtreeFreq[cur]) {
                maxSubtreeFreq[cur] = freq;
            }
            if (letterLen > maxWordLen[cur]) {
                maxWordLen[cur] = letterLen;
            }
        }

        String display = (displayWord != null) ? displayWord : word.toLowerCase();

        if (frequency[cur] == 0) {
            wordCount++;
        }
        if (freq > frequency[cur]) {
            frequency[cur] = freq;
            wordRef[cur] = display;
        }

        List<WordForm> forms = wordForms.get(cur);
        if (forms == null) {
            forms = new ArrayList<>(2);
            wordForms.put(cur, forms);
        }
        int existingIdx = -1;
        for (int i = 0; i < forms.size(); i++) {
            if (forms.get(i).displayWord.equals(display)) {
                existingIdx = i;
                break;
            }
        }
        if (existingIdx >= 0) {
            WordForm existing = forms.get(existingIdx);
            if (freq > existing.frequency) {
                forms.set(existingIdx, new WordForm(display, freq, isProperNoun));
            }
        } else {
            forms.add(new WordForm(display, freq, isProperNoun));
        }

        String lowerKey = display.toLowerCase();
        Integer prevFreq = wordFreqLookup.get(lowerKey);
        if (prevFreq == null || freq > prevFreq) {
            wordFreqLookup.put(lowerKey, freq);
        }
    }

    public int child(int node, int charIdx) {
        if (node < 0 || charIdx < 0 || charIdx >= MultiSwipeAlphabet.ALPHABET_SIZE) return -1;
        return children[node * MultiSwipeAlphabet.ALPHABET_SIZE + charIdx];
    }

    public boolean isWord(int node) {
        return node >= 0 && frequency[node] > 0;
    }

    public int getFrequency(int node) {
        return node >= 0 ? frequency[node] : 0;
    }

    public int getMaxSubtreeFreq(int node) {
        return node >= 0 ? maxSubtreeFreq[node] : 0;
    }

    public int getMaxWordLen(int node) {
        return node >= 0 ? maxWordLen[node] : 0;
    }

    public String getWord(int node) {
        return node >= 0 ? wordRef[node] : null;
    }

    public List<WordForm> getWordForms(int node) {
        if (node < 0) return Collections.emptyList();
        List<WordForm> list = wordForms.get(node);
        if (list == null || list.isEmpty()) return Collections.emptyList();
        if (list.size() == 1) return list;
        List<WordForm> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        return sorted;
    }

    public int findNode(String word) {
        int cur = 0;
        for (int i = 0; i < word.length(); i++) {
            int idx = MultiSwipeAlphabet.charIndex(word.charAt(i));
            if (idx < 0) continue;
            cur = child(cur, idx);
            if (cur == -1) return -1;
        }
        return cur;
    }

    public int lookupFrequency(String word) {
        Integer fast = wordFreqLookup.get(word.toLowerCase());
        if (fast != null) return fast;
        String folded = MultiSwipeAccentFolder.fold(word);
        int node = findNode(folded);
        return (node >= 0 && isWord(node)) ? getFrequency(node) : 0;
    }

    public List<WordMatch> wordsWithPrefix(String prefix, int limit) {
        String foldedPrefix = MultiSwipeAccentFolder.fold(prefix);
        int startNode = findNode(foldedPrefix);
        if (startNode == -1) return Collections.emptyList();

        List<WordMatch> results = new ArrayList<>();
        collectWords(startNode, results, limit * 4);
        results.sort((a, b) -> Integer.compare(b.frequency, a.frequency));
        if (results.size() > limit) {
            return new ArrayList<>(results.subList(0, limit));
        }
        return results;
    }

    private void collectWords(int node, List<WordMatch> out, int maxCollect) {
        if (out.size() >= maxCollect) return;
        if (isWord(node)) {
            List<WordForm> forms = wordForms.get(node);
            if (forms != null && !forms.isEmpty()) {
                for (WordForm form : forms) {
                    out.add(new WordMatch(form.displayWord, form.frequency, form.isProperNoun));
                }
            } else {
                String w = getWord(node);
                if (w != null) {
                    out.add(new WordMatch(w, getFrequency(node), false));
                }
            }
        }
        int base = node * MultiSwipeAlphabet.ALPHABET_SIZE;
        for (int c = 0; c < MultiSwipeAlphabet.ALPHABET_SIZE; c++) {
            int ch = children[base + c];
            if (ch != -1) {
                collectWords(ch, out, maxCollect);
            }
        }
    }

    public void compact() {
        children = Arrays.copyOf(children, nodeCount * MultiSwipeAlphabet.ALPHABET_SIZE);
        frequency = Arrays.copyOf(frequency, nodeCount);
        maxSubtreeFreq = Arrays.copyOf(maxSubtreeFreq, nodeCount);
        maxWordLen = Arrays.copyOf(maxWordLen, nodeCount);
        wordRef = Arrays.copyOf(wordRef, nodeCount);
        capacity = nodeCount;
    }
}
