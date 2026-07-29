package com.hhovhann.hivemind.core.entity;

import java.util.Optional;

/**
 * What a mention turned out to refer to, and on what basis.
 *
 * <p>The {@link Method} is recorded because not all resolutions deserve equal trust.
 * An email match is a fact; a model's guess between two people in a meeting is an
 * inference, and the graph should be able to tell them apart when someone disputes
 * an owner. It also makes the resolver measurable: the share of mentions resolved
 * deterministically is the number to push up, since every one moved out of
 * {@link Method#LLM_ADJUDICATED} is a call not made and a guess not risked.
 *
 * @param mention   the surface form, as written
 * @param person    who it refers to, absent when nothing could be established
 * @param method    how the conclusion was reached
 * @param rationale short human-readable justification, kept for the audit trail
 */
public record MentionResolution(String mention, Optional<Person> person, Method method, String rationale) {

    public enum Method {
        /** Matched on an email address a source system supplied. Exact. */
        EMAIL(1.0),
        /** Matched a full name in the directory. */
        EXACT_NAME(0.95),
        /** Matched a Slack handle or user id. */
        HANDLE(0.98),
        /** One candidate among the people present in this episode, and only one. */
        UNIQUE_IN_EPISODE(0.85),
        /** Several candidates fit; a model chose between them given the transcript. */
        LLM_ADJUDICATED(0.7),
        /** Nothing was established. Better than a guess. */
        UNRESOLVED(0.0);

        private final double confidence;

        Method(double confidence) {
            this.confidence = confidence;
        }

        public double confidence() {
            return confidence;
        }

        public boolean isDeterministic() {
            return this == EMAIL || this == EXACT_NAME || this == HANDLE;
        }
    }

    public static MentionResolution of(String mention, Person person, Method method, String rationale) {
        return new MentionResolution(mention, Optional.of(person), method, rationale);
    }

    public static MentionResolution unresolved(String mention, String rationale) {
        return new MentionResolution(mention, Optional.empty(), Method.UNRESOLVED, rationale);
    }

    public boolean isResolved() {
        return person.isPresent();
    }

    public double confidence() {
        return method.confidence();
    }
}
