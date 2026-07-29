package com.hhovhann.hivemind.ingest.slack;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Reads Slack threads in {@code conversations.replies} shape.
 *
 * <p>One thread, one episode — never one message. A reply chain is a single argument
 * with a conclusion at the end; split it and "+1" stops agreeing with anything.
 *
 * <p>Speakers stay as Slack user ids wrapped in a {@link SpeakerRef}, with the
 * display name attached for readability. Resolving them to people happens later,
 * with evidence.
 */
@Component
public class SlackThreadReader implements EpisodeReader {

    private final CorpusProperties corpus;
    private final ObjectMapper objectMapper;

    public SlackThreadReader(CorpusProperties corpus, ObjectMapper objectMapper) {
        this.corpus = corpus;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceSystem system() {
        return SourceSystem.SLACK;
    }

    @Override
    public List<Episode> readAll() {
        Map<String, JsonNode> users = usersById();
        Path threads = corpus.slack().resolve("threads");
        if (!Files.isDirectory(threads)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(threads)) {
            return files.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .map(path -> readThread(path, users))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list Slack threads in " + threads, e);
        }
    }

    private Map<String, JsonNode> usersById() {
        Path usersFile = corpus.slack().resolve("users.json");
        if (!Files.exists(usersFile)) {
            return Map.of();
        }
        try {
            Map<String, JsonNode> byId = new HashMap<>();
            objectMapper.readTree(usersFile.toFile()).forEach(user -> byId.put(user.path("id").asText(), user));
            return byId;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + usersFile, e);
        }
    }

    private Episode readThread(Path file, Map<String, JsonNode> users) {
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            JsonNode channel = root.path("channel");
            String channelId = channel.path("id").asText();

            List<JsonNode> messages = new ArrayList<>();
            root.path("messages").forEach(messages::add);
            messages.sort(Comparator.comparing(message -> parseTs(message.path("ts").asText())));

            List<Utterance> utterances = new ArrayList<>();
            for (int ordinal = 0; ordinal < messages.size(); ordinal++) {
                JsonNode message = messages.get(ordinal);
                String userId = message.path("user").asText();
                JsonNode user = users.get(userId);
                String displayName = user == null
                        ? userId
                        : user.path("profile").path("display_name").asText(user.path("real_name").asText(userId));
                utterances.add(new Utterance(
                        ordinal,
                        new SpeakerRef(SourceSystem.SLACK, userId, displayName),
                        parseTs(message.path("ts").asText()),
                        message.path("text").asText(),
                        message.path("permalink").asText(null)));
            }

            String threadTs = root.path("thread_ts").asText();
            return Episode.assemble(
                    new SourceRef(
                            SourceSystem.SLACK,
                            channelId + "/" + threadTs,
                            utterances.isEmpty() ? null : utterances.getFirst().permalink()),
                    EpisodeKind.SLACK_THREAD,
                    title(channel.path("name").asText(channelId), utterances),
                    utterances.isEmpty() ? Instant.EPOCH : utterances.getFirst().at(),
                    Instant.now(),
                    utterances,
                    aclFor(channelId, channel.path("visibility").asText("PUBLIC")));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read Slack thread " + file, e);
        }
    }

    /** Slack threads have no subject line, so the root message stands in for one. */
    private static String title(String channelName, List<Utterance> utterances) {
        if (utterances.isEmpty()) {
            return "#" + channelName;
        }
        String opener = utterances.getFirst().text().replaceAll("\\s+", " ").strip();
        return "#" + channelName + " — " + (opener.length() <= 70 ? opener : opener.substring(0, 67) + "...");
    }

    private static AclScope aclFor(String channelId, String visibility) {
        return AclScope.of(new ScopeRef(SourceSystem.SLACK, channelId, Visibility.valueOf(visibility)));
    }

    /**
     * Slack message ids are {@code <epoch seconds>.<six digits>}. The suffix is a
     * sequence number rather than true microseconds, but treating it as such keeps
     * ordering correct and is what every Slack client does.
     */
    static Instant parseTs(String ts) {
        int dot = ts.indexOf('.');
        if (dot < 0) {
            return Instant.ofEpochSecond(Long.parseLong(ts));
        }
        long seconds = Long.parseLong(ts.substring(0, dot));
        long micros = Long.parseLong(ts.substring(dot + 1));
        return Instant.ofEpochSecond(seconds, micros * 1_000);
    }
}
