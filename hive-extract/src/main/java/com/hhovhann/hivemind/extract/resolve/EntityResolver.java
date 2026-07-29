package com.hhovhann.hivemind.extract.resolve;

import com.hhovhann.hivemind.core.entity.MentionResolution;
import com.hhovhann.hivemind.core.entity.Person;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.episode.Utterance;
import com.hhovhann.hivemind.core.source.SourceSystem;
import com.hhovhann.hivemind.core.source.SpeakerRef;
import com.hhovhann.hivemind.ingest.directory.Directory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Turns the names in a conversation into people.
 *
 * <p>Resolution goes cheapest-first and stops at the first answer that is actually
 * established: an alias the source system gave us, then an exact directory name, then
 * a unique match among the people known to be in this episode, and only then a model.
 * Each rung down that ladder is weaker evidence, so the rung used is recorded on the
 * result — a graph that cannot distinguish "Slack told us" from "a model guessed"
 * cannot be argued with when it is wrong.
 *
 * <p>Episode scope is what makes the ambiguous cases tractable. "Alex owns the CMS
 * migration" is unresolvable against a company directory containing two Alexes, and
 * trivial once you notice it was said in {@code #eng} one message after Alex Chen
 * volunteered. Ambiguity that survives episode scope is left unresolved rather than
 * broken by coin flip.
 */
@Service
public class EntityResolver {

    private final SpeakerAdjudicator adjudicator;

    public EntityResolver(SpeakerAdjudicator adjudicator) {
        this.adjudicator = adjudicator;
    }

    /**
     * Resolves every distinct speaker in an episode.
     *
     * @return speaker key ({@link SpeakerRef#key()}) to resolution
     */
    public Map<String, MentionResolution> resolveSpeakers(Episode episode, Directory directory) {
        Map<String, MentionResolution> resolved = new LinkedHashMap<>();
        List<SpeakerRef> distinct = episode.utterances().stream()
                .map(Utterance::speaker)
                .distinct()
                .toList();

        List<String> unresolvedLabels = new ArrayList<>();
        for (SpeakerRef speaker : distinct) {
            directory
                    .byAlias(speaker.system(), speaker.rawId())
                    .or(() -> directory.byAlias(speaker.system(), speaker.label()))
                    .ifPresentOrElse(
                            person -> resolved.put(
                                    speaker.key(),
                                    MentionResolution.of(
                                            speaker.label(),
                                            person,
                                            MentionResolution.Method.HANDLE,
                                            "%s supplied this identity directly".formatted(speaker.system()))),
                            () -> unresolvedLabels.add(speaker.label()));
        }

        // What is left is almost always anonymous Zoom labels, whose candidate set is
        // the meeting's own participant list.
        if (!unresolvedLabels.isEmpty()) {
            List<Person> participants = directory.participantsOf(episode.id());
            Map<String, String> adjudicated = adjudicator.adjudicate(episode, unresolvedLabels, participants);
            for (SpeakerRef speaker : distinct) {
                if (resolved.containsKey(speaker.key())) {
                    continue;
                }
                String chosen = adjudicated.get(speaker.label());
                Optional<Person> person = chosen == null
                        ? Optional.empty()
                        : participants.stream()
                                .filter(candidate -> candidate.canonicalName().equals(chosen))
                                .findFirst();
                resolved.put(
                        speaker.key(),
                        person.map(match -> MentionResolution.of(
                                        speaker.label(),
                                        match,
                                        MentionResolution.Method.LLM_ADJUDICATED,
                                        "identified from what this speaker says, among %d attendees"
                                                .formatted(participants.size())))
                                .orElseGet(() -> MentionResolution.unresolved(
                                        speaker.label(),
                                        participants.isEmpty()
                                                ? "no participant list for this episode"
                                                : "could not be told apart from the other attendees")));
            }
        }
        return resolved;
    }

    /**
     * Resolves a name written inside a fact — an owner, a participant.
     *
     * @param speakers resolutions for this episode's speakers, which supply the
     *                 people known to be present
     */
    public MentionResolution resolveMention(
            String mention, Episode episode, Directory directory, Map<String, MentionResolution> speakers) {
        if (mention == null || mention.isBlank()) {
            return MentionResolution.unresolved(mention, "empty mention");
        }
        String cleaned = mention.strip();

        if (cleaned.contains("@") && cleaned.contains(".")) {
            Optional<Person> byEmail = directory.byEmail(cleaned);
            if (byEmail.isPresent()) {
                return MentionResolution.of(cleaned, byEmail.get(), MentionResolution.Method.EMAIL, "email match");
            }
        }

        // A speaker label from this very episode — "Speaker 3" in a fact extracted
        // from the recording that label came from.
        Optional<MentionResolution> asSpeaker = speakers.values().stream()
                .filter(resolution -> resolution.mention().equalsIgnoreCase(cleaned))
                .filter(MentionResolution::isResolved)
                .findFirst();
        if (asSpeaker.isPresent()) {
            return asSpeaker.get();
        }

        List<Person> exact = directory.byName(cleaned);
        if (exact.size() == 1) {
            return MentionResolution.of(
                    cleaned, exact.getFirst(), MentionResolution.Method.EXACT_NAME, "unique name in the directory");
        }

        Set<Person> present = peoplePresent(speakers);
        if (exact.size() > 1) {
            List<Person> narrowed = exact.stream().filter(present::contains).toList();
            if (narrowed.size() == 1) {
                return MentionResolution.of(
                        cleaned,
                        narrowed.getFirst(),
                        MentionResolution.Method.UNIQUE_IN_EPISODE,
                        "%d people share this name; one of them is in this conversation".formatted(exact.size()));
            }
            return MentionResolution.unresolved(
                    cleaned, "%d people answer to this name and the episode does not narrow it".formatted(exact.size()));
        }

        // No exact match: a partial name like "Alex" or "Priya".
        List<Person> byPrefix = directory.byNamePrefix(cleaned);
        List<Person> presentByPrefix = byPrefix.stream().filter(present::contains).toList();
        if (presentByPrefix.size() == 1) {
            return MentionResolution.of(
                    cleaned,
                    presentByPrefix.getFirst(),
                    MentionResolution.Method.UNIQUE_IN_EPISODE,
                    byPrefix.size() > 1
                            ? "%d people match '%s'; only one is in this conversation".formatted(byPrefix.size(), cleaned)
                            : "only person matching '%s' in this conversation".formatted(cleaned));
        }
        if (byPrefix.size() == 1) {
            return MentionResolution.of(
                    cleaned,
                    byPrefix.getFirst(),
                    MentionResolution.Method.EXACT_NAME,
                    "unique prefix match in the directory");
        }
        return MentionResolution.unresolved(
                cleaned,
                byPrefix.isEmpty()
                        ? "nobody in the directory answers to this"
                        : "%d people match and none is uniquely present".formatted(byPrefix.size()));
    }

    private static Set<Person> peoplePresent(Map<String, MentionResolution> speakers) {
        Set<Person> present = new LinkedHashSet<>();
        speakers.values().stream()
                .map(MentionResolution::person)
                .flatMap(Optional::stream)
                .forEach(present::add);
        return present;
    }

    /** Convenience for reporting: how much of the work was done without a model. */
    public static double deterministicShare(List<MentionResolution> resolutions) {
        if (resolutions.isEmpty()) {
            return 1.0;
        }
        long deterministic = resolutions.stream()
                .filter(MentionResolution::isResolved)
                .filter(resolution -> resolution.method().isDeterministic())
                .count();
        return (double) deterministic / resolutions.size();
    }

    /** Speakers whose system already told us who they are — currently Slack and Notion. */
    public static boolean isSelfIdentifying(SourceSystem system) {
        return system == SourceSystem.SLACK || system == SourceSystem.NOTION;
    }
}
