package com.hhovhann.hivemind.eval;

import com.hhovhann.hivemind.core.ontology.ExtractedFact;
import java.util.List;
import java.util.Map;

/**
 * How much of the answer key extraction found.
 *
 * <p><strong>Recall only, deliberately.</strong> The key lists the facts that must be
 * found; it does not enumerate every fact the corpus legitimately contains.
 * Extraction routinely produces true facts nobody bothered to write down — "episodes
 * drop weekly after the premiere" is correct and absent from the key — so counting
 * those as false positives would measure the key's completeness rather than the
 * extractor's precision, and would punish a model for being thorough.
 *
 * <p>The precision-shaped question is answered separately by
 * {@link #groundingRejectRate()}: the share of proposed facts whose quotes could not
 * be found in the source. That is a real fabrication measure, computed against the
 * text rather than against anyone's opinion of what mattered.
 *
 * @param goldTotal           facts in the answer key
 * @param matched             found, and labelled with the right type
 * @param mistyped            found, but labelled with the wrong type — a prompt problem, not a retrieval one
 * @param missed              nothing matched at all; the actionable list
 * @param byType              strict recall broken down by fact type
 * @param proposed            facts the model offered, before validation
 * @param groundingRejections facts discarded because their quotes were not real
 */
public record ExtractionScore(
        int goldTotal,
        int matched,
        List<Match> matches,
        List<Match> mistyped,
        List<GoldFact> missed,
        Map<String, TypeRecall> byType,
        int proposed,
        int groundingRejections) {

    public record Match(GoldFact gold, ExtractedFact found, String episodeId, double similarity) {}

    public record TypeRecall(int gold, int matched) {
        public double recall() {
            return gold == 0 ? 1.0 : (double) matched / gold;
        }
    }

    public ExtractionScore {
        matches = List.copyOf(matches);
        mistyped = List.copyOf(mistyped);
        missed = List.copyOf(missed);
    }

    /** Found and correctly typed. */
    public double strictRecall() {
        return goldTotal == 0 ? 1.0 : (double) matched / goldTotal;
    }

    /**
     * Found at all, whatever it was labelled.
     *
     * <p>The distance from {@link #strictRecall()} is the cost of type confusion, and
     * it is worth watching on its own: a wide gap says the model reads the
     * conversation correctly and classifies it badly, which is fixed in the prompt,
     * not by retrieving harder.
     */
    public double contentRecall() {
        return goldTotal == 0 ? 1.0 : (double) (matched + mistyped.size()) / goldTotal;
    }

    /** Share of proposed facts rejected because their evidence was not in the source. */
    public double groundingRejectRate() {
        return proposed == 0 ? 0.0 : (double) groundingRejections / proposed;
    }
}
