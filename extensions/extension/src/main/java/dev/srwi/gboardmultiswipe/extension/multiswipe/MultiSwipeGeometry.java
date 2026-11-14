package dev.srwi.gboardmultiswipe.extension.multiswipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spatial geometry model of Gboard's active keyboard for MultiSwipe DTW decoding.
 * Ported from Kinetica's KeyboardGeometry.kt.
 */
public final class MultiSwipeGeometry {

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

        public boolean contains(float px, float py, float marginX, float marginY) {
            float hW = width * 0.5f + marginX;
            float hH = height * 0.5f + marginY;
            return px >= centerX - hW && px <= centerX + hW
                    && py >= centerY - hH && py <= centerY + hH;
        }
    }

    public static final String NO_SPACE_BEFORE = ".,!?;:)]}\u00bb\u2026";

    public boolean isPunctuationChar(char c) {
        return NO_SPACE_BEFORE.indexOf(c) >= 0;
    }

    private final List<KeySpec> allKeys = new ArrayList<>(32);
    private final List<KeySpec> nonLetterKeys = new ArrayList<>(16);
    private final KeySpec[] letterArray = new KeySpec[MultiSwipeAlphabet.ALPHABET_SIZE];

    public float keyWidthPx = 96f;
    public float keyHeightPx = 140f;

    private float minLetterY = 0f;
    private float maxLetterY = 600f;
    private boolean hasDedicatedUmlauts = false;

    public MultiSwipeGeometry() {}

    public void setKeys(List<KeySpec> specs, float avgWidth, float avgHeight, boolean hasUmlauts) {
        allKeys.clear();
        nonLetterKeys.clear();
        Arrays.fill(letterArray, null);
        this.hasDedicatedUmlauts = hasUmlauts;

        if (avgWidth > 10f) this.keyWidthPx = avgWidth;
        if (avgHeight > 10f) this.keyHeightPx = avgHeight;

        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (int i = 0; i < specs.size(); i++) {
            KeySpec spec = specs.get(i);
            if (spec.character != '\0') {
                allKeys.add(spec);
            }
            int idx = MultiSwipeAlphabet.charIndex(spec.character);
            if (idx >= 0) {
                if (letterArray[idx] == null) {
                    letterArray[idx] = spec;
                }
                float top = spec.centerY - spec.height * 0.5f;
                float bot = spec.centerY + spec.height * 0.5f;
                if (top < minY) minY = top;
                if (bot > maxY) maxY = bot;
            } else {
                nonLetterKeys.add(spec);
            }
        }

        if (minY < maxY) {
            this.minLetterY = minY;
            this.maxLetterY = maxY;
        }
    }

    public boolean isValid() {
        int count = 0;
        for (int i = 0; i < MultiSwipeAlphabet.ALPHABET_SIZE; i++) {
            if (letterArray[i] != null) count++;
        }
        return count >= 20;
    }

    public boolean hasDedicatedUmlauts() {
        return hasDedicatedUmlauts;
    }

    public float midlinePx() {
        KeySpec kT = letterArray[MultiSwipeAlphabet.charIndex('t')];
        KeySpec kY = letterArray[MultiSwipeAlphabet.charIndex('y')];
        KeySpec kZ = letterArray[MultiSwipeAlphabet.charIndex('z')];
        KeySpec kRightTop = kY;
        if (kT != null && kY != null && kZ != null) {
            // In QWERTZ, 'z' sits on the top row adjacent to 't' while 'y' sits in the bottom row.
            if (Math.abs(kZ.centerY - kT.centerY) < Math.abs(kY.centerY - kT.centerY)) {
                kRightTop = kZ;
            }
        } else if (kRightTop == null) {
            kRightTop = kZ;
        }
        float sum = 0f;
        int pairs = 0;
        if (kT != null && kRightTop != null) {
            sum += (kT.centerX + kRightTop.centerX) * 0.5f;
            pairs++;
        }
        KeySpec kG = letterArray[MultiSwipeAlphabet.charIndex('g')];
        KeySpec kH = letterArray[MultiSwipeAlphabet.charIndex('h')];
        if (kG != null && kH != null) {
            sum += (kG.centerX + kH.centerX) * 0.5f;
            pairs++;
        }
        if (pairs > 0) {
            return sum / pairs;
        }

        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (int i = 0; i < letterArray.length; i++) {
            KeySpec k = letterArray[i];
            if (k == null) continue;
            if (k.centerX < minX) minX = k.centerX;
            if (k.centerX > maxX) maxX = k.centerX;
        }
        if (minX < maxX) {
            return (minX + maxX) * 0.5f;
        }
        if (!allKeys.isEmpty()) {
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

    /**
     * Returns the letter code (0..25) whose key bounds contain the (xKw, yKw) point,
     * falling back to the nearest letter key within 1.45 kw, or -1 if outside the letter area.
     */
    public int keyAtKw(float xKw, float yKw) {
        float px = xKw * keyWidthPx;
        float py = yKw * keyWidthPx;
        for (int i = 0; i < MultiSwipeAlphabet.ALPHABET_SIZE; i++) {
            KeySpec k = letterArray[i];
            if (k == null) continue;
            float hw = k.width * 0.5f;
            float hh = k.height * 0.5f;
            if (px >= k.centerX - hw && px <= k.centerX + hw
                    && py >= k.centerY - hh && py <= k.centerY + hh) {
                return i;
            }
        }
        // Check dedicated umlauts/punctuation keys mapped to folded letter idx if inside box
        for (int i = 0; i < allKeys.size(); i++) {
            KeySpec k = allKeys.get(i);
            float hw = k.width * 0.5f;
            float hh = k.height * 0.5f;
            if (px >= k.centerX - hw && px <= k.centerX + hw
                    && py >= k.centerY - hh && py <= k.centerY + hh) {
                int folded = foldSupportedCharIndex(k.character);
                if (folded >= 0) return folded;
            }
        }
        if (!isOnLetterArea(px, py)) return -1;
        KeySpec nearest = nearestLetterKey(px, py);
        return nearest != null ? MultiSwipeAlphabet.charIndex(nearest.character) : -1;
    }

    /**
     * Sticky-bounds hysteresis check: true while (xKw, yKw) stays within `code`'s key rect
     * inflated by `inflateKw` on every side.
     */
    public boolean insideInflatedKw(float xKw, float yKw, int code, float inflateKw) {
        if (code < 0 || code >= MultiSwipeAlphabet.ALPHABET_SIZE) return false;
        KeySpec k = letterArray[code];
        if (k == null) return false;
        float px = xKw * keyWidthPx;
        float py = yKw * keyWidthPx;
        float inflatePx = inflateKw * keyWidthPx;
        float hw = k.width * 0.5f + inflatePx;
        float hh = k.height * 0.5f + inflatePx;
        return px >= k.centerX - hw && px <= k.centerX + hw
                && py >= k.centerY - hh && py <= k.centerY + hh;
    }

    public static int foldSupportedCharIndex(char c) {
        int idx = MultiSwipeAlphabet.charIndex(c);
        if (idx >= 0) return idx;
        char mapped = MultiSwipeAccentFolder.mapSingleChar(Character.toLowerCase(c));
        return MultiSwipeAlphabet.charIndex(mapped);
    }

    public KeySpec keySpecByIdx(int charIdx) {
        if (charIdx < 0 || charIdx >= MultiSwipeAlphabet.ALPHABET_SIZE) return null;
        return letterArray[charIdx];
    }

    public boolean hasKey(int code) {
        return code >= 0 && code < MultiSwipeAlphabet.ALPHABET_SIZE && letterArray[code] != null;
    }

    public float centerXKw(int code) {
        KeySpec k = (code >= 0 && code < MultiSwipeAlphabet.ALPHABET_SIZE) ? letterArray[code] : null;
        return k != null ? (k.centerX / keyWidthPx) : 0f;
    }

    public float centerYKw(int code) {
        KeySpec k = (code >= 0 && code < MultiSwipeAlphabet.ALPHABET_SIZE) ? letterArray[code] : null;
        return k != null ? (k.centerY / keyWidthPx) : 0f;
    }

    public float distToCenterKw(float xKw, float yKw, int code) {
        KeySpec k = (code >= 0 && code < MultiSwipeAlphabet.ALPHABET_SIZE) ? letterArray[code] : null;
        if (k == null) return Float.MAX_VALUE;
        float dx = xKw - (k.centerX / keyWidthPx);
        float dy = yKw - (k.centerY / keyWidthPx);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public float keyDist(int code1, int code2) {
        if (code1 == code2) return 0f;
        if (code1 < 0 || code1 >= MultiSwipeAlphabet.ALPHABET_SIZE || code2 < 0 || code2 >= MultiSwipeAlphabet.ALPHABET_SIZE) return 0f;
        KeySpec k1 = letterArray[code1];
        KeySpec k2 = letterArray[code2];
        if (k1 == null || k2 == null) return 0f;
        float dx = (k1.centerX - k2.centerX) / keyWidthPx;
        float dy = (k1.centerY - k2.centerY) / keyWidthPx;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public KeySpec keySpec(char c) {
        int idx = MultiSwipeAlphabet.charIndex(c);
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

    public KeySpec nearestLetterKey(float px, float py) {
        KeySpec best = null;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < MultiSwipeAlphabet.ALPHABET_SIZE; i++) {
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
        for (int i = 0; i < letterArray.length; i++) {
            KeySpec k = letterArray[i];
            if (k != null && k.contains(px, py, 0f, 0f)) {
                return true;
            }
        }
        for (int i = 0; i < nonLetterKeys.size(); i++) {
            if (nonLetterKeys.get(i).contains(px, py, 0f, 0f)) {
                return false;
            }
        }
        float gapX = keyWidthPx * 0.12f;
        float gapY = keyHeightPx * 0.12f;
        if (py < minLetterY - gapY || py > maxLetterY + gapY) return false;
        KeySpec nearest = nearestLetterKey(px, py);
        if (nearest == null) return false;
        return nearest.contains(px, py, gapX, gapY);
    }

    public KeySpec findKeyAt(float px, float py) {
        for (int i = 0; i < allKeys.size(); i++) {
            KeySpec k = allKeys.get(i);
            float hW = k.width * 0.5f;
            float hH = k.height * 0.5f;
            if (px >= k.centerX - hW && px <= k.centerX + hW
                    && py >= k.centerY - hH && py <= k.centerY + hH) {
                return k;
            }
        }
        return null;
    }

    public KeySpec nearestAnyKey(float px, float py) {
        KeySpec best = null;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < allKeys.size(); i++) {
            KeySpec k = allKeys.get(i);
            float d = k.distanceSqTo(px, py);
            if (d < bestDist) {
                bestDist = d;
                best = k;
            }
        }
        return best;
    }

    /**
     * Builds a standard QWERTY or QWERTZ geometry for fallback or unit testing.
     */
    public static MultiSwipeGeometry createStandardLayout(
            float totalWidthPx,
            float totalHeightPx,
            boolean qwertz
    ) {
        MultiSwipeGeometry geo = new MultiSwipeGeometry();
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

    public List<KeySpec> allKeysSnapshot() {
        return new ArrayList<>(allKeys);
    }
}
