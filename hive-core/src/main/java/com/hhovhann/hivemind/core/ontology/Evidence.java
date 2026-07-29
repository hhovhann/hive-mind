package com.hhovhann.hivemind.core.ontology;

import java.util.Objects;

/**
 * The words a fact was read out of.
 *
 * <p>Extraction must return one of these per fact, and the span is checked against
 * the source before the fact is accepted. That single requirement removes most
 * fabrication for free: a model cannot quote a sentence that was never written, and
 * a deterministic string comparison is cheaper and more reliable than asking a
 * second model whether the first one was honest.
 *
 * <p>It is also what makes an answer auditable — every claim leads back to a
 * timestamped line someone actually typed or said.
 *
 * @param episodeId        episode the span came from
 * @param utteranceOrdinal which contribution within that episode
 * @param verbatimSpan     text copied from that contribution, unmodified
 */
public record Evidence(String episodeId, int utteranceOrdinal, String verbatimSpan) {

    public Evidence {
        Objects.requireNonNull(episodeId, "episodeId");
        Objects.requireNonNull(verbatimSpan, "verbatimSpan");
        if (utteranceOrdinal < 0) {
            throw new IllegalArgumentException("utteranceOrdinal must be zero-based, got " + utteranceOrdinal);
        }
    }
}
