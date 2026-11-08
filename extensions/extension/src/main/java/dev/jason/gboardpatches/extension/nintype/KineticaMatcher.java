package dev.jason.gboardpatches.extension.nintype;

import java.util.Arrays;
import java.util.List;

/**
 * Geometric matcher representation for Kinetica DTW search.
 * Ported from Kinetica's Matcher.kt.
 */
public abstract class KineticaMatcher {

    public static final class Anchor extends KineticaMatcher {
        public final int code;
        public final float xKw;
        public final float yKw;
        public final long t;
        public final int streamId;

        public Anchor(int code, float xKw, float yKw, long t, int streamId) {
            this.code = code;
            this.xKw = xKw;
            this.yKw = yKw;
            this.t = t;
            this.streamId = streamId;
        }
    }

    public static final class Segment extends KineticaMatcher {
        public final float[] resampled; // 64 floats (32 points) in KW
        public final float arcLen;      // arc length in KW
        public final float letterArcLen;
        public final int minLetters;
        public final int maxLetters;
        public final boolean[] isStartNeighbor; // [26]
        public final boolean[] isEndNeighbor;   // [26]
        public final boolean[] nearPath;        // [26]
        public final int[][] passIdx;           // [26][] resample indices per key
        public final boolean softStart;
        public final boolean softEnd;
        public final int streamId;
        public final long startTimeMs;
        public final long endTimeMs;

        public Segment(
                float[] resampled,
                float arcLen,
                float letterArcLen,
                int minLetters,
                int maxLetters,
                boolean[] isStartNeighbor,
                boolean[] isEndNeighbor,
                boolean[] nearPath,
                int[][] passIdx,
                boolean softStart,
                boolean softEnd,
                int streamId,
                long startTimeMs,
                long endTimeMs
        ) {
            this.resampled = resampled;
            this.arcLen = arcLen;
            this.letterArcLen = letterArcLen;
            this.minLetters = minLetters;
            this.maxLetters = maxLetters;
            this.isStartNeighbor = isStartNeighbor;
            this.isEndNeighbor = isEndNeighbor;
            this.nearPath = nearPath;
            this.passIdx = passIdx;
            this.softStart = softStart;
            this.softEnd = softEnd;
            this.streamId = streamId;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
        }

        public boolean isStart(int code) {
            return code >= 0 && code < 26 && isStartNeighbor[code];
        }

        public boolean isEnd(int code) {
            return code >= 0 && code < 26 && isEndNeighbor[code];
        }

        public boolean isNear(int code) {
            return code >= 0 && code < 26 && nearPath[code];
        }

        public int passAtOrAfter(int code, int minIdx) {
            if (code < 0 || code >= 26) return -1;
            int[] passes = passIdx[code];
            if (passes == null) return -1;
            for (int i = 0; i < passes.length; i++) {
                if (passes[i] >= minIdx) return passes[i];
            }
            return -1;
        }

        public long letterTime(int code, int pass) {
            int idx = (pass < 0) ? 0 : (pass > 31 ? 31 : pass);
            return startTimeMs + (long) ((endTimeMs - startTimeMs) * (idx / 31.0f));
        }
    }

    public static Segment buildSegment(
            List<DtwMatcher.Point2D> rawPointsKw,
            long startTimeMs,
            long endTimeMs,
            NintypeGeometry geometry,
            int streamId,
            DtwMatcher dtw
    ) {
        int n = KineticaConstants.RESAMPLE_N;
        float[] resampled = new float[2 * n];
        dtw.resample(rawPointsKw, resampled);

        // Compute total arc length in KW
        float arcLen = 0f;
        for (int i = 1; i < rawPointsKw.size(); i++) {
            DtwMatcher.Point2D p0 = rawPointsKw.get(i - 1);
            DtwMatcher.Point2D p1 = rawPointsKw.get(i);
            float dx = p1.x - p0.x;
            float dy = p1.y - p0.y;
            arcLen += (float) Math.sqrt(dx * dx + dy * dy);
        }

        int minLetters = (arcLen < KineticaConstants.MIN_SWIPE_ARC_KW) ? 1 : 2;
        int maxLetters = Math.max(minLetters, Math.min(KineticaConstants.MAX_WORD_LEN, (int) Math.ceil(arcLen / KineticaConstants.KW_PER_LETTER) + 2));

        boolean[] isStart = new boolean[26];
        boolean[] isEnd = new boolean[26];
        boolean[] nearPath = new boolean[26];
        int[][] passIdx = new int[26][];

        float startX = resampled[0];
        float startY = resampled[1];
        float endX = resampled[2 * (n - 1)];
        float endY = resampled[2 * (n - 1) + 1];

        float prominence = KineticaConstants.PASS_SPLIT_PROMINENCE_KW;
        int maxPasses = n / 2;
        int[] passScratch = new int[maxPasses];

        for (int code = 0; code < 26; code++) {
            if (!geometry.hasKey(code)) continue;

            float dStart = geometry.distToCenterKw(startX, startY, code);
            if (dStart <= KineticaConstants.R_ENDPOINT_KW) {
                isStart[code] = true;
            }

            float dEnd = geometry.distToCenterKw(endX, endY, code);
            if (dEnd <= KineticaConstants.R_ENDPOINT_KW) {
                isEnd[code] = true;
            }

            int count = 0;
            int bestIdx = -1;
            float best = 0f;
            float peak = 0f;
            for (int k = 0; k < n; k++) {
                float d = geometry.distToCenterKw(resampled[2 * k], resampled[2 * k + 1], code);
                if (d > KineticaConstants.R_INNER_KW) {
                    if (bestIdx != -1 && count < maxPasses) passScratch[count++] = bestIdx;
                    bestIdx = -1;
                    continue;
                }
                if (bestIdx == -1) {
                    bestIdx = k;
                    best = d;
                    peak = d;
                    continue;
                }
                if (d > peak) peak = d;
                if (peak - best >= prominence && peak - d >= prominence) {
                    if (count < maxPasses) passScratch[count++] = bestIdx;
                    bestIdx = k;
                    best = d;
                    peak = d;
                } else if (d < best) {
                    best = d;
                    bestIdx = k;
                    peak = d;
                }
            }
            if (bestIdx != -1 && count < maxPasses) {
                passScratch[count++] = bestIdx;
            }

            if (count > 0) {
                nearPath[code] = true;
                passIdx[code] = Arrays.copyOf(passScratch, count);
            }
        }

        return new Segment(
                resampled, arcLen, arcLen, minLetters, maxLetters, isStart, isEnd, nearPath, passIdx,
                false, false, streamId, startTimeMs, endTimeMs
        );
    }
}
