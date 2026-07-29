package com.hhovhann.hivemind.ingest.zoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.core.acl.AclScope;
import com.hhovhann.hivemind.core.acl.ScopeRef;
import com.hhovhann.hivemind.core.acl.Visibility;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.episode.EpisodeKind;
import com.hhovhann.hivemind.core.episode.Utterance;
import com.hhovhann.hivemind.core.source.SourceRef;
import com.hhovhann.hivemind.core.source.SourceSystem;
import com.hhovhann.hivemind.core.source.SpeakerRef;
import com.hhovhann.hivemind.ingest.CorpusProperties;
import com.hhovhann.hivemind.ingest.EpisodeReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Reads Zoom cloud-recording transcripts (WebVTT) plus their recording metadata.
 *
 * <p>Zoom often cannot map a voice to an account and emits {@code Speaker 1},
 * {@code Speaker 2} instead of names. Those labels are kept exactly as they are and
 * scoped to the meeting, because {@code Speaker 2} means a different person in every
 * recording — collapsing them globally would fuse half the company into one node.
 * The recording's participant list is carried alongside so entity resolution has
 * something to work with later.
 */
@Component
public class ZoomTranscriptReader implements EpisodeReader {

    private final CorpusProperties corpus;
    private final ObjectMapper objectMapper;

    public ZoomTranscriptReader(CorpusProperties corpus, ObjectMapper objectMapper) {
        this.corpus = corpus;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceSystem system() {
        return SourceSystem.ZOOM;
    }

    @Override
    public List<Episode> readAll() {
        if (!Files.isDirectory(corpus.zoom())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(corpus.zoom())) {
            return files.filter(path -> path.toString().endsWith(".vtt"))
                    .sorted()
                    .map(this::readTranscript)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list Zoom transcripts in " + corpus.zoom(), e);
        }
    }

    private Episode readTranscript(Path vttFile) {
        String key = vttFile.getFileName().toString().replaceFirst("\\.vtt$", "");
        JsonNode meta = readMeta(corpus.zoom().resolve(key + ".meta.json"));
        Instant startedAt = Instant.parse(meta.path("start_time").asText("1970-01-01T00:00:00Z"));

        List<Cue> cues;
        try {
            cues = parseVtt(Files.readString(vttFile));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read Zoom transcript " + vttFile, e);
        }

        List<Utterance> utterances = new ArrayList<>();
        for (Cue cue : mergeConsecutiveTurns(cues)) {
            utterances.add(new Utterance(
                    utterances.size(),
                    // Scoped to the meeting: "Speaker 2" is a different person in each one.
                    new SpeakerRef(SourceSystem.ZOOM, key + ":" + cue.speaker(), cue.speaker()),
                    startedAt.plus(cue.offset()),
                    cue.text(),
                    null));
        }

        String scopeId = meta.path("scope").path("id").asText(key);
        String visibility = meta.path("scope").path("visibility").asText("PUBLIC");
        return Episode.assemble(
                new SourceRef(SourceSystem.ZOOM, meta.path("uuid").asText(key), null),
                EpisodeKind.MEETING_TRANSCRIPT,
                meta.path("topic").asText(key),
                startedAt,
                Instant.now(),
                utterances,
                AclScope.of(new ScopeRef(SourceSystem.ZOOM, scopeId, Visibility.valueOf(visibility))));
    }

    private JsonNode readMeta(Path metaFile) {
        try {
            return Files.exists(metaFile)
                    ? objectMapper.readTree(metaFile.toFile())
                    : objectMapper.createObjectNode();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read Zoom metadata " + metaFile, e);
        }
    }

    /** One cue: when it starts, who spoke, what they said. */
    record Cue(Duration offset, String speaker, String text) {}

    static List<Cue> parseVtt(String vtt) {
        List<Cue> cues = new ArrayList<>();
        for (String block : vtt.split("\\n\\s*\\n")) {
            List<String> lines = block.strip().lines().filter(line -> !line.isBlank()).toList();
            if (lines.isEmpty() || lines.getFirst().startsWith("WEBVTT")) {
                continue;
            }
            int timingIndex = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("-->")) {
                    timingIndex = i;
                    break;
                }
            }
            if (timingIndex < 0 || timingIndex == lines.size() - 1) {
                continue;
            }
            Duration offset = parseVttTime(lines.get(timingIndex).split("-->")[0].strip());
            String payload = String.join(" ", lines.subList(timingIndex + 1, lines.size()));

            int colon = payload.indexOf(':');
            // Only treat a leading "Name:" as a speaker label — a colon deep in the
            // sentence is punctuation, not attribution.
            if (colon > 0 && colon < 40) {
                cues.add(new Cue(offset, payload.substring(0, colon).strip(), payload.substring(colon + 1).strip()));
            } else {
                cues.add(new Cue(offset, "Unknown", payload.strip()));
            }
        }
        return cues;
    }

    /**
     * Real transcripts break one person's sentence across several cues, which would
     * otherwise become several utterances and several fragmentary citations.
     */
    static List<Cue> mergeConsecutiveTurns(List<Cue> cues) {
        List<Cue> merged = new ArrayList<>();
        for (Cue cue : cues) {
            if (!merged.isEmpty() && merged.getLast().speaker().equals(cue.speaker())) {
                Cue previous = merged.removeLast();
                merged.add(new Cue(previous.offset(), previous.speaker(), previous.text() + " " + cue.text()));
            } else {
                merged.add(cue);
            }
        }
        return merged;
    }

    /** {@code HH:MM:SS.mmm} or {@code MM:SS.mmm}. */
    static Duration parseVttTime(String timestamp) {
        String[] parts = timestamp.split(":");
        double seconds = Double.parseDouble(parts[parts.length - 1].replace(',', '.'));
        long minutes = parts.length >= 2 ? Long.parseLong(parts[parts.length - 2]) : 0;
        long hours = parts.length >= 3 ? Long.parseLong(parts[parts.length - 3]) : 0;
        return Duration.ofHours(hours).plusMinutes(minutes).plusMillis(Math.round(seconds * 1000));
    }
}
