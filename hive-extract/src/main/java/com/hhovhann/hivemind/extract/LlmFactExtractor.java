package com.hhovhann.hivemind.extract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.ontology.Evidence;
import com.hhovhann.hivemind.core.ontology.ExtractedFact;
import com.hhovhann.hivemind.core.ontology.OntologyValidator;
import com.hhovhann.hivemind.core.ontology.ValidationIssue;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Extracts facts from a whole episode in one schema-constrained call.
 *
 * <p>Deliberately one pass, not two. The plan called for mentions-then-relations,
 * and that may well win — but a second pass doubles cost and latency, and the honest
 * order is to measure the single pass against the gold set first and add the second
 * only where the numbers ask for it. The interface takes an {@link Episode} and
 * returns an {@link ExtractionResult}, so a two-pass implementation slots in behind
 * it without anything upstream changing.
 *
 * <p>Misattributed quotes are repaired rather than discarded: when the quoted words
 * exist but the cited line number is wrong, the citation is corrected and the warning
 * kept. The validator sees the fact first, so the signal survives even though the
 * data is fixed — otherwise repair would quietly hide a real regression in the model.
 */
@Service
public class LlmFactExtractor implements FactExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmFactExtractor.class);

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final OntologyValidator validator;
    private final ResponseFormat responseFormat;

    public LlmFactExtractor(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.validator = new OntologyValidator();
        this.responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(OntologySchema.forExtraction())
                .build();
    }

    @Override
    public ExtractionResult extract(Episode episode) {
        long start = System.nanoTime();
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(ExtractionPrompt.system()), UserMessage.from(episode.render()))
                .responseFormat(responseFormat)
                .build();

        ChatResponse response;
        try {
            response = chatModel.chat(request);
        } catch (RuntimeException e) {
            log.warn("extraction call failed for episode {}: {}", episode.id(), e.toString());
            return ExtractionResult.empty(episode.id(), ExtractionPrompt.signature(), elapsed(start));
        }

        List<FactPayload> payloads = parse(response.aiMessage().text(), episode.id());
        List<ExtractedFact> accepted = new ArrayList<>();
        List<ExtractionResult.RejectedFact> rejected = new ArrayList<>();
        List<ValidationIssue> warnings = new ArrayList<>();

        for (FactPayload payload : payloads) {
            payload.toDomain(episode.id(), episode.occurredAt()).ifPresent(fact -> {
                List<ValidationIssue> issues = validator.validate(fact, episode);
                if (issues.stream().anyMatch(ValidationIssue::isRejection)) {
                    rejected.add(new ExtractionResult.RejectedFact(fact, issues));
                } else {
                    warnings.addAll(issues);
                    accepted.add(repairCitations(fact, episode));
                }
            });
        }

        var usage = response.tokenUsage();
        ExtractionResult result = new ExtractionResult(
                episode.id(),
                accepted,
                rejected,
                warnings,
                ExtractionPrompt.signature(),
                elapsed(start),
                usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());

        log.debug(
                "episode {}: {} accepted, {} rejected in {}ms",
                episode.id(),
                accepted.size(),
                rejected.size(),
                result.elapsed().toMillis());
        return result;
    }

    /**
     * Points each quote at the line it actually came from.
     *
     * <p>Only runs on facts that already passed validation, so every span here is
     * known to exist somewhere in the episode.
     */
    private static ExtractedFact repairCitations(ExtractedFact fact, Episode episode) {
        boolean needsRepair = fact.evidence().stream()
                .anyMatch(span -> !episode.supports(span.utteranceOrdinal(), span.verbatimSpan()));
        if (!needsRepair) {
            return fact;
        }
        List<Evidence> repaired = fact.evidence().stream()
                .map(span -> episode.supports(span.utteranceOrdinal(), span.verbatimSpan())
                        ? span
                        : episode.locate(span.verbatimSpan())
                                .map(ordinal -> new Evidence(span.episodeId(), ordinal, span.verbatimSpan()))
                                .orElse(span))
                .toList();
        return new ExtractedFact(
                fact.type(),
                fact.statement(),
                fact.ownerMention(),
                fact.participantMentions(),
                fact.occurredAt(),
                fact.dueDate(),
                fact.status(),
                fact.topics(),
                repaired,
                fact.confidence());
    }

    private List<FactPayload> parse(String raw, String episodeId) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(unwrap(raw), FactPayload.Envelope.class).factsOrEmpty();
        } catch (JsonProcessingException e) {
            log.warn("episode {}: model returned unparseable JSON — {}", episodeId, e.getOriginalMessage());
            return List.of();
        }
    }

    /**
     * Strips a markdown fence if the model added one.
     *
     * <p>Schema-constrained responses should never be fenced. Small models fence them
     * anyway, often enough that failing here would look like an extraction problem
     * rather than a formatting one.
     */
    static String unwrap(String raw) {
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

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
