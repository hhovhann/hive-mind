package com.hhovhann.hivemind.core.ontology;

/**
 * Something wrong with an extracted fact.
 *
 * @param severity whether the fact is discarded or merely flagged
 * @param field    which part of the fact is at fault
 * @param message  what a human reviewing the extraction run needs to know
 */
public record ValidationIssue(Severity severity, String field, String message) {

    public enum Severity {
        /** The fact does not enter the graph. */
        REJECT,
        /** The fact enters the graph, but is worth a human's attention. */
        WARN
    }

    public static ValidationIssue reject(String field, String message) {
        return new ValidationIssue(Severity.REJECT, field, message);
    }

    public static ValidationIssue warn(String field, String message) {
        return new ValidationIssue(Severity.WARN, field, message);
    }

    public boolean isRejection() {
        return severity == Severity.REJECT;
    }
}
