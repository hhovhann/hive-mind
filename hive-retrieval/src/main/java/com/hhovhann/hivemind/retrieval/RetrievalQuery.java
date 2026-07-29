package com.hhovhann.hivemind.retrieval;

import java.time.Instant;

/**
 * A question, asked by someone, optionally as of some date.
 *
 * @param asOf       the moment to answer as of; null means now. This is the whole
 *                   point of storing validity intervals — "what did we decide in
 *                   April" is this same query with a date rather than a different
 *                   index or a different corpus
 * @param seedLimit  candidates each seeding strategy returns before fusion
 * @param finalLimit facts that reach the context pack
 */
public record RetrievalQuery(String question, Principal principal, Instant asOf, int seedLimit, int finalLimit) {

    private static final int DEFAULT_SEED_LIMIT = 40;
    private static final int DEFAULT_FINAL_LIMIT = 8;

    public static RetrievalQuery of(String question) {
        return new RetrievalQuery(question, Principal.ANONYMOUS, null, DEFAULT_SEED_LIMIT, DEFAULT_FINAL_LIMIT);
    }

    public RetrievalQuery as(Principal principal) {
        return new RetrievalQuery(question, principal, asOf, seedLimit, finalLimit);
    }

    public RetrievalQuery asOf(Instant moment) {
        return new RetrievalQuery(question, principal, moment, seedLimit, finalLimit);
    }

    /** Narrows what reaches the context pack. Seeding is unchanged: the funnel is the point. */
    public RetrievalQuery limitedTo(int cards) {
        return new RetrievalQuery(question, principal, asOf, seedLimit, cards);
    }

    public boolean isHistorical() {
        return asOf != null;
    }
}
