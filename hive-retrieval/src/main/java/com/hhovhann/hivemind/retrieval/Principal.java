package com.hhovhann.hivemind.retrieval;

import java.util.Set;

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

    public boolean mayRead(Set<String> requiredGrants) {
        return grants.containsAll(requiredGrants);
    }
}
