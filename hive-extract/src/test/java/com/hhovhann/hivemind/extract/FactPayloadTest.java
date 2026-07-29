package com.hhovhann.hivemind.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.hhovhann.hivemind.core.ontology.ExtractedFact;
import com.hhovhann.hivemind.core.ontology.FactStatus;
import com.hhovhann.hivemind.core.ontology.FactType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FactPayloadTest {

    private static final Instant EPISODE_TIME = Instant.parse("2026-02-11T09:00:00Z");

    @Test
    @DisplayName("sentence punctuation is stripped from mentions but initials survive")
    void cleansMentions() {
        assertThat(owner("Speaker 2.")).isEqualTo("Speaker 2");
        assertThat(owner("Alexandra Petrova.")).isEqualTo("Alexandra Petrova");
        assertThat(owner("Priya R.")).isEqualTo("Priya R.");
        assertThat(owner("Alex P.")).isEqualTo("Alex P.");
        assertThat(owner("Dana O.,")).isEqualTo("Dana O.");
        assertThat(owner("   ")).isNull();
    }

    @Test
    @DisplayName("an empty string means absent, not a value")
    void emptyStringsBecomeNull() {
        ExtractedFact fact = payload("DECISION", "", "").toDomain("ep", EPISODE_TIME).orElseThrow();

        assertThat(fact.owner()).isEmpty();
        assertThat(fact.dueDate()).isNull();
    }

    @Test
    @DisplayName("a missing timestamp falls back to the episode's own time")
    void fallsBackToEpisodeTime() {
        ExtractedFact fact = payload("DECISION", "Alex P.", "").toDomain("ep", EPISODE_TIME).orElseThrow();

        assertThat(fact.occurredAt()).isEqualTo(EPISODE_TIME);
    }

    @Test
    @DisplayName("a date offered where an instant was asked for is accepted rather than dropped")
    void toleratesDateInsteadOfInstant() {
        FactPayload payload = new FactPayload(
                "DECISION",
                "The launch moves to June.",
                "Alex P.",
                List.of(),
                "2026-04-08",
                "2026-06-15",
                "AGREED",
                List.of(),
                List.of(new FactPayload.EvidencePayload(1, "push it to June")),
                0.9);

        ExtractedFact fact = payload.toDomain("ep", EPISODE_TIME).orElseThrow();

        assertThat(fact.occurredAt()).isEqualTo(Instant.parse("2026-04-08T00:00:00Z"));
        assertThat(fact.dueDate()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    @DisplayName("an unknown fact type is dropped rather than guessed at")
    void unknownTypeIsDropped() {
        assertThat(payload("MEETING_NOTE", "Alex P.", "").toDomain("ep", EPISODE_TIME)).isEmpty();
    }

    @Test
    @DisplayName("an unknown status falls back to PROPOSED, the weakest claim")
    void unknownStatusFallsBackToProposed() {
        FactPayload payload = new FactPayload(
                "DECISION",
                "The launch moves to June.",
                "Alex P.",
                List.of(),
                "",
                "",
                "MAYBE",
                List.of(),
                List.of(new FactPayload.EvidencePayload(1, "push it to June")),
                0.9);

        assertThat(payload.toDomain("ep", EPISODE_TIME).orElseThrow().status()).isEqualTo(FactStatus.PROPOSED);
    }

    @Test
    @DisplayName("evidence with a null quote or negative line is discarded, not passed on")
    void malformedEvidenceIsDropped() {
        FactPayload payload = new FactPayload(
                "DECISION",
                "The launch moves to June.",
                "Alex P.",
                List.of(),
                "",
                "",
                "AGREED",
                List.of(),
                java.util.Arrays.asList(
                        new FactPayload.EvidencePayload(-1, "negative line"),
                        new FactPayload.EvidencePayload(2, "  "),
                        new FactPayload.EvidencePayload(null, "no line number"),
                        new FactPayload.EvidencePayload(3, "push it to June")),
                0.9);

        assertThat(payload.toDomain("ep", EPISODE_TIME).orElseThrow().evidence())
                .singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.utteranceOrdinal()).isEqualTo(3);
                    assertThat(evidence.episodeId()).isEqualTo("ep");
                });
    }

    @Test
    @DisplayName("a markdown fence around the JSON is stripped")
    void unwrapsFencedJson() {
        assertThat(LlmFactExtractor.unwrap("```json\n{\"facts\":[]}\n```")).isEqualTo("{\"facts\":[]}");
        assertThat(LlmFactExtractor.unwrap("{\"facts\":[]}")).isEqualTo("{\"facts\":[]}");
    }

    private static String owner(String raw) {
        return payload("DECISION", raw, "")
                .toDomain("ep", EPISODE_TIME)
                .orElseThrow()
                .ownerMention();
    }

    private static FactPayload payload(String type, String owner, String dueDate) {
        return new FactPayload(
                type,
                "The Frontier launch moves to April 2026.",
                owner,
                List.of(),
                "",
                dueDate,
                "AGREED",
                List.of("launch"),
                List.of(new FactPayload.EvidencePayload(0, "push it to April")),
                0.9);
    }
}
