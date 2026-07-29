package com.hhovhann.hivemind.core.episode;

import com.hhovhann.hivemind.core.acl.AclScope;
import com.hhovhann.hivemind.core.source.SourceRef;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A bounded unit of discourse — a whole Slack thread, a whole meeting, one revision
 * of a Notion page.
 *
 * <p><strong>The episode, not the chunk, is the unit of extraction.</strong> A
 * 500-character window cuts through the middle of an argument and throws away the
 * reply structure, so "we'll go with the second option" stops referring to
 * anything and "+1" stops agreeing with anyone. Extracting from the complete
 * episode is what lets the model resolve those references at all. Chunking still
 * happens — but downstream, for the vector index, after meaning has been captured.
 *
 * @param id          stable identifier derived from the source reference
 * @param source      pointer back to the originating content
 * @param kind        shape of the discourse
 * @param title       thread subject, meeting name, or page title
 * @param occurredAt  when the conversation happened — the axis facts are dated on
 * @param ingestedAt  when Hive Mind first saw it
 * @param utterances  contributions in order, ordinals matching their positions
 * @param acl         containers a reader must have access to
 * @param contentHash digest of the content, so unchanged episodes are not re-extracted
 */
public record Episode(
        String id,
        SourceRef source,
        EpisodeKind kind,
        String title,
        Instant occurredAt,
        Instant ingestedAt,
        List<Utterance> utterances,
        AclScope acl,
        String contentHash) {

    /** Below this length a span matches too much text to prove anything. */
    private static final int MIN_EVIDENCE_SPAN_LENGTH = 12;

    private static final DateTimeFormatter RENDER_TIME = DateTimeFormatter.ISO_INSTANT;

    public Episode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(acl, "acl");
        utterances = List.copyOf(utterances);
        for (int i = 0; i < utterances.size(); i++) {
            if (utterances.get(i).ordinal() != i) {
                throw new IllegalArgumentException(
                        "utterance ordinals must match their position — evidence citations address them by ordinal; "
                                + "expected %d at index %d but found %d"
                                        .formatted(i, i, utterances.get(i).ordinal()));
            }
        }
    }

    /** Assembles an episode, deriving its id and content hash from the content itself. */
    public static Episode assemble(
            SourceRef source,
            EpisodeKind kind,
            String title,
            Instant occurredAt,
            Instant ingestedAt,
            List<Utterance> utterances,
            AclScope acl) {
        return new Episode(
                source.key(), source, kind, title, occurredAt, ingestedAt, utterances, acl, hashOf(utterances));
    }

    /**
     * The text handed to the model for extraction.
     *
     * <p>Ordinals are printed because extracted evidence cites them; changing this
     * format without changing the extraction prompt breaks grounding.
     */
    public String render() {
        StringBuilder rendered = new StringBuilder();
        rendered.append("# ").append(title == null ? kind.name() : title).append('\n');
        rendered.append("Source: ").append(source.key()).append('\n');
        rendered.append("Occurred: ").append(RENDER_TIME.format(occurredAt)).append("\n\n");
        for (Utterance utterance : utterances) {
            rendered.append('[')
                    .append(utterance.ordinal())
                    .append("] ")
                    .append(utterance.speaker().label())
                    .append(" (")
                    .append(RENDER_TIME.format(utterance.at()))
                    .append("): ")
                    .append(utterance.text())
                    .append('\n');
        }
        return rendered.toString();
    }

    public Optional<Utterance> utterance(int ordinal) {
        return ordinal >= 0 && ordinal < utterances.size()
                ? Optional.of(utterances.get(ordinal))
                : Optional.empty();
    }

    /**
     * Whether {@code verbatimSpan} genuinely appears in the cited utterance.
     *
     * <p>This is the grounding gate. Extraction is asked to quote the words it
     * relied on; if the quote is not actually there, the fact was invented and never
     * reaches the graph. Comparison tolerates whitespace and case differences —
     * models reflow both — but nothing else, so paraphrase still fails.
     */
    public boolean supports(int utteranceOrdinal, String verbatimSpan) {
        if (verbatimSpan == null || normalise(verbatimSpan).length() < MIN_EVIDENCE_SPAN_LENGTH) {
            return false;
        }
        return utterance(utteranceOrdinal)
                .map(u -> normalise(u.text()).contains(normalise(verbatimSpan)))
                .orElse(false);
    }

    /** Same check without trusting the cited ordinal — used to report near-misses. */
    public Optional<Integer> locate(String verbatimSpan) {
        if (verbatimSpan == null || normalise(verbatimSpan).length() < MIN_EVIDENCE_SPAN_LENGTH) {
            return Optional.empty();
        }
        String needle = normalise(verbatimSpan);
        return utterances.stream()
                .filter(u -> normalise(u.text()).contains(needle))
                .map(Utterance::ordinal)
                .findFirst();
    }

    private static String normalise(String text) {
        return text.replaceAll("\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static String hashOf(List<Utterance> utterances) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Utterance utterance : utterances) {
                digest.update(utterance.speaker().key().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(utterance.text().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS and must be present", e);
        }
    }
}
