package com.hhovhann.hivemind.core.acl;

import com.hhovhann.hivemind.core.source.SourceSystem;
import java.util.Objects;

/**
 * One readable container in a source system — a Slack channel, a Notion page, a
 * Zoom meeting's invite list.
 *
 * @param system     system the container belongs to
 * @param id         system-native container id
 * @param visibility how widely that container is readable
 */
public record ScopeRef(SourceSystem system, String id, Visibility visibility) {

    public ScopeRef {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(visibility, "visibility");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("scope id is required");
        }
    }

    public static ScopeRef publicScope(SourceSystem system, String id) {
        return new ScopeRef(system, id, Visibility.PUBLIC);
    }

    public static ScopeRef restricted(SourceSystem system, String id) {
        return new ScopeRef(system, id, Visibility.RESTRICTED);
    }

    /** Grant key a principal must hold, e.g. {@code slack:C04AB}. */
    public String key() {
        return system.name().toLowerCase() + ":" + id;
    }

    public boolean requiresGrant() {
        return visibility.requiresGrant();
    }
}
