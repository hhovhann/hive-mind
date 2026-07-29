package com.hhovhann.hivemind.ingest.directory;

import com.hhovhann.hivemind.core.entity.Person;
import com.hhovhann.hivemind.core.source.SourceSystem;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everyone the source systems already know about, and who was in which meeting.
 *
 * <p>This is the cheap half of entity resolution and it should carry most of the
 * load. Slack knows its users' emails, Zoom lists its participants, Notion names its
 * authors — joining those needs no model at all. Reaching for an LLM before
 * exhausting the directory means paying per call for answers the systems already
 * gave you, and getting worse ones.
 */
public class Directory {

    private final Map<String, Person> byId;
    private final Map<String, String> aliasToPersonId;
    private final Map<String, String> emailToPersonId;
    private final Map<String, List<String>> episodeParticipants;

    Directory(
            Collection<Person> people,
            Map<String, String> aliasToPersonId,
            Map<String, List<String>> episodeParticipants) {
        Map<String, Person> people1 = new LinkedHashMap<>();
        Map<String, String> emails = new LinkedHashMap<>();
        for (Person person : people) {
            people1.put(person.id(), person);
            person.emailIfPresent()
                    .ifPresent(email -> emails.put(email.toLowerCase(java.util.Locale.ROOT), person.id()));
        }
        this.byId = Map.copyOf(people1);
        this.emailToPersonId = Map.copyOf(emails);
        this.aliasToPersonId = Map.copyOf(aliasToPersonId);
        this.episodeParticipants = Map.copyOf(episodeParticipants);
    }

    public List<Person> people() {
        return List.copyOf(byId.values());
    }

    public Optional<Person> byId(String personId) {
        return Optional.ofNullable(byId.get(personId));
    }

    public Optional<Person> byEmail(String email) {
        return email == null
                ? Optional.empty()
                : Optional.ofNullable(emailToPersonId.get(email.toLowerCase(java.util.Locale.ROOT)))
                        .map(byId::get);
    }

    /** Exact alias lookup, e.g. the Slack user id on a message. */
    public Optional<Person> byAlias(SourceSystem system, String value) {
        String key = system.name().toLowerCase(java.util.Locale.ROOT) + ":" + value;
        return Optional.ofNullable(aliasToPersonId.get(key)).map(byId::get);
    }

    /**
     * Everyone whose canonical name or any alias matches.
     *
     * <p>Returns a list on purpose. "Alex" matches two people here, and a resolver
     * that gets handed the first one silently attributes work to the wrong person.
     */
    public List<Person> byName(String name) {
        return byId.values().stream().filter(person -> person.answersTo(name)).toList();
    }

    /** People whose canonical name starts with the given token — "Alex" finding "Alex Chen". */
    public List<Person> byNamePrefix(String prefix) {
        String needle = Person.normalise(prefix);
        if (needle.isBlank()) {
            return List.of();
        }
        return byId.values().stream()
                .filter(person -> matchesPrefix(person, needle))
                .toList();
    }

    private static boolean matchesPrefix(Person person, String needle) {
        if (Person.normalise(person.canonicalName()).startsWith(needle)) {
            return true;
        }
        return person.aliases().stream().anyMatch(alias -> Person.normalise(alias.value()).startsWith(needle));
    }

    /** Who was in the room, for episodes where a source told us — currently Zoom. */
    public List<Person> participantsOf(String episodeId) {
        return episodeParticipants.getOrDefault(episodeId, List.of()).stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public Set<String> episodesWithParticipants() {
        return episodeParticipants.keySet();
    }
}
