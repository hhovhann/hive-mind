package com.hhovhann.hivemind.core.ontology;

import java.util.Set;

/**
 * The classes of thing worth pulling out of a conversation.
 *
 * <p>Kept deliberately small. Every type added is a type the model must learn to
 * tell apart from the others, and precision falls faster than coverage rises —
 * a taxonomy of thirty labels extracts worse than one of five.
 *
 * <p>Each type declares its required fields, which does double duty: it drives the
 * JSON Schema that constrains extraction, and it rejects malformed facts on write.
 */
public enum FactType {

    /** A choice that was actually made and is now in force. The reason this system exists. */
    DECISION(Set.of(FactField.STATEMENT, FactField.EVIDENCE)),

    /** Work someone agreed to do, ideally with an owner and a date. */
    ACTION_ITEM(Set.of(FactField.STATEMENT, FactField.OWNER, FactField.EVIDENCE)),

    /** A promise made to someone outside the team — a client date, a partner deliverable. */
    COMMITMENT(Set.of(FactField.STATEMENT, FactField.OWNER, FactField.EVIDENCE)),

    /** A stated concern that has not been resolved. */
    RISK(Set.of(FactField.STATEMENT, FactField.EVIDENCE)),

    /** Something explicitly left open — the thing everyone forgets was never settled. */
    OPEN_QUESTION(Set.of(FactField.STATEMENT, FactField.EVIDENCE));

    private final Set<FactField> requiredFields;

    FactType(Set<FactField> requiredFields) {
        this.requiredFields = Set.copyOf(requiredFields);
    }

    public Set<FactField> requiredFields() {
        return requiredFields;
    }

    public boolean requires(FactField field) {
        return requiredFields.contains(field);
    }

    /** Neo4j label for this type — {@code DECISION} becomes {@code :Decision}. */
    public String graphLabel() {
        StringBuilder label = new StringBuilder();
        for (String word : name().split("_")) {
            label.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return label.toString();
    }
}
