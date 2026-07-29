package com.hhovhann.hivemind.core.episode;

import com.hhovhann.hivemind.core.source.SpeakerRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One contribution inside an episode: a Slack message, a paragraph of a Notion
 * page, a turn in a transcript.
 *
 * <p>The {@code ordinal} is the citation address. It is assigned when the episode
 * is assembled, it appears in the text handed to the model, and extracted evidence
 * points back at it — so it must stay stable for the life of the episode.
 *
 * @param ordinal   zero-based position within the episode
 * @param speaker   who contributed it, unresolved
 * @param at        when, as reported by the source
 * @param text      the content, already normalised to plain text
 * @param permalink deep link to this specific contribution, when the source has one
 */
public record Utterance(int ordinal, SpeakerRef speaker, Instant at, String text, String permalink) {

    public Utterance {
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be zero-based, got " + ordinal);
        }
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(text, "text");
    }

    public Optional<String> permalinkIfPresent() {
        return Optional.ofNullable(permalink).filter(link -> !link.isBlank());
    }
}
