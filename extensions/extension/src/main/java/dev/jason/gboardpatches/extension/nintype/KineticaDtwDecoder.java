package dev.jason.gboardpatches.extension.nintype;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Continuous Dynamic Time Warping (DTW) multi-finger & single-finger swipe decoder.
 * Ported 1:1 from Kinetica's WordPredictor.kt, MergeAlternatives.kt, DtwMatcher.kt & ThumbBeam.kt.
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

        public boolean isFull() {
            return pq.size() >= capacity;
        }

        public float minScore() {
            if (pq.isEmpty()) return 0f;
            return pq.peek().score;
        }

        public List<Candidate> sortedCandidatesDescending() {
            List<Candidate> list = new ArrayList<>(pq);
            list.sort((a, b) -> Float.compare(b.score, a.score));
            return list;
        }

        public List<NintypeInputToken.WordCandidate> sortedDescending() {
            return toWordCandidates(sortedCandidatesDescending());
        }

        public boolean isEmpty() {
            return pq.isEmpty();
        }

        public int size() {
            return pq.size();
        }
    }

    private static List<NintypeInputToken.WordCandidate> toWordCandidates(List<Candidate> list) {
        List<NintypeInputToken.WordCandidate> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Candidate c = list.get(i);
            out.add(new NintypeInputToken.WordCandidate(c.word, c.score, c.dTotal, 1.0f, 1.0f));
        }
        return out;
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

        boolean hasSwipe = false;
        List<KineticaMatcher.Anchor> rawAnchors = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            NintypeInputToken t = tokens.get(i);
            if (t.kind == NintypeInputToken.Kind.SWIPE && t.points != null && !t.points.isEmpty()) {
                hasSwipe = true;
            } else if (t.kind == NintypeInputToken.Kind.TAP) {
                int code = NintypeGeometry.foldSupportedCharIndex(t.character);
                if (code >= 0) {
                    rawAnchors.add(new KineticaMatcher.Anchor(
                            code,
                            t.tapX / geometry.keyWidthPx,
                            t.tapY / geometry.keyWidthPx,
                            t.startTimeMs,
                            t.streamId
                    ));
                }
            }
        }

        if (!hasSwipe) {
            CandidateHeap tapHeap = new CandidateHeap(KineticaConstants.TOP_K);
            decodeTapsOnly(rawAnchors, trie, bigrams, geometry, prevWord, tapHeap);
            return tapHeap.sortedDescending();
        }

        List<List<NintypeInputToken>> seqs = MergeAlternatives.sequences(tokens, geometry);
        List<List<KineticaMatcher>> patterns = new ArrayList<>(seqs.size());
        List<List<NintypeInputToken>> validSeqs = new ArrayList<>(seqs.size());
        for (int i = 0; i < seqs.size(); i++) {
            List<KineticaMatcher> p = buildPattern(seqs.get(i), geometry);
            if (p != null && !p.isEmpty()) {
                patterns.add(p);
                validSeqs.add(seqs.get(i));
            }
        }
        if (patterns.isEmpty()) {
            return Collections.emptyList();
        }

        CandidateHeap heap = new CandidateHeap(KineticaConstants.TOP_K);

        // 1) Primary DFS passes across MergeAlternatives sequences (exact anchors)
        for (int i = 0; i < patterns.size(); i++) {
            runSearch(patterns.get(i), trie, bigrams, geometry, prevWord, heap, false, false, 0f);
        }

        // 2) Dual-thumb beam search on primary sequence + Kinetica mergeBeam rules
        List<Candidate> merged;
        if (ThumbBeam.applies(validSeqs.get(0), patterns.get(0))) {
            CandidateHeap beamHeap = new CandidateHeap(KineticaConstants.TOP_K);
            ThumbBeam beam = new ThumbBeam(
                    validSeqs.get(0), patterns.get(0), geometry, trie, bigrams, prevWord, beamHeap, dtw);
            beam.run();
            merged = mergeBeam(heap.sortedCandidatesDescending(), beamHeap.sortedCandidatesDescending());
            heap = new CandidateHeap(KineticaConstants.TOP_K);
            for (int i = 0; i < merged.size(); i++) {
                heap.offer(merged.get(i));
            }
        } else {
            merged = heap.sortedCandidatesDescending();
        }

        // 3) Fuzzy anchor & adjacent-tap transposition fallbacks when list is underfilled
        if (heap.size() < KineticaConstants.TOP_K) {
            boolean anyAnchors = hasAnchors(patterns.get(0));
            if (anyAnchors) {
                for (int i = 0; i < patterns.size(); i++) {
                    runSearch(patterns.get(i), trie, bigrams, geometry, prevWord, heap, true, false, 0f);
                }
            }
            if (heap.size() < KineticaConstants.TOP_K && anyAnchors) {
                List<List<KineticaMatcher>> transposed = transposedPatterns(patterns.get(0));
                for (int i = 0; i < transposed.size(); i++) {
                    runSearch(
                            transposed.get(i), trie, bigrams, geometry, prevWord, heap,
                            false, false, KineticaConstants.TRANSPOSE_PENALTY);
                }
            }
            merged = heap.sortedCandidatesDescending();
        }

        // 4) Rescue pass when nothing decoded or lead distance > RESCUE_MIN_LEAD_D
        Candidate lead = merged.isEmpty() ? null : merged.get(0);
        if (lead == null || lead.dTotal > KineticaConstants.RESCUE_MIN_LEAD_D) {
            int rescueLimit = Math.min(patterns.size(), 4);
            for (int i = 0; i < rescueLimit; i++) {
                runSearch(patterns.get(i), trie, bigrams, geometry, prevWord, heap, true, true, 0f);
            }
            merged = heap.sortedCandidatesDescending();
        }

        return toWordCandidates(merged);
    }

    private List<KineticaMatcher> buildPattern(List<NintypeInputToken> seq, NintypeGeometry geometry) {
        List<KineticaMatcher> items = new ArrayList<>(seq.size());
        for (int i = 0; i < seq.size(); i++) {
            NintypeInputToken t = seq.get(i);
            if (t.kind == NintypeInputToken.Kind.SWIPE && t.points != null && !t.points.isEmpty()) {
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
                        ptsKw, t.startTimeMs, t.endTimeMs, geometry, t.streamId, dtw,
                        t.keyContacts, t.softStart, t.softEnd
                );
                items.add(seg);
            } else if (t.kind == NintypeInputToken.Kind.TAP) {
                int code = NintypeGeometry.foldSupportedCharIndex(t.character);
                if (code < 0 || !geometry.hasKey(code)) {
                    // Fallback to nearest key if tap was on umlaut/accent not present
                    NintypeGeometry.KeySpec k = geometry.nearestLetterKey(t.tapX, t.tapY);
                    code = (k != null) ? NintypeGeometry.foldSupportedCharIndex(k.character) : -1;
                }
                if (code < 0 || !geometry.hasKey(code)) return null;
                items.add(new KineticaMatcher.Anchor(
                        code,
                        t.tapX / geometry.keyWidthPx,
                        t.tapY / geometry.keyWidthPx,
                        t.startTimeMs,
                        t.streamId
                ));
            }
        }
        return items;
    }

    private static boolean hasAnchors(List<KineticaMatcher> pattern) {
        for (int i = 0; i < pattern.size(); i++) {
            if (pattern.get(i) instanceof KineticaMatcher.Anchor) return true;
        }
        return false;
    }

    private static List<List<KineticaMatcher>> transposedPatterns(List<KineticaMatcher> pattern) {
        List<List<KineticaMatcher>> out = new ArrayList<>();
        for (int i = 0; i < pattern.size() - 1; i++) {
            KineticaMatcher m0 = pattern.get(i);
            KineticaMatcher m1 = pattern.get(i + 1);
            if (!(m0 instanceof KineticaMatcher.Anchor) || !(m1 instanceof KineticaMatcher.Anchor)) continue;
            KineticaMatcher.Anchor a = (KineticaMatcher.Anchor) m0;
            KineticaMatcher.Anchor b = (KineticaMatcher.Anchor) m1;
            if (a.code == b.code) continue;
            List<KineticaMatcher> v = new ArrayList<>(pattern);
            v.set(i, b);
            v.set(i + 1, a);
            out.add(v);
        }
        return out;
    }

    private static List<Candidate> mergeBeam(List<Candidate> passes, List<Candidate> beam) {
        if (passes.isEmpty()) return beam;
        Set<String> known = new HashSet<>(passes.size() * 2);
        for (int i = 0; i < passes.size(); i++) {
            known.add(passes.get(i).word);
        }
        float lead = passes.get(0).score;
        List<Candidate> out = new ArrayList<>(KineticaConstants.TOP_K);
        int added = 0;
        int filled = 0;
        for (int i = 0; i < beam.size(); i++) {
            Candidate c = beam.get(i);
            if (known.contains(c.word)) continue;
            if (added < KineticaConstants.BEAM_MAX_NEW && c.score > lead) {
                out.add(c);
                added++;
            } else if (passes.size() + added + filled < KineticaConstants.TOP_K) {
                out.add(c);
                filled++;
            }
        }
        Map<String, Candidate> byWord = new HashMap<>(beam.size() * 2);
        for (int i = 0; i < beam.size(); i++) {
            Candidate c = beam.get(i);
            byWord.put(c.word, c);
        }
        for (int i = 0; i < passes.size(); i++) {
            Candidate c = passes.get(i);
            Candidate b = byWord.get(c.word);
            out.add((b != null && b.score > c.score) ? b : c);
        }
        out.sort((a, b) -> Float.compare(b.score, a.score));
        if (out.size() > KineticaConstants.TOP_K) {
            return new ArrayList<>(out.subList(0, KineticaConstants.TOP_K));
        }
        return out;
    }

    private void runSearch(
            List<KineticaMatcher> pattern,
            NintypeTrie trie,
            NintypeBigramTable bigrams,
            NintypeGeometry geometry,
            String prevWord,
            CandidateHeap heap,
            boolean fuzzyAnchors,
            boolean rescue,
            float extraPenalty
    ) {
        Search search = new Search(
                pattern, trie, bigrams, geometry, prevWord, heap, fuzzyAnchors, rescue, extraPenalty);
        search.run();
    }

    private final class Search {
        private final List<KineticaMatcher> pattern;
        private final NintypeTrie trie;
        private final NintypeBigramTable bigrams;
        private final NintypeGeometry geometry;
        private final String prevWord;
        private final CandidateHeap heap;
        private final boolean fuzzyAnchors;
        private final boolean rescue;
        private final float extraPenalty;

        private final int maxAttempts;
        private final int stepBudget;
        private final int[] restMin;
        private final KineticaMatcher.Segment[] pieceSeg =
                new KineticaMatcher.Segment[KineticaConstants.MAX_WORD_LEN + 1];
        private final int[] pieceFrom = new int[KineticaConstants.MAX_WORD_LEN + 1];
        private final int[] pieceTo = new int[KineticaConstants.MAX_WORD_LEN + 1];
        private final int[][] childOrder = new int[KineticaConstants.MAX_WORD_LEN + 1][26];

        private int pieceCount = 0;
        private int emitted = 0;
        private int attempts = 0;
        private int steps = 0;
        private int emitCap = KineticaConstants.MAX_CANDIDATES;

        Search(
                List<KineticaMatcher> pattern,
                NintypeTrie trie,
                NintypeBigramTable bigrams,
                NintypeGeometry geometry,
                String prevWord,
                CandidateHeap heap,
                boolean fuzzyAnchors,
                boolean rescue,
                float extraPenalty
        ) {
            this.pattern = pattern;
            this.trie = trie;
            this.bigrams = bigrams;
            this.geometry = geometry;
            this.prevWord = prevWord;
            this.heap = heap;
            this.fuzzyAnchors = fuzzyAnchors;
            this.rescue = rescue;
            this.extraPenalty = extraPenalty;
            this.maxAttempts = rescue
                    ? KineticaConstants.RESCUE_MAX_ATTEMPTS
                    : KineticaConstants.MAX_EMIT_ATTEMPTS;
            this.stepBudget = rescue
                    ? KineticaConstants.RESCUE_STEP_BUDGET
                    : KineticaConstants.STREAM_SEARCH_STEP_BUDGET;

            this.restMin = new int[pattern.size() + 1];
            for (int j = pattern.size() - 1; j >= 0; j--) {
                KineticaMatcher m = pattern.get(j);
                int need = (m instanceof KineticaMatcher.Segment)
                        ? Math.max(1, ((KineticaMatcher.Segment) m).minLetters)
                        : 1;
                this.restMin[j] = this.restMin[j + 1] + need;
            }
        }

        private boolean branchExhausted() {
            return emitted >= emitCap || attempts >= maxAttempts || steps >= stepBudget;
        }

        private boolean passExhausted() {
            return emitted >= KineticaConstants.MAX_CANDIDATES
                    || attempts >= maxAttempts
                    || steps >= stepBudget;
        }

        void run() {
            descend(0, 0, 0, 0, 0, -1, -1, 0f, 0f, 1.0f, 1.0f);
        }

        private int sortedChildren(int node, int depth, int[] outOrder) {
            int count = 0;
            for (int c = 0; c < 26; c++) {
                if (trie.child(node, c) != -1) {
                    outOrder[count++] = c;
                }
            }
            // Insertion sort descending by maxSubtreeFreq for fast best-first traversal
            for (int i = 1; i < count; i++) {
                int key = outOrder[i];
                int keyFreq = trie.getMaxSubtreeFreq(trie.child(node, key));
                int j = i - 1;
                while (j >= 0 && trie.getMaxSubtreeFreq(trie.child(node, outOrder[j])) < keyFreq) {
                    outOrder[j + 1] = outOrder[j];
                    j--;
                }
                outOrder[j + 1] = key;
            }
            return count;
        }

        private void descend(
                int node,
                int depth,
                int ti,
                int segStartDepth,
                int segConsumed,
                int segPrevPass,
                int segLastCode,
                float segIdeal,
                float tapPen,
                float keep,
                float segKeep
        ) {
            if (branchExhausted()) return;
            steps++;
            if (steps >= stepBudget) return;

            if (ti == pattern.size()) {
                if (trie.isWord(node)) {
                    emit(node, depth, tapPen, keep);
                }
                return;
            }

            if (depth >= KineticaConstants.MAX_WORD_LEN) return;
            KineticaMatcher m = pattern.get(ti);

            if (m instanceof KineticaMatcher.Anchor) {
                KineticaMatcher.Anchor anc = (KineticaMatcher.Anchor) m;
                if (depth + 1 + restMin[ti + 1] > KineticaConstants.MAX_WORD_LEN) return;

                if (!fuzzyAnchors) {
                    int ch = trie.child(node, anc.code);
                    if (ch != -1) {
                        wordLetters[depth] = anc.code;
                        descend(ch, depth + 1, ti + 1, depth + 1, 0, -1, -1, 0f, tapPen, keep, 1.0f);
                        // Doubled letter from a single tap
                        int ch2 = trie.child(ch, anc.code);
                        if (ch2 != -1 && depth + 2 + restMin[ti + 1] <= KineticaConstants.MAX_WORD_LEN
                                && !branchExhausted()) {
                            wordLetters[depth + 1] = anc.code;
                            descend(
                                    ch2, depth + 2, ti + 1, depth + 2, 0, -1, -1, 0f,
                                    tapPen + KineticaConstants.DOUBLE_TAP_PEN_KW, keep, 1.0f);
                        }
                    }
                    return;
                }

                int[] order = childOrder[depth];
                int nChildren = sortedChildren(node, depth, order);
                for (int ci = 0; ci < nChildren; ci++) {
                    if (branchExhausted()) break;
                    int c = order[ci];
                    float pen;
                    if (c == anc.code) {
                        pen = 0f;
                    } else {
                        float d = geometry.distToCenterKw(anc.xKw, anc.yKw, c);
                        if (d > KineticaConstants.FUZZY_TAP_RADIUS_KW) continue;
                        pen = KineticaConstants.FUZZY_TAP_LAMBDA * d;
                    }
                    int ch = trie.child(node, c);
                    wordLetters[depth] = c;
                    descend(ch, depth + 1, ti + 1, depth + 1, 0, -1, -1, 0f, tapPen + pen, keep, 1.0f);
                    if (c == anc.code && depth + 2 + restMin[ti + 1] <= KineticaConstants.MAX_WORD_LEN
                            && !branchExhausted()) {
                        int ch2 = trie.child(ch, c);
                        if (ch2 != -1) {
                            wordLetters[depth + 1] = c;
                            descend(
                                    ch2, depth + 2, ti + 1, depth + 2, 0, -1, -1, 0f,
                                    tapPen + KineticaConstants.DOUBLE_TAP_PEN_KW, keep, 1.0f);
                        }
                    }
                }
                return;
            }

            if (!(m instanceof KineticaMatcher.Segment)) return;
            KineticaMatcher.Segment seg = (KineticaMatcher.Segment) m;

            if (segConsumed == 0) {
                if (depth + seg.minLetters + restMin[ti + 1] > KineticaConstants.MAX_WORD_LEN) return;
                int[] order = childOrder[depth];
                int nChildren = sortedChildren(node, depth, order);

                // Count admissible root branches for start-subtree fairness slicing
                boolean atRoot = (depth == 0);
                int rootBranches = 0;
                if (atRoot) {
                    for (int ci = 0; ci < nChildren; ci++) {
                        int c = order[ci];
                        boolean okStart = seg.softStart ? seg.isNear(c) : (seg.isStart(c) || (rescue && seg.isNear(c)));
                        if (okStart && seg.passAtOrAfter(c, -KineticaConstants.MONOTONE_SLACK) >= 0) {
                            rootBranches++;
                        }
                    }
                }

                int remainingBranches = rootBranches;
                for (int ci = 0; ci < nChildren; ci++) {
                    if (atRoot ? passExhausted() : branchExhausted()) break;
                    int c = order[ci];
                    boolean naturalStart = seg.softStart || seg.isStart(c);
                    if (!naturalStart && (!rescue || !seg.isNear(c))) continue;
                    int pass = seg.passAtOrAfter(c, -KineticaConstants.MONOTONE_SLACK);
                    if (pass < 0) continue;

                    int prevCap = emitCap;
                    if (atRoot && remainingBranches > 0) {
                        int remBudget = KineticaConstants.MAX_CANDIDATES - emitted;
                        int slice = Math.max(1, (remBudget + remainingBranches - 1) / remainingBranches);
                        emitCap = Math.min(KineticaConstants.MAX_CANDIDATES, emitted + slice);
                        remainingBranches--;
                    }

                    int ch = trie.child(node, c);
                    float nextKeep = naturalStart ? 1.0f : KineticaConstants.RESCUE_GATE_KEEP;
                    if (!seg.isContacted(c)) {
                        nextKeep *= KineticaConstants.UNCONTACTED_LETTER_KEEP;
                    }
                    wordLetters[depth] = c;
                    descend(ch, depth + 1, ti, depth, 1, pass, c, 0f, tapPen, keep, nextKeep);
                    emitCap = prevCap;
                }
                return;
            }

            // Segment already open: try closing if minLetters reached
            if (segConsumed >= seg.minLetters && !branchExhausted()) {
                int closeGates = 0;
                if (!seg.softEnd && !seg.isEnd(segLastCode)) {
                    closeGates++;
                }
                if (segIdeal < KineticaConstants.LEN_BAND_LO * seg.letterArcLen - KineticaConstants.LEN_BAND_MARGIN_KW) {
                    closeGates++;
                }
                if (segIdeal > KineticaConstants.LEN_BAND_HI * seg.arcLen + KineticaConstants.LEN_BAND_MARGIN_KW) {
                    closeGates++;
                }
                if (closeGates == 0 || rescue) {
                    float closeKeep = segKeep;
                    for (int g = 0; g < closeGates; g++) {
                        closeKeep *= KineticaConstants.RESCUE_GATE_KEEP;
                    }
                    pieceSeg[pieceCount] = seg;
                    pieceFrom[pieceCount] = segStartDepth;
                    pieceTo[pieceCount] = depth;
                    pieceCount++;
                    descend(node, depth, ti + 1, depth, 0, -1, -1, 0f, tapPen, keep * closeKeep, 1.0f);
                    pieceCount--;
                }
            }

            // Or continue consuming inside the open segment
            if (segConsumed < seg.maxLetters
                    && depth + 1 + Math.max(0, seg.minLetters - segConsumed - 1) + restMin[ti + 1]
                    <= KineticaConstants.MAX_WORD_LEN
                    && !branchExhausted()) {
                int[] order = childOrder[depth];
                int nChildren = sortedChildren(node, depth, order);
                for (int ci = 0; ci < nChildren; ci++) {
                    if (branchExhausted()) break;
                    int c = order[ci];
                    if (!seg.isNear(c)) continue;
                    int pass = seg.passAtOrAfter(c, segPrevPass - KineticaConstants.MONOTONE_SLACK);
                    if (pass < 0) continue;

                    float newIdeal = segIdeal + ((c != segLastCode) ? geometry.keyDist(segLastCode, c) : 0f);
                    float nextKeep = segKeep;
                    if (!seg.isContacted(c)) {
                        nextKeep *= KineticaConstants.UNCONTACTED_LETTER_KEEP;
                    }
                    int ch = trie.child(node, c);
                    wordLetters[depth] = c;
                    descend(
                            ch, depth + 1, ti, segStartDepth, segConsumed + 1,
                            Math.max(segPrevPass, pass), c, newIdeal, tapPen, keep, nextKeep);
                }
            }
        }

        private void emit(int node, int length, float tapPen, float keep) {
            if (attempts >= maxAttempts) return;
            attempts++;

            int rawFreq = trie.getFrequency(node);
            float maxFw = KineticaConstants.FREQ_WEIGHT_FLOOR +
                    (1.0f - KineticaConstants.FREQ_WEIGHT_FLOOR) * (rawFreq / 255.0f);
            float maxBm = (prevWord != null && bigrams != null) ? 2.0f : 1.0f;

            float accumBudget = Float.POSITIVE_INFINITY;
            int totalSteps = pieceCount * KineticaConstants.RESAMPLE_N;
            if (heap.isFull() && totalSteps > 0) {
                float maxDTotal = KineticaConstants.maxDTotalForScore(maxFw * maxBm * keep, heap.minScore());
                if (maxDTotal <= tapPen + extraPenalty) return;
                if (!Float.isInfinite(maxDTotal)) {
                    accumBudget = totalSteps * (maxDTotal - tapPen - extraPenalty);
                }
            }

            float accum = 0f;
            for (int pi = 0; pi < pieceCount; pi++) {
                KineticaMatcher.Segment seg = pieceSeg[pi];
                boolean hasPath = dtw.idealPath(
                        wordLetters, pieceFrom[pi], pieceTo[pi], geometry, idealPathScratch);
                if (!hasPath) return;

                float remBudget = Float.isInfinite(accumBudget)
                        ? Float.POSITIVE_INFINITY
                        : (accumBudget - accum);
                if (remBudget <= 0f) return;

                float d = dtw.distanceAccum(seg.resampled, idealPathScratch, remBudget);
                if (Float.isInfinite(d)) return;
                accum += d;
            }

            float geoFit = (totalSteps > 0) ? accum / (float) totalSteps : 0f;
            float dTotal = geoFit + tapPen + extraPenalty;
            float geo = KineticaConstants.geometricTerm(dTotal);

            String word = trie.getWord(node);
            float bm = 1.0f;
            if (prevWord != null && bigrams != null && word != null) {
                bm = 1.0f + bigrams.score(prevWord, word);
            }
            float bmApplied = KineticaConstants.appliedBoost(bm, geoFit);

            List<NintypeTrie.WordForm> forms = trie.getWordForms(node);
            if (forms != null && !forms.isEmpty()) {
                for (int i = 0; i < forms.size(); i++) {
                    NintypeTrie.WordForm f = forms.get(i);
                    float fv = KineticaConstants.FREQ_WEIGHT_FLOOR +
                            (1.0f - KineticaConstants.FREQ_WEIGHT_FLOOR) * (f.frequency / 255.0f);
                    heap.offer(new Candidate(f.displayWord, fv * geo * bmApplied * keep, dTotal, f.isProperNoun));
                }
                emitted++;
            } else if (word != null) {
                heap.offer(new Candidate(word, maxFw * geo * bmApplied * keep, dTotal, false));
                emitted++;
            }
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
        float bm = 1.0f;
        if (prevWord != null && bigrams != null && !literal.isEmpty()) {
            bm = 1.0f + bigrams.score(prevWord, literal);
        }
        float score = fw * bm;

        if (node >= 0 && trie.isWord(node)) {
            List<NintypeTrie.WordForm> forms = trie.getWordForms(node);
            if (forms != null && !forms.isEmpty()) {
                for (int i = 0; i < forms.size(); i++) {
                    NintypeTrie.WordForm f = forms.get(i);
                    float fv = KineticaConstants.FREQ_WEIGHT_FLOOR +
                            (1.0f - KineticaConstants.FREQ_WEIGHT_FLOOR) * (f.frequency / 255.0f);
                    heap.offer(new Candidate(f.displayWord, fv * bm, 0f, f.isProperNoun));
                }
                return;
            }
        }
        heap.offer(new Candidate(literal, score, 0f, false));
    }
}
