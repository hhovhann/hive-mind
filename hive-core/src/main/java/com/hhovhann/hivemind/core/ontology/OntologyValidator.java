package com.hhovhann.hivemind.core.ontology;

import com.hhovhann.hivemind.core.episode.Episode;
import java.util.ArrayList;
import java.util.List;

/**
 * The gate every extracted fact passes before it can become a node.
 *
 * <p>Three things are checked, in rising order of usefulness:
 *
 * <ol>
 *   <li><b>Shape</b> — required fields for the declared {@link FactType}.
 *   <li><b>Sanity</b> — confidence in range, statuses only extraction may set,
 *       dates that run backwards.
 *   <li><b>Grounding</b> — every cited span really occurs in the cited utterance.
 * </ol>
 *
 * <p>The third is the one that carries the weight. Asking a second model whether
 * the first hallucinated is expensive, slow, and itself unreliable; comparing the
 * quoted text against the source is none of those things and cannot be talked out
 * of its answer. When a span is real but attributed to the wrong line, that is
 * reported separately from a span that appears nowhere — the first is a citation
 * bug worth repairing automatically, the second means the fact was invented.
 */
public class OntologyValidator {

    private static final int MIN_STATEMENT_LENGTH = 10;
    private static final int MAX_STATEMENT_LENGTH = 500;
    private static final double LOW_CONFIDENCE = 0.55;

    /** @return every issue found; empty means the fact is clean */
    public List<ValidationIssue> validate(ExtractedFact fact, Episode episode) {
        List<ValidationIssue> issues = new ArrayList<>();
        checkRequiredFields(fact, issues);
        checkStatement(fact, issues);
        checkConfidence(fact, issues);
        checkStatus(fact, issues);
        checkDates(fact, issues);
        checkGrounding(fact, episode, issues);
        return List.copyOf(issues);
    }

    /** Convenience for the ingestion path: does this fact reach the graph? */
    public boolean accepts(ExtractedFact fact, Episode episode) {
        return validate(fact, episode).stream().noneMatch(ValidationIssue::isRejection);
    }

    private void checkRequiredFields(ExtractedFact fact, List<ValidationIssue> issues) {
        for (FactField required : fact.type().requiredFields()) {
            if (!fact.hasField(required)) {
                issues.add(ValidationIssue.reject(
                        required.name().toLowerCase(java.util.Locale.ROOT),
                        "%s requires %s".formatted(fact.type(), required)));
            }
        }
    }

    private void checkStatement(ExtractedFact fact, List<ValidationIssue> issues) {
        String statement = fact.statement();
        if (statement == null || statement.isBlank()) {
            return; // already reported as a missing required field
        }
        if (statement.length() < MIN_STATEMENT_LENGTH) {
            issues.add(ValidationIssue.reject("statement", "too short to be self-contained: " + statement));
        }
        if (statement.length() > MAX_STATEMENT_LENGTH) {
            issues.add(ValidationIssue.warn(
                    "statement",
                    "%d characters — a fact this long is usually several facts".formatted(statement.length())));
        }
    }

    private void checkConfidence(ExtractedFact fact, List<ValidationIssue> issues) {
        double confidence = fact.confidence();
        if (confidence < 0 || confidence > 1) {
            issues.add(ValidationIssue.reject("confidence", "outside [0,1]: " + confidence));
        } else if (confidence < LOW_CONFIDENCE) {
            issues.add(ValidationIssue.warn("confidence", "low (%.2f) — route to human review".formatted(confidence)));
        }
    }

    private void checkStatus(ExtractedFact fact, List<ValidationIssue> issues) {
        if (fact.status() == FactStatus.SUPERSEDED) {
            issues.add(ValidationIssue.reject(
                    "status",
                    "SUPERSEDED is set by the graph when a newer fact replaces this one, "
                            + "never by extraction — the extractor cannot see the future"));
            return;
        }
        // A risk that is "agreed" and a question that is "done" are contradictions in
        // terms; the fact is usually right and only the label is wrong, so warn.
        boolean unsettledType = fact.type() == FactType.RISK || fact.type() == FactType.OPEN_QUESTION;
        boolean settledStatus = fact.status() == FactStatus.AGREED || fact.status() == FactStatus.DONE;
        if (unsettledType && settledStatus) {
            issues.add(ValidationIssue.warn(
                    "status",
                    "%s cannot be %s — if it were settled it would no longer be one"
                            .formatted(fact.type(), fact.status())));
        }
    }

    private void checkDates(ExtractedFact fact, List<ValidationIssue> issues) {
        if (fact.occurredAt() == null || fact.dueDate() == null) {
            return;
        }
        var occurredOn = fact.occurredAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        if (fact.dueDate().isBefore(occurredOn)) {
            issues.add(ValidationIssue.warn(
                    "dueDate",
                    "due %s precedes the conversation on %s — usually a year the model guessed"
                            .formatted(fact.dueDate(), occurredOn)));
        }
    }

    private void checkGrounding(ExtractedFact fact, Episode episode, List<ValidationIssue> issues) {
        if (fact.evidence().isEmpty()) {
            return; // already reported as a missing required field
        }
        for (Evidence evidence : fact.evidence()) {
            if (!evidence.episodeId().equals(episode.id())) {
                issues.add(ValidationIssue.reject(
                        "evidence.episodeId",
                        "cites %s but was extracted from %s".formatted(evidence.episodeId(), episode.id())));
                continue;
            }
            if (episode.supports(evidence.utteranceOrdinal(), evidence.verbatimSpan())) {
                continue;
            }
            // The words exist but the ordinal is wrong: a citation bug, not a fabrication.
            var actualOrdinal = episode.locate(evidence.verbatimSpan());
            if (actualOrdinal.isPresent()) {
                issues.add(ValidationIssue.warn(
                        "evidence.utteranceOrdinal",
                        "span cites utterance %d but appears at %d"
                                .formatted(evidence.utteranceOrdinal(), actualOrdinal.get())));
            } else {
                issues.add(ValidationIssue.reject(
                        "evidence.verbatimSpan",
                        "not found anywhere in the episode — the fact is ungrounded: \"%s\""
                                .formatted(truncate(evidence.verbatimSpan()))));
            }
        }
    }

    private static String truncate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
}
