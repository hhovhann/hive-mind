package com.hhovhann.hivemind.core.source;

import java.util.Objects;

/**
 * Who said something, as the source system reports it — before entity resolution.
 *
 * <p>Deliberately unresolved. The same human arrives as {@code U04H2} in Slack,
 * {@code "Alexandra Petrova"} in Notion, and {@code "Speaker 2"} in a Zoom
 * transcript; collapsing those too early is exactly the flattening we are trying
 * to avoid. Resolution happens later, with evidence, and stays reversible.
 *
 * @param system      system that produced the label
 * @param rawId       system-native identifier, or the raw label when that is all we get
 * @param displayName human-readable name if the source offered one
 */
public record SpeakerRef(SourceSystem system, String rawId, String displayName) {

    public SpeakerRef {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(rawId, "rawId");
    }

    public static SpeakerRef of(SourceSystem system, String rawId) {
        return new SpeakerRef(system, rawId, null);
    }

    /** Best label to show a human, falling back to the raw id. */
    public String label() {
        return displayName == null || displayName.isBlank() ? rawId : displayName;
    }

    public String key() {
        return system.name().toLowerCase() + ":" + rawId;
    }
}
