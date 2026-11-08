package dev.jason.gboardpatches.extension.nintype;

import java.util.List;

/**
 * Touch sample, gesture token, and decoded candidate representations for Nintype.
 */
public final class NintypeInputToken {

    public static final class TouchPoint {
        public final float x;
        public final float y;
        public final long timeMs;
        public final int pointerId;

        public TouchPoint(float x, float y, long timeMs, int pointerId) {
            this.x = x;
            this.y = y;
            this.timeMs = timeMs;
            this.pointerId = pointerId;
        }
    }

    public enum Kind {
        TAP,
        SWIPE
    }

    public final Kind kind;
    public final int pointerId;
    public final long startTimeMs;
    public final long endTimeMs;
    // For TAP:
    public final char character;
    public final float tapX;
    public final float tapY;
    // For SWIPE:
    public final List<TouchPoint> points;
    public final float pathLengthPx;

    private NintypeInputToken(
            Kind kind,
            int pointerId,
            long startTimeMs,
            long endTimeMs,
            char character,
            float tapX,
            float tapY,
            List<TouchPoint> points,
            float pathLengthPx
    ) {
        this.kind = kind;
        this.pointerId = pointerId;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.character = character;
        this.tapX = tapX;
        this.tapY = tapY;
        this.points = points;
        this.pathLengthPx = pathLengthPx;
    }

    public static NintypeInputToken tap(
            int pointerId,
            char character,
            float x,
            float y,
            long startTimeMs,
            long endTimeMs
    ) {
        return new NintypeInputToken(
                Kind.TAP, pointerId, startTimeMs, endTimeMs, character, x, y, null, 0f);
    }

    public static NintypeInputToken swipe(
            int pointerId,
            List<TouchPoint> points,
            float pathLengthPx,
            long startTimeMs,
            long endTimeMs
    ) {
        return new NintypeInputToken(
                Kind.SWIPE, pointerId, startTimeMs, endTimeMs, '\0', 0f, 0f, points, pathLengthPx);
    }

    public static final class WordCandidate implements Comparable<WordCandidate> {
        public final String word;
        public final float totalScore;
        public final float spatialScore;
        public final float frequencyScore;
        public final float bigramScore;

        public WordCandidate(
                String word,
                float totalScore,
                float spatialScore,
                float frequencyScore,
                float bigramScore
        ) {
            this.word = word;
            this.totalScore = totalScore;
            this.spatialScore = spatialScore;
            this.frequencyScore = frequencyScore;
            this.bigramScore = bigramScore;
        }

        @Override
        public int compareTo(WordCandidate other) {
            return Float.compare(other.totalScore, this.totalScore);
        }
    }
}
