package dev.jason.gboardpatches.extension.nintype;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Continuous multi-finger thumb-cursor beam search.
 * Ported 1:1 from Kinetica's ThumbBeam.kt.
 *
 * Simultaneously decodes single-finger, dual-thumb, and chorded gestures
 * by tracking independent cursors per finger/stream and scoring swipes
 * against continuous DTW polyline curves.
 */
public final class ThumbBeam {

    private final List<NintypeInputToken> tokens;
    private final List<KineticaMatcher> items;
    private final NintypeGeometry geometry;
    private final NintypeTrie trie;
    private final NintypeBigramTable bigrams;
    private final String prevWord;
    private final KineticaDtwDecoder.CandidateHeap heap;
    private final DtwMatcher dtw;

    private final int[][] streams;
    private final int[] swipeItems;
    private final int[][] restMin;
    private final float[] idealScratch = new float[2 * KineticaConstants.RESAMPLE_N];

    private int attempts = 0;
    private int offered = 0;

    public ThumbBeam(
            List<NintypeInputToken> tokens,
            List<KineticaMatcher> items,
            NintypeGeometry geometry,
            NintypeTrie trie,
            NintypeBigramTable bigrams,
            String prevWord,
            KineticaDtwDecoder.CandidateHeap heap,
            DtwMatcher dtw
    ) {
        this.tokens = tokens;
        this.items = items;
        this.geometry = geometry;
        this.trie = trie;
        this.bigrams = bigrams;
        this.prevWord = prevWord;
        this.heap = heap;
        this.dtw = dtw;

        List<Integer> left = new ArrayList<>();
        List<Integer> right = new ArrayList<>();
        float midX = geometry.midlinePx();

        for (int i = 0; i < tokens.size(); i++) {
            NintypeInputToken t = tokens.get(i);
            int s = t.streamId;
            if (s != 0 && s != 1) {
                float refX;
                if (t.kind == NintypeInputToken.Kind.TAP) {
                    refX = t.tapX;
                } else if (t.points != null && !t.points.isEmpty()) {
                    refX = t.points.get(0).x;
                } else {
                    refX = midX;
                }
                s = (refX < midX) ? 0 : 1;
            }
            if (s == 0) {
                left.add(i);
            } else {
                right.add(i);
            }
        }

        int[] leftArr = new int[left.size()];
        for (int i = 0; i < left.size(); i++) leftArr[i] = left.get(i);
        int[] rightArr = new int[right.size()];
        for (int i = 0; i < right.size(); i++) rightArr[i] = right.get(i);
        this.streams = new int[][]{leftArr, rightArr};

        List<Integer> swipes = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof KineticaMatcher.Segment) {
                swipes.add(i);
            }
        }
        this.swipeItems = new int[swipes.size()];
        for (int i = 0; i < swipes.size(); i++) this.swipeItems[i] = swipes.get(i);

        this.restMin = restMinOf(this.streams, this.items);
    }

    private static int[][] restMinOf(int[][] streams, List<KineticaMatcher> items) {
        int[][] out = new int[2][];
        for (int s = 0; s < 2; s++) {
            int[] list = streams[s];
            out[s] = new int[list.length + 1];
            for (int j = list.length - 1; j >= 0; j--) {
                KineticaMatcher m = items.get(list[j]);
                int needed = (m instanceof KineticaMatcher.Segment)
                        ? Math.max(1, ((KineticaMatcher.Segment) m).minLetters)
                        : 1;
                out[s][j] = out[s][j + 1] + needed;
            }
        }
        return out;
    }

    private static final class Key {
        final int node;
        final int nx0, nx1;
        final int o0, o1;
        final int n0, n1;
        final int l0, l1;
        final int c0, c1;
        final int stream;

        Key(int node, int nx0, int nx1, int o0, int o1, int n0, int n1, int l0, int l1, int c0, int c1, int stream) {
            this.node = node;
            this.nx0 = nx0; this.nx1 = nx1;
            this.o0 = o0; this.o1 = o1;
            this.n0 = n0; this.n1 = n1;
            this.l0 = l0; this.l1 = l1;
            this.c0 = c0; this.c1 = c1;
            this.stream = stream;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key other = (Key) o;
            return node == other.node && nx0 == other.nx0 && nx1 == other.nx1
                    && o0 == other.o0 && o1 == other.o1 && n0 == other.n0 && n1 == other.n1
                    && l0 == other.l0 && l1 == other.l1 && c0 == other.c0 && c1 == other.c1
                    && stream == other.stream;
        }

        @Override
        public int hashCode() {
            int result = node;
            result = 31 * result + nx0;
            result = 31 * result + nx1;
            result = 31 * result + o0;
            result = 31 * result + o1;
            result = 31 * result + n0;
            result = 31 * result + n1;
            result = 31 * result + l0;
            result = 31 * result + l1;
            result = 31 * result + c0;
            result = 31 * result + c1;
            result = 31 * result + stream;
            return result;
        }
    }

    private static final class Hyp {
        final int node;
        final int depth;
        final Hyp parent;
        final int code;
        final int item;
        final int[] nx;
        final int[] open;
        final int[] n;
        final int[] last;
        final int[] lastCode;
        final float[] ideal;
        final long lastT;
        final int lastStream;
        final float cost;
        final float tapPen;
        final float keep;
        final int gates;
        final float priority;

        Hyp(
                int node, int depth, Hyp parent, int code, int item,
                int[] nx, int[] open, int[] n, int[] last, int[] lastCode, float[] ideal,
                long lastT, int lastStream, float cost, float tapPen, float keep, int gates, float priority
        ) {
            this.node = node;
            this.depth = depth;
            this.parent = parent;
            this.code = code;
            this.item = item;
            this.nx = nx;
            this.open = open;
            this.n = n;
            this.last = last;
            this.lastCode = lastCode;
            this.ideal = ideal;
            this.lastT = lastT;
            this.lastStream = lastStream;
            this.cost = cost;
            this.tapPen = tapPen;
            this.keep = keep;
            this.gates = gates;
            this.priority = priority;
        }
    }

    private Key keyOf(Hyp h) {
        return new Key(
                h.node,
                h.nx[0], h.nx[1],
                h.open[0], h.open[1],
                h.n[0], h.n[1],
                h.last[0], h.last[1],
                h.lastCode[0], h.lastCode[1],
                h.lastStream
        );
    }

    public void run() {
        Hyp root = new Hyp(
                0, 0, null, -1, -1,
                new int[2], new int[]{-1, -1}, new int[2],
                new int[2], new int[]{-1, -1}, new float[2],
                Long.MIN_VALUE, -1, 0f, 0f, 1f, 0, 0f
        );

        List<Hyp> frontier = Collections.singletonList(root);
        List<Hyp> kids = new ArrayList<>(256);

        for (int depth = 0; depth < KineticaConstants.MAX_WORD_LEN; depth++) {
            Map<Key, Hyp> best = new HashMap<>(frontier.size() * 8);

            for (int i = 0; i < frontier.size(); i++) {
                Hyp h = frontier.get(i);
                kids.clear();
                expand(h, kids);
                for (int j = 0; j < kids.size(); j++) {
                    Hyp k = kids.get(j);
                    Key key = keyOf(k);
                    Hyp prior = best.get(key);
                    if (prior == null || k.priority < prior.priority) {
                        best.put(key, k);
                    }
                }
            }

            if (best.isEmpty()) break;

            List<Hyp> sorted = new ArrayList<>(best.values());
            sorted.sort((a, b) -> Float.compare(a.priority, b.priority));

            if (sorted.size() > KineticaConstants.BEAM_WIDTH) {
                frontier = sorted.subList(0, KineticaConstants.BEAM_WIDTH);
            } else {
                frontier = sorted;
            }

            for (int i = 0; i < frontier.size(); i++) {
                if (attempts >= KineticaConstants.BEAM_MAX_ATTEMPTS) break;
                maybeEmit(frontier.get(i));
            }

            if (attempts >= KineticaConstants.BEAM_MAX_ATTEMPTS) break;
        }
    }

    private void expand(Hyp h, List<Hyp> out) {
        for (int code = 0; code < 26; code++) {
            int child = trie.child(h.node, code);
            if (child == -1) continue;
            if (!geometry.hasKey(code)) continue;

            for (int s = 0; s < 2; s++) {
                continueOpen(h, child, code, s, out);
                openNext(h, child, code, s, out);
            }
        }
    }

    private boolean timeOk(Hyp h, int s, long t) {
        return h.lastStream < 0 || h.lastStream == s || t >= h.lastT - KineticaConstants.HANDOVER_ALLOWANCE_MS;
    }

    private void continueOpen(Hyp h, int child, int code, int s, List<Hyp> out) {
        int o = h.open[s];
        if (o < 0) return;
        KineticaMatcher m = items.get(o);

        if (m instanceof KineticaMatcher.Segment) {
            KineticaMatcher.Segment seg = (KineticaMatcher.Segment) m;
            if (h.n[s] >= seg.maxLetters) return;

            int pass = seg.passAtOrAfter(code, h.last[s] - KineticaConstants.MONOTONE_SLACK);
            if (pass < 0) return;

            long t = seg.letterTime(code, pass);
            if (!timeOk(h, s, t)) return;

            float c = h.cost + passCost(seg, code, pass);
            float keep = seg.isContacted(code)
                    ? h.keep
                    : (h.keep * KineticaConstants.UNCONTACTED_LETTER_KEEP);

            int n0 = h.n[0], n1 = h.n[1];
            int l0 = h.last[0], l1 = h.last[1];
            int c0 = h.lastCode[0], c1 = h.lastCode[1];
            float id0 = h.ideal[0], id1 = h.ideal[1];

            if (s == 0) {
                n0++;
                l0 = Math.max(l0, pass);
                if (code != c0) id0 += geometry.keyDist(c0, code);
                c0 = code;
            } else {
                n1++;
                l1 = Math.max(l1, pass);
                if (code != c1) id1 += geometry.keyDist(c1, code);
                c1 = code;
            }

            out.add(next(
                    h, child, code, o, c, h.tapPen, keep, h.gates, t, s,
                    h.nx[0], h.nx[1], h.open[0], h.open[1], n0, n1, l0, l1, c0, c1, id0, id1
            ));
        } else if (m instanceof KineticaMatcher.Anchor) {
            // One tap may stand for a doubled letter
            if (h.n[s] != 1 || code != h.lastCode[s]) return;

            int n0 = (s == 0) ? 2 : h.n[0];
            int n1 = (s == 1) ? 2 : h.n[1];

            out.add(next(
                    h, child, code, o, h.cost, h.tapPen + KineticaConstants.DOUBLE_TAP_PEN_KW,
                    h.keep, h.gates, ((KineticaMatcher.Anchor) m).t, s,
                    h.nx[0], h.nx[1], h.open[0], h.open[1], n0, n1,
                    h.last[0], h.last[1], h.lastCode[0], h.lastCode[1],
                    h.ideal[0], h.ideal[1]
            ));
        }
    }

    private void openNext(Hyp h, int child, int code, int s, List<Hyp> out) {
        int[] list = streams[s];
        if (h.nx[s] >= list.length) return;

        int closeGates = 0;
        int o = h.open[s];
        if (o >= 0) {
            Integer cg = closeGates(items.get(o), h.n[s], h.lastCode[s], h.ideal[s]);
            if (cg == null) return;
            closeGates = cg;
        }

        int nextItem = list[h.nx[s]];
        KineticaMatcher m = items.get(nextItem);

        if (m instanceof KineticaMatcher.Anchor) {
            KineticaMatcher.Anchor anc = (KineticaMatcher.Anchor) m;
            float pen = 0f;
            if (code != anc.code) {
                float d = geometry.distToCenterKw(anc.xKw, anc.yKw, code);
                if (d > KineticaConstants.FUZZY_TAP_RADIUS_KW) return;
                pen = KineticaConstants.FUZZY_TAP_LAMBDA * d;
            }

            if (!timeOk(h, s, anc.t)) return;

            float c = h.cost + pen + closeGates * KineticaConstants.BEAM_GATE_COST;

            int nx0 = (s == 0) ? h.nx[0] + 1 : h.nx[0];
            int nx1 = (s == 1) ? h.nx[1] + 1 : h.nx[1];
            int op0 = (s == 0) ? nextItem : h.open[0];
            int op1 = (s == 1) ? nextItem : h.open[1];
            int n0 = (s == 0) ? 1 : h.n[0];
            int n1 = (s == 1) ? 1 : h.n[1];
            int l0 = (s == 0) ? 0 : h.last[0];
            int l1 = (s == 1) ? 0 : h.last[1];
            int c0 = (s == 0) ? code : h.lastCode[0];
            int c1 = (s == 1) ? code : h.lastCode[1];

            out.add(next(
                    h, child, code, nextItem, c, h.tapPen + pen, h.keep, h.gates + closeGates,
                    anc.t, s, nx0, nx1, op0, op1, n0, n1, l0, l1, c0, c1, h.ideal[0], h.ideal[1]
            ));
        } else if (m instanceof KineticaMatcher.Segment) {
            KineticaMatcher.Segment seg = (KineticaMatcher.Segment) m;
            int pass = seg.passAtOrAfter(code, -KineticaConstants.MONOTONE_SLACK);
            if (pass < 0) return;

            long t = seg.letterTime(code, pass);
            if (!timeOk(h, s, t)) return;

            int startGate = (seg.softStart || seg.isStart(code)) ? 0 : 1;
            int gates = closeGates + startGate;
            float c = h.cost + passCost(seg, code, pass) + gates * KineticaConstants.BEAM_GATE_COST;
            float keep = seg.isContacted(code)
                    ? h.keep
                    : (h.keep * KineticaConstants.UNCONTACTED_LETTER_KEEP);

            int nx0 = (s == 0) ? h.nx[0] + 1 : h.nx[0];
            int nx1 = (s == 1) ? h.nx[1] + 1 : h.nx[1];
            int op0 = (s == 0) ? nextItem : h.open[0];
            int op1 = (s == 1) ? nextItem : h.open[1];
            int n0 = (s == 0) ? 1 : h.n[0];
            int n1 = (s == 1) ? 1 : h.n[1];
            int l0 = (s == 0) ? pass : h.last[0];
            int l1 = (s == 1) ? pass : h.last[1];
            int c0 = (s == 0) ? code : h.lastCode[0];
            int c1 = (s == 1) ? code : h.lastCode[1];
            float id0 = (s == 0) ? 0f : h.ideal[0];
            float id1 = (s == 1) ? 0f : h.ideal[1];

            out.add(next(
                    h, child, code, nextItem, c, h.tapPen, keep, h.gates + gates,
                    t, s, nx0, nx1, op0, op1, n0, n1, l0, l1, c0, c1, id0, id1
            ));
        }
    }

    private Integer closeGates(KineticaMatcher m, int n, int lastCode, float ideal) {
        if (!(m instanceof KineticaMatcher.Segment)) return 0;
        KineticaMatcher.Segment seg = (KineticaMatcher.Segment) m;
        if (n < seg.minLetters) return null;

        int gates = (seg.softEnd || seg.isEnd(lastCode)) ? 0 : 1;
        if (ideal < KineticaConstants.LEN_BAND_LO * seg.letterArcLen - KineticaConstants.LEN_BAND_MARGIN_KW) {
            gates++;
        }
        if (ideal > KineticaConstants.LEN_BAND_HI * seg.arcLen + KineticaConstants.LEN_BAND_MARGIN_KW) {
            gates++;
        }
        return gates;
    }

    private float passCost(KineticaMatcher.Segment m, int code, int pass) {
        if (pass < 0 || pass >= 32) return 1.0f;
        return geometry.distToCenterKw(m.resampled[2 * pass], m.resampled[2 * pass + 1], code);
    }

    private Hyp next(
            Hyp h,
            int child,
            int code,
            int item,
            float cost,
            float tapPen,
            float keep,
            int gates,
            long t,
            int stream,
            int nx0, int nx1,
            int open0, int open1,
            int n0, int n1,
            int last0, int last1,
            int lastCode0, int lastCode1,
            float ideal0, float ideal1
    ) {
        int maxDesc = Math.min(255, trie.getMaxSubtreeFreq(child));
        float fwMax = KineticaConstants.FREQ_WEIGHT_FLOOR +
                (1.0f - KineticaConstants.FREQ_WEIGHT_FLOOR) * (maxDesc / 255.0f);
        float priority = cost + tapPen - KineticaConstants.BEAM_FREQ_WEIGHT * (float) Math.log(fwMax);

        return new Hyp(
                child,
                h.depth + 1,
                h,
                code,
                item,
                new int[]{nx0, nx1},
                new int[]{open0, open1},
                new int[]{n0, n1},
                new int[]{last0, last1},
                new int[]{lastCode0, lastCode1},
                new float[]{ideal0, ideal1},
                t,
                stream,
                cost,
                tapPen,
                keep,
                gates,
                priority
        );
    }

    private void maybeEmit(Hyp h) {
        if (h.depth == 0 || !trie.isWord(h.node)) return;

        int gates = h.gates;
        for (int s = 0; s < 2; s++) {
            if (h.nx[s] < streams[s].length) {
                return; // Not all items were opened
            }
            int o = h.open[s];
            if (o >= 0) {
                Integer g = closeGates(items.get(o), h.n[s], h.lastCode[s], h.ideal[s]);
                if (g == null) return;
                gates += g;
            }
        }

        attempts++;

        // Unwind word letters and token ownership
        int[] letters = new int[h.depth];
        int[] owner = new int[h.depth];
        Hyp x = h;
        for (int i = h.depth - 1; i >= 0; i--) {
            letters[i] = x.code;
            owner[i] = x.item;
            x = x.parent;
        }

        int pieceCount = swipeItems.length;
        KineticaMatcher.Segment[] pieceSeg = new KineticaMatcher.Segment[pieceCount];
        int[][] pieceLetters = new int[pieceCount][];
        int[] pieceFrom = new int[pieceCount];
        int[] pieceTo = new int[pieceCount];

        for (int pi = 0; pi < pieceCount; pi++) {
            int itemIdx = swipeItems[pi];
            int own = 0;
            for (int j = 0; j < h.depth; j++) {
                if (owner[j] == itemIdx) own++;
            }
            if (own == 0) return; // A swipe piece was given 0 letters

            int[] arr = new int[own];
            int k = 0;
            for (int j = 0; j < h.depth; j++) {
                if (owner[j] == itemIdx) arr[k++] = letters[j];
            }
            pieceSeg[pi] = (KineticaMatcher.Segment) items.get(itemIdx);
            pieceLetters[pi] = arr;
            pieceFrom[pi] = 0;
            pieceTo[pi] = own;
        }

        float keep = h.keep;
        for (int g = 0; g < gates; g++) {
            keep *= KineticaConstants.BEAM_GATE_KEEP;
        }

        scoreReading(h.node, letters, h.depth, pieceCount, pieceSeg, pieceLetters, pieceFrom, pieceTo, h.tapPen, keep);
    }

    private void scoreReading(
            int node,
            int[] letters,
            int depth,
            int pieceCount,
            KineticaMatcher.Segment[] pieceSeg,
            int[][] pieceLetters,
            int[] pieceFrom,
            int[] pieceTo,
            float tapPen,
            float keep
    ) {
        float accum = 0f;
        for (int pi = 0; pi < pieceCount; pi++) {
            KineticaMatcher.Segment seg = pieceSeg[pi];
            int[] let = (pieceLetters != null && pieceLetters[pi] != null) ? pieceLetters[pi] : letters;
            int from = pieceFrom[pi];
            int to = pieceTo[pi];

            boolean hasPath = dtw.idealPath(let, from, to, geometry, idealScratch);
            if (!hasPath) return;

            float d = dtw.distanceAccum(seg.resampled, idealScratch, Float.POSITIVE_INFINITY);
            if (Float.isInfinite(d)) return;
            accum += d;
        }

        int totalSteps = pieceCount * KineticaConstants.RESAMPLE_N;
        float geoFit = (totalSteps > 0) ? accum / (float) totalSteps : 0f;
        float dTotal = geoFit + tapPen;

        float geo = KineticaConstants.BEAM_UNSATURATED
                ? KineticaConstants.geometricTermUnsaturated(dTotal)
                : KineticaConstants.geometricTerm(dTotal);

        int rawFreq = trie.getFrequency(node);
        float fw = KineticaConstants.FREQ_WEIGHT_FLOOR +
                (1.0f - KineticaConstants.FREQ_WEIGHT_FLOOR) * (rawFreq / 255.0f);

        String word = trie.getWord(node);
        float bm = 1.0f;
        if (prevWord != null && bigrams != null && word != null) {
            bm = 1.0f + bigrams.score(prevWord, word);
        }
        float bmApplied = KineticaConstants.appliedBoost(bm, geoFit);
        float score = fw * geo * bmApplied * keep;

        List<NintypeTrie.WordForm> forms = trie.getWordForms(node);
        if (forms != null && !forms.isEmpty()) {
            for (int i = 0; i < forms.size(); i++) {
                NintypeTrie.WordForm f = forms.get(i);
                float fv = KineticaConstants.FREQ_WEIGHT_FLOOR +
                        (1.0f - KineticaConstants.FREQ_WEIGHT_FLOOR) * (f.frequency / 255.0f);
                heap.offer(new KineticaDtwDecoder.Candidate(f.displayWord, fv * geo * bmApplied * keep, dTotal, f.isProperNoun));
            }
        } else if (word != null) {
            heap.offer(new KineticaDtwDecoder.Candidate(word, score, dTotal, false));
        }

        offered++;
    }

    public static boolean applies(List<NintypeInputToken> tokens, List<KineticaMatcher> items) {
        if (tokens == null || items == null || tokens.size() < 2) return false;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).kind == NintypeInputToken.Kind.SWIPE) {
                return true;
            }
        }
        return false;
    }
}
