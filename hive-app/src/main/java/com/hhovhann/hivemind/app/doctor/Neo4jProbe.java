package com.hhovhann.hivemind.app.doctor;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Component;

/**
 * Checks that Neo4j is reachable and new enough to hold the vector index.
 *
 * <p>Native vector indexes landed in 5.13. On anything older the graph half of
 * Hive Mind still works but hybrid retrieval does not, which is DEGRADED rather
 * than DOWN — the distinction saves an afternoon of confused debugging.
 */
@Component
public class Neo4jProbe implements HealthProbe {

    private static final int VECTOR_INDEX_MIN_MAJOR = 5;
    private static final int VECTOR_INDEX_MIN_MINOR = 13;

    private final Driver driver;

    public Neo4jProbe(Driver driver) {
        this.driver = driver;
    }

    @Override
    public String component() {
        return "neo4j";
    }

    @Override
    public CheckResult probe() {
        long start = System.nanoTime();
        try (var session = driver.session()) {
            var record = session
                    .run("CALL dbms.components() YIELD name, versions, edition "
                            + "RETURN versions[0] AS version, edition LIMIT 1")
                    .single();
            String version = record.get("version").asString();
            String edition = record.get("edition").asString();
            long elapsedMs = elapsedMs(start);

            String detail = "Neo4j %s (%s)".formatted(version, edition);
            return supportsVectorIndex(version)
                    ? CheckResult.ok(component(), detail, elapsedMs)
                    : CheckResult.degraded(
                            component(),
                            detail + " — needs 5.13+ for native vector indexes, hybrid retrieval will not work",
                            elapsedMs);
        } catch (RuntimeException e) {
            return CheckResult.down(component(), rootMessage(e), elapsedMs(start));
        }
    }

    static boolean supportsVectorIndex(String version) {
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major > VECTOR_INDEX_MIN_MAJOR
                    || (major == VECTOR_INDEX_MIN_MAJOR && minor >= VECTOR_INDEX_MIN_MINOR);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String rootMessage(Throwable t) {
        Throwable cursor = t;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.toString() : cursor.getMessage();
    }
}
