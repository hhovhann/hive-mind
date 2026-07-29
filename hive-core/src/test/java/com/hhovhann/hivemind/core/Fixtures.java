package com.hhovhann.hivemind.core;

import com.hhovhann.hivemind.core.acl.AclScope;
import com.hhovhann.hivemind.core.acl.ScopeRef;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.episode.EpisodeKind;
import com.hhovhann.hivemind.core.episode.Utterance;
import com.hhovhann.hivemind.core.source.SourceRef;
import com.hhovhann.hivemind.core.source.SourceSystem;
import com.hhovhann.hivemind.core.source.SpeakerRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Shared test data — a small Slack thread with a decision in it. */
public final class Fixtures {

    public static final Instant MARCH = Instant.parse("2026-03-04T09:00:00Z");

    private Fixtures() {}

    public static Episode slackThread(String... messages) {
        List<Utterance> utterances = new ArrayList<>();
        for (int i = 0; i < messages.length; i++) {
            utterances.add(new Utterance(
                    i,
                    SpeakerRef.of(SourceSystem.SLACK, "U" + i),
                    MARCH.plusSeconds(60L * i),
                    messages[i],
                    null));
        }
        return Episode.assemble(
                SourceRef.of(SourceSystem.SLACK, "C_GENERAL/1741078800.000100"),
                EpisodeKind.SLACK_THREAD,
                "launch timing",
                MARCH,
                Instant.parse("2026-07-28T12:00:00Z"),
                utterances,
                AclScope.of(ScopeRef.publicScope(SourceSystem.SLACK, "C_GENERAL")));
    }
}
