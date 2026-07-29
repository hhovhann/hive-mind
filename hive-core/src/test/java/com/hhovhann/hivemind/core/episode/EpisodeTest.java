package com.hhovhann.hivemind.core.episode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hhovhann.hivemind.core.Fixtures;
import com.hhovhann.hivemind.core.acl.AclScope;
import com.hhovhann.hivemind.core.source.SourceRef;
import com.hhovhann.hivemind.core.source.SourceSystem;
import com.hhovhann.hivemind.core.source.SpeakerRef;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EpisodeTest {

    @Test
    @DisplayName("rendered transcript prints ordinals, because evidence cites them")
    void renderPrintsOrdinals() {
        Episode episode = Fixtures.slackThread("Should we delay the launch?", "Yes — push it to April.");

        assertThat(episode.render()).contains("[0] ").contains("[1] ").contains("push it to April");
    }

    @Test
    @DisplayName("grounding accepts a verbatim span from the cited utterance")
    void groundingAcceptsVerbatimSpan() {
        Episode episode = Fixtures.slackThread("Should we delay the launch?", "Yes — push it to April.");

        assertThat(episode.supports(1, "push it to April")).isTrue();
    }

    @Test
    @DisplayName("grounding tolerates reflowed whitespace and case, which models change freely")
    void groundingToleratesWhitespaceAndCase() {
        Episode episode = Fixtures.slackThread("Should we delay the launch?", "Yes — push  it\n  to April.");

        assertThat(episode.supports(1, "Push It To April")).isTrue();
    }

    @Test
    @DisplayName("grounding rejects a paraphrase — the whole point of quoting")
    void groundingRejectsParaphrase() {
        Episode episode = Fixtures.slackThread("Should we delay the launch?", "Yes — push it to April.");

        assertThat(episode.supports(1, "the launch was moved to April")).isFalse();
    }

    @Test
    @DisplayName("grounding rejects a span that is real but attributed to the wrong utterance")
    void groundingRejectsWrongOrdinal() {
        Episode episode = Fixtures.slackThread("Should we delay the launch?", "Yes — push it to April.");

        assertThat(episode.supports(0, "push it to April")).isFalse();
        assertThat(episode.locate("push it to April")).contains(1);
    }

    @Test
    @DisplayName("a span too short to prove anything is not grounding")
    void groundingRejectsTrivialSpans() {
        Episode episode = Fixtures.slackThread("Should we delay the launch?", "Yes — push it to April.");

        assertThat(episode.supports(1, "Yes")).isFalse();
    }

    @Test
    @DisplayName("content hash ignores identity of the episode and tracks only content")
    void contentHashTracksContent() {
        Episode first = Fixtures.slackThread("Should we delay the launch?", "Yes — push it to April.");
        Episode same = Fixtures.slackThread("Should we delay the launch?", "Yes — push it to April.");
        Episode edited = Fixtures.slackThread("Should we delay the launch?", "Yes — push it to May.");

        assertThat(first.contentHash()).isEqualTo(same.contentHash()).isNotEqualTo(edited.contentHash());
    }

    @Test
    @DisplayName("out-of-order ordinals are rejected, since citations address them by position")
    void ordinalsMustMatchPositions() {
        var speaker = SpeakerRef.of(SourceSystem.SLACK, "U1");
        List<Utterance> misnumbered = List.of(
                new Utterance(0, speaker, Fixtures.MARCH, "first", null),
                new Utterance(5, speaker, Fixtures.MARCH, "second", null));

        assertThatThrownBy(() -> Episode.assemble(
                        SourceRef.of(SourceSystem.SLACK, "C/1"),
                        EpisodeKind.SLACK_THREAD,
                        "t",
                        Fixtures.MARCH,
                        Instant.now(),
                        misnumbered,
                        AclScope.WORKSPACE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ordinal");
    }
}
