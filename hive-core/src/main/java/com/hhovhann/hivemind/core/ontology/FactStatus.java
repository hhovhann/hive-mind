package com.hhovhann.hivemind.core.ontology;

/**
 * Where a fact stands.
 *
 * <p>{@link #PROPOSED} versus {@link #AGREED} is the distinction that stops a
 * brainstorm from being reported as policy — the single most common way these
 * systems mislead the person asking.
 */
public enum FactStatus {
    PROPOSED,
    AGREED,
    IN_PROGRESS,
    DONE,
    BLOCKED,
    /** Set by the graph when a newer fact supersedes this one, never by extraction. */
    SUPERSEDED,
    ABANDONED
}
