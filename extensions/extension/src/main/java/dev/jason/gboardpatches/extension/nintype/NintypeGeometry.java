package dev.jason.gboardpatches.extension.nintype;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spatial geometry model of Gboard's active keyboard for Nintype Gaussian proximity scoring.
 * Ported from Kinetica's KeyboardGeometry.kt.
 */
public final class NintypeGeometry {

    public static final class KeySpec {
        public final char character;
        public final float centerX;
        public final float centerY;
        public final float width;
        public final float height;

        public KeySpec(char character, float centerX, float centerY, float width, float height) {
            this.character = character;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
        }

        public float distanceSqTo(float px, float py) {
            float dx = px - centerX;
            float dy = py - centerY;
            return dx * dx + dy * dy;
        }
    }

    public static final class KeyProximity implements Comparable<KeyProximity> {
        public final char key;
        public final int charIdx;
        public final float score;

        public KeyProximity(char key, int charIdx, float score) {
            this.key = key;
            this.charIdx = charIdx;
            this.score = score;
        }

        @Override
        public int compareTo(KeyProximity other) {
            return Float.compare(other.score, this.score);
        }
    }

    private final List<KeySpec> allKeys = new ArrayList<>(32);
    private final KeySpec[] letterArray = new KeySpec[NintypeAlphabet.ALPHABET_SIZE];

    public float keyWidthPx = 96f;
    public float keyHeightPx = 140f;
    public float sigma = 96f * 0.78f;
    private float invTwoSigmaSq = 1f / (2f * sigma * sigma);

    private float minLetterY = 0f;
    private float maxLetterY = 600f;
    private boolean hasDedicatedUmlauts = false;

    public NintypeGeometry() {}

    public void setKeys(List<KeySpec> specs, float avgWidth, float avgHeight, boolean hasUmlauts) {
        allKeys.clear();
        Arrays.fill(letterArray, null);
        this.hasDedicatedUmlauts = hasUmlauts;

        if (avgWidth > 10f) this.keyWidthPx = avgWidth;
        if (avgHeight > 10f) this.keyHeightPx = avgHeight;
        this.sigma = this.keyWidthPx * 0.78f;
        this.invTwoSigmaSq = 1f / (2f * sigma * sigma);

        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (int i = 0; i < specs.size(); i++) {
            KeySpec spec = specs.get(i);
            allKeys.add(spec);
            int idx = NintypeAlphabet.charIndex(spec.character);
            if (idx >= 0) {
                if (letterArray[idx] == null) {
                    letterArray[idx] = spec;
                }
                float top = spec.centerY - spec.height * 0.65f;
                float bot = spec.centerY + spec.height * 0.65f;
                if (top < minY) minY = top;
                if (bot > maxY) maxY = bot;
            }
        }

        if (minY < maxY) {
            this.minLetterY = minY;
            this.maxLetterY = maxY;
        }
    }

    public boolean isValid() {
        int count = 0;
        for (int i = 0; i < NintypeAlphabet.ALPHABET_SIZE; i++) {
            if (letterArray[i] != null) count++;
        }
        return count >= 20;
    }

    public boolean hasDedicatedUmlauts() {
        return hasDedicatedUmlauts;
    }

    public float midlinePx() {
        if (!allKeys.isEmpty()) {
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            for (int i = 0; i < allKeys.size(); i++) {
                KeySpec k = allKeys.get(i);
                if (k.centerX < minX) minX = k.centerX;
                if (k.centerX > maxX) maxX = k.centerX;
            }
            if (minX < maxX) {
                return (minX + maxX) * 0.5f;
            }
        }
        return 540f;
    }

    public KeySpec keySpecByIdx(int charIdx) {
        if (charIdx < 0 || charIdx >= NintypeAlphabet.ALPHABET_SIZE) return null;
        return letterArray[charIdx];
    }

    public KeySpec keySpec(char c) {
        int idx = NintypeAlphabet.charIndex(c);
        if (idx >= 0) {
            KeySpec s = letterArray[idx];
            if (s != null) return s;
        }
        char lower = Character.toLowerCase(c);
        for (int i = 0; i < allKeys.size(); i++) {
            KeySpec k = allKeys.get(i);
            if (k.character == lower) return k;
        }
        return null;
    }

    public float gaussianProximity(float px, float py, char keyChar) {
        int idx = NintypeAlphabet.charIndex(keyChar);
        if (idx >= 0) return gaussianProximityByIdx(px, py, idx);
        KeySpec k = keySpec(keyChar);
        if (k == null) return 0f;
        float d2 = k.distanceSqTo(px, py);
        return (float) Math.exp(-d2 * invTwoSigmaSq);
    }

    public float gaussianProximityByIdx(float px, float py, int charIdx) {
        if (charIdx < 0 || charIdx >= NintypeAlphabet.ALPHABET_SIZE) return 0f;
        KeySpec k = letterArray[charIdx];
        if (k == null) return 0f;
        float d2 = k.distanceSqTo(px, py);
        return (float) Math.exp(-d2 * invTwoSigmaSq);
    }

    public KeySpec nearestLetterKey(float px, float py) {
        KeySpec best = null;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < NintypeAlphabet.ALPHABET_SIZE; i++) {
            KeySpec k = letterArray[i];
            if (k == null) continue;
            float d = k.distanceSqTo(px, py);
            if (d < bestDist) {
                bestDist = d;
                best = k;
            }
        }
        return best;
    }

    public boolean isOnLetterArea(float px, float py) {
        if (py < minLetterY || py > maxLetterY) return false;
        KeySpec nearest = nearestLetterKey(px, py);
        if (nearest == null) return false;
        float distSq = nearest.distanceSqTo(px, py);
        float maxRadius = Math.max(keyWidthPx, keyHeightPx) * 1.45f;
        return distSq <= maxRadius * maxRadius;
    }

    public List<KeyProximity> nearKeys(float px, float py, int maxCount) {
        float cutoffDistSq = (sigma * 2.5f) * (sigma * 2.5f);
        List<KeyProximity> out = new ArrayList<>(maxCount);

        for (int idx = 0; idx < NintypeAlphabet.ALPHABET_SIZE; idx++) {
            KeySpec k = letterArray[idx];
            if (k == null) continue;
            float d2 = k.distanceSqTo(px, py);
            if (d2 <= cutoffDistSq) {
                float score = (float) Math.exp(-d2 * invTwoSigmaSq);
                out.add(new KeyProximity(k.character, idx, score));
            }
        }

        if (out.isEmpty()) {
            KeySpec nearest = nearestLetterKey(px, py);
            if (nearest != null) {
                int idx = NintypeAlphabet.charIndex(nearest.character);
                if (idx >= 0) {
                    out.add(new KeyProximity(nearest.character, idx, 0.2f));
                }
            }
        }

        out.sort(null);
        if (out.size() > maxCount) {
            return out.subList(0, maxCount);
        }
        return out;
    }

    /**
     * Builds a standard QWERTY or QWERTZ geometry for fallback or unit testing.
     */
    public static NintypeGeometry createStandardLayout(
            float totalWidthPx,
            float totalHeightPx,
            boolean qwertz
    ) {
        NintypeGeometry geo = new NintypeGeometry();
        String row0 = qwertz ? "qwertzuiop" : "qwertyuiop";
        String row1 = "asdfghjkl";
        String row2 = qwertz ? "yxcvbnm" : "zxcvbnm";

        float keyW = totalWidthPx / 10f;
        float rowH = totalHeightPx / 4f;
        List<KeySpec> specs = new ArrayList<>(26);

        for (int i = 0; i < row0.length(); i++) {
            specs.add(new KeySpec(row0.charAt(i), (i + 0.5f) * keyW, 0.5f * rowH, keyW, rowH));
        }
        float offset1 = (totalWidthPx - row1.length() * keyW) * 0.5f;
        for (int i = 0; i < row1.length(); i++) {
            specs.add(new KeySpec(row1.charAt(i), offset1 + (i + 0.5f) * keyW, 1.5f * rowH, keyW, rowH));
        }
        float offset2 = (totalWidthPx - row2.length() * keyW) * 0.5f;
        for (int i = 0; i < row2.length(); i++) {
            specs.add(new KeySpec(row2.charAt(i), offset2 + (i + 0.5f) * keyW, 2.5f * rowH, keyW, rowH));
        }

        geo.setKeys(specs, keyW, rowH, false);
        return geo;
    }
}
