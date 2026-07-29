package com.hhovhann.hivemind.core.source;

import java.util.Objects;
import java.util.Optional;

/**
 * A stable pointer back to the original content.
 *
 * <p>Every extracted fact traces to one of these. It is what makes a citation
 * clickable, and what lets re-ingestion recognise content it has already seen
 * instead of duplicating it.
 *
 * @param system     originating system
 * @param externalId the system's own identifier — Slack {@code channel/ts},
 *                   Notion page id, Zoom meeting uuid. Must be stable across syncs.
 * @param permalink  a URL a human can open, when the system offers one
 */
public record SourceRef(SourceSystem system, String externalId, String permalink) {

    public SourceRef {
        Objects.requireNonNull(system, "system");
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId is required — it is the dedupe key");
        }
    }

    public static SourceRef of(SourceSystem system, String externalId) {
        return new SourceRef(system, externalId, null);
    }

    /** Globally unique key, e.g. {@code slack:C04AB/1712345678.001200}. */
    public String key() {
        return system.name().toLowerCase() + ":" + externalId;
    }

    public Optional<String> permalinkIfPresent() {
        return Optional.ofNullable(permalink).filter(link -> !link.isBlank());
    }
}
