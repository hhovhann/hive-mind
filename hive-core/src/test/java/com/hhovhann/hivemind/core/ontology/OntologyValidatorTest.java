package com.hhovhann.hivemind.core.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import com.hhovhann.hivemind.core.Fixtures;
import com.hhovhann.hivemind.core.episode.Episode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OntologyValidatorTest {

    private static final String THREAD_ID = "slack:C_GENERAL/1741078800.000100";

    private final OntologyValidator validator = new OntologyValidator();

    private final Episode episode = Fixtures.slackThread(
            "Should we delay the Q2 launch?",
            "Yes — push it to April, the edit team needs two more weeks.",
            "Agreed. Priya will update the roadmap by Friday.");

    @Test
    @DisplayName("a grounded, well-formed decision is accepted")
    void acceptsGroundedFact() {
        ExtractedFact fact = decision("The Q2 launch moves to April.", "push it to April", 1, 0.9);

        assertThat(validator.validate(fact, episode)).isEmpty();
        assertThat(validator.accepts(fact, episode)).isTrue();
    }

    @Test
    @DisplayName("a fact whose quote appears nowhere is rejected as ungrounded")
    void rejectsFabricatedEvidence() {
        ExtractedFact fact =
                decision("The launch moves to June.", "we agreed to launch in June", 1, 0.95);

        assertThat(validator.accepts(fact, episode)).isFalse();
        assertThat(validator.validate(fact, episode))
                .anySatisfy(issue -> {
                    assertThat(issue.isRejection()).isTrue();
                    assertThat(issue.message()).contains("ungrounded");
                });
    }

    @Test
    @DisplayName("a real quote cited against the wrong line is a warning, not a rejection")
    void warnsOnMisattributedEvidence() {
        ExtractedFact fact = decision("The Q2 launch moves to April.", "push it to April", 0, 0.9);

        assertThat(validator.accepts(fact, episode)).isTrue();
        assertThat(validator.validate(fact, episode))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.isRejection()).isFalse();
                    assertThat(issue.message()).contains("appears at 1");
                });
    }

    @Test
    @DisplayName("an action item without an owner is rejected — an unowned task is not a task")
    void rejectsActionItemWithoutOwner() {
        ExtractedFact fact = new ExtractedFact(
                FactType.ACTION_ITEM,
                "Update the roadmap to reflect the April date.",
                null,
                List.of(),
                Fixtures.MARCH,
                LocalDate.of(2026, 3, 6),
                FactStatus.AGREED,
                List.of("roadmap"),
                List.of(new Evidence(THREAD_ID, 2, "Priya will update the roadmap by Friday")),
                0.88);

        assertThat(validator.validate(fact, episode))
                .anySatisfy(issue -> {
                    assertThat(issue.isRejection()).isTrue();
                    assertThat(issue.field()).isEqualTo("owner");
                });
    }

    @Test
    @DisplayName("extraction may not declare a fact SUPERSEDED — only the graph knows that")
    void rejectsSupersededFromExtraction() {
        ExtractedFact fact = new ExtractedFact(
                FactType.DECISION,
                "The Q2 launch moves to April.",
                "Alex",
                List.of(),
                Fixtures.MARCH,
                null,
                FactStatus.SUPERSEDED,
                List.of(),
                List.of(new Evidence(THREAD_ID, 1, "push it to April")),
                0.9);

        assertThat(validator.validate(fact, episode))
                .anySatisfy(issue -> {
                    assertThat(issue.isRejection()).isTrue();
                    assertThat(issue.field()).isEqualTo("status");
                });
    }

    @Test
    @DisplayName("low confidence keeps the fact but flags it for review")
    void warnsOnLowConfidence() {
        ExtractedFact fact = decision("The Q2 launch moves to April.", "push it to April", 1, 0.4);

        assertThat(validator.accepts(fact, episode)).isTrue();
        assertThat(validator.validate(fact, episode))
                .anySatisfy(issue -> assertThat(issue.field()).isEqualTo("confidence"));
    }

    @Test
    @DisplayName("evidence citing a different episode is rejected")
    void rejectsCrossEpisodeEvidence() {
        ExtractedFact fact = new ExtractedFact(
                FactType.DECISION,
                "The Q2 launch moves to April.",
                "Alex",
                List.of(),
                Fixtures.MARCH,
                null,
                FactStatus.AGREED,
                List.of(),
                List.of(new Evidence("slack:C_OTHER/999.0001", 1, "push it to April")),
                0.9);

        assertThat(validator.validate(fact, episode))
                .anySatisfy(issue -> {
                    assertThat(issue.isRejection()).isTrue();
                    assertThat(issue.field()).isEqualTo("evidence.episodeId");
                });
    }

    private ExtractedFact decision(String statement, String span, int ordinal, double confidence) {
        return new ExtractedFact(
                FactType.DECISION,
                statement,
                "Alex",
                List.of(),
                Fixtures.MARCH,
                null,
                FactStatus.AGREED,
                List.of("launch"),
                List.of(new Evidence(THREAD_ID, ordinal, span)),
                confidence);
    }
}
