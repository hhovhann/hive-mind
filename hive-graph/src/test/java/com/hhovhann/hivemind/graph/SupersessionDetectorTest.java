package com.hhovhann.hivemind.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.hhovhann.hivemind.core.acl.AclScope;
import com.hhovhann.hivemind.core.acl.ScopeRef;
import com.hhovhann.hivemind.core.entity.MentionResolution;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.episode.EpisodeKind;
import com.hhovhann.hivemind.core.episode.Utterance;
import com.hhovhann.hivemind.core.ontology.Evidence;
import com.hhovhann.hivemind.core.ontology.ExtractedFact;
import com.hhovhann.hivemind.core.ontology.FactStatus;
import com.hhovhann.hivemind.core.ontology.FactType;
import com.hhovhann.hivemind.core.source.SourceRef;
import com.hhovhann.hivemind.core.source.SourceSystem;
import com.hhovhann.hivemind.core.source.SpeakerRef;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SupersessionDetectorTest {

    private static final float[] LAUNCH_DATE = {1.0f, 0.0f, 0.0f};
    private static final float[] NEARLY_LAUNCH_DATE = {0.97f, 0.24f, 0.0f};
    private static final float[] UNRELATED = {0.0f, 0.0f, 1.0f};

    @Test
    @DisplayName("a revision meets the most recent open fact, not the one it resembles most")
    void chainsToTheMostRecentNotTheMostSimilar() {
        // The three Frontier dates. May 4 is the closest match to June 1 by wording,
        // but June 1 replaces June 15 — the state that was actually current.
        ResolvedFact may4 = fact("May 4", Instant.parse("2026-02-11T09:00:00Z"));
        ResolvedFact june15 = fact("June 15", Instant.parse("2026-04-08T10:29:00Z"));
        ResolvedFact june1 = fact("June 1", Instant.parse("2026-05-06T09:59:00Z"));

        Map<String, float[]> embeddings = new HashMap<>();
        embeddings.put(may4.id(), LAUNCH_DATE);
        embeddings.put(june15.id(), NEARLY_LAUNCH_DATE);
        embeddings.put(june1.id(), LAUNCH_DATE);

        var proposal = SupersessionDetector.mostRecentSimilar(june1, List.of(may4, june15), embeddings);

        assertThat(proposal).isPresent();
        assertThat(proposal.get().older().statement()).isEqualTo("June 15");
    }

    @Test
    @DisplayName("nothing is proposed for a fact with no similar predecessor")
    void unrelatedFactsAreNotProposed() {
        ResolvedFact launch = fact("May 4", Instant.parse("2026-02-11T09:00:00Z"));
        ResolvedFact newsletter = fact("newsletter stays", Instant.parse("2026-03-18T08:30:00Z"));

        Map<String, float[]> embeddings = new HashMap<>();
        embeddings.put(launch.id(), LAUNCH_DATE);
        embeddings.put(newsletter.id(), UNRELATED);

        assertThat(SupersessionDetector.mostRecentSimilar(newsletter, List.of(launch), embeddings))
                .isEmpty();
    }

    @Test
    @DisplayName("two facts from the same moment cannot revise each other")
    void simultaneousFactsAreNotRevisions() {
        Instant sameMoment = Instant.parse("2026-02-11T09:00:00Z");
        ResolvedFact fromZoom = fact("May 4 premiere", sameMoment);
        ResolvedFact fromSlack = fact("May 4 launch", sameMoment);

        Map<String, float[]> embeddings = new HashMap<>();
        embeddings.put(fromZoom.id(), LAUNCH_DATE);
        embeddings.put(fromSlack.id(), LAUNCH_DATE);

        assertThat(SupersessionDetector.mostRecentSimilar(fromSlack, List.of(fromZoom), embeddings))
                .isEmpty();
    }

    @Test
    @DisplayName("a derived fact carries the grants of the episode it came from")
    void aclIsInherited() {
        Episode execThread = episode(
                "C_EXEC/1",
                Instant.parse("2026-04-22T16:45:00Z"),
                AclScope.of(ScopeRef.restricted(SourceSystem.SLACK, "C_EXEC")));
        ResolvedFact resolved = ResolvedFact.of(
                extracted("hiring freeze through Q3", execThread.occurredAt()),
                execThread,
                MentionResolution.unresolved(null, "no owner"),
                List.of(),
                "1.0.0",
                "extract-v2");

        assertThat(resolved.acl().requiredGrants()).containsExactly("slack:C_EXEC");
        assertThat(resolved.validity().isOpen()).isTrue();
        assertThat(resolved.validity().validFrom()).isEqualTo(execThread.occurredAt());
    }

    @Test
    @DisplayName("fact ids come from content, so reloading the same corpus updates in place")
    void idsAreContentDerived() {
        Episode thread = episode("C/1", Instant.parse("2026-02-11T09:00:00Z"), AclScope.WORKSPACE);
        ExtractedFact same = extracted("May 4", thread.occurredAt());
        ExtractedFact different = extracted("June 15", thread.occurredAt());

        assertThat(ResolvedFact.idFor(thread.id(), same)).isEqualTo(ResolvedFact.idFor(thread.id(), same));
        assertThat(ResolvedFact.idFor(thread.id(), same)).isNotEqualTo(ResolvedFact.idFor(thread.id(), different));
    }

    private static ResolvedFact fact(String statement, Instant occurredAt) {
        Episode thread = episode("C/" + statement.hashCode(), occurredAt, AclScope.WORKSPACE);
        return ResolvedFact.of(
                extracted(statement, occurredAt),
                thread,
                MentionResolution.unresolved(null, "none"),
                List.of(),
                "1.0.0",
                "extract-v2");
    }

    private static ExtractedFact extracted(String statement, Instant occurredAt) {
        return new ExtractedFact(
                FactType.DECISION,
                statement,
                null,
                List.of(),
                occurredAt,
                null,
                FactStatus.AGREED,
                List.of(),
                List.of(new Evidence("ep", 0, "a verbatim span long enough")),
                0.9);
    }

    private static Episode episode(String externalId, Instant occurredAt, AclScope acl) {
        return Episode.assemble(
                SourceRef.of(SourceSystem.SLACK, externalId),
                EpisodeKind.SLACK_THREAD,
                "thread",
                occurredAt,
                Instant.parse("2026-07-29T00:00:00Z"),
                List.of(new Utterance(
                        0, SpeakerRef.of(SourceSystem.SLACK, "U1"), occurredAt, "a verbatim span long enough", null)),
                acl);
    }
}
