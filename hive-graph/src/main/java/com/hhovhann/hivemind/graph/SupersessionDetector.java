package com.hhovhann.hivemind.graph;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Works out which earlier fact each new fact replaces.
 *
 * <p>This is what makes the graph answer "what did we decide in April" and "what
 * holds now" with the same query and a different date. A superseding fact does not
 * overwrite its predecessor — it closes it, and the {@code SUPERSEDES} edge keeps the
 * chain walkable. Overwriting would make the current answer correct and the history
 * unrecoverable, which is the opposite of what an organisation needs six months later.
 *
 * <p>Similarity proposes; {@link SupersessionAdjudicator} decides. Cosine distance is
 * a good candidate generator and a bad judge — two facts about the same partnership
 * sit around 0.89 whether one revises the other, restates it from a second source, or
 * says something unrelated about it. Acting on similarity alone closed roughly half a
 * corpus of live facts here, and a falsely closed fact vanishes from every
 * current-state query without appearing anywhere as an error.
 *
 * <p>The walk is chronological and adjudicates inline, because each verdict changes
 * what is still open for the next fact. A candidate is matched against the <em>most
 * recent</em> open fact above the threshold rather than the most similar one: a
 * revision replaces the current state, so in a chain of three dates the third must
 * meet the second, not the first it happens to resemble most.
 */
@Component
public class SupersessionDetector {

    private static final Logger log = LoggerFactory.getLogger(SupersessionDetector.class);

    /**
     * Cosine similarity above which a pair is worth adjudicating.
     *
     * <p>Deliberately loose. This bound trades precision for recall because the
     * adjudicator supplies the precision; a real revision missed here is never
     * considered again, and no later stage can recover it.
     */
    private static final double SIMILARITY_THRESHOLD = 0.80;

    public record Supersession(ResolvedFact newer, ResolvedFact older, double similarity) {}

    private final SupersessionAdjudicator adjudicator;

    public SupersessionDetector(SupersessionAdjudicator adjudicator) {
        this.adjudicator = adjudicator;
    }

    /**
     * @param chronological facts ordered oldest first
     * @param embeddings    statement vectors, keyed by fact id
     * @return every adjudicated pair, including the ones judged not to be revisions
     */
    public List<SupersessionAdjudicator.Judgement> resolve(
            List<ResolvedFact> chronological, Map<String, float[]> embeddings) {
        List<SupersessionAdjudicator.Judgement> judgements = new ArrayList<>();
        // Per type, the facts still considered current at the point we have reached.
        Map<String, List<ResolvedFact>> openByType = new LinkedHashMap<>();

        for (ResolvedFact candidate : chronological) {
            List<ResolvedFact> open =
                    openByType.computeIfAbsent(candidate.fact().type().name(), key -> new ArrayList<>());
            mostRecentSimilar(candidate, open, embeddings).ifPresent(proposal -> {
                var judgement = adjudicator.judge(List.of(proposal)).getFirst();
                judgements.add(judgement);
                if (judgement.verdict() == SupersessionAdjudicator.Verdict.SUPERSEDES) {
                    open.remove(proposal.older());
                }
            });
            open.add(candidate);
        }

        log.info(
                "{} candidate pairs adjudicated, {} are revisions",
                judgements.size(),
                judgements.stream()
                        .filter(judgement -> judgement.verdict() == SupersessionAdjudicator.Verdict.SUPERSEDES)
                        .count());
        return judgements;
    }

    /** The latest still-open fact this one plausibly revises. */
    static java.util.Optional<Supersession> mostRecentSimilar(
            ResolvedFact candidate, List<ResolvedFact> open, Map<String, float[]> embeddings) {
        float[] candidateVector = embeddings.get(candidate.id());
        if (candidateVector == null) {
            return java.util.Optional.empty();
        }
        Supersession best = null;
        for (ResolvedFact existing : open) {
            if (!existing.fact().occurredAt().isBefore(candidate.fact().occurredAt())) {
                continue;
            }
            float[] existingVector = embeddings.get(existing.id());
            if (existingVector == null) {
                continue;
            }
            double similarity = cosine(candidateVector, existingVector);
            if (similarity < SIMILARITY_THRESHOLD) {
                continue;
            }
            boolean laterThanBest = best == null
                    || existing.fact().occurredAt().isAfter(best.older().fact().occurredAt());
            if (laterThanBest) {
                best = new Supersession(candidate, existing, similarity);
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    /** When a fact was closed: the moment its replacement became true. */
    public static Instant closedAt(Supersession supersession) {
        return supersession.newer().fact().occurredAt();
    }

    static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
