package com.hhovhann.hivemind.app.api;

import com.hhovhann.hivemind.retrieval.Answer;
import com.hhovhann.hivemind.retrieval.HybridRetriever;
import com.hhovhann.hivemind.retrieval.Principal;
import com.hhovhann.hivemind.retrieval.RetrievalQuery;
import com.hhovhann.hivemind.retrieval.RetrievalService;
import com.hhovhann.hivemind.retrieval.RetrievedFact;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface for asking questions.
 *
 * <p>Two endpoints on purpose. {@code /ask} is the product; {@code /retrieve} stops
 * after assembling the context and returns the facts without generating an answer.
 * Splitting them is what makes load testing informative — measured together, every
 * result reads as "the model is slow" and says nothing about whether the graph,
 * the vector index or the application can carry the traffic. Measured apart, the
 * gap between them is the generation cost and everything below it is ours to fix.
 *
 * <p>Grants arrive in the request body, which is a development affordance rather
 * than an auth design: in production they are materialised per principal from the
 * source systems and cached, and the caller does not get to name its own. The
 * plumbing beneath this — how a grant reaches the Cypher — is real either way.
 */
@RestController
@RequestMapping("/api")
public class AskController {

    private final RetrievalService retrieval;
    private final HybridRetriever retriever;

    public AskController(RetrievalService retrieval, HybridRetriever retriever) {
        this.retrieval = retrieval;
        this.retriever = retriever;
    }

    public record AskRequest(@NotBlank String question, Instant asOf, Set<String> grants) {

        RetrievalQuery toQuery() {
            RetrievalQuery query = RetrievalQuery.of(question)
                    .as(grants == null || grants.isEmpty() ? Principal.ANONYMOUS : new Principal("api", grants));
            return asOf == null ? query : query.asOf(asOf);
        }
    }

    public record AskResponse(
            String question,
            String answer,
            boolean refused,
            List<Answer.Citation> citations,
            int cardsConsidered,
            long elapsedMs) {}

    public record RetrieveResponse(String question, int factsFound, List<FactSummary> facts, long elapsedMs) {}

    public record FactSummary(
            String id, String type, String statement, String status, boolean current, String owner, double score) {

        static FactSummary of(RetrievedFact fact) {
            return new FactSummary(
                    fact.id(),
                    fact.type(),
                    fact.statement(),
                    fact.status(),
                    fact.isCurrent(),
                    fact.ownerName(),
                    fact.score());
        }
    }

    /** Retrieval, assembly and generation — what a user actually asks for. */
    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        long start = System.nanoTime();
        RetrievalService.Result result = retrieval.ask(request.toQuery());
        Answer answer = result.answer();
        return ResponseEntity.ok(new AskResponse(
                request.question(),
                answer.text(),
                answer.refused(),
                answer.citations(),
                result.pack().cards().size(),
                elapsedMs(start)));
    }

    /** Everything except generation — the half of the system that is ours to make fast. */
    @PostMapping("/retrieve")
    public ResponseEntity<RetrieveResponse> retrieve(@Valid @RequestBody AskRequest request) {
        long start = System.nanoTime();
        List<RetrievedFact> facts = retriever.retrieve(request.toQuery());
        return ResponseEntity.ok(new RetrieveResponse(
                request.question(), facts.size(), facts.stream().map(FactSummary::of).toList(), elapsedMs(start)));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
