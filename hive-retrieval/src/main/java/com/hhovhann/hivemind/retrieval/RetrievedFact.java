package com.hhovhann.hivemind.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A fact with everything needed to present it honestly: where it came from, what it
 * replaced, and whether anything has replaced it since.
 *
 * <p>{@link #supersededBy} is the field that stops the commonest wrong answer. A
 * query about the launch date matches all three decided dates roughly equally well,
 * and the stale ones look exactly as authoritative as the current one until you can
 * see that something came after them.
 */
public record RetrievedFact(
        String id,
        String type,
        String statement,
        String status,
        Instant occurredAt,
        Instant validFrom,
        Instant validTo,
        String ownerName,
        List<String> topics,
        double score,
        List<EvidenceRef> evidence,
        List<FactRef> history,
        FactRef supersededBy,
        String episodeTitle,
        String episodeSystem) {

    /** A supporting quote, with enough context to be checked by a human. */
    public record EvidenceRef(String span, String speaker, Instant at, String permalink, String episodeTitle) {}

    /** A neighbouring fact in a supersession chain. */
    public record FactRef(String id, String statement, Instant occurredAt) {}

    public RetrievedFact {
        topics = topics == null ? List.of() : List.copyOf(topics);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public boolean isCurrent() {
        return validTo == null;
    }

    public Optional<FactRef> replacedBy() {
        return Optional.ofNullable(supersededBy);
    }

    public RetrievedFact withScore(double newScore) {
        return new RetrievedFact(
                id,
                type,
                statement,
                status,
                occurredAt,
                validFrom,
                validTo,
                ownerName,
                topics,
                newScore,
                evidence,
                history,
                supersededBy,
                episodeTitle,
                episodeSystem);
    }
}
