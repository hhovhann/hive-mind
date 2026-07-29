package com.hhovhann.hivemind.retrieval;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who is asking, and what they are allowed to read.
 *
 * <p>Grants are the container keys from {@code AclScope} — {@code slack:C_EXEC},
 * {@code notion:P_BOARD_Q1}. They go into the retrieval query as a parameter rather
 * than being applied to its results: post-filtering silently shortens top-k, so a
 * reader without access gets fewer and worse answers rather than the same answers
 * minus the ones they may not see.
 *
 * <p>In production these are materialised per principal from the source systems and
 * cached; here they are supplied directly so the same question can be asked as two
 * different readers and the answers compared.
 */
public record Principal(String id, Set<String> grants) {

    /** Workspace member with no special access — sees only public containers. */
    public static final Principal ANONYMOUS = new Principal("anonymous", Set.of());

    public Principal {
        grants = Set.copyOf(grants);
    }

    public static Principal withGrants(String id, String... grants) {
        return new Principal(id, Set.of(grants));
    }

    /**
     * Parses the comma-separated form every entry point takes a reader's grants in.
     *
     * <p>One parser rather than one per surface, so {@code --grants=} on the CLI and
     * the launch argument the MCP server is started with cannot drift apart. Empty or
     * absent means {@link #ANONYMOUS}: the default is no access, never all access.
     */
    public static Principal parse(String id, String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return ANONYMOUS;
        }
        Set<String> grants = Arrays.stream(commaSeparated.split(","))
                .map(String::strip)
                .filter(grant -> !grant.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return grants.isEmpty() ? ANONYMOUS : new Principal(id, grants);
    }

    public boolean mayRead(Set<String> requiredGrants) {
        return grants.containsAll(requiredGrants);
    }
}
