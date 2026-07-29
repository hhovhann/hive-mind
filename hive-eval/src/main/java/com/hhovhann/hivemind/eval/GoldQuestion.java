package com.hhovhann.hivemind.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One question from the answer key, with the grants its asker holds.
 *
 * @param principal grant keys the reader holds. Two questions in the corpus are
 *                  textually identical and differ only here — the same question must
 *                  answer differently for different readers, and nothing else in the
 *                  harness tests that
 * @param mustNotSay      phrases that may not appear anywhere. Reserved for leaks and
 *                        for claims nobody made — there is no safe place for those
 * @param mustNotLeadWith phrases that may appear, but not as the answer. A superseded
 *                        date is legitimate history further down and a misleading
 *                        opening line; collapsing the two prohibitions marks a correct
 *                        answer wrong for explaining itself
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldQuestion(
        String id,
        String question,
        List<String> principal,
        String expected,
        @JsonProperty("expected_facts") List<String> expectedFacts,
        @JsonProperty("must_not_say") List<String> mustNotSay,
        @JsonProperty("must_not_lead_with") List<String> mustNotLeadWith,
        String trap) {

    public GoldQuestion {
        principal = principal == null ? List.of() : List.copyOf(principal);
        expectedFacts = expectedFacts == null ? List.of() : List.copyOf(expectedFacts);
        mustNotSay = mustNotSay == null ? List.of() : List.copyOf(mustNotSay);
        mustNotLeadWith = mustNotLeadWith == null ? List.of() : List.copyOf(mustNotLeadWith);
    }

    /** True when the correct behaviour is to decline rather than answer. */
    public boolean expectsNoAnswer() {
        return expectedFacts.isEmpty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope(List<GoldQuestion> questions) {}
}
