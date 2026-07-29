package com.hhovhann.hivemind.app.doctor;

/**
 * Outcome of a single dependency check.
 *
 * @param component  what was checked, e.g. "neo4j"
 * @param status     OK, DEGRADED (reachable but not usable), or DOWN
 * @param detail     one line a human can act on — a version, or the reason it failed
 * @param latencyMs  how long the check took, wall clock
 */
public record CheckResult(String component, Status status, String detail, long latencyMs) {

    public enum Status {
        OK,
        DEGRADED,
        DOWN
    }

    public static CheckResult ok(String component, String detail, long latencyMs) {
        return new CheckResult(component, Status.OK, detail, latencyMs);
    }

    public static CheckResult degraded(String component, String detail, long latencyMs) {
        return new CheckResult(component, Status.DEGRADED, detail, latencyMs);
    }

    public static CheckResult down(String component, String detail, long latencyMs) {
        return new CheckResult(component, Status.DOWN, detail, latencyMs);
    }

    public boolean healthy() {
        return status == Status.OK;
    }
}
