package com.hhovhann.hivemind.extract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hhovhann.hivemind.core.ontology.Evidence;
import com.hhovhann.hivemind.core.ontology.ExtractedFact;
import com.hhovhann.hivemind.core.ontology.FactStatus;
import com.hhovhann.hivemind.core.ontology.FactType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * What the model returns, before it becomes a domain object.
 *
 * <p>Separate from {@link ExtractedFact} because the wire format has to survive a
 * model: every field is a string, absent is an empty string, and dates arrive
 * malformed often enough that parsing must not throw. Converting here keeps that
 * tolerance at the boundary instead of letting nullable strings leak into the domain.
 *
 * <p>Evidence carries no episode id — we know which episode we sent, and asking the
 * model to repeat an identifier back is one more thing for it to get wrong.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FactPayload(
        String type,
        String statement,
        String owner,
        List<String> participants,
        String occurredAt,
        String dueDate,
        String status,
        List<String> topics,
        List<EvidencePayload> evidence,
        Double confidence) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidencePayload(Integer utterance, String quote) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope(List<FactPayload> facts) {
        public List<FactPayload> factsOrEmpty() {
            return facts == null ? List.of() : facts;
        }
    }

    /**
     * @param episodeId  the episode this was extracted from
     * @param fallbackAt the episode's own timestamp, used when the model gave none
     * @return the domain fact, or empty when the payload is too broken to be one
     */
    public Optional<ExtractedFact> toDomain(String episodeId, Instant fallbackAt) {
        Optional<FactType> factType = parseEnum(FactType.class, type);
        if (factType.isEmpty()) {
            return Optional.empty();
        }
        List<Evidence> spans = (evidence == null ? List.<EvidencePayload>of() : evidence).stream()
                .filter(span -> span != null && span.utterance() != null && span.quote() != null)
                .filter(span -> span.utterance() >= 0 && !span.quote().isBlank())
                .map(span -> new Evidence(episodeId, span.utterance(), span.quote()))
                .toList();

        return Optional.of(new ExtractedFact(
                factType.get(),
                blankToNull(statement),
                cleanMention(owner),
                participants == null ? List.of() : participants.stream()
                        .map(FactPayload::cleanMention)
                        .filter(java.util.Objects::nonNull)
                        .toList(),
                parseInstant(occurredAt).orElse(fallbackAt),
                parseDate(dueDate).orElse(null),
                parseEnum(FactStatus.class, status).orElse(FactStatus.PROPOSED),
                topics,
                spans,
                confidence == null ? 0.0 : confidence));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Strips sentence punctuation a model dragged in with a name.
     *
     * <p>Quoting mid-sentence yields {@code "Speaker 2."} and {@code "Priya R.,"}.
     * Entity resolution matches mentions as strings, so a stray full stop is enough
     * to split one person into two nodes — a trivial-looking bug with an expensive
     * result. Internal periods survive, because "Priya R." is a name, not a sentence.
     */
    private static String cleanMention(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip().replaceAll("[,;:]+$", "").strip();
        if (cleaned.endsWith(".")) {
            String withoutDot = cleaned.substring(0, cleaned.length() - 1);
            String lastToken = withoutDot.substring(withoutDot.lastIndexOf(' ') + 1);
            // "Priya R." keeps its initial; "Speaker 2." and "Alexandra Petrova."
            // lose a full stop that belonged to the sentence, not the name.
            boolean isInitial = lastToken.length() == 1 && Character.isLetter(lastToken.charAt(0));
            if (!isInitial) {
                cleaned = withoutDot.strip();
            }
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static <E extends Enum<E>> Optional<E> parseEnum(Class<E> enumType, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(enumType, value.strip().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value.strip()));
        } catch (DateTimeParseException e) {
            // Models reach for "2026-04-08" when asked for an instant. Take the date.
            return parseDate(value).map(date -> date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        }
    }

    private static Optional<LocalDate> parseDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.strip().substring(0, Math.min(10, value.strip().length()))));
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }
}
