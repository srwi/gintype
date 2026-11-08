package dev.jason.gboardpatches.extension.nintype;

import dev.jason.gboardpatches.extension.nintype.NintypeGeometry.KeyProximity;
import dev.jason.gboardpatches.extension.nintype.NintypeGeometry.KeySpec;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.Kind;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.TouchPoint;
import dev.jason.gboardpatches.extension.nintype.NintypeInputToken.WordCandidate;
import dev.jason.gboardpatches.extension.nintype.NintypeTrie.WordForm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full Nintype two-finger swipe and tap+swipe decoder engine.
 *
 * Combines:
 * 1. Continuous beam search + curve-mapper over chronological and interleaved multi-thumb anchor sequences
 * 2. Dual-thumb independent beam + split/interleave combiner (ThumbBeam.kt + MergeAlternatives.kt)
 * 3. DTW ideal path re-scoring for single-stroke paths (DtwMatcher.kt)
 * 4. Gboard active dictionary validation & boosting bridge.
 */
public final class NintypeDecoder {

    public interface GboardWordCheckCallback {
        /**
         * Returns a boolean array of length words.size() indicating which words are known in
         * Gboard's active Delight5 dictionary / DynamicLm user dictionary.
         */
        boolean[] checkWords(List<String> words);
    }

    private static final int BEAM_WIDTH = 42;
    private static final int KEYS_PER_STEP = 5;
    private static final float SKIP_PENALTY = 0.25f;
    private static final float ANCHOR_COMMIT_WEIGHT = 1.15f;
    private static final float WAYPOINT_COMMIT_WEIGHT = 0.65f;
    private static final float DOUBLE_LETTER_FACTOR = 0.72f;
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

    private static final class Anchor {
        final float x;
        final float y;
        final long timeMs;
        final boolean isMandatory;
        final boolean isEndpoint;
        final float weight;

        Anchor(float x, float y, long timeMs, boolean isMandatory, boolean isEndpoint, float weight) {
            this.x = x;
            this.y = y;
            this.timeMs = timeMs;
            this.isMandatory = isMandatory;
            this.isEndpoint = isEndpoint;
            this.weight = weight;
        }
    }

    private static final class AnchorSequence {
        final List<Anchor> anchors;
        final float sequencePenalty;

        AnchorSequence(List<Anchor> anchors, float sequencePenalty) {
            this.anchors = anchors;
            this.sequencePenalty = sequencePenalty;
        }
    }

    private static final class BeamHypothesis implements Comparable<BeamHypothesis> {
        final int trieNode;
        final float spatialLogScore;
        final int matchedCount;
        final int lastCharIdx;
        final boolean matchedStart;

        BeamHypothesis(
                int trieNode,
                float spatialLogScore,
                int matchedCount,
                int lastCharIdx,
                boolean matchedStart
        ) {
            this.trieNode = trieNode;
            this.spatialLogScore = spatialLogScore;
            this.matchedCount = matchedCount;
            this.lastCharIdx = lastCharIdx;
            this.matchedStart = matchedStart;
        }

        float rankingKey(NintypeTrie trie) {
            int maxFreq = trie.getMaxSubtreeFreq(trieNode);
            float freqPrior = (float) (Math.log(maxFreq + 1.0) / NintypeAlphabet.FREQ_LOG_SCALE);
            return spatialLogScore + freqPrior * 0.40f + (matchedStart ? 0.35f : 0f);
        }

        @Override
        public int compareTo(BeamHypothesis other) {
            return Float.compare(other.spatialLogScore, this.spatialLogScore);
        }
    }

    /**
     * Decodes a multi-touch gesture session (2-finger swipe, tap+swipe, or swipe+tap).
     */
    public List<WordCandidate> decode(
            List<NintypeInputToken> tokens,
            String prevWord,
            int maxResults,
            GboardWordCheckCallback gboardWordCheck
    ) {
        if (tokens == null || tokens.isEmpty()) return Collections.emptyList();

        List<AnchorSequence> sequences = buildAnchorSequences(tokens);
        if (sequences.isEmpty()) return Collections.emptyList();

        // Only use single-curve DTW when there is a single swipe token; multi-finger chords jump
        // between left and right hands and should not be penalized against a 1-piece polyline.
        List<TouchPoint> singleCurvePoints = (tokens.size() == 1)
                ? collectRawPoints(tokens)
                : Collections.emptyList();

        Map<String, WordCandidate> seenWords = new LinkedHashMap<>();

        for (int sIdx = 0; sIdx < sequences.size(); sIdx++) {
            AnchorSequence seq = sequences.get(sIdx);
            if (seq.anchors.isEmpty()) continue;
            runBeamOnAnchorSequence(
                    seq.anchors,
                    seq.sequencePenalty,
                    singleCurvePoints,
                    prevWord,
                    seenWords
            );
        }

        // Also run dual-thumb split/interleave combiner when >= 2 tokens are present
        if (tokens.size() >= 2) {
            List<WordCandidate> dualThumbCandidates = decodeDualThumbCombinations(tokens, prevWord);
            for (int i = 0; i < dualThumbCandidates.size(); i++) {
                WordCandidate dc = dualThumbCandidates.get(i);
                String key = dc.word.toLowerCase();
                WordCandidate existing = seenWords.get(key);
                if (existing == null || dc.totalScore > existing.totalScore) {
                    seenWords.put(key, dc);
                }
            }
        }

        List<WordCandidate> candidates = new ArrayList<>(seenWords.values());

        // Bridge with Gboard's active Delight5 dictionary / user dictionary if available
        if (gboardWordCheck != null && !candidates.isEmpty()) {
            List<String> wordStrings = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                wordStrings.add(candidates.get(i).word);
            }
            boolean[] validInGboard = gboardWordCheck.checkWords(wordStrings);
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

    private void runBeamOnAnchorSequence(
            List<Anchor> anchors,
            float sequencePenalty,
            List<TouchPoint> singleCurvePoints,
            String prevWord,
            Map<String, WordCandidate> seenWords
    ) {
        Map<Integer, BeamHypothesis> beam = new HashMap<>(BEAM_WIDTH * 2);
        beam.put(0, new BeamHypothesis(0, -sequencePenalty, 0, -1, false));

        for (int stepIdx = 0; stepIdx < anchors.size(); stepIdx++) {
            Anchor anchor = anchors.get(stepIdx);
            boolean isFirstStep = (stepIdx == 0);
            boolean isLastStep = (stepIdx == anchors.size() - 1);

            List<KeyProximity> nearKeys = geometry.nearKeys(anchor.x, anchor.y, KEYS_PER_STEP);
            if (nearKeys.isEmpty()) continue;

            Map<Integer, BeamHypothesis> nextBeam = new HashMap<>(BEAM_WIDTH * 3);

            for (BeamHypothesis hyp : beam.values()) {
                // 1) Skip / pass-through transition
                if (!anchor.isMandatory && !isFirstStep) {
                    float skipCost = anchor.isEndpoint ? SKIP_PENALTY * 2.2f : SKIP_PENALTY;
                    BeamHypothesis skipped = new BeamHypothesis(
                            hyp.trieNode,
                            hyp.spatialLogScore - skipCost,
                            hyp.matchedCount,
                            hyp.lastCharIdx,
                            hyp.matchedStart
                    );
                    mergeHypothesis(nextBeam, skipped);
                }

                // 2) Commit key transitions
                for (int k = 0; k < nearKeys.size(); k++) {
                    KeyProximity kp = nearKeys.get(k);
                    float commitWeight = (anchor.isMandatory || anchor.isEndpoint)
                            ? ANCHOR_COMMIT_WEIGHT
                            : WAYPOINT_COMMIT_WEIGHT;
                    float stepGain = kp.score * anchor.weight * commitWeight;

                    int childNode = trie.child(hyp.trieNode, kp.charIdx);
                    if (childNode != -1) {
                        BeamHypothesis advanced = new BeamHypothesis(
                                childNode,
                                hyp.spatialLogScore + stepGain,
                                hyp.matchedCount + 1,
                                kp.charIdx,
                                hyp.matchedStart || isFirstStep
                        );
                        mergeHypothesis(nextBeam, advanced);

                        // Double-letter transition (e.g. "ll" in "hello", "ss" in "müssen")
                        int doubleChild = trie.child(childNode, kp.charIdx);
                        if (doubleChild != -1 && kp.score >= 0.55f) {
                            BeamHypothesis doubled = new BeamHypothesis(
                                    doubleChild,
                                    hyp.spatialLogScore + stepGain * (1f + DOUBLE_LETTER_FACTOR),
                                    hyp.matchedCount + 2,
                                    kp.charIdx,
                                    hyp.matchedStart || isFirstStep
                            );
                            mergeHypothesis(nextBeam, doubled);
                        }
                    }

                    // Same-key dwell absorption
                    if (kp.charIdx == hyp.lastCharIdx) {
                        BeamHypothesis dwell = new BeamHypothesis(
                                hyp.trieNode,
                                hyp.spatialLogScore + stepGain * 0.20f,
                                hyp.matchedCount,
                                hyp.lastCharIdx,
                                hyp.matchedStart
                        );
                        mergeHypothesis(nextBeam, dwell);
                    }
                }

                if (anchor.isMandatory && hyp.trieNode != 0) {
                    BeamHypothesis fallbackSkip = new BeamHypothesis(
                            hyp.trieNode,
                            hyp.spatialLogScore - SKIP_PENALTY * 3.5f,
                            hyp.matchedCount,
                            hyp.lastCharIdx,
                            hyp.matchedStart
                    );
                    mergeHypothesis(nextBeam, fallbackSkip);
                }

                if (isLastStep && trie.isWord(hyp.trieNode)) {
                    BeamHypothesis keepFinished = new BeamHypothesis(
                            hyp.trieNode,
                            hyp.spatialLogScore - SKIP_PENALTY * 1.2f,
                            hyp.matchedCount,
                            hyp.lastCharIdx,
                            hyp.matchedStart
                    );
                    mergeHypothesis(nextBeam, keepFinished);
                }
            }

            beam = pruneBeam(nextBeam, BEAM_WIDTH);
        }

        Anchor firstAnchor = anchors.get(0);
        Anchor lastAnchor = anchors.get(anchors.size() - 1);

        for (BeamHypothesis hyp : beam.values()) {
            if (hyp.matchedCount == 0) continue;

            if (trie.isWord(hyp.trieNode)) {
                List<WordForm> forms = trie.getWordForms(hyp.trieNode);
                if (!forms.isEmpty()) {
                    for (int i = 0; i < forms.size(); i++) {
                        WordForm form = forms.get(i);
                        addScoredCandidate(
                                seenWords,
                                form.displayWord,
                                form.frequency,
                                hyp,
                                anchors.size(),
                                firstAnchor,
                                lastAnchor,
                                singleCurvePoints,
                                prevWord
                        );
                    }
                } else {
                    String word = trie.getWord(hyp.trieNode);
                    if (word != null) {
                        addScoredCandidate(
                                seenWords,
                                word,
                                trie.getFrequency(hyp.trieNode),
                                hyp,
                                anchors.size(),
                                firstAnchor,
                                lastAnchor,
                                singleCurvePoints,
                                prevWord
                        );
                    }
                }
            }

            // 1-char completion if user lifted slightly early
            for (int cIdx = 0; cIdx < NintypeAlphabet.ALPHABET_SIZE; cIdx++) {
                int child = trie.child(hyp.trieNode, cIdx);
                if (child != -1 && trie.isWord(child)) {
                    float endProx = geometry.gaussianProximityByIdx(lastAnchor.x, lastAnchor.y, cIdx);
                    if (endProx >= 0.30f) {
                        BeamHypothesis childHyp = new BeamHypothesis(
                                child,
                                hyp.spatialLogScore + endProx * 0.55f,
                                hyp.matchedCount + 1,
                                cIdx,
                                hyp.matchedStart
                        );
                        List<WordForm> forms = trie.getWordForms(child);
                        if (!forms.isEmpty()) {
                            WordForm topForm = forms.get(0);
                            addScoredCandidate(
                                    seenWords,
                                    topForm.displayWord,
                                    topForm.frequency,
                                    childHyp,
                                    anchors.size(),
                                    firstAnchor,
                                    lastAnchor,
                                    singleCurvePoints,
                                    prevWord
                            );
                        } else {
                            String w = trie.getWord(child);
                            if (w != null) {
                                addScoredCandidate(
                                        seenWords,
                                        w,
                                        trie.getFrequency(child),
                                        childHyp,
                                        anchors.size(),
                                        firstAnchor,
                                        lastAnchor,
                                        singleCurvePoints,
                                        prevWord
                                );
                            }
                        }
                    }
                }
            }
        }
    }

    private void addScoredCandidate(
            Map<String, WordCandidate> seenWords,
            String word,
            int freq,
            BeamHypothesis hyp,
            int anchorCount,
            Anchor firstAnchor,
            Anchor lastAnchor,
            List<TouchPoint> rawPoints,
            String prevWord
    ) {
        WordCandidate cand = scoreCandidate(
                word, freq, hyp, anchorCount, firstAnchor, lastAnchor, rawPoints, prevWord);
        String key = word.toLowerCase();
        WordCandidate existing = seenWords.get(key);
        if (existing == null || cand.totalScore > existing.totalScore) {
            seenWords.put(key, cand);
        }
    }

    private WordCandidate scoreCandidate(
            String word,
            int freq,
            BeamHypothesis hyp,
            int anchorCount,
            Anchor firstAnchor,
            Anchor lastAnchor,
            List<TouchPoint> rawPoints,
            String prevWord
    ) {
        String folded = NintypeAccentFolder.fold(word);
        if (folded.isEmpty()) {
            return new WordCandidate(word, 0f, 0f, 0f, 0f);
        }

        float normDenom = Math.max(1f, Math.min((float) folded.length(), (float) anchorCount) * 0.85f);
        float beamSpatial = clamp01(hyp.spatialLogScore / normDenom);

        float startBonus = geometry.gaussianProximity(firstAnchor.x, firstAnchor.y, folded.charAt(0));
        float endBonus = geometry.gaussianProximity(lastAnchor.x, lastAnchor.y, folded.charAt(folded.length() - 1));
        float endpointScore = (startBonus * 0.55f + endBonus * 0.45f);

        float dtwSim = 0f;
        if (rawPoints != null && rawPoints.size() >= 3 && folded.length() >= 2) {
            dtwSim = computeDtwSimilarity(rawPoints, folded);
        }

        float spatialScore = (dtwSim > 0f)
                ? clamp01(beamSpatial * (1f - DTW_RESCORE_WEIGHT) + dtwSim * DTW_RESCORE_WEIGHT) * 0.72f
                        + endpointScore * 0.28f
                : clamp01(beamSpatial * 0.68f + endpointScore * 0.32f);

        float lengthRatio = (float) hyp.matchedCount / (float) Math.max(1, folded.length());
        float lengthPenalty = 1.0f;
        if (lengthRatio < 0.55f) {
            lengthPenalty = 0.72f;
        } else if (lengthRatio > 1.6f) {
            lengthPenalty = 0.82f;
        }

        float freqScore = clamp01((float) (Math.log(freq + 1.0) / NintypeAlphabet.FREQ_LOG_SCALE));
        float bigramScore = (prevWord != null && !prevWord.isEmpty())
                ? bigrams.score(prevWord, word)
                : 0f;

        boolean hasContext = prevWord != null && !prevWord.isEmpty() && bigramScore > 0f;
        float total = hasContext
                ? (NintypeAlphabet.WEIGHT_SPATIAL * spatialScore * lengthPenalty
                        + NintypeAlphabet.WEIGHT_FREQUENCY * freqScore
                        + NintypeAlphabet.WEIGHT_BIGRAM * bigramScore)
                : (0.62f * spatialScore * lengthPenalty + 0.38f * freqScore);

        return new WordCandidate(word, total, spatialScore, freqScore, bigramScore);
    }

    /**
     * Dual-thumb split/interleave combiner (ported from Kinetica's ThumbBeam.kt + MergeAlternatives.kt).
     * Handles chords where Left Thumb and Right Thumb swipe/tap separate prefix+suffix segments
     * or alternating characters (such as "als" = swipe a->s + tap l, "sie" = swipe s->e + tap i).
     */
    private List<WordCandidate> decodeDualThumbCombinations(
            List<NintypeInputToken> tokens,
            String prevWord
    ) {
        List<NintypeInputToken> ordered = new ArrayList<>(tokens);
        ordered.sort((a, b) -> Long.compare(a.startTimeMs, b.startTimeMs));

        List<List<String>> tokenPrefixes = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            tokenPrefixes.add(extractTokenSubstrings(ordered.get(i)));
        }

        List<WordCandidate> results = new ArrayList<>();
        if (tokenPrefixes.size() >= 2) {
            List<String> firstSubs = tokenPrefixes.get(0);
            List<String> secondSubs = tokenPrefixes.get(1);
            List<String> thirdSubs = tokenPrefixes.size() >= 3 ? tokenPrefixes.get(2) : null;

            for (int i = 0; i < firstSubs.size(); i++) {
                String p1 = firstSubs.get(i);
                for (int j = 0; j < secondSubs.size(); j++) {
                    String p2 = secondSubs.get(j);
                    int baseRank = i + j;
                    checkCombinedPattern(p1 + p2, baseRank, prevWord, results);
                    checkCombinedPattern(p2 + p1, baseRank + 1, prevWord, results);

                    if (p1.length() <= 4 && p2.length() <= 4) {
                        checkCombinedPattern(interleaveStrings(p1, p2), baseRank, prevWord, results);
                        checkCombinedPattern(interleaveStrings(p2, p1), baseRank, prevWord, results);
                    }

                    // Explicit tap-inside-swipe insertion (e.g. swipe "as" + tap "l" -> "als")
                    if (p1.length() >= 2 && p2.length() == 1) {
                        checkCombinedPattern(
                                p1.charAt(0) + p2 + p1.substring(1), baseRank, prevWord, results);
                        if (p1.length() >= 3) {
                            checkCombinedPattern(
                                    p1.substring(0, p1.length() - 1) + p2 + p1.charAt(p1.length() - 1),
                                    baseRank,
                                    prevWord,
                                    results
                            );
                        }
                    } else if (p2.length() >= 2 && p1.length() == 1) {
                        checkCombinedPattern(
                                p2.charAt(0) + p1 + p2.substring(1), baseRank, prevWord, results);
                        if (p2.length() >= 3) {
                            checkCombinedPattern(
                                    p2.substring(0, p2.length() - 1) + p1 + p2.charAt(p2.length() - 1),
                                    baseRank,
                                    prevWord,
                                    results
                            );
                        }
                    }

                    if (thirdSubs != null) {
                        for (int k = 0; k < Math.min(3, thirdSubs.size()); k++) {
                            checkCombinedPattern(p1 + p2 + thirdSubs.get(k), baseRank + k, prevWord, results);
                        }
                    }
                }
            }
        }
        return results;
    }

    private List<String> extractTokenSubstrings(NintypeInputToken token) {
        List<String> out = new ArrayList<>(6);
        if (token.kind == Kind.TAP) {
            List<KeyProximity> near = geometry.nearKeys(token.tapX, token.tapY, 3);
            for (int i = 0; i < near.size(); i++) {
                out.add(String.valueOf(near.get(i).key));
            }
            if (out.isEmpty() && token.character != '\0') {
                out.add(String.valueOf(Character.toLowerCase(token.character)));
            }
            return out;
        }

        List<Anchor> anchors = extractSingleSwipeAnchors(token);
        if (anchors.isEmpty()) return out;

        // Build compact key sequences from start, inflection, and end anchors of this swipe
        Anchor first = anchors.get(0);
        Anchor last = anchors.get(anchors.size() - 1);
        List<KeyProximity> startKeys = geometry.nearKeys(first.x, first.y, 2);
        List<KeyProximity> endKeys = geometry.nearKeys(last.x, last.y, 2);

        StringBuilder midChars = new StringBuilder();
        char prevMid = '\0';
        for (int i = 1; i < anchors.size() - 1; i++) {
            Anchor a = anchors.get(i);
            if (a.isMandatory || a.weight >= 0.85f) {
                KeySpec nearest = geometry.nearestLetterKey(a.x, a.y);
                if (nearest != null && nearest.character != prevMid) {
                    midChars.append(nearest.character);
                    prevMid = nearest.character;
                }
            }
        }

        for (int s = 0; s < startKeys.size(); s++) {
            char sc = startKeys.get(s).key;
            for (int e = 0; e < endKeys.size(); e++) {
                char ec = endKeys.get(e).key;
                if (midChars.length() > 0) {
                    StringBuilder full = new StringBuilder();
                    full.append(sc);
                    for (int m = 0; m < midChars.length(); m++) {
                        char mc = midChars.charAt(m);
                        if (mc != sc && mc != ec) {
                            full.append(mc);
                        }
                    }
                    if (ec != full.charAt(full.length() - 1)) {
                        full.append(ec);
                    }
                    out.add(full.toString());
                }
                if (sc != ec) {
                    out.add("" + sc + ec);
                } else {
                    out.add("" + sc);
                }
            }
        }
        return out;
    }

    private void checkCombinedPattern(
            String pattern,
            int rankPenalty,
            String prevWord,
            List<WordCandidate> out
    ) {
        if (pattern == null || pattern.length() < 2) return;
        int node = trie.findNode(pattern);
        if (node != -1 && trie.isWord(node)) {
            List<WordForm> forms = trie.getWordForms(node);
            String display = !forms.isEmpty() ? forms.get(0).displayWord : trie.getWord(node);
            int freq = !forms.isEmpty() ? forms.get(0).frequency : trie.getFrequency(node);
            if (display != null) {
                float spatial = Math.max(0.50f, 0.92f - rankPenalty * 0.05f);
                float freqScore = clamp01((float) (Math.log(freq + 1.0) / NintypeAlphabet.FREQ_LOG_SCALE));
                float bigram = (prevWord != null && !prevWord.isEmpty())
                        ? bigrams.score(prevWord, display)
                        : 0f;
                float total = (bigram > 0f)
                        ? (NintypeAlphabet.WEIGHT_SPATIAL * spatial
                                + NintypeAlphabet.WEIGHT_FREQUENCY * freqScore
                                + NintypeAlphabet.WEIGHT_BIGRAM * bigram)
                        : (0.62f * spatial + 0.38f * freqScore);
                out.add(new WordCandidate(display, total, spatial, freqScore, bigram));
            }
        }
    }

    private static String interleaveStrings(String a, String b) {
        StringBuilder sb = new StringBuilder(a.length() + b.length());
        int max = Math.max(a.length(), b.length());
        for (int i = 0; i < max; i++) {
            if (i < a.length()) sb.append(a.charAt(i));
            if (i < b.length()) sb.append(b.charAt(i));
        }
        return sb.toString();
    }

    private List<AnchorSequence> buildAnchorSequences(List<NintypeInputToken> tokens) {
        List<AnchorSequence> sequences = new ArrayList<>(4);
        List<Anchor> chronological = extractAnchors(tokens);
        if (!chronological.isEmpty()) {
            sequences.add(new AnchorSequence(chronological, 0f));
        }

        if (tokens.size() == 2) {
            List<NintypeInputToken> ordered = new ArrayList<>(tokens);
            ordered.sort((a, b) -> Long.compare(a.startTimeMs, b.startTimeMs));

            List<Anchor> a0 = extractTokenAnchors(ordered.get(0));
            List<Anchor> a1 = extractTokenAnchors(ordered.get(1));

            if (!a0.isEmpty() && !a1.isEmpty()) {
                // 1) Token 0 anchors interleaved around Token 1 (e.g. swipe a->s + tap l => a -> l -> s)
                if (a0.size() >= 2) {
                    List<Anchor> interleaved010 = new ArrayList<>(a0.size() + a1.size());
                    interleaved010.add(a0.get(0));
                    interleaved010.addAll(a1);
                    for (int i = 1; i < a0.size(); i++) {
                        interleaved010.add(a0.get(i));
                    }
                    sequences.add(new AnchorSequence(deduplicateCloseAnchors(interleaved010), 0f));
                }

                // 2) Token 1 anchors interleaved around Token 0 (e.g. tap l + swipe a->s => a -> l -> s)
                if (a1.size() >= 2) {
                    List<Anchor> interleaved101 = new ArrayList<>(a0.size() + a1.size());
                    interleaved101.add(a1.get(0));
                    interleaved101.addAll(a0);
                    for (int i = 1; i < a1.size(); i++) {
                        interleaved101.add(a1.get(i));
                    }
                    sequences.add(new AnchorSequence(deduplicateCloseAnchors(interleaved101), 0f));
                }

                // 3) All of Token 0 followed by all of Token 1 (e.g. swipe u->n + tap d => u -> n -> d)
                List<Anchor> seq01 = new ArrayList<>(a0.size() + a1.size());
                seq01.addAll(a0);
                seq01.addAll(a1);
                sequences.add(new AnchorSequence(deduplicateCloseAnchors(seq01), 0f));

                // 4) All of Token 1 followed by all of Token 0
                List<Anchor> seq10 = new ArrayList<>(a0.size() + a1.size());
                seq10.addAll(a1);
                seq10.addAll(a0);
                sequences.add(new AnchorSequence(deduplicateCloseAnchors(seq10), 0.08f));
            }
        }

        return sequences;
    }

    private List<Anchor> extractTokenAnchors(NintypeInputToken token) {
        if (token.kind == Kind.TAP) {
            return Collections.singletonList(
                    new Anchor(token.tapX, token.tapY, token.startTimeMs, true, true, 1.25f));
        } else if (token.kind == Kind.SWIPE) {
            return extractSingleSwipeAnchors(token);
        }
        return Collections.emptyList();
    }

    private List<Anchor> extractAnchors(List<NintypeInputToken> tokens) {
        List<Anchor> rawAnchors = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            rawAnchors.addAll(extractTokenAnchors(tokens.get(i)));
        }
        rawAnchors.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return deduplicateCloseAnchors(rawAnchors);
    }

    private List<Anchor> extractSingleSwipeAnchors(NintypeInputToken swipe) {
        List<TouchPoint> pts = swipe.points;
        if (pts == null || pts.isEmpty()) return Collections.emptyList();
        if (pts.size() == 1) {
            TouchPoint p = pts.get(0);
            return Collections.singletonList(new Anchor(p.x, p.y, p.timeMs, true, true, 1.15f));
        }

        List<Anchor> anchors = new ArrayList<>();
        TouchPoint first = pts.get(0);
        TouchPoint last = pts.get(pts.size() - 1);

        anchors.add(new Anchor(first.x, first.y, first.timeMs, true, true, 1.25f));

        float stepDist = Math.max(30f, geometry.keyWidthPx * 0.68f);
        float accumDist = 0f;

        for (int i = 1; i < pts.size() - 1; i++) {
            TouchPoint prev = pts.get(i - 1);
            TouchPoint cur = pts.get(i);
            TouchPoint next = pts.get(i + 1);

            float dx1 = cur.x - prev.x;
            float dy1 = cur.y - prev.y;
            float dx2 = next.x - cur.x;
            float dy2 = next.y - cur.y;

            float segLen1 = (float) Math.hypot(dx1, dy1);
            float segLen2 = (float) Math.hypot(dx2, dy2);
            accumDist += segLen1;

            boolean isCorner = false;
            float cornerWeight = 0.85f;
            if (segLen1 > 4f && segLen2 > 4f) {
                float cosAngle = (dx1 * dx2 + dy1 * dy2) / (segLen1 * segLen2);
                if (cosAngle < 0.55f) {
                    isCorner = true;
                    cornerWeight = (cosAngle < 0.0f) ? 1.15f : 0.95f;
                    accumDist = 0f;
                }
            }

            long dt = Math.max(1L, next.timeMs - prev.timeMs);
            float speedPxPerMs = (segLen1 + segLen2) / (float) dt;
            boolean isDwell = speedPxPerMs < 0.25f && accumDist > stepDist * 0.45f;

            if (isCorner || isDwell) {
                anchors.add(
                        new Anchor(
                                cur.x,
                                cur.y,
                                cur.timeMs,
                                isCorner && cornerWeight > 1.0f,
                                false,
                                isCorner ? cornerWeight : 0.95f
                        )
                );
                accumDist = 0f;
            } else if (accumDist >= stepDist) {
                anchors.add(new Anchor(cur.x, cur.y, cur.timeMs, false, false, 0.55f));
                accumDist = 0f;
            }
        }

        anchors.add(new Anchor(last.x, last.y, Math.max(last.timeMs, first.timeMs + 1L), true, true, 1.20f));
        return anchors;
    }

    private List<Anchor> deduplicateCloseAnchors(List<Anchor> anchors) {
        if (anchors.size() <= 2) return anchors;
        List<Anchor> out = new ArrayList<>(anchors.size());
        float minDistSq = (geometry.keyWidthPx * 0.28f) * (geometry.keyWidthPx * 0.28f);

        for (int i = 0; i < anchors.size(); i++) {
            Anchor a = anchors.get(i);
            if (out.isEmpty()) {
                out.add(a);
                continue;
            }
            Anchor prev = out.get(out.size() - 1);
            float dx = a.x - prev.x;
            float dy = a.y - prev.y;
            float d2 = dx * dx + dy * dy;

            if (d2 < minDistSq && !a.isEndpoint && !prev.isEndpoint) {
                if (a.weight > prev.weight || a.isMandatory) {
                    out.set(
                            out.size() - 1,
                            new Anchor(
                                    (prev.x + a.x) * 0.5f,
                                    (prev.y + a.y) * 0.5f,
                                    a.timeMs,
                                    prev.isMandatory || a.isMandatory,
                                    prev.isEndpoint || a.isEndpoint,
                                    Math.max(prev.weight, a.weight)
                            )
                    );
                }
            } else {
                out.add(a);
            }
        }
        return out;
    }

    private static List<TouchPoint> collectRawPoints(List<NintypeInputToken> tokens) {
        List<TouchPoint> pts = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            NintypeInputToken t = tokens.get(i);
            if (t.kind == Kind.TAP) {
                pts.add(new TouchPoint(t.tapX, t.tapY, t.startTimeMs, t.pointerId));
            } else if (t.points != null) {
                pts.addAll(t.points);
            }
        }
        pts.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return pts;
    }

    private static void mergeHypothesis(Map<Integer, BeamHypothesis> map, BeamHypothesis candidate) {
        BeamHypothesis existing = map.get(candidate.trieNode);
        if (existing == null || candidate.spatialLogScore > existing.spatialLogScore) {
            map.put(candidate.trieNode, candidate);
        }
    }

    private Map<Integer, BeamHypothesis> pruneBeam(Map<Integer, BeamHypothesis> map, int maxWidth) {
        if (map.size() <= maxWidth) return map;
        List<BeamHypothesis> list = new ArrayList<>(map.values());
        list.sort((a, b) -> Float.compare(b.rankingKey(trie), a.rankingKey(trie)));
        Map<Integer, BeamHypothesis> pruned = new HashMap<>(maxWidth * 2);
        for (int i = 0; i < maxWidth; i++) {
            BeamHypothesis h = list.get(i);
            pruned.put(h.trieNode, h);
        }
        return pruned;
    }

    private float computeDtwSimilarity(List<TouchPoint> rawPoints, String word) {
        int n = DTW_RESAMPLE_N;
        float[] userX = new float[n];
        float[] userY = new float[n];
        resamplePoints(rawPoints, n, userX, userY);

        List<TouchPoint> idealVertices = new ArrayList<>(word.length());
        for (int i = 0; i < word.length(); i++) {
            KeySpec ks = geometry.keySpec(word.charAt(i));
            if (ks != null) {
                idealVertices.add(new TouchPoint(ks.centerX, ks.centerY, i, 0));
            }
        }
        if (idealVertices.size() < 2) return 0.5f;

        float[] idealX = new float[n];
        float[] idealY = new float[n];
        resamplePoints(idealVertices, n, idealX, idealY);

        int band = Math.max(4, n / 4);
        float[] prevRow = new float[n + 1];
        float[] currRow = new float[n + 1];
        Arrays.fill(prevRow, Float.MAX_VALUE * 0.25f);
        prevRow[0] = 0f;

        for (int i = 1; i <= n; i++) {
            Arrays.fill(currRow, Float.MAX_VALUE * 0.25f);
            int jMin = Math.max(1, i - band);
            int jMax = Math.min(n, i + band);
            float ux = userX[i - 1];
            float uy = userY[i - 1];

            for (int j = jMin; j <= jMax; j++) {
                float dx = ux - idealX[j - 1];
                float dy = uy - idealY[j - 1];
                float cost = (float) Math.hypot(dx, dy);
                float bestPrev = Math.min(prevRow[j - 1], Math.min(prevRow[j], currRow[j - 1]));
                currRow[j] = cost + bestPrev;
            }
            float[] tmp = prevRow;
            prevRow = currRow;
            currRow = tmp;
        }

        float meanDist = prevRow[n] / (2f * n);
        float normScale = Math.max(40f, geometry.keyWidthPx * 1.35f);
        float normalized = meanDist / normScale;
        return clamp01((float) Math.exp(-normalized * normalized));
    }

    private static void resamplePoints(List<TouchPoint> pts, int n, float[] outX, float[] outY) {
        int m = pts.size();
        if (m == 1) {
            TouchPoint p = pts.get(0);
            Arrays.fill(outX, p.x);
            Arrays.fill(outY, p.y);
            return;
        }
        float[] cumLen = new float[m];
        float total = 0f;
        for (int i = 1; i < m; i++) {
            TouchPoint a = pts.get(i - 1);
            TouchPoint b = pts.get(i);
            total += (float) Math.hypot(b.x - a.x, b.y - a.y);
            cumLen[i] = total;
        }
        if (total < 1e-3f) {
            TouchPoint p = pts.get(0);
            Arrays.fill(outX, p.x);
            Arrays.fill(outY, p.y);
            return;
        }

        float step = total / (n - 1);
        int seg = 0;
        for (int k = 0; k < n; k++) {
            float target = k * step;
            while (seg < m - 2 && cumLen[seg + 1] < target) {
                seg++;
            }
            float segStart = cumLen[seg];
            float segEnd = cumLen[seg + 1];
            float span = segEnd - segStart;
            float t = (span > 1e-4f) ? clamp01((target - segStart) / span) : 0f;
            TouchPoint p0 = pts.get(seg);
            TouchPoint p1 = pts.get(seg + 1);
            outX[k] = p0.x + (p1.x - p0.x) * t;
            outY[k] = p0.y + (p1.y - p0.y) * t;
        }
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
