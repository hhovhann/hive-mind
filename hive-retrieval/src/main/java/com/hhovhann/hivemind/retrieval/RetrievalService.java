package com.hhovhann.hivemind.retrieval;

import java.util.List;
import org.springframework.stereotype.Service;

/** Retrieve, assemble, answer. */
@Service
public class RetrievalService {

    private final HybridRetriever retriever;
    private final AnswerGenerator generator;

    public RetrievalService(HybridRetriever retriever, AnswerGenerator generator) {
        this.retriever = retriever;
        this.generator = generator;
    }

    public Result ask(RetrievalQuery query) {
        List<RetrievedFact> facts = retriever.retrieve(query);
        ContextPack pack = ContextPack.of(query, facts);
        return new Result(query, pack, generator.answer(pack));
    }

    /** Keeps the pack alongside the answer, so a wrong answer can be traced to what it saw. */
    public record Result(RetrievalQuery query, ContextPack pack, Answer answer) {}
}
