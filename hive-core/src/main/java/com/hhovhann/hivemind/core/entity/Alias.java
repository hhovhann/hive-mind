package com.hhovhann.hivemind.core.entity;

import com.hhovhann.hivemind.core.source.SourceSystem;
import java.util.Objects;

/**
 * One name a person appears under in one system.
 *
 * <p>Aliases are kept rather than collapsed into a single canonical string, because
 * they are the audit trail for a merge. When someone asks why the graph believes
 * {@code Speaker 2} is Alexandra Petrova, the answer has to be inspectable — and
 * when the merge is wrong, unpicking it means knowing exactly which alias was
 * responsible.
 *
 * @param system where this name was seen
 * @param value  the name as that system reports it. Zoom speaker labels arrive
 *               already scoped to their meeting, since {@code Speaker 2} means a
 *               different person in every recording
 */
public record Alias(SourceSystem system, String value) {

    public Alias {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(value, "value");
    }

    public String key() {
        return system.name().toLowerCase(java.util.Locale.ROOT) + ":" + value;
    }
}
