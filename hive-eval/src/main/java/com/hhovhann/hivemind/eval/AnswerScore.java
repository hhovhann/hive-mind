package com.hhovhann.hivemind.eval;

import com.hhovhann.hivemind.retrieval.Answer;
import java.util.List;

/**
 * How the system answered the whole question set.
 *
 * <p>Verdicts are separated rather than collapsed into one accuracy figure because
 * the failures are not interchangeable. A missed answer costs a user a search; a
 * forbidden one hands them information they are not allowed to have, or a fact
 * nobody stated. Averaging those together produces a number that improves when you
 * make the system leakier, which is the opposite of useful.
 */
public record AnswerScore(List<QuestionResult> results) {

    public enum Verdict {
        /** Answered, on target, with citations. */
        PASS,
        /** Said something the key forbids — a leak, or a claim nobody made. */
        FORBIDDEN,
        /** Declined or answered off-target when the corpus contained the answer. */
        MISSED,
        /** On target, but cited nothing, so the claim cannot be checked. */
        UNSUPPORTED
    }

    public record QuestionResult(
            GoldQuestion question,
            Answer answer,
            Verdict verdict,
            double similarity,
            List<String> violations,
            int cardsRetrieved) {

        public boolean passed() {
            return verdict == Verdict.PASS;
        }
    }

    public AnswerScore {
        results = List.copyOf(results);
    }

    public long count(Verdict verdict) {
        return results.stream().filter(result -> result.verdict() == verdict).count();
    }

    public double passRate() {
        return results.isEmpty() ? 1.0 : (double) count(Verdict.PASS) / results.size();
    }

    public List<QuestionResult> failures() {
        return results.stream().filter(result -> !result.passed()).toList();
    }
}
