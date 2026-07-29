package com.hhovhann.hivemind.retrieval;

import java.util.List;

/**
 * A generated answer with the cards it leaned on.
 *
 * @param refused true when the knowledge base did not contain an answer. Saying so is
 *                a correct outcome, and on a corpus with access control it is
 *                sometimes the <em>only</em> correct outcome — the reader who cannot
 *                see the exec channel must be told nothing was found, not given a
 *                reconstruction from the public fragments
 */
public record Answer(String text, List<Citation> citations, boolean refused, int cardsConsidered) {

    public record Citation(int card, String factId, String statement, String permalink) {}

    public Answer {
        citations = List.copyOf(citations);
    }

    public static Answer refusal(String reason, int cardsConsidered) {
        return new Answer(reason, List.of(), true, cardsConsidered);
    }

    public boolean isGrounded() {
        return !refused && !citations.isEmpty();
    }
}
