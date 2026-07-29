package com.hhovhann.hivemind.core.acl;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The set of containers a reader must have access to before they may see something.
 *
 * <p>The rule that matters: <strong>a derived fact inherits the union of its
 * sources' requirements, which is the intersection of who may read it.</strong> A
 * decision extracted half from {@code #general} and half from a private exec
 * channel is readable only by someone who can read both. Anything looser leaks the
 * private half through a summary, which is the failure mode that makes RBAC on a
 * knowledge base worth taking seriously.
 *
 * <p>Enforcement belongs in the retrieval query, not after it: filtering results
 * post-hoc silently shrinks top-k and turns a permissions bug into a quality bug.
 * {@link #requiredGrants()} is built to drop straight into a Cypher parameter.
 *
 * @param scopes containers this content lives in; empty means workspace-wide
 */
public record AclScope(Set<ScopeRef> scopes) {

    /** Readable by anyone with workspace access — no container grants needed. */
    public static final AclScope WORKSPACE = new AclScope(Set.of());

    public AclScope {
        // Sorted copy so hashing and query parameters are deterministic.
        scopes = scopes.stream()
                .sorted(Comparator.comparing(ScopeRef::key))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        scopes = Set.copyOf(scopes);
    }

    public static AclScope of(ScopeRef... scopes) {
        return new AclScope(Set.of(scopes));
    }

    /**
     * The scope a fact derived from {@code sources} must carry.
     *
     * @throws IllegalArgumentException if there are no sources — an ungrounded fact
     *     would otherwise default to workspace-readable, and failing open here is
     *     the one mistake this class exists to prevent
     */
    public static AclScope inheritedFrom(Collection<AclScope> sources) {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "cannot derive an ACL from zero sources — this would fail open");
        }
        Set<ScopeRef> combined = sources.stream()
                .flatMap(scope -> scope.scopes().stream())
                .collect(Collectors.toSet());
        return new AclScope(combined);
    }

    /** Grant keys the reader must hold, all of them. Public containers need none. */
    public Set<String> requiredGrants() {
        return scopes.stream()
                .filter(ScopeRef::requiresGrant)
                .map(ScopeRef::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Tightest visibility among the containers — what a UI badge should say. */
    public Visibility effectiveVisibility() {
        return scopes.stream()
                .map(ScopeRef::visibility)
                .reduce(Visibility.PUBLIC, Visibility::mostRestrictive);
    }

    /**
     * Whether a principal holding {@code heldGrants} may read this.
     *
     * <p>The authoritative check happens in the retrieval query; this is for unit
     * tests and for asserting the invariant at write time.
     */
    public boolean readableBy(Set<String> heldGrants) {
        return heldGrants.containsAll(requiredGrants());
    }
}
