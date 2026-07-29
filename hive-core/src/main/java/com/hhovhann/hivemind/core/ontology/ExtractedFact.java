package com.hhovhann.hivemind.core.ontology;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One fact read out of one episode, before entity resolution and before it reaches
 * the graph.
 *
 * <p>Deliberately flat rather than a sealed hierarchy per {@link FactType}. A
 * polymorphic schema costs accuracy at the point where it hurts most: models
 * choose a variant badly, and strict JSON Schema handles {@code oneOf} poorly
 * across providers. One shape with a discriminator plus per-type required fields
 * — enforced by {@link OntologyValidator} — gets the same guarantees with better
 * extraction.
 *
 * <p>Mentions are stored as raw surface forms ("Alex", "the design lead"). Turning
 * those into people is entity resolution's job, and it needs the original wording
 * to do it.
 *
 * @param type                 what kind of fact this is
 * @param statement            canonical one-sentence form, self-contained
 * @param ownerMention         who owns or made it, as written
 * @param participantMentions  others involved, as written
 * @param occurredAt           when it was decided or raised; defaults to the episode time
 * @param dueDate              deadline, for action items and commitments
 * @param status               where it stands
 * @param topics               short subject labels used for graph clustering
 * @param evidence             spans it was read from; never empty in a valid fact
 * @param confidence           extractor's own estimate in [0,1]; low values route to review
 */
public record ExtractedFact(
        FactType type,
        String statement,
        String ownerMention,
        List<String> participantMentions,
        Instant occurredAt,
        LocalDate dueDate,
        FactStatus status,
        List<String> topics,
        List<Evidence> evidence,
        double confidence) {

    public ExtractedFact {
        Objects.requireNonNull(type, "type");
        participantMentions = participantMentions == null ? List.of() : List.copyOf(participantMentions);
        topics = topics == null ? List.of() : List.copyOf(topics);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public Optional<String> owner() {
        return Optional.ofNullable(ownerMention).filter(mention -> !mention.isBlank());
    }

    public Optional<LocalDate> due() {
        return Optional.ofNullable(dueDate);
    }

    public boolean hasField(FactField field) {
        return switch (field) {
            case STATEMENT -> statement != null && !statement.isBlank();
            case OWNER -> owner().isPresent();
            case PARTICIPANTS -> !participantMentions.isEmpty();
            case OCCURRED_AT -> occurredAt != null;
            case DUE_DATE -> dueDate != null;
            case STATUS -> status != null;
            case TOPICS -> !topics.isEmpty();
            case EVIDENCE -> !evidence.isEmpty();
        };
    }

    /** Same fact with the episode's time filled in where extraction left it blank. */
    public ExtractedFact withDefaultOccurredAt(Instant episodeTime) {
        return occurredAt != null
                ? this
                : new ExtractedFact(
                        type,
                        statement,
                        ownerMention,
                        participantMentions,
                        episodeTime,
                        dueDate,
                        status,
                        topics,
                        evidence,
                        confidence);
    }
}
