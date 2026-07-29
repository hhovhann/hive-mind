package com.hhovhann.hivemind.extract;

import com.hhovhann.hivemind.core.ontology.ExtractedFact;
import com.hhovhann.hivemind.core.ontology.ValidationIssue;
import java.time.Duration;
import java.util.List;

/**
 * What one episode produced, including what it failed to produce.
 *
 * <p>Rejections are kept rather than logged and dropped. They are the signal: a run
 * where the model hallucinated eight quotes and a run where it found nothing look
 * identical if you only count what survived, and they call for opposite fixes.
 *
 * @param episodeId       episode this came from
 * @param accepted        facts that passed validation and may enter the graph
 * @param rejected        facts that failed, with the reasons
 * @param warnings        issues that did not block acceptance but are worth review
 * @param promptSignature prompt and ontology version, so runs are comparable
 * @param elapsed         wall clock for the model call and validation
 * @param inputTokens     prompt tokens, for cost tracking
 * @param outputTokens    completion tokens
 */
public record ExtractionResult(
        String episodeId,
        List<ExtractedFact> accepted,
        List<RejectedFact> rejected,
        List<ValidationIssue> warnings,
        String promptSignature,
        Duration elapsed,
        int inputTokens,
        int outputTokens) {

    public record RejectedFact(ExtractedFact fact, List<ValidationIssue> issues) {

        public String reason() {
            return issues.stream()
                    .filter(ValidationIssue::isRejection)
                    .map(ValidationIssue::message)
                    .findFirst()
                    .orElse("unspecified");
        }
    }

    public ExtractionResult {
        accepted = List.copyOf(accepted);
        rejected = List.copyOf(rejected);
        warnings = List.copyOf(warnings);
    }

    public static ExtractionResult empty(String episodeId, String promptSignature, Duration elapsed) {
        return new ExtractionResult(
                episodeId, List.of(), List.of(), List.of(), promptSignature, elapsed, 0, 0);
    }

    public int proposed() {
        return accepted.size() + rejected.size();
    }

    /**
     * Share of proposed facts that survived validation.
     *
     * <p>Worth watching per prompt version: a rate near 1.0 usually means the gate is
     * too lax rather than the model being that good, and a collapsing rate after a
     * prompt edit is the clearest signal that the edit made things worse.
     */
    public double acceptanceRate() {
        return proposed() == 0 ? 1.0 : (double) accepted.size() / proposed();
    }
}
