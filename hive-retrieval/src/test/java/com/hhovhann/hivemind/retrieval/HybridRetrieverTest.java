package com.hhovhann.hivemind.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HybridRetrieverTest {

    @Test
    @DisplayName("fusion rewards a result both strategies found over one either ranked first")
    void fusionPrefersAgreement() {
        Map<String, Double> fused = HybridRetriever.fuse(List.of(
                List.of("vectorTop", "agreed"),
                List.of("keywordTop", "agreed")));

        assertThat(fused).containsKeys("agreed", "vectorTop", "keywordTop");
        assertThat(fused.get("agreed")).isGreaterThan(fused.get("vectorTop"));
        assertThat(fused.get("agreed")).isGreaterThan(fused.get("keywordTop"));
    }

    @Test
    @DisplayName("a question becomes a safe Lucene disjunction with its punctuation removed")
    void luceneQueryIsSanitised() {
        String lucene = HybridRetriever.toLuceneQuery("When does Frontier premiere? (the doc series)");

        assertThat(lucene).contains("frontier").contains("premiere").contains(" OR ");
        assertThat(lucene).doesNotContain("?").doesNotContain("(").doesNotContain(")");
        // Question words carry no signal and would match most of the corpus.
        assertThat(lucene).doesNotContain("when").doesNotContain("does");
    }

    @Test
    @DisplayName("one talkative thread cannot fill the whole context")
    void rerankCapsPerEpisode() {
        List<RetrievedFact> facts = List.of(
                fact("a", "#frontier-launch", 0.9, true),
                fact("b", "#frontier-launch", 0.8, true),
                fact("c", "#frontier-launch", 0.7, true),
                fact("d", "#frontier-launch", 0.6, true),
                fact("e", "Weekly content sync", 0.5, true));

        List<RetrievedFact> ranked = HybridRetriever.rerank(facts, RetrievalQuery.of("q"));

        assertThat(ranked).hasSize(4);
        assertThat(ranked).filteredOn(f -> f.episodeTitle().equals("#frontier-launch")).hasSize(3);
        // The other source survives rather than being crowded out.
        assertThat(ranked).extracting(RetrievedFact::id).contains("e");
    }

    @Test
    @DisplayName("a superseded fact still reaches the pack, so the answer can explain what changed")
    void supersededFactsAreNotDiscarded() {
        List<RetrievedFact> ranked = HybridRetriever.rerank(
                List.of(fact("stale", "#frontier-launch", 0.9, false), fact("current", "Retro", 0.4, true)),
                RetrievalQuery.of("q"));

        assertThat(ranked).extracting(RetrievedFact::id).containsExactly("stale", "current");
    }

    @Test
    @DisplayName("a citation naming a card that was never supplied is dropped, not repaired")
    void inventedCitationsAreDropped() {
        ContextPack pack = ContextPack.of(
                RetrievalQuery.of("q"), List.of(fact("real", "#eng", 0.9, true)));

        List<Answer.Citation> citations = AnswerGenerator.citationsIn("Yes [1], and also [7].", pack);

        assertThat(citations).singleElement().satisfies(citation -> assertThat(citation.card()).isEqualTo(1));
    }

    @Test
    @DisplayName("a superseded card is labelled so on its face, before its content")
    void packMarksStaleCardsLoudly() {
        String rendered = ContextPack.of(
                        RetrievalQuery.of("q"), List.of(fact("stale", "#frontier-launch", 0.9, false)))
                .render();

        assertThat(rendered).contains("NO LONGER TRUE");
        assertThat(rendered.indexOf("NO LONGER TRUE")).isLessThan(rendered.indexOf("statement of stale"));
    }

    private static RetrievedFact fact(String id, String episode, double score, boolean current) {
        Instant occurred = Instant.parse("2026-05-06T09:59:00Z");
        return new RetrievedFact(
                id,
                "DECISION",
                "statement of " + id,
                "AGREED",
                occurred,
                occurred,
                current ? null : Instant.parse("2026-06-01T00:00:00Z"),
                "Alexandra Petrova",
                List.of(),
                score,
                List.of(),
                List.of(),
                null,
                episode,
                "SLACK");
    }
}
