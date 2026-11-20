package dev.srwi.gboardmultiswipe.extension.multiswipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Memory-compact array-backed prefix Trie for O(1) per-character beam transitions
 * during MultiSwipe decoding.
 *
 * <p>Uses a dense 26-way array during bulk dictionary construction, then packs children
 * into a bitmask + flat target array on {@link #compact()} to cut heap usage by ~80-85%
 * while providing O(1) {@link #child(int, int)} lookup and O(outDegree) bitmask iteration via
 * {@link #getChildMask(int)}.
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

    private static final int LEN_MASK = 0x3F;
    private static final int FLAG_PROPER_NOUN = 0x40;
    private static final int FLAG_MULTI_FORMS = 0x80;

    private int capacity;
    /** Dense 26-way child array used during uncompacted bulk insertion; null when compacted. */
    private int[] children;
    /** 26-bit child presence bitmask per node. */
    private int[] childMask;
    /** Base index into {@link #packedChildTargets} per node when {@link #compacted} is true. */
    private int[] packedChildOffsets;
    /** Flat target node IDs ordered by set bit in {@link #childMask} when {@link #compacted} is true. */
    private int[] packedChildTargets;
    private boolean compacted = false;

    private byte[] frequency;
    private byte[] maxSubtreeFreq;
    private byte[] metaFlags;
    private String[] wordRef;

    /** Populated only for the ~6% of terminal nodes that have >= 2 distinct surface forms. */
    private final Map<Integer, List<WordForm>> multiWordForms = new HashMap<>();

    public int nodeCount = 1; // 0 is root
    public int wordCount = 0;

    public MultiSwipeTrie(int initialCapacity) {
        this.capacity = Math.max(1024, initialCapacity);
        this.children = new int[this.capacity * MultiSwipeAlphabet.ALPHABET_SIZE];
        Arrays.fill(this.children, -1);
        this.childMask = new int[this.capacity];
        this.frequency = new byte[this.capacity];
        this.maxSubtreeFreq = new byte[this.capacity];
        this.metaFlags = new byte[this.capacity];
        this.wordRef = new String[this.capacity];
    }

    private void ensureMutableAndCapacity(int needed) {
        if (compacted) {
            uncompactForMutation(Math.max(capacity + 256, needed));
        }
        if (needed <= capacity) return;
        int newCap = Math.max(capacity + (capacity >>> 1), needed);
        int oldChildrenLen = capacity * MultiSwipeAlphabet.ALPHABET_SIZE;
        int newChildrenLen = newCap * MultiSwipeAlphabet.ALPHABET_SIZE;
        children = Arrays.copyOf(children, newChildrenLen);
        Arrays.fill(children, oldChildrenLen, newChildrenLen, -1);
        childMask = Arrays.copyOf(childMask, newCap);
        frequency = Arrays.copyOf(frequency, newCap);
        maxSubtreeFreq = Arrays.copyOf(maxSubtreeFreq, newCap);
        metaFlags = Arrays.copyOf(metaFlags, newCap);
        wordRef = Arrays.copyOf(wordRef, newCap);
        capacity = newCap;
    }

    private void uncompactForMutation(int newCap) {
        int currentNodes = Math.min(nodeCount, capacity);
        int cap = Math.max(currentNodes, newCap);
        int[] dense = new int[cap * MultiSwipeAlphabet.ALPHABET_SIZE];
        Arrays.fill(dense, -1);
        for (int node = 0; node < currentNodes; node++) {
            int mask = childMask[node];
            if (mask == 0) continue;
            int offset = packedChildOffsets[node];
            int base = node * MultiSwipeAlphabet.ALPHABET_SIZE;
            int slot = 0;
            int m = mask;
            while (m != 0) {
                int c = Integer.numberOfTrailingZeros(m);
                dense[base + c] = packedChildTargets[offset + slot++];
                m &= m - 1;
            }
        }
        this.children = dense;
        this.packedChildOffsets = null;
        this.packedChildTargets = null;
        if (cap > capacity) {
            childMask = Arrays.copyOf(childMask, cap);
            frequency = Arrays.copyOf(frequency, cap);
            maxSubtreeFreq = Arrays.copyOf(maxSubtreeFreq, cap);
            metaFlags = Arrays.copyOf(metaFlags, cap);
            wordRef = Arrays.copyOf(wordRef, cap);
            capacity = cap;
        }
        this.compacted = false;
    }

    private int allocNode() {
        ensureMutableAndCapacity(nodeCount + 1);
        return nodeCount++;
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
        if (word == null || word.isEmpty()) return;
        int clampedFreq = Math.max(1, Math.min(255, freq));

        int letterLen = 0;
        for (int i = 0; i < word.length(); i++) {
            if (MultiSwipeAlphabet.charIndex(word.charAt(i)) >= 0) letterLen++;
        }
        if (letterLen == 0) return;
        int clampedLen = Math.min(LEN_MASK, letterLen);

        int cur = 0;
        updateSubtreeMeta(0, clampedFreq, clampedLen);

        for (int i = 0; i < word.length(); i++) {
            int idx = MultiSwipeAlphabet.charIndex(word.charAt(i));
            if (idx < 0) continue;

            int next = child(cur, idx);
            if (next == -1) {
                next = allocNode();
                children[cur * MultiSwipeAlphabet.ALPHABET_SIZE + idx] = next;
                childMask[cur] |= (1 << idx);
            }
            cur = next;
            updateSubtreeMeta(cur, clampedFreq, clampedLen);
        }

        String display = (displayWord != null) ? displayWord : word.toLowerCase(Locale.ROOT);
        int curFreq = frequency[cur] & 0xFF;

        if (curFreq == 0) {
            wordCount++;
            frequency[cur] = (byte) clampedFreq;
            wordRef[cur] = display;
            setProperNounFlag(cur, isProperNoun);
            return;
        }

        String primaryWord = wordRef[cur];
        boolean primaryProper = isPrimaryProperNoun(cur);

        if ((metaFlags[cur] & FLAG_MULTI_FORMS) == 0) {
            if (display.equals(primaryWord)) {
                if (clampedFreq > curFreq) {
                    frequency[cur] = (byte) clampedFreq;
                    setProperNounFlag(cur, isProperNoun);
                }
                return;
            }
            // Promote from single-form fast path to multi-form list
            List<WordForm> forms = new ArrayList<>(2);
            forms.add(new WordForm(primaryWord, curFreq, primaryProper));
            forms.add(new WordForm(display, clampedFreq, isProperNoun));
            multiWordForms.put(cur, forms);
            metaFlags[cur] = (byte) (metaFlags[cur] | FLAG_MULTI_FORMS);
            if (clampedFreq > curFreq) {
                frequency[cur] = (byte) clampedFreq;
                wordRef[cur] = display;
                setProperNounFlag(cur, isProperNoun);
            }
            return;
        }

        List<WordForm> forms = multiWordForms.get(cur);
        if (forms == null) {
            forms = new ArrayList<>(2);
            forms.add(new WordForm(primaryWord, curFreq, primaryProper));
            multiWordForms.put(cur, forms);
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
            if (clampedFreq > existing.frequency) {
                forms.set(existingIdx, new WordForm(display, clampedFreq, isProperNoun));
            }
        } else {
            forms.add(new WordForm(display, clampedFreq, isProperNoun));
        }

        if (clampedFreq > curFreq) {
            frequency[cur] = (byte) clampedFreq;
            wordRef[cur] = display;
            setProperNounFlag(cur, isProperNoun);
        }
    }

    private void updateSubtreeMeta(int node, int freq, int clampedLen) {
        if (freq > (maxSubtreeFreq[node] & 0xFF)) {
            maxSubtreeFreq[node] = (byte) freq;
        }
        int meta = metaFlags[node] & 0xFF;
        if (clampedLen > (meta & LEN_MASK)) {
            metaFlags[node] = (byte) ((meta & ~LEN_MASK) | clampedLen);
        }
    }

    private void setProperNounFlag(int node, boolean isProper) {
        if (isProper) {
            metaFlags[node] = (byte) (metaFlags[node] | FLAG_PROPER_NOUN);
        } else {
            metaFlags[node] = (byte) (metaFlags[node] & ~FLAG_PROPER_NOUN);
        }
    }

    private boolean isPrimaryProperNoun(int node) {
        return (metaFlags[node] & FLAG_PROPER_NOUN) != 0;
    }

    public int getChildMask(int node) {
        return (node >= 0 && node < nodeCount) ? childMask[node] : 0;
    }

    public int child(int node, int charIdx) {
        if (node < 0 || node >= nodeCount || charIdx < 0 || charIdx >= MultiSwipeAlphabet.ALPHABET_SIZE) {
            return -1;
        }
        int mask = childMask[node];
        int bit = 1 << charIdx;
        if ((mask & bit) == 0) {
            return -1;
        }
        if (compacted) {
            int rank = Integer.bitCount(mask & (bit - 1));
            return packedChildTargets[packedChildOffsets[node] + rank];
        }
        return children[node * MultiSwipeAlphabet.ALPHABET_SIZE + charIdx];
    }

    public boolean isWord(int node) {
        return node >= 0 && node < nodeCount && (frequency[node] & 0xFF) > 0;
    }

    public int getFrequency(int node) {
        return (node >= 0 && node < nodeCount) ? (frequency[node] & 0xFF) : 0;
    }

    public int getMaxSubtreeFreq(int node) {
        return (node >= 0 && node < nodeCount) ? (maxSubtreeFreq[node] & 0xFF) : 0;
    }

    public int getMaxWordLen(int node) {
        return (node >= 0 && node < nodeCount) ? (metaFlags[node] & LEN_MASK) : 0;
    }

    public String getWord(int node) {
        return (node >= 0 && node < nodeCount) ? wordRef[node] : null;
    }

    public List<WordForm> getWordForms(int node) {
        if (node < 0 || node >= nodeCount || (frequency[node] & 0xFF) == 0) {
            return Collections.emptyList();
        }
        if ((metaFlags[node] & FLAG_MULTI_FORMS) != 0) {
            List<WordForm> list = multiWordForms.get(node);
            if (list != null && !list.isEmpty()) {
                if (list.size() == 1) return list;
                List<WordForm> sorted = new ArrayList<>(list);
                Collections.sort(sorted);
                return sorted;
            }
        }
        String w = wordRef[node];
        if (w == null) return Collections.emptyList();
        return Collections.singletonList(
                new WordForm(w, frequency[node] & 0xFF, isPrimaryProperNoun(node)));
    }

    public int findNode(String word) {
        if (word == null) return -1;
        int cur = 0;
        for (int i = 0; i < word.length(); i++) {
            int idx = MultiSwipeAlphabet.charIndex(word.charAt(i));
            if (idx < 0) continue;
            cur = child(cur, idx);
            if (cur == -1) return -1;
        }
        return cur;
    }

    /**
     * Checks whether the given display word (case-insensitive) is already present in the trie
     * along its folded letter path.
     */
    public boolean containsWordIgnoreCase(String rawWord) {
        if (rawWord == null || rawWord.isEmpty()) return false;
        String folded = MultiSwipeAccentFolder.fold(rawWord);
        if (folded.isEmpty()) return false;
        int node = findNode(folded);
        if (node < 0 || !isWord(node)) return false;
        if ((metaFlags[node] & FLAG_MULTI_FORMS) != 0) {
            List<WordForm> forms = multiWordForms.get(node);
            if (forms != null) {
                for (int i = 0; i < forms.size(); i++) {
                    if (forms.get(i).displayWord.equalsIgnoreCase(rawWord)) {
                        return true;
                    }
                }
                return false;
            }
        }
        String primary = wordRef[node];
        return primary != null && primary.equalsIgnoreCase(rawWord);
    }

    public int lookupFrequency(String word) {
        if (word == null || word.isEmpty()) return 0;
        String folded = MultiSwipeAccentFolder.fold(word);
        if (folded.isEmpty()) return 0;
        int node = findNode(folded);
        if (node < 0 || !isWord(node)) return 0;
        if ((metaFlags[node] & FLAG_MULTI_FORMS) != 0) {
            List<WordForm> forms = multiWordForms.get(node);
            if (forms != null) {
                int bestMatch = 0;
                for (int i = 0; i < forms.size(); i++) {
                    WordForm wf = forms.get(i);
                    if (wf.displayWord.equalsIgnoreCase(word) && wf.frequency > bestMatch) {
                        bestMatch = wf.frequency;
                    }
                }
                if (bestMatch > 0) return bestMatch;
            }
        }
        return getFrequency(node);
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
            if ((metaFlags[node] & FLAG_MULTI_FORMS) != 0) {
                List<WordForm> forms = multiWordForms.get(node);
                if (forms != null && !forms.isEmpty()) {
                    for (int i = 0; i < forms.size(); i++) {
                        WordForm form = forms.get(i);
                        out.add(new WordMatch(form.displayWord, form.frequency, form.isProperNoun));
                    }
                }
            } else {
                String w = getWord(node);
                if (w != null) {
                    out.add(new WordMatch(w, getFrequency(node), isPrimaryProperNoun(node)));
                }
            }
        }
        int mask = childMask[node];
        while (mask != 0) {
            int c = Integer.numberOfTrailingZeros(mask);
            int ch = child(node, c);
            if (ch != -1) {
                collectWords(ch, out, maxCollect);
            }
            mask &= mask - 1;
        }
    }

    public void compact() {
        if (compacted) return;
        childMask = Arrays.copyOf(childMask, nodeCount);
        frequency = Arrays.copyOf(frequency, nodeCount);
        maxSubtreeFreq = Arrays.copyOf(maxSubtreeFreq, nodeCount);
        metaFlags = Arrays.copyOf(metaFlags, nodeCount);
        wordRef = Arrays.copyOf(wordRef, nodeCount);
        capacity = nodeCount;

        int totalEdges = 0;
        for (int node = 0; node < nodeCount; node++) {
            totalEdges += Integer.bitCount(childMask[node]);
        }

        int[] offsets = new int[nodeCount];
        int[] targets = new int[totalEdges];
        int cursor = 0;
        for (int node = 0; node < nodeCount; node++) {
            offsets[node] = cursor;
            int mask = childMask[node];
            if (mask == 0) continue;
            int base = node * MultiSwipeAlphabet.ALPHABET_SIZE;
            int m = mask;
            while (m != 0) {
                int c = Integer.numberOfTrailingZeros(m);
                targets[cursor++] = children[base + c];
                m &= m - 1;
            }
        }
        this.packedChildOffsets = offsets;
        this.packedChildTargets = targets;
        this.children = null;
        this.compacted = true;
    }
}
