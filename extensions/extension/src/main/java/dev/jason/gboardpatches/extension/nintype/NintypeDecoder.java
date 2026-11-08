package dev.jason.gboardpatches.extension.nintype;

import dev.jason.gboardpatches.extension.nintype.NintypeGeometry.KeySpec;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.Kind;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.TouchPoint;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.WordCandidate;
import dev.jason.gboardpatches.extension.nintype.NintypeTrie.WordForm;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact Nintype Multi-Finger Swipe Decoding Engine.
 *
 * Reverse-engineered and ported from Kinetica's NintypeDecoder.kt / libgl2jni.so:
 * - Key-unit coordinate normalization (kw) with Gaussian proximity
 * - Independent dual-thumb anchor extraction (Left Thumb stream 0, Right Thumb stream 1)
 * - Curvature turn detection (3-point angle deflection > 30 degrees, cosTheta < 0.85)
 * - Dwell / pause detection and double-letter vowel bitmask (0x00104111)
 * - 4-tier Priority Queue Buckets (Same-finger, Hand-switch, Skip, Apostrophe)
 * - Temporal consistency lattice (anchor.t >= lastTime - 80ms)
 * - Fast piecewise curves: coverageCurve (pow 0.60), lengthMismatchCurve, skipPenaltyCurve
 * - Gboard active dictionary validation & personal dictionary boost.
 */
public final class NintypeDecoder {

    public interface GboardWordCheckCallback {
        /**
         * Returns a boolean array of length words.size() indicating which words are known in
         * Gboard's active Delight5 dictionary / DynamicLm user dictionary.
         */
        boolean[] checkWords(List<String> words);
    }

    private static final int BUCKET_CAPACITY = 20;
    private static final int MAX_STEPS = 2500;
    private static final float PRUNE_CUTOFF = 2.0f;
    private static final float DTW_RESCORE_WEIGHT = 0.28f;
    private static final int DTW_RESAMPLE_N = 32;

    private final NintypeTrie trie;
    private final NintypeBigramTable bigrams;
    private final NintypeGeometry geometry;

    public NintypeDecoder(
            NintypeTrie trie,
            NintypeBigramTable bigrams,
            NintypeGeometry geometry
    ) {
        this.trie = trie;
        this.bigrams = bigrams;
        this.geometry = geometry;
    }

    // =========================================================================
    // 1. Fast Piecewise Curves (Ported from Kinetica's FastCurveMapper)
    // =========================================================================
    private static float evalCoverageCurve(float distKw) {
        if (distKw <= 0.00f) return 1.00f;
        if (distKw <= 0.25f) return 1.00f;
        if (distKw <= 0.50f) return 1.00f + (distKw - 0.25f) * (0.80f - 1.00f) / 0.25f;
        if (distKw <= 0.75f) return 0.80f + (distKw - 0.50f) * (0.40f - 0.80f) / 0.25f;
        if (distKw <= 1.00f) return 0.40f + (distKw - 0.75f) * (0.25f - 0.40f) / 0.25f;
        if (distKw <= 1.25f) return 0.25f + (distKw - 1.00f) * (0.10f - 0.25f) / 0.25f;
        if (distKw <= 1.50f) return 0.10f + (distKw - 1.25f) * (0.00f - 0.10f) / 0.25f;
        return 0.00f;
    }

    private static float evalSkipCurve(float skipRatio) {
        if (skipRatio <= 0.000f) return 1.00f;
        if (skipRatio <= 0.100f) return 1.00f + (skipRatio - 0.000f) * (0.85f - 1.00f) / 0.100f;
        if (skipRatio <= 0.143f) return 0.85f + (skipRatio - 0.100f) * (0.75f - 0.85f) / 0.043f;
        if (skipRatio <= 0.167f) return 0.75f + (skipRatio - 0.143f) * (0.60f - 0.75f) / 0.024f;
        if (skipRatio <= 0.200f) return 0.60f + (skipRatio - 0.167f) * (0.40f - 0.60f) / 0.033f;
        if (skipRatio <= 0.330f) return 0.40f + (skipRatio - 0.200f) * (0.20f - 0.40f) / 0.130f;
        if (skipRatio <= 0.500f) return 0.20f + (skipRatio - 0.330f) * (0.05f - 0.20f) / 0.170f;
        if (skipRatio <= 0.600f) return 0.05f + (skipRatio - 0.500f) * (0.00f - 0.05f) / 0.100f;
        return 0.00f;
    }

    private static float evalLengthCurve(float excessLen) {
        if (excessLen <= 1.00f) return 1.00f;
        if (excessLen <= 2.00f) return 1.00f + (excessLen - 1.00f) * (0.93f - 1.00f) / 1.00f;
        if (excessLen <= 3.00f) return 0.93f + (excessLen - 2.00f) * (0.85f - 0.93f) / 1.00f;
        if (excessLen <= 3.50f) return 0.85f + (excessLen - 3.00f) * (0.50f - 0.85f) / 0.50f;
        if (excessLen <= 5.00f) return 0.50f + (excessLen - 3.50f) * (0.30f - 0.50f) / 1.50f;
        if (excessLen <= 10.0f) return 0.30f + (excessLen - 5.00f) * (0.10f - 0.30f) / 5.00f;
        return 0.10f;
    }

    private static boolean isVowel(char c) {
        char upper = Character.toUpperCase(c);
        if (upper < 'A' || upper > 'U') return false;
        int shift = upper - 'A';
        return ((1 << shift) & 0x00104111) != 0;
    }

    private static float getDoubleLetterPenalty(char c, float dwellRatio) {
        float base = isVowel(c) ? 0.56f : 0.86f;
        return dwellRatio + (1.0f - dwellRatio) * base;
    }

    // =========================================================================
    // 2. Data Structures
    // =========================================================================
    private static final class TravKey implements Comparable<TravKey> {
        final int charIdx;
        final char character;
        final float distKw;
        final float coverageScore;

        TravKey(int charIdx, char character, float distKw, float coverageScore) {
            this.charIdx = charIdx;
            this.character = character;
            this.distKw = distKw;
            this.coverageScore = coverageScore;
        }

        @Override
        public int compareTo(TravKey other) {
            return Float.compare(other.coverageScore, this.coverageScore);
        }
    }

    private static final class MooPoint {
        final float x;
        final float y;
        final long t;
        float speed = 0f;
        float dwellRatio = 0f;
        boolean isCurvatureTurn = false;
        boolean isBoundaryPoint = false;
        final List<TravKey> travKeys = new ArrayList<>(6);

        MooPoint(float x, float y, long t) {
            this.x = x;
            this.y = y;
            this.t = t;
        }

        MooPoint(float x, float y, long t, boolean isBoundaryPoint, float dwellRatio) {
            this.x = x;
            this.y = y;
            this.t = t;
            this.isBoundaryPoint = isBoundaryPoint;
            this.dwellRatio = dwellRatio;
        }

        boolean isVisitationPoint() {
            return isCurvatureTurn || isBoundaryPoint || dwellRatio > 0.2f;
        }
    }

    private static final class Anchor {
        final int fingerId; // 0 = Left, 1 = Right
        final int index;
        final MooPoint pt;
        final long t;
        final List<TravKey> cands;
        final boolean isVisitation;

        Anchor(int fingerId, int index, MooPoint pt, long t, List<TravKey> cands, boolean isVisitation) {
            this.fingerId = fingerId;
            this.index = index;
            this.pt = pt;
            this.t = t;
            this.cands = cands;
            this.isVisitation = isVisitation;
        }
    }

    private static final class PredPath implements Comparable<PredPath> {
        final int node;
        final String word;
        final int leftAnchorIdx;
        final int rightAnchorIdx;
        final int activeFinger; // 0 = Left, 1 = Right
        final long lastTime;
        final int skipCount;
        final int branchMode; // 0 = same, 1 = switch, 2 = apos, 3 = skip
        final float heuristicScore;

        PredPath(
                int node,
                String word,
                int leftAnchorIdx,
                int rightAnchorIdx,
                int activeFinger,
                long lastTime,
                int skipCount,
                int branchMode,
                float heuristicScore
        ) {
            this.node = node;
            this.word = word;
            this.leftAnchorIdx = leftAnchorIdx;
            this.rightAnchorIdx = rightAnchorIdx;
            this.activeFinger = activeFinger;
            this.lastTime = lastTime;
            this.skipCount = skipCount;
            this.branchMode = branchMode;
            this.heuristicScore = heuristicScore;
        }

        @Override
        public int compareTo(PredPath other) {
            return Float.compare(other.heuristicScore, this.heuristicScore);
        }
    }

    // =========================================================================
    // 3. Anchor Extraction (Ported from Kinetica's extractAnchors)
    // =========================================================================
    private List<Anchor> extractAnchors(List<NintypeInputToken> streamTokens, int fingerId) {
        if (streamTokens.isEmpty()) return Collections.emptyList();

        List<NintypeInputToken> sorted = new ArrayList<>(streamTokens);
        sorted.sort((a, b) -> Long.compare(a.startTimeMs, b.startTimeMs));

        List<MooPoint> rawPoints = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            NintypeInputToken token = sorted.get(i);
            if (token.kind == Kind.SWIPE) {
                if (token.points != null) {
                    for (int p = 0; p < token.points.size(); p++) {
                        TouchPoint pt = token.points.get(p);
                        rawPoints.add(new MooPoint(pt.x, pt.y, pt.timeMs));
                    }
                }
            } else if (token.kind == Kind.TAP) {
                rawPoints.add(new MooPoint(token.tapX, token.tapY, token.startTimeMs, true, 0f));
                if (token.endTimeMs > token.startTimeMs) {
                    float dwell = Math.min(1.0f, (token.endTimeMs - token.startTimeMs) / 150f);
                    rawPoints.add(new MooPoint(token.tapX, token.tapY, token.endTimeMs, true, dwell));
                }
            }
        }
        if (rawPoints.isEmpty()) return Collections.emptyList();

        float keyW = (geometry != null && geometry.keyWidthPx > 10f) ? geometry.keyWidthPx : 96f;

        // Resample spatially to ~0.15 kw
        List<MooPoint> resampled = new ArrayList<>();
        MooPoint firstPt = rawPoints.get(0);
        firstPt.isBoundaryPoint = true;
        resampled.add(firstPt);

        MooPoint last = firstPt;
        for (int i = 1; i < rawPoints.size(); i++) {
            MooPoint curr = rawPoints.get(i);
            float dx = curr.x - last.x;
            float dy = curr.y - last.y;
            float d = (float) Math.hypot(dx, dy);
            long dt = Math.max(1L, curr.t - last.t);

            if (d >= 0.15f * keyW || curr.isBoundaryPoint || i == rawPoints.size() - 1) {
                float speed = (d / (dt / 1000f));
                float dwell = (speed < 15f && dt >= 100L) ? Math.min(1.0f, dt / 150f) : 0f;
                curr.speed = speed;
                curr.dwellRatio = Math.max(curr.dwellRatio, dwell);
                resampled.add(curr);
                last = curr;
            }
        }
        resampled.get(resampled.size() - 1).isBoundaryPoint = true;

        // Candidate Key Harvesting (syncCoverage) on each resampled point
        for (int p = 0; p < resampled.size(); p++) {
            MooPoint pt = resampled.get(p);
            for (int code = 0; code < NintypeAlphabet.ALPHABET_SIZE; code++) {
                KeySpec ks = geometry.keySpecByIdx(code);
                if (ks == null) continue;
                float distPx = (float) Math.hypot(pt.x - ks.centerX, pt.y - ks.centerY);
                float distKw = distPx / keyW;
                if (distKw <= 1.50f) {
                    float rawCov = evalCoverageCurve(distKw);
                    float finalCov = (float) Math.pow(rawCov, 0.60f);
                    if (finalCov > 0.05f) {
                        pt.travKeys.add(new TravKey(code, ks.character, distKw, finalCov));
                    }
                }
            }
            Collections.sort(pt.travKeys);
        }

        // Curvature turn & closest approach apex detection
        for (int i = 1; i < resampled.size() - 1; i++) {
            MooPoint prev = resampled.get(i - 1);
            MooPoint cur = resampled.get(i);
            MooPoint next = resampled.get(i + 1);

            // 1. Curvature angle deflection > 30 degrees (cosTheta < 0.85)
            float v1x = cur.x - prev.x;
            float v1y = cur.y - prev.y;
            float v2x = next.x - cur.x;
            float v2y = next.y - cur.y;
            float len1 = (float) Math.hypot(v1x, v1y);
            float len2 = (float) Math.hypot(v2x, v2y);

            if (len1 > 0.02f * keyW && len2 > 0.02f * keyW) {
                float dot = v1x * v2x + v1y * v2y;
                float cosTheta = dot / (len1 * len2);
                if (cosTheta < 0.85f) {
                    cur.isCurvatureTurn = true;
                }
            }

            // 2. Closest approach apex to a key
            if (!cur.travKeys.isEmpty()) {
                TravKey top = cur.travKeys.get(0);
                KeySpec ks = geometry.keySpecByIdx(top.charIdx);
                if (ks != null) {
                    float dPrev = (float) Math.hypot(prev.x - ks.centerX, prev.y - ks.centerY);
                    float dCur = (float) Math.hypot(cur.x - ks.centerX, cur.y - ks.centerY);
                    float dNext = (float) Math.hypot(next.x - ks.centerX, next.y - ks.centerY);
                    if (dCur <= dPrev && dCur <= dNext && dCur <= 0.85f * keyW) {
                        cur.isCurvatureTurn = true;
                    }
                }
            }
        }

        // Extract key visitation anchors
        List<Anchor> anchors = new ArrayList<>();
        addAnchorIfValid(anchors, resampled.get(0), fingerId, keyW);

        for (int i = 1; i < resampled.size() - 1; i++) {
            MooPoint pt = resampled.get(i);
            if (pt.isCurvatureTurn || pt.dwellRatio > 0.2f) {
                addAnchorIfValid(anchors, pt, fingerId, keyW);
            }
        }

        addAnchorIfValid(anchors, resampled.get(resampled.size() - 1), fingerId, keyW);
        return anchors;
    }

    private void addAnchorIfValid(List<Anchor> anchors, MooPoint pt, int fingerId, float keyW) {
        if (pt.travKeys.isEmpty()) return;
        int topKey = pt.travKeys.get(0).charIdx;
        if (!anchors.isEmpty() && !anchors.get(anchors.size() - 1).cands.isEmpty()) {
            int lastTopKey = anchors.get(anchors.size() - 1).cands.get(0).charIdx;
            if (lastTopKey == topKey) {
                float distBetween = (float) Math.hypot(
                        pt.x - anchors.get(anchors.size() - 1).pt.x,
                        pt.y - anchors.get(anchors.size() - 1).pt.y
                );
                if (distBetween < 0.25f * keyW) return;
            }
        }
        anchors.add(new Anchor(fingerId, anchors.size(), pt, pt.t, pt.travKeys, pt.isVisitationPoint()));
    }

    // =========================================================================
    // 4. Decode Engine (Ported from Kinetica's NintypeDecoder.kt decode)
    // =========================================================================
    public List<WordCandidate> decode(
            List<NintypeInputToken> tokens,
            String prevWord,
            int maxResults
    ) {
        return decode(tokens, prevWord, maxResults, null);
    }

    public List<WordCandidate> decode(
            List<NintypeInputToken> tokens,
            String prevWord,
            int maxResults,
            GboardWordCheckCallback gboardWordCheck
    ) {
        if (tokens == null || tokens.isEmpty()) return Collections.emptyList();

        float midX = (geometry != null) ? geometry.midlinePx() : 540f;
        List<NintypeInputToken> leftTokens = new ArrayList<>(tokens.size());
        List<NintypeInputToken> rightTokens = new ArrayList<>(tokens.size());

        for (int i = 0; i < tokens.size(); i++) {
            NintypeInputToken t = tokens.get(i);
            int sid = t.streamId;
            if (sid != 0 && sid != 1) {
                float x = (t.kind == Kind.TAP)
                        ? t.tapX
                        : ((t.points != null && !t.points.isEmpty()) ? t.points.get(0).x : 0f);
                sid = (x < midX) ? 0 : 1;
                // If two tokens are present and both landed on the same side, assign based on relative x
                if (tokens.size() == 2) {
                    NintypeInputToken other = tokens.get(1 - i);
                    float otherX = (other.kind == Kind.TAP)
                            ? other.tapX
                            : ((other.points != null && !other.points.isEmpty()) ? other.points.get(0).x : 0f);
                    sid = (x <= otherX) ? 0 : 1;
                }
            }
            if (sid == 0) {
                leftTokens.add(t);
            } else {
                rightTokens.add(t);
            }
        }

        List<Anchor> leftAnchors = extractAnchors(leftTokens, 0);
        List<Anchor> rightAnchors = extractAnchors(rightTokens, 1);

        if (leftAnchors.isEmpty() && rightAnchors.isEmpty()) {
            return Collections.emptyList();
        }

        int predWordLen = leftAnchors.size() + rightAnchors.size();
        List<PredPath> bucketSameFinger = new ArrayList<>(BUCKET_CAPACITY);
        List<PredPath> bucketSwitchFinger = new ArrayList<>(BUCKET_CAPACITY);
        List<PredPath> bucketSkip = new ArrayList<>(BUCKET_CAPACITY);
        List<PredPath> bucketApos = new ArrayList<>(BUCKET_CAPACITY);

        // Seed search root on fingers that have anchors
        if (!leftAnchors.isEmpty()) {
            pushPath(
                    new PredPath(0, "", -1, -1, 0, 0L, 0, 0, 10.0f),
                    bucketSameFinger, bucketSwitchFinger, bucketSkip, bucketApos
            );
        }
        if (!rightAnchors.isEmpty()) {
            pushPath(
                    new PredPath(0, "", -1, -1, 1, 0L, 0, 0, 10.0f),
                    bucketSameFinger, bucketSwitchFinger, bucketSkip, bucketApos
            );
        }

        Map<String, WordCandidate> candidateMap = new HashMap<>(64);
        int steps = 0;

        while (steps++ < MAX_STEPS) {
            PredPath current;
            if (!bucketSameFinger.isEmpty()) current = bucketSameFinger.remove(0);
            else if (!bucketSwitchFinger.isEmpty()) current = bucketSwitchFinger.remove(0);
            else if (!bucketSkip.isEmpty()) current = bucketSkip.remove(0);
            else if (!bucketApos.isEmpty()) current = bucketApos.remove(0);
            else break;

            // If terminal word, evaluate final score
            if (current.node != 0 && trie.isWord(current.node)) {
                String word = current.word;
                int freq = trie.getFrequency(current.node);
                float freqWeight = 0.20f + 0.80f * (float) (Math.log(freq + 1.0) / NintypeAlphabet.FREQ_LOG_SCALE);

                float excessLen = Math.max(0f, (float) (predWordLen - word.length()));
                float lenMult = evalLengthCurve(excessLen + 1.0f);

                float skipRatio = (word.length() > 0) ? (float) current.skipCount / (float) word.length() : 0f;
                float skipMult = evalSkipCurve(skipRatio);

                List<WordForm> forms = trie.getWordForms(current.node);
                String display = (forms != null && !forms.isEmpty()) ? forms.get(0).displayWord : word;
                if (display == null || display.isEmpty()) display = word;

                float bm = 0f;
                if (prevWord != null && !prevWord.isEmpty()) {
                    bm = bigrams.score(prevWord, display);
                    if (bm <= 0f && !display.equals(word)) {
                        bm = bigrams.score(prevWord, word);
                    }
                }

                int unconsumed = 0;
                if (!leftAnchors.isEmpty() && current.leftAnchorIdx < leftAnchors.size() - 1) {
                    unconsumed += (leftAnchors.size() - 1 - Math.max(0, current.leftAnchorIdx));
                }
                if (!rightAnchors.isEmpty() && current.rightAnchorIdx < rightAnchors.size() - 1) {
                    unconsumed += (rightAnchors.size() - 1 - Math.max(0, current.rightAnchorIdx));
                }
                float unconsumedMult = Math.max(0.15f, 1.0f - unconsumed * 0.32f);

                boolean bothHandsUsed = (leftAnchors.isEmpty() || current.leftAnchorIdx >= 0)
                        && (rightAnchors.isEmpty() || current.rightAnchorIdx >= 0);
                float dualHandMult = bothHandsUsed ? 1.0f : 0.45f;

                float geoFit = Math.min(1.0f, current.heuristicScore / 10.0f);
                float finalScore = freqWeight * geoFit * lenMult * skipMult * unconsumedMult * dualHandMult * (1.0f + bm * 0.8f);

                String key = display.toLowerCase();
                WordCandidate existing = candidateMap.get(key);
                if (existing == null || finalScore > existing.totalScore) {
                    candidateMap.put(key, new WordCandidate(display, finalScore, geoFit, freqWeight, bm));
                }
            }

            int curLeftIdx = current.leftAnchorIdx;
            int curRightIdx = current.rightAnchorIdx;

            // 1. Advance on Left Finger
            if (curLeftIdx + 1 < leftAnchors.size()) {
                for (int a = curLeftIdx + 1; a < leftAnchors.size(); a++) {
                    Anchor anchor = leftAnchors.get(a);
                    if (current.word.length() > 0 && anchor.t < current.lastTime - 80L) continue;

                    for (int k = 0; k < anchor.cands.size(); k++) {
                        TravKey cand = anchor.cands.get(k);
                        int childNode = trie.child(current.node, cand.charIdx);
                        if (childNode != -1) {
                            float modeMult = (current.activeFinger == 0) ? 1.00f : 0.96f;
                            if (current.word.length() > 0 && anchor.t < current.lastTime) {
                                float lag = (float) (current.lastTime - anchor.t);
                                modeMult *= Math.max(0.60f, 1.0f - (lag / 80f) * 0.40f);
                            }
                            if (current.word.length() > 0
                                    && current.word.charAt(current.word.length() - 1) == cand.character) {
                                modeMult *= getDoubleLetterPenalty(cand.character, anchor.pt.dwellRatio);
                            }
                            float nextScore = current.heuristicScore * cand.coverageScore * modeMult;
                            if (nextScore >= PRUNE_CUTOFF) {
                                pushPath(
                                        new PredPath(
                                                childNode,
                                                current.word + cand.character,
                                                a,
                                                curRightIdx,
                                                0,
                                                anchor.t,
                                                current.skipCount,
                                                (current.activeFinger == 0) ? 0 : 1,
                                                nextScore
                                        ),
                                        bucketSameFinger, bucketSwitchFinger, bucketSkip, bucketApos
                                );
                            }
                        }
                    }
                }
            }

            // 2. Advance on Right Finger
            if (curRightIdx + 1 < rightAnchors.size()) {
                for (int a = curRightIdx + 1; a < rightAnchors.size(); a++) {
                    Anchor anchor = rightAnchors.get(a);
                    if (current.word.length() > 0 && anchor.t < current.lastTime - 80L) continue;

                    for (int k = 0; k < anchor.cands.size(); k++) {
                        TravKey cand = anchor.cands.get(k);
                        int childNode = trie.child(current.node, cand.charIdx);
                        if (childNode != -1) {
                            float modeMult = (current.activeFinger == 1) ? 1.00f : 0.96f;
                            if (current.word.length() > 0 && anchor.t < current.lastTime) {
                                float lag = (float) (current.lastTime - anchor.t);
                                modeMult *= Math.max(0.60f, 1.0f - (lag / 80f) * 0.40f);
                            }
                            if (current.word.length() > 0
                                    && current.word.charAt(current.word.length() - 1) == cand.character) {
                                modeMult *= getDoubleLetterPenalty(cand.character, anchor.pt.dwellRatio);
                            }
                            float nextScore = current.heuristicScore * cand.coverageScore * modeMult;
                            if (nextScore >= PRUNE_CUTOFF) {
                                pushPath(
                                        new PredPath(
                                                childNode,
                                                current.word + cand.character,
                                                curLeftIdx,
                                                a,
                                                1,
                                                anchor.t,
                                                current.skipCount,
                                                (current.activeFinger == 1) ? 0 : 1,
                                                nextScore
                                        ),
                                        bucketSameFinger, bucketSwitchFinger, bucketSkip, bucketApos
                                );
                            }
                        }
                    }
                }
            }

            // 3. Fuzzy Letter Skip (Bucket 3)
            if (current.skipCount < 1 && current.word.length() >= 2) {
                for (int ci = 0; ci < NintypeAlphabet.ALPHABET_SIZE; ci++) {
                    int childNode = trie.child(current.node, ci);
                    if (childNode != -1) {
                        char ch = NintypeAlphabet.indexToChar(ci);
                        float skipMult = 0.60f;
                        float nextScore = current.heuristicScore * skipMult;
                        if (nextScore >= PRUNE_CUTOFF) {
                            pushPath(
                                    new PredPath(
                                            childNode,
                                            current.word + ch,
                                            curLeftIdx,
                                            curRightIdx,
                                            current.activeFinger,
                                            current.lastTime,
                                            current.skipCount + 1,
                                            3,
                                            nextScore
                                    ),
                                    bucketSameFinger, bucketSwitchFinger, bucketSkip, bucketApos
                            );
                        }
                    }
                }
            }
        }

        // Single-stroke DTW rescore if single swipe token was provided
        List<TouchPoint> singleCurvePoints = (tokens.size() == 1 && tokens.get(0).kind == Kind.SWIPE)
                ? tokens.get(0).points
                : null;
        if (singleCurvePoints != null && singleCurvePoints.size() >= 3) {
            for (Map.Entry<String, WordCandidate> entry : candidateMap.entrySet()) {
                WordCandidate c = entry.getValue();
                String folded = NintypeAccentFolder.fold(c.word);
                if (folded.length() >= 2) {
                    float dtwSim = computeDtwSimilarity(singleCurvePoints, folded);
                    if (dtwSim > 0f) {
                        float blendedSpatial = c.spatialScore * (1f - DTW_RESCORE_WEIGHT) + dtwSim * DTW_RESCORE_WEIGHT;
                        float rescoredTotal = c.totalScore * (0.70f + 0.30f * blendedSpatial);
                        entry.setValue(new WordCandidate(c.word, rescoredTotal, blendedSpatial, c.frequencyScore, c.bigramScore));
                    }
                }
            }
        }

        List<WordCandidate> candidates = new ArrayList<>(candidateMap.values());

        // Gboard dictionary boost
        if (gboardWordCheck != null && !candidates.isEmpty()) {
            List<String> words = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                words.add(candidates.get(i).word);
            }
            boolean[] validInGboard = gboardWordCheck.checkWords(words);
            if (validInGboard != null && validInGboard.length == candidates.size()) {
                for (int i = 0; i < candidates.size(); i++) {
                    if (validInGboard[i]) {
                        WordCandidate c = candidates.get(i);
                        candidates.set(
                                i,
                                new WordCandidate(
                                        c.word,
                                        c.totalScore + 0.08f,
                                        c.spatialScore,
                                        c.frequencyScore,
                                        c.bigramScore
                                )
                        );
                    }
                }
            }
        }

        Collections.sort(candidates);
        if (candidates.size() > maxResults) {
            return new ArrayList<>(candidates.subList(0, maxResults));
        }
        return candidates;
    }

    private void pushPath(
            PredPath p,
            List<PredPath> bSame,
            List<PredPath> bSwitch,
            List<PredPath> bSkip,
            List<PredPath> bApos
    ) {
        if (p.heuristicScore < PRUNE_CUTOFF) return;
        List<PredPath> target;
        switch (p.branchMode) {
            case 0: target = bSame; break;
            case 1: target = bSwitch; break;
            case 2: target = bApos; break;
            default: target = bSkip; break;
        }
        target.add(p);
        Collections.sort(target);
        while (target.size() > BUCKET_CAPACITY) {
            target.remove(target.size() - 1);
        }
    }

    // =========================================================================
    // 5. Dynamic Time Warping (Single-Finger Rescoring)
    // =========================================================================
    private float computeDtwSimilarity(List<TouchPoint> rawPoints, String word) {
        List<TouchPoint> resampled = resamplePath(rawPoints, DTW_RESAMPLE_N);
        List<TouchPoint> idealPath = buildIdealWordPath(word, DTW_RESAMPLE_N);
        if (resampled.size() < 2 || idealPath.size() < 2) return 0f;

        int n = resampled.size();
        int m = idealPath.size();
        float[][] dtw = new float[n + 1][m + 1];

        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) {
                dtw[i][j] = Float.MAX_VALUE;
            }
        }
        dtw[0][0] = 0f;

        float normDist = Math.max(30f, geometry.keyWidthPx * 1.5f);

        for (int i = 1; i <= n; i++) {
            TouchPoint p1 = resampled.get(i - 1);
            for (int j = 1; j <= m; j++) {
                TouchPoint p2 = idealPath.get(j - 1);
                float cost = (float) Math.hypot(p1.x - p2.x, p1.y - p2.y) / normDist;
                float minPrev = Math.min(dtw[i - 1][j], Math.min(dtw[i][j - 1], dtw[i - 1][j - 1]));
                dtw[i][j] = cost + minPrev;
            }
        }

        float totalCost = dtw[n][m] / (float) (n + m);
        return (float) Math.exp(-totalCost * 1.25f);
    }

    private List<TouchPoint> resamplePath(List<TouchPoint> pts, int targetCount) {
        if (pts.size() <= 1) return pts;
        float totalLen = 0f;
        for (int i = 1; i < pts.size(); i++) {
            totalLen += (float) Math.hypot(pts.get(i).x - pts.get(i - 1).x, pts.get(i).y - pts.get(i - 1).y);
        }
        if (totalLen <= 1f) return pts;

        List<TouchPoint> resampled = new ArrayList<>(targetCount);
        resampled.add(pts.get(0));
        float step = totalLen / (targetCount - 1);
        float currentDist = 0f;

        int srcIdx = 0;
        float segDist = 0f;

        for (int i = 1; i < targetCount - 1; i++) {
            float targetDist = i * step;
            while (srcIdx < pts.size() - 1) {
                TouchPoint p0 = pts.get(srcIdx);
                TouchPoint p1 = pts.get(srcIdx + 1);
                float d = (float) Math.hypot(p1.x - p0.x, p1.y - p0.y);
                if (currentDist + d >= targetDist) {
                    float rem = targetDist - currentDist;
                    float frac = (d > 0.001f) ? (rem / d) : 0f;
                    float nx = p0.x + (p1.x - p0.x) * frac;
                    float ny = p0.y + (p1.y - p0.y) * frac;
                    long nt = p0.timeMs + (long) ((p1.timeMs - p0.timeMs) * frac);
                    resampled.add(new TouchPoint(nx, ny, nt, p0.pointerId));
                    break;
                }
                currentDist += d;
                srcIdx++;
            }
        }
        resampled.add(pts.get(pts.size() - 1));
        return resampled;
    }

    private List<TouchPoint> buildIdealWordPath(String word, int sampleCount) {
        List<KeySpec> keys = new ArrayList<>(word.length());
        for (int i = 0; i < word.length(); i++) {
            KeySpec ks = geometry.keySpec(word.charAt(i));
            if (ks != null) keys.add(ks);
        }
        if (keys.isEmpty()) return Collections.emptyList();
        if (keys.size() == 1) {
            KeySpec k = keys.get(0);
            return Collections.nCopies(sampleCount, new TouchPoint(k.centerX, k.centerY, 0L, 0));
        }

        List<TouchPoint> anchors = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            anchors.add(new TouchPoint(keys.get(i).centerX, keys.get(i).centerY, (long) i * 100L, 0));
        }
        return resamplePath(anchors, sampleCount);
    }
}
