package com.hhovhann.hivemind.extract.resolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.core.entity.Person;
import com.hhovhann.hivemind.core.episode.Episode;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Works out who {@code Speaker 2} was.
 *
 * <p>Zoom emits anonymous labels whenever it cannot match a voice to an account, and
 * those recordings hold decisions that appear nowhere else — in this corpus, the
 * original Frontier launch date was set in a room where nobody is named. Leaving the
 * labels unresolved means the graph knows a decision was made and not who made it,
 * which is most of the value gone.
 *
 * <p>The candidate set is closed: the answer must be one of the meeting's
 * participants, enforced by a JSON enum rather than by asking politely. That turns
 * an open-ended identification into a multiple-choice question, which small models
 * are markedly better at, and makes inventing a colleague impossible rather than
 * unlikely.
 *
 * <p>One call resolves every label in a recording at once — the labels constrain each
 * other, since two of them are rarely the same person.
 */
@Service
public class SpeakerAdjudicator {

    private static final Logger log = LoggerFactory.getLogger(SpeakerAdjudicator.class);

    private static final String SYSTEM_PROMPT =
            """
            A meeting transcript labels its speakers anonymously. You are given the list of
            people who attended. Work out which attendee each label refers to.

            Each attendee is listed with their job title. Match what a speaker talks about
            to the job that would make them talk about it: whoever reports on edit
            schedules runs post-production, whoever volunteers to build the promo calendar
            runs operations, whoever closes the debate and sets the date is the most senior
            person in the room. The title is usually a stronger signal than the tone.

            Rules:
            - Every answer must be one of the listed attendees. There are no other options.
            - Two labels are rarely the same person. Prefer an assignment that uses each
              attendee once.
            - Leave a person empty only when a label says nothing identifying at all — a
              single word, or pure agreement. That is rare. Anyone who states a schedule,
              takes on a task or settles a question has told you which job they hold.
            - You are choosing among a handful of named people who were definitely in the
              room, not identifying a stranger. Weak evidence is still evidence.

            For each label, name the attendee and quote the line that convinced you.
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public SpeakerAdjudicator(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * @param labels     unresolved speaker labels, as they appear in the transcript
     * @param candidates people known to have attended
     * @return label to canonical name, omitting anything it could not establish
     */
    public Map<String, String> adjudicate(Episode episode, List<String> labels, List<Person> candidates) {
        if (labels.isEmpty() || candidates.isEmpty()) {
            return Map.of();
        }
        List<String> names = candidates.stream().map(Person::canonicalName).toList();
        // Titles do most of the work here: "Post can hit early May" is unattributable
        // among four names and obvious once one of them is Head of Post-Production.
        String attendees = candidates.stream()
                .map(person -> person.title() == null || person.title().isBlank()
                        ? person.canonicalName()
                        : "%s — %s".formatted(person.canonicalName(), person.title()))
                .collect(java.util.stream.Collectors.joining("\n  "));
        String request =
                """
                Attendees:
                  %s

                Labels to identify: %s

                Transcript:
                %s
                """
                        .formatted(attendees, String.join(", ", labels), episode.render());

        log.info("adjudicating {} speaker labels {} against {} attendees", labels.size(), labels, names.size());
        try {
            var response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(request))
                    .responseFormat(ResponseFormat.builder()
                            .type(ResponseFormatType.JSON)
                            .jsonSchema(schemaFor(labels, names))
                            .build())
                    .build());
            log.debug("speaker adjudication raw response: {}", abbreviate(response.aiMessage().text()));
            return parse(response.aiMessage().text(), labels, names);
        } catch (RuntimeException e) {
            log.warn("speaker adjudication failed for {}: {}", episode.id(), e.toString());
            return Map.of();
        }
    }

    private static JsonSchema schemaFor(List<String> labels, List<String> names) {
        List<String> withEmpty = new ArrayList<>(names);
        withEmpty.add(""); // the escape hatch for "cannot tell"
        return JsonSchema.builder()
                .name("speaker_identities")
                .rootElement(JsonObjectSchema.builder()
                        .addProperty(
                                "identities",
                                JsonArraySchema.builder()
                                        .description("One entry per label, in the order given.")
                                        .items(JsonObjectSchema.builder()
                                                .addProperty(
                                                        "label",
                                                        JsonEnumSchema.builder()
                                                                .enumValues(labels)
                                                                .build())
                                                .addProperty(
                                                        "person",
                                                        JsonEnumSchema.builder()
                                                                .enumValues(withEmpty)
                                                                .description("An attendee, or empty if unidentifiable.")
                                                                .build())
                                                .addProperty(
                                                        "evidence",
                                                        JsonStringSchema.builder()
                                                                .description("The line that convinced you.")
                                                                .build())
                                                .required("label", "person", "evidence")
                                                .build())
                                        .build())
                        .required("identities")
                        .build())
                .build();
    }

    private Map<String, String> parse(String raw, List<String> labels, List<String> names) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return resolved;
        }
        try {
            var identities = objectMapper.readTree(unwrap(raw)).path("identities");
            if (identities.isMissingNode() || identities.isEmpty()) {
                log.warn("speaker adjudication returned no identities. raw: {}", abbreviate(raw));
                return resolved;
            }
            for (var identity : identities) {
                String label = identity.path("label").asText("");
                if (!labels.contains(label)) {
                    log.warn("speaker adjudication returned label '{}', which was not asked about", label);
                    continue;
                }
                // Constrained decoding is not guaranteed on every backend, so the
                // closed candidate set is enforced here too — leniently, because the
                // prompt shows names with their titles attached and the model echoes
                // them back that way ("Alexandra Petrova — VP Content").
                String answer = identity.path("person").asText("");
                matchName(answer, names)
                        .ifPresentOrElse(
                                person -> resolved.put(label, person),
                                () -> {
                                    if (!answer.isBlank()) {
                                        log.warn(
                                                "speaker adjudication named '{}' for {}, which is not an attendee",
                                                answer,
                                                label);
                                    }
                                });
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn(
                    "speaker adjudication returned unparseable JSON: {} — raw: {}",
                    e.getOriginalMessage(),
                    abbreviate(raw));
        }
        return resolved;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "(null)";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    /** Recovers a candidate name from whatever decoration the model wrapped it in. */
    static java.util.Optional<String> matchName(String answer, List<String> candidates) {
        if (answer == null || answer.isBlank()) {
            return java.util.Optional.empty();
        }
        String cleaned = answer.split("—|--| - ")[0].strip();
        return candidates.stream()
                .filter(candidate -> candidate.equalsIgnoreCase(cleaned))
                .findFirst()
                .or(() -> candidates.stream()
                        .filter(candidate -> cleaned.toLowerCase(java.util.Locale.ROOT)
                                .startsWith(candidate.toLowerCase(java.util.Locale.ROOT)))
                        .findFirst());
    }

    private static String unwrap(String raw) {
        String text = raw.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int closingFence = text.lastIndexOf("```");
        return firstNewline < 0 || closingFence <= firstNewline
                ? text
                : text.substring(firstNewline + 1, closingFence).strip();
    }
}
