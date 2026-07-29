package com.hhovhann.hivemind.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Decides what a pair of similar facts actually is.
 *
 * <p>Embedding similarity is a good candidate generator and a bad judge. Two facts
 * about the Nordwind deal sit at 0.89 whether one revises the other, repeats it from
 * a second source, or says something entirely different about the same partner. At a
 * threshold loose enough to catch the real revisions, roughly half of what comes back
 * is not a revision at all — and acting on those closes live facts, which removes
 * them from every current-state query without anyone noticing.
 *
 * <p>So similarity proposes and this decides, the same shape as entity resolution:
 * cheap blocking, then adjudication only on what survives it. Three outcomes, because
 * the corpus genuinely contains all three — the same decision recorded in a recording
 * and again in a Slack recap is a duplicate, not a revision, and calling it one would
 * make the first record look obsolete an hour after it was made.
 */
@Service
public class SupersessionAdjudicator {

    private static final Logger log = LoggerFactory.getLogger(SupersessionAdjudicator.class);

    public enum Verdict {
        /** The later fact changes the earlier one. The same question was settled again, differently. */
        SUPERSEDES,
        /** Both describe the same thing. Nothing changed; it was recorded twice. */
        DUPLICATE,
        /** Different facts that merely share a subject. */
        UNRELATED
    }

    public record Judgement(
            SupersessionDetector.Supersession candidate, Verdict verdict, String rationale) {}

    private static final String SYSTEM_PROMPT =
            """
            Two facts were taken from one organisation's conversations. The second was said
            later. Decide the relationship between them.

            SUPERSEDES  the later fact changes the earlier one — the same question was
                        decided again and the answer is different. A date that moved, an
                        owner who changed, a plan that was reversed.
            DUPLICATE   both describe the same thing and nothing changed. Usually the same
                        decision written down twice, in a meeting and again in chat, or
                        restated later in a document.
            UNRELATED   two different facts that happen to share a project, a person or a
                        deadline. Most pairs are this.

            Be strict about SUPERSEDES. It is only correct when the later fact makes the
            earlier one no longer true. If both can be true at once, they are DUPLICATE or
            UNRELATED — never SUPERSEDES.

            In particular, a fact reporting that something happened does not supersede the
            decision that planned it. "The launch is finished" and "the launch is on
            June 1" are both true at once; the plan was carried out, not revised. Only a
            fact that contradicts the earlier one supersedes it.

            Examples:
              "Frontier premieres May 4"  ->  "Frontier moves to June 15"        SUPERSEDES
              "Frontier premieres June 1" ->  "Frontier launched on June 1"      DUPLICATE
              "Frontier premieres June 1" ->  "The Frontier launch is done"      UNRELATED
              "Migration runs in four phases" -> "Migration targets end of Q2"   UNRELATED
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public SupersessionAdjudicator(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public List<Judgement> judge(List<SupersessionDetector.Supersession> candidates) {
        List<Judgement> judgements = new ArrayList<>();
        for (SupersessionDetector.Supersession candidate : candidates) {
            judgements.add(judgeOne(candidate));
        }
        long superseding = judgements.stream()
                .filter(judgement -> judgement.verdict() == Verdict.SUPERSEDES)
                .count();
        log.info(
                "adjudicated {} candidate pairs: {} supersede, {} duplicate, {} unrelated",
                judgements.size(),
                superseding,
                judgements.stream().filter(j -> j.verdict() == Verdict.DUPLICATE).count(),
                judgements.stream().filter(j -> j.verdict() == Verdict.UNRELATED).count());
        return judgements;
    }

    private Judgement judgeOne(SupersessionDetector.Supersession candidate) {
        String request =
                """
                Earlier (%s): %s
                Later   (%s): %s
                """
                        .formatted(
                                candidate.older().fact().occurredAt().toString().substring(0, 10),
                                candidate.older().statement(),
                                candidate.newer().fact().occurredAt().toString().substring(0, 10),
                                candidate.newer().statement());
        try {
            var response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(request))
                    .responseFormat(ResponseFormat.builder()
                            .type(ResponseFormatType.JSON)
                            .jsonSchema(SCHEMA)
                            .build())
                    .build());
            var parsed = objectMapper.readTree(unwrap(response.aiMessage().text()));
            Verdict verdict = Arrays.stream(Verdict.values())
                    .filter(value -> value.name().equalsIgnoreCase(parsed.path("verdict").asText()))
                    // An unreadable answer must not close a live fact.
                    .findFirst()
                    .orElse(Verdict.UNRELATED);
            String whatChanged = parsed.path("whatChanged").asText("").strip();
            if (verdict == Verdict.SUPERSEDES && whatChanged.isEmpty()) {
                // It claimed a revision and could not name what the revision was.
                log.debug("downgrading SUPERSEDES to DUPLICATE: nothing named as changed");
                verdict = Verdict.DUPLICATE;
            }
            return new Judgement(candidate, verdict, parsed.path("rationale").asText(""));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("supersession adjudication failed, defaulting to UNRELATED: {}", e.toString());
            return new Judgement(candidate, Verdict.UNRELATED, "adjudication failed");
        }
    }

    private static final JsonSchema SCHEMA = JsonSchema.builder()
            .name("supersession_verdict")
            .rootElement(JsonObjectSchema.builder()
                    .addProperty(
                            "verdict",
                            JsonEnumSchema.builder()
                                    .enumValues(Arrays.stream(Verdict.values())
                                            .map(Enum::name)
                                            .toList())
                                    .build())
                    // Naming the changed value before choosing a verdict forces the
                    // model to check that something actually changed. A restatement has
                    // nothing to put here, which is exactly when SUPERSEDES is wrong.
                    .addProperty(
                            "whatChanged",
                            JsonStringSchema.builder()
                                    .description("The specific thing the later fact changes — a date, an owner, "
                                            + "an amount, a reversal. Empty string if nothing changed.")
                                    .build())
                    .addProperty(
                            "rationale",
                            JsonStringSchema.builder()
                                    .description("One sentence. What changed, or why nothing did.")
                                    .build())
                    .required("whatChanged", "verdict", "rationale")
                    .build())
            .build();

    private static String unwrap(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (!text.startsWith("```")) {
            return text.isEmpty() ? "{}" : text;
        }
        int firstNewline = text.indexOf('\n');
        int closingFence = text.lastIndexOf("```");
        return firstNewline < 0 || closingFence <= firstNewline
                ? text
                : text.substring(firstNewline + 1, closingFence).strip();
    }
}
