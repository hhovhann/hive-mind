package com.hhovhann.hivemind.core.ontology;

/** Fields a fact can carry. Which ones are mandatory depends on {@link FactType}. */
public enum FactField {
    STATEMENT,
    OWNER,
    PARTICIPANTS,
    OCCURRED_AT,
    DUE_DATE,
    STATUS,
    TOPICS,
    EVIDENCE
}
