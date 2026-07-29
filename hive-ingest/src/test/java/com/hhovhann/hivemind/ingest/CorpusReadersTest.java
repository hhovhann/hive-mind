package com.hhovhann.hivemind.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.core.acl.Visibility;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.episode.EpisodeKind;
import com.hhovhann.hivemind.core.source.SourceSystem;
import com.hhovhann.hivemind.ingest.notion.NotionPageReader;
import com.hhovhann.hivemind.ingest.slack.SlackThreadReader;
import com.hhovhann.hivemind.ingest.zoom.ZoomTranscriptReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reads the real sample corpus — these are the shapes the M2 connectors must also produce. */
class CorpusReadersTest {

    private static final CorpusProperties CORPUS =
            new CorpusProperties(Path.of(System.getProperty("hive.repo.root"), "corpus", "meridian-media"));

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SlackThreadReader slack = new SlackThreadReader(CORPUS, objectMapper);
    private final NotionPageReader notion = new NotionPageReader(CORPUS);
    private final ZoomTranscriptReader zoom = new ZoomTranscriptReader(CORPUS, objectMapper);

    @Test
    @DisplayName("the corpus reads as 26 episodes across three systems")
    void readsWholeCorpus() {
        assertThat(slack.readAll()).hasSize(14);
        assertThat(notion.readAll()).hasSize(7);
        assertThat(zoom.readAll()).hasSize(5);
    }

    @Test
    @DisplayName("a Slack thread becomes one episode, not one episode per message")
    void threadIsOneEpisode() {
        Episode episode = slackEpisode("1778060400");

        assertThat(episode.kind()).isEqualTo(EpisodeKind.SLACK_THREAD);
        assertThat(episode.utterances()).hasSize(5);
        assertThat(episode.title()).startsWith("#frontier-launch — Heads up");
        assertThat(episode.utterances().getFirst().permalink()).contains("/archives/C_FRONTIER/p");
    }

    @Test
    @DisplayName("grounding works against real corpus text")
    void groundingWorksOnRealContent() {
        Episode episode = slackEpisode("1778060400");

        assertThat(episode.supports(3, "Frontier premieres June 1")).isTrue();
        assertThat(episode.supports(0, "Frontier premieres June 1")).isFalse();
        assertThat(episode.locate("Frontier premieres June 1")).contains(3);
    }

    @Test
    @DisplayName("a private channel produces a restricted scope that needs an explicit grant")
    void privateChannelIsRestricted() {
        Episode execThread = slack.readAll().stream()
                .filter(episode -> episode.source().externalId().startsWith("C_EXEC/"))
                .findFirst()
                .orElseThrow();

        assertThat(execThread.acl().effectiveVisibility()).isEqualTo(Visibility.RESTRICTED);
        assertThat(execThread.acl().requiredGrants()).containsExactly("slack:C_EXEC");
        assertThat(execThread.acl().readableBy(java.util.Set.of())).isFalse();
    }

    @Test
    @DisplayName("public channels need no grant")
    void publicChannelNeedsNoGrant() {
        assertThat(slackEpisode("1778060400").acl().requiredGrants()).isEmpty();
    }

    @Test
    @DisplayName("unresolved Zoom speaker labels are scoped per meeting, not shared globally")
    void zoomSpeakerLabelsAreScopedToTheirMeeting() {
        Episode contentSync = zoomEpisode("Weekly content sync");

        assertThat(contentSync.utterances().getFirst().speaker().rawId())
                .isEqualTo("content-sync-2026-02-11:Speaker 2");
        assertThat(contentSync.utterances())
                .extracting(utterance -> utterance.speaker().rawId())
                .allSatisfy(rawId -> assertThat(rawId).startsWith("content-sync-2026-02-11:"));
    }

    @Test
    @DisplayName("Zoom turn timings are offsets from the meeting start, so facts get real timestamps")
    void zoomTurnsCarryAbsoluteTime() {
        Episode contentSync = zoomEpisode("Weekly content sync");

        assertThat(contentSync.occurredAt()).isEqualTo(java.time.Instant.parse("2026-02-11T09:00:00Z"));
        assertThat(contentSync.utterances().getFirst().at()).isEqualTo(java.time.Instant.parse("2026-02-11T09:00:04Z"));
        assertThat(contentSync.supports(4, "Frontier premieres May the fourth")).isTrue();
    }

    @Test
    @DisplayName("the restricted meeting recording requires its own grant")
    void execRecordingIsRestricted() {
        Episode offsite = zoomEpisode("Exec offsite — Q2 planning");

        assertThat(offsite.acl().requiredGrants()).containsExactly("zoom:M_EXEC_OFFSITE");
    }

    @Test
    @DisplayName("a Notion page is dated by its last edit, since Notion edits in place")
    void notionPageUsesLastEditedTime() {
        Episode plan = notionEpisode("P_FRONTIER_PLAN");

        assertThat(plan.occurredAt()).isEqualTo(java.time.Instant.parse("2026-05-06T16:30:00Z"));
        assertThat(plan.title()).isEqualTo("Frontier — Launch Plan");
    }

    @Test
    @DisplayName("episodes come back oldest first, so supersession chains build in the right order")
    void ingestIsChronological() {
        List<Episode> episodes = new EpisodeIngestService(List.of(slack, notion, zoom)).readAll();

        assertThat(episodes).hasSize(26);
        assertThat(episodes).isSortedAccordingTo(java.util.Comparator.comparing(Episode::occurredAt));
        assertThat(episodes.getFirst().source().system()).isEqualTo(SourceSystem.NOTION); // team directory, January
    }

    private Episode slackEpisode(String threadTsPrefix) {
        return slack.readAll().stream()
                .filter(episode -> episode.source().externalId().contains(threadTsPrefix))
                .findFirst()
                .orElseThrow();
    }

    private Episode zoomEpisode(String topic) {
        return zoom.readAll().stream()
                .filter(episode -> topic.equals(episode.title()))
                .findFirst()
                .orElseThrow();
    }

    private Episode notionEpisode(String pageId) {
        return notion.readAll().stream()
                .filter(episode -> pageId.equals(episode.source().externalId()))
                .findFirst()
                .orElseThrow();
    }
}
