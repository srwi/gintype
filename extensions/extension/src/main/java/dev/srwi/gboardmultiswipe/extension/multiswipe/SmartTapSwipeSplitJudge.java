package dev.srwi.gboardmultiswipe.extension.multiswipe;

import dev.srwi.gboardmultiswipe.extension.multiswipe.MultiSwipeInputToken.WordCandidate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates whether a short pure-tap prefix (1–3 letters) followed by a swipe should stay
 * separate with a space instead of being combined into a single word.
 */
final class SmartTapSwipeSplitJudge {

    static final int MIN_PREFIX_LEN = 1;
    static final int MAX_PREFIX_LEN = 3;
    static final int MIN_PREFIX_FREQ_2_3 = 8;
    static final float MIN_SPLIT_SWIPE_SCORE = 0.08f;

    static final float ONE_LETTER_RATIO = 1.40f;
    static final float ONE_LETTER_MARGIN = 0.25f;
    static final float MULTI_LETTER_RATIO = 1.25f;
    static final float MULTI_LETTER_MARGIN = 0.08f;
    static final float BIGRAM_BONUS = 0.04f;

    private static final Map<String, Set<String>> ONE_LETTER_WORDS_BY_LANG = buildOneLetterMap();

    private SmartTapSwipeSplitJudge() {}

    private static Map<String, Set<String>> buildOneLetterMap() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put("en", wordSet("a", "i"));
        map.put("es", wordSet("y", "a", "o", "e", "u"));
        map.put("it", wordSet("e", "a", "o", "i", "è"));
        map.put("pt", wordSet("a", "o", "e", "é"));
        map.put("fr", wordSet("a", "à", "y"));
        map.put("pl", wordSet("w", "z", "i", "o", "a", "u"));
        Set<String> csSk = wordSet("v", "z", "s", "k", "u", "o", "a", "i");
        map.put("cs", csSk);
        map.put("sk", csSk);
        map.put("hu", wordSet("a"));
        Set<String> nordic = wordSet("i", "å");
        map.put("da", nordic);
        map.put("nb", nordic);
        map.put("nn", nordic);
        map.put("no", nordic);
        map.put("sv", wordSet("i"));
        map.put("ca", wordSet("i", "a", "o"));
        map.put("ro", wordSet("o", "e", "a"));
        map.put("nl", wordSet("u"));
        return Collections.unmodifiableMap(map);
    }

    private static Set<String> wordSet(String... words) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(words)));
    }

    static String normalizeLang(String lang) {
        if (lang == null) return "";
        String trimmed = lang.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return "";
        int sep = trimmed.indexOf('-');
        int under = trimmed.indexOf('_');
        int cut = (sep >= 0 && under >= 0) ? Math.min(sep, under) : Math.max(sep, under);
        return (cut >= 0) ? trimmed.substring(0, cut) : trimmed;
    }

    static boolean isEligibleTapPrefix(String rawPrefix) {
        if (rawPrefix == null) return false;
        int len = rawPrefix.length();
        if (len < MIN_PREFIX_LEN || len > MAX_PREFIX_LEN) return false;
        for (int i = 0; i < len; i++) {
            if (!Character.isLetter(rawPrefix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean canStandAlone(String rawPrefix, String lang, int prefixFreq) {
        if (!isEligibleTapPrefix(rawPrefix)) return false;
        String lower = rawPrefix.toLowerCase(Locale.ROOT);
        if (lower.length() == 1) {
            Set<String> allowed = ONE_LETTER_WORDS_BY_LANG.get(normalizeLang(lang));
            return allowed != null && allowed.contains(lower);
        }
        return prefixFreq >= MIN_PREFIX_FREQ_2_3;
    }

    static String contextForm(String rawPrefix, String lang) {
        if (rawPrefix == null) return null;
        if ("en".equals(normalizeLang(lang)) && "i".equalsIgnoreCase(rawPrefix)) {
            return "I";
        }
        return rawPrefix;
    }

    static boolean shouldSplit(
            String rawPrefix,
            String lang,
            int prefixFreq,
            float prefixBigramScore,
            List<WordCandidate> combinedCandidates,
            List<WordCandidate> splitCandidates
    ) {
        if (!canStandAlone(rawPrefix, lang, prefixFreq)) {
            return false;
        }
        if (splitCandidates == null || splitCandidates.isEmpty()) {
            return false;
        }
        WordCandidate splitTop = splitCandidates.get(0);
        if (splitTop == null
                || splitTop.word == null
                || splitTop.word.isEmpty()
                || splitTop.totalScore < MIN_SPLIT_SWIPE_SCORE) {
            return false;
        }
        WordCandidate combinedTop = (combinedCandidates != null && !combinedCandidates.isEmpty())
                ? combinedCandidates.get(0) : null;
        if (combinedTop == null || combinedTop.word == null || combinedTop.word.isEmpty()) {
            return true;
        }
        float effectiveSplit = splitTop.totalScore + Math.max(0f, prefixBigramScore) * BIGRAM_BONUS;
        if (rawPrefix.length() == 1) {
            return effectiveSplit >= combinedTop.totalScore * ONE_LETTER_RATIO + ONE_LETTER_MARGIN;
        }
        return effectiveSplit >= combinedTop.totalScore * MULTI_LETTER_RATIO + MULTI_LETTER_MARGIN;
    }
}
