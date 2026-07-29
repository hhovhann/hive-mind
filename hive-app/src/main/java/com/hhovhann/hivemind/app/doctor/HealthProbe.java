package com.hhovhann.hivemind.app.doctor;

/**
 * A single checkable dependency. Implementations are Spring beans; {@link HiveDoctor}
 * discovers them all, so adding a probe never means editing the doctor.
 *
 * <p>Implementations must not throw — return a DOWN {@link CheckResult} instead.
 */
public interface HealthProbe {

    /** Short lowercase identifier, e.g. {@code "neo4j"}. */
    String component();

    CheckResult probe();
}
