package com.hhovhann.hivemind.ingest.directory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.core.entity.Alias;
import com.hhovhann.hivemind.core.entity.Person;
import com.hhovhann.hivemind.core.source.SourceSystem;
import com.hhovhann.hivemind.ingest.CorpusProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds the people registry from what each source system already knows.
 *
 * <p>Order matters. Slack goes first because it is the only source with both a
 * stable id and an email, which makes it the spine everything else attaches to. Zoom
 * participants join on email and contribute their display names. Notion authors have
 * no email at all, so they join on an exact full name — deliberately exact, since a
 * looser rule is what merges the two Alexes.
 *
 * <p>Anyone who fails to join becomes a new person rather than being forced into an
 * existing one. External participants are real — Nordwind's Ingrid Halvorsen belongs
 * in the graph — and quietly attaching her to the nearest employee would be worse
 * than an extra node.
 */
@Component
public class DirectoryReader {

    private static final Logger log = LoggerFactory.getLogger(DirectoryReader.class);

    private final CorpusProperties corpus;
    private final ObjectMapper objectMapper;

    public DirectoryReader(CorpusProperties corpus, ObjectMapper objectMapper) {
        this.corpus = corpus;
        this.objectMapper = objectMapper;
    }

    public Directory read() {
        Map<String, Person> people = new LinkedHashMap<>();
        Map<String, String> aliasToPersonId = new LinkedHashMap<>();
        Map<String, List<String>> episodeParticipants = new LinkedHashMap<>();

        readSlackUsers(people, aliasToPersonId);
        readZoomParticipants(people, aliasToPersonId, episodeParticipants);
        readNotionAuthors(people, aliasToPersonId);

        log.info("directory: {} people, {} aliases", people.size(), aliasToPersonId.size());
        return new Directory(people.values(), aliasToPersonId, episodeParticipants);
    }

    private void readSlackUsers(Map<String, Person> people, Map<String, String> aliases) {
        Path usersFile = corpus.slack().resolve("users.json");
        if (!Files.exists(usersFile)) {
            return;
        }
        for (JsonNode user : readJson(usersFile)) {
            JsonNode profile = user.path("profile");
            String email = profile.path("email").asText(null);
            String realName = user.path("real_name").asText(user.path("name").asText());
            String id = Person.idFor(email, realName);

            List<Alias> found = new ArrayList<>();
            found.add(new Alias(SourceSystem.SLACK, user.path("id").asText()));
            found.add(new Alias(SourceSystem.SLACK, user.path("name").asText()));
            found.add(new Alias(SourceSystem.SLACK, realName));
            String displayName = profile.path("display_name").asText(null);
            if (displayName != null && !displayName.isBlank()) {
                found.add(new Alias(SourceSystem.SLACK, displayName));
            }

            Person person = new Person(
                    id, realName, email, profile.path("title").asText(null), new java.util.LinkedHashSet<>(found));
            people.put(id, person);
            found.forEach(alias -> aliases.put(alias.key(), id));
        }
    }

    private void readZoomParticipants(
            Map<String, Person> people, Map<String, String> aliases, Map<String, List<String>> episodeParticipants) {
        if (!Files.isDirectory(corpus.zoom())) {
            return;
        }
        try (Stream<Path> files = Files.list(corpus.zoom())) {
            files.filter(path -> path.toString().endsWith(".meta.json")).sorted().forEach(metaFile -> {
                JsonNode meta = readJson(metaFile);
                String episodeId = "zoom:" + meta.path("uuid").asText();
                List<String> participantIds = new ArrayList<>();

                for (JsonNode participant : meta.path("participants")) {
                    String name = participant.path("name").asText();
                    String email = participant.path("email").asText(null);
                    Person person = findByEmail(people, email)
                            .orElseGet(() -> {
                                String id = Person.idFor(email, name);
                                return people.computeIfAbsent(
                                        id, key -> new Person(key, name, email, null, java.util.Set.of()));
                            });
                    Person withAlias = person.withAlias(new Alias(SourceSystem.ZOOM, name));
                    people.put(withAlias.id(), withAlias);
                    aliases.put(new Alias(SourceSystem.ZOOM, name).key(), withAlias.id());
                    participantIds.add(withAlias.id());
                }
                episodeParticipants.put(episodeId, participantIds);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list Zoom metadata in " + corpus.zoom(), e);
        }
    }

    private void readNotionAuthors(Map<String, Person> people, Map<String, String> aliases) {
        if (!Files.isDirectory(corpus.notion())) {
            return;
        }
        try (Stream<Path> files = Files.list(corpus.notion())) {
            files.filter(path -> path.toString().endsWith(".md")).sorted().forEach(page -> {
                for (String author : authorsOf(page)) {
                    // Exact full-name match only. A first-name or prefix rule here is
                    // precisely what fuses Alexandra Petrova with Alex Chen.
                    List<Person> candidates = people.values().stream()
                            .filter(person -> Person.normalise(person.canonicalName())
                                    .equals(Person.normalise(author)))
                            .toList();
                    if (candidates.size() == 1) {
                        Person withAlias = candidates.getFirst().withAlias(new Alias(SourceSystem.NOTION, author));
                        people.put(withAlias.id(), withAlias);
                        aliases.put(new Alias(SourceSystem.NOTION, author).key(), withAlias.id());
                    } else if (candidates.isEmpty()) {
                        String id = Person.idFor(null, author);
                        Person person = people.computeIfAbsent(
                                id,
                                key -> new Person(
                                        key, author, null, null, java.util.Set.of(new Alias(SourceSystem.NOTION, author))));
                        aliases.put(new Alias(SourceSystem.NOTION, author).key(), person.id());
                    } else {
                        log.warn("notion author '{}' matches {} people — left unattached", author, candidates.size());
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list Notion pages in " + corpus.notion(), e);
        }
    }

    private static java.util.Optional<Person> findByEmail(Map<String, Person> people, String email) {
        if (email == null || email.isBlank()) {
            return java.util.Optional.empty();
        }
        return people.values().stream()
                .filter(person -> email.equalsIgnoreCase(person.email()))
                .findFirst();
    }

    private static List<String> authorsOf(Path page) {
        List<String> authors = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(page)) {
                if (line.startsWith("---") && !authors.isEmpty()) {
                    break;
                }
                if (line.startsWith("created_by:") || line.startsWith("last_edited_by:")) {
                    String value = line.substring(line.indexOf(':') + 1).strip();
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!value.isBlank() && !authors.contains(value)) {
                        authors.add(value);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + page, e);
        }
        return authors;
    }

    private JsonNode readJson(Path file) {
        try {
            return objectMapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
