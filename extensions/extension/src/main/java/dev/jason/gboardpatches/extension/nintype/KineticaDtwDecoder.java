package dev.jason.gboardpatches.extension.nintype;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Continuous Dynamic Time Warping (DTW) multi-finger & single-finger swipe decoder.
 * Ported 1:1 from Kinetica's WordPredictor.kt, DtwMatcher.kt & ThumbBeam.kt.
 *
 * Uses continuous geometric curve matching and dual-thumb beam search.
 */
public final class KineticaDtwDecoder {

    public static final class Candidate implements Comparable<Candidate> {
        public final String word;
        public final float score;
        public final float dTotal;
        public final boolean isProperNoun;

        public Candidate(String word, float score, float dTotal, boolean isProperNoun) {
            this.word = word;
            this.score = score;
            this.dTotal = dTotal;
            this.isProperNoun = isProperNoun;
        }

        @Override
        public int compareTo(Candidate other) {
            // Min-heap order by score (lowest score at root)
            return Float.compare(this.score, other.score);
        }
    }

    public static final class CandidateHeap {
        private final int capacity;
        private final PriorityQueue<Candidate> pq;
        private final Set<String> words;

        public CandidateHeap(int capacity) {
            this.capacity = capacity;
            this.pq = new PriorityQueue<>(capacity);
            this.words = new HashSet<>(capacity * 2);
        }

        public void offer(Candidate c) {
            if (c == null || c.word == null || c.word.isEmpty()) return;
            if (words.contains(c.word)) {
                for (Candidate existing : pq) {
                    if (existing.word.equals(c.word)) {
                        if (c.score > existing.score) {
                            pq.remove(existing);
                            pq.offer(c);
                        }
                        return;
                    }
                }
                return;
            }

            if (pq.size() < capacity) {
                pq.offer(c);
                words.add(c.word);
            } else if (c.score > pq.peek().score) {
                Candidate dropped = pq.poll();
                if (dropped != null) words.remove(dropped.word);
                pq.offer(c);
                words.add(c.word);
            }
        }

        public List<NintypeInputToken.WordCandidate> sortedDescending() {
            List<Candidate> list = new ArrayList<>(pq);
            list.sort((a, b) -> Float.compare(b.score, a.score));
            List<NintypeInputToken.WordCandidate> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Candidate c = list.get(i);
                out.add(new NintypeInputToken.WordCandidate(c.word, c.score, c.dTotal, 1.0f, 1.0f));
            }
            return out;
        }

        public boolean isEmpty() {
            return pq.isEmpty();
        }

        public int size() {
            return pq.size();
        }
    }

    private final DtwMatcher dtw = new DtwMatcher();
    private final float[] idealPathScratch = new float[2 * KineticaConstants.RESAMPLE_N];
    private final int[] wordLetters = new int[KineticaConstants.MAX_WORD_LEN + 2];

    public List<NintypeInputToken.WordCandidate> decode(
            List<NintypeInputToken> tokens,
            NintypeTrie trie,
            NintypeBigramTable bigrams,
            NintypeGeometry geometry,
            String prevWord
    ) {
        if (tokens == null || tokens.isEmpty() || trie == null || geometry == null || !geometry.isValid()) {
            return Collections.emptyList();
        }

        List<KineticaMatcher> items = new ArrayList<>(tokens.size());
        List<KineticaMatcher.Anchor> anchors = new ArrayList<>(tokens.size());
        boolean hasSwipe = false;

        for (int i = 0; i < tokens.size(); i++) {
            NintypeInputToken t = tokens.get(i);
            if (t.kind == NintypeInputToken.Kind.SWIPE && t.points != null && !t.points.isEmpty()) {
                hasSwipe = true;
                List<DtwMatcher.Point2D> ptsKw = new ArrayList<>(t.points.size());
                for (int j = 0; j < t.points.size(); j++) {
                    NintypeInputToken.TouchPoint tp = t.points.get(j);
                    ptsKw.add(new DtwMatcher.Point2D(
                            tp.x / geometry.keyWidthPx,
                            tp.y / geometry.keyWidthPx,
                            tp.timeMs
                    ));
                }
                KineticaMatcher.Segment seg = KineticaMatcher.buildSegment(
                        ptsKw, t.startTimeMs, t.endTimeMs, geometry, t.streamId, dtw
                );
                items.add(seg);
            } else if (t.kind == NintypeInputToken.Kind.TAP) {
                int code = NintypeAlphabet.charIndex(t.character);
                if (code >= 0) {
                    KineticaMatcher.Anchor anc = new KineticaMatcher.Anchor(
                            code,
                            t.tapX / geometry.keyWidthPx,
                            t.tapY / geometry.keyWidthPx,
                            t.startTimeMs,
                            t.streamId
                    );
                    items.add(anc);
                    anchors.add(anc);
                }
            }
        }

        CandidateHeap heap = new CandidateHeap(KineticaConstants.TOP_K);

        if (!hasSwipe) {
            // Pure tap decode
            decodeTapsOnly(anchors, trie, bigrams, geometry, prevWord, heap);
            return heap.sortedDescending();
        }

        // Fast DFS pass for single swipe
        if (tokens.size() == 1 && items.size() == 1 && items.get(0) instanceof KineticaMatcher.Segment) {
            KineticaMatcher.Segment seg = (KineticaMatcher.Segment) items.get(0);
            searchSingle(0, 0, -1, seg, trie, geometry, bigrams, prevWord, heap);
        }

        // Beam search pass for multi-finger / chorded / interleaved gestures
        if (ThumbBeam.applies(tokens, items)) {
            ThumbBeam beam = new ThumbBeam(tokens, items, geometry, trie, bigrams, prevWord, heap, dtw);
            beam.run();
        }

        return heap.sortedDescending();
    }

    /**
     * Single-finger swipe DFS search with continuous DTW.
     */
    private void searchSingle(
            int node,
            int depth,
            int prevIdx,
            KineticaMatcher.Segment seg,
            NintypeTrie trie,
            NintypeGeometry geometry,
            NintypeBigramTable bigrams,
            String prevWord,
            CandidateHeap heap
    ) {
        if (depth >= KineticaConstants.MAX_WORD_LEN) return;

        if (depth == 0) {
            for (int c = 0; c < 26; c++) {
                if (!seg.isStart(c)) continue;
                int ch = trie.child(node, c);
                if (ch == -1) continue;

                int pass = seg.passAtOrAfter(c, -KineticaConstants.MONOTONE_SLACK);
                if (pass == -1) continue;

                wordLetters[0] = c;
                searchSingle(ch, 1, pass, seg, trie, geometry, bigrams, prevWord, heap);
            }
            return;
        }

        if (trie.isWord(node)) {
            int lastCode = wordLetters[depth - 1];
            if (depth >= seg.minLetters && depth <= seg.maxLetters && seg.isEnd(lastCode)) {
                emitSingleCandidate(node, depth, seg, trie, geometry, bigrams, prevWord, heap);
            }
        }

        for (int c = 0; c < 26; c++) {
            if (!seg.isNear(c)) continue;

            int nextIdx = seg.passAtOrAfter(c, prevIdx - KineticaConstants.MONOTONE_SLACK);
            if (nextIdx == -1) continue;

            int ch = trie.child(node, c);
            if (ch == -1) continue;

            wordLetters[depth] = c;
            searchSingle(ch, depth + 1, Math.max(prevIdx, nextIdx), seg, trie, geometry, bigrams, prevWord, heap);
        }
    }

    private void emitSingleCandidate(
            int node,
            int length,
            KineticaMatcher.Segment seg,
            NintypeTrie trie,
            NintypeGeometry geometry,
            NintypeBigramTable bigrams,
            String prevWord,
            CandidateHeap heap
    ) {
        boolean hasPath = dtw.idealPath(wordLetters, 0, length, geometry, idealPathScratch);
        if (!hasPath) return;

        float accum = dtw.distanceAccum(seg.resampled, idealPathScratch, Float.POSITIVE_INFINITY);
        if (Float.isInfinite(accum)) return;

        float geoFit = accum / (float) KineticaConstants.RESAMPLE_N;
        float geo = KineticaConstants.geometricTerm(geoFit);

        int rawFreq = trie.getFrequency(node);
        float fw = KineticaConstants.FREQ_WEIGHT_FLOOR +
                (1.0f - KineticaConstants.FREQ_WEIGHT_FLOOR) * (rawFreq / 255.0f);

        float bm = 1.0f;
        String word = trie.getWord(node);
        if (prevWord != null && bigrams != null && word != null) {
            bm = 1.0f + bigrams.score(prevWord, word);
        }
        float bmApplied = KineticaConstants.appliedBoost(bm, geoFit);
        float score = fw * geo * bmApplied;

        List<NintypeTrie.WordForm> forms = trie.getWordForms(node);
        if (forms != null && !forms.isEmpty()) {
            for (int i = 0; i < forms.size(); i++) {
                NintypeTrie.WordForm f = forms.get(i);
                float fv = KineticaConstants.FREQ_WEIGHT_FLOOR +
                        (1.0f - KineticaConstants.FREQ_WEIGHT_FLOOR) * (f.frequency / 255.0f);
                heap.offer(new Candidate(f.displayWord, fv * geo * bmApplied, geoFit, f.isProperNoun));
            }
        } else if (word != null) {
            heap.offer(new Candidate(word, score, geoFit, false));
        }
    }

    private void decodeTapsOnly(
            List<KineticaMatcher.Anchor> anchors,
            NintypeTrie trie,
            NintypeBigramTable bigrams,
            NintypeGeometry geometry,
            String prevWord,
            CandidateHeap heap
    ) {
        StringBuilder sb = new StringBuilder(anchors.size());
        for (int i = 0; i < anchors.size(); i++) {
            sb.append(NintypeAlphabet.indexToChar(anchors.get(i).code));
        }
        String literal = sb.toString();
        int node = trie.findNode(literal);
        int freq = (node >= 0) ? trie.getFrequency(node) : 50;

        float fw = KineticaConstants.FREQ_WEIGHT_FLOOR +
                (1.0f - KineticaConstants.FREQ_WEIGHT_FLOOR) * (Math.min(255, freq) / 255.0f);
        float score = fw * 1.0f;

        if (node >= 0 && trie.isWord(node)) {
            List<NintypeTrie.WordForm> forms = trie.getWordForms(node);
            if (forms != null && !forms.isEmpty()) {
                for (int i = 0; i < forms.size(); i++) {
                    NintypeTrie.WordForm f = forms.get(i);
                    heap.offer(new Candidate(f.displayWord, score, 0f, f.isProperNoun));
                }
                return;
            }
        }
        heap.offer(new Candidate(literal, score, 0f, false));
    }
}
