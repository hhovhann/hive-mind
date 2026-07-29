package com.hhovhann.hivemind.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One fact the corpus authors decided must be found.
 *
 * @param sources    episode ids, already resolved by the corpus build script to the
 *                   identifiers the pipeline emits
 * @param sourceKeys the readable keys the same sources were authored under
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldFact(
        String id,
        String type,
        String statement,
        String owner,
        String status,
        List<String> sources,
        @JsonProperty("source_keys") List<String> sourceKeys,
        String supersedes,
        @JsonProperty("superseded_by") String supersededBy,
        String note) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope(List<GoldFact> facts) {}
}
