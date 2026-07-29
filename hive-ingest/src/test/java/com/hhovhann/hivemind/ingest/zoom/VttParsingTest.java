package com.hhovhann.hivemind.ingest.zoom;

import static org.assertj.core.api.Assertions.assertThat;

import com.hhovhann.hivemind.ingest.zoom.ZoomTranscriptReader.Cue;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VttParsingTest {

    @Test
    @DisplayName("cues parse into speaker, offset and text")
    void parsesCues() {
        List<Cue> cues = ZoomTranscriptReader.parseVtt(
                """
                WEBVTT

                1
                00:00:04.000 --> 00:00:12.000
                Speaker 2: Okay, Frontier. We need a premiere date today.

                2
                00:01:13.500 --> 00:01:20.000
                Dana Okonkwo: Post can hit early May.
                """);

        assertThat(cues).hasSize(2);
        assertThat(cues.getFirst().speaker()).isEqualTo("Speaker 2");
        assertThat(cues.getFirst().text()).isEqualTo("Okay, Frontier. We need a premiere date today.");
        assertThat(cues.getLast().offset()).isEqualTo(Duration.ofSeconds(73).plusMillis(500));
    }

    @Test
    @DisplayName("a colon deep in the sentence is punctuation, not a speaker label")
    void doesNotMistakePunctuationForAttribution() {
        List<Cue> cues = ZoomTranscriptReader.parseVtt(
                """
                WEBVTT

                1
                00:00:00.000 --> 00:00:05.000
                There is one thing I want to flag before we close this out: the lease.
                """);

        assertThat(cues).singleElement().satisfies(cue -> {
            assertThat(cue.speaker()).isEqualTo("Unknown");
            assertThat(cue.text()).startsWith("There is one thing");
        });
    }

    @Test
    @DisplayName("consecutive cues from one speaker merge into a single turn")
    void consecutiveCuesMerge() {
        List<Cue> merged = ZoomTranscriptReader.mergeConsecutiveTurns(List.of(
                new Cue(Duration.ZERO, "Marcus Webb", "We are keeping"),
                new Cue(Duration.ofSeconds(4), "Marcus Webb", "the newsletter."),
                new Cue(Duration.ofSeconds(9), "Priya R.", "Understood.")));

        assertThat(merged).hasSize(2);
        assertThat(merged.getFirst().text()).isEqualTo("We are keeping the newsletter.");
        assertThat(merged.getFirst().offset()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("MM:SS timestamps parse as well as HH:MM:SS")
    void parsesShortTimestamps() {
        assertThat(ZoomTranscriptReader.parseVttTime("02:30.250")).isEqualTo(Duration.ofSeconds(150).plusMillis(250));
        assertThat(ZoomTranscriptReader.parseVttTime("01:02:03.000")).isEqualTo(Duration.ofSeconds(3723));
    }
}
