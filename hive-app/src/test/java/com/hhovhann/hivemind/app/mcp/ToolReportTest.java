package com.hhovhann.hivemind.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.hhovhann.hivemind.retrieval.ContextPack;
import com.hhovhann.hivemind.retrieval.KnowledgeQueries;
import com.hhovhann.hivemind.retrieval.Principal;
import com.hhovhann.hivemind.retrieval.RetrievalQuery;
import com.hhovhann.hivemind.retrieval.RetrievedFact;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolReportTest {

    @Test
    @DisplayName("a filtered result says so, and says what not to do about it")
    void withheldFactsAreDisclosed() {
        String report = ToolReport.search(pack(), Principal.ANONYMOUS, null, 3);

        assertThat(report).contains("At least 3 further facts");
        assertThat(report).contains("outside this reader's access");
        // Naming the wrong behaviour is the part that works. "Some results were
        // filtered" is acknowledged and then ignored, because it says nothing to do.
        assertThat(report).contains("say the record is incomplete");
        assertThat(report).contains("inferring");
    }

    @Test
    @DisplayName("nothing withheld means no disclaimer at all")
    void unfilteredResultsSayNothing() {
        String report = ToolReport.search(pack(), Principal.ANONYMOUS, null, 0);

        assertThat(report).doesNotContain("outside this reader's access");
        assertThat(report).contains("a reader with no special access");
    }

    @Test
    @DisplayName("an as-of search says which day it was answered for")
    void historicalSearchesSayTheDate() {
        String report = ToolReport.search(pack(), Principal.ANONYMOUS, Instant.parse("2026-04-15T00:00:00Z"), 0);

        assertThat(report).contains("as the record stood on 2026-04-15");
    }

    @Test
    @DisplayName("a chain marks the live version and the dead ones, before their content")
    void traceLabelsCurrencyFirst() {
        String report = ToolReport.trace("frontier launch", List.of(link("older", false), link("newer", true)), List.of());

        assertThat(report).contains("2 versions of this decision, oldest first");
        assertThat(report).contains("SUPERSEDED  statement of older");
        assertThat(report).contains("CURRENT     statement of newer");
        assertThat(report.indexOf("SUPERSEDED")).isLessThan(report.indexOf("statement of older"));
        assertThat(report).contains("and still holding");
    }

    @Test
    @DisplayName("a chain of one offers the other candidates rather than implying certainty")
    void singleLinkChainsOfferAlternatives() {
        String report = ToolReport.trace("newsletter", List.of(link("only", true)), List.of(fact("other")));

        assertThat(report).contains("no recorded revisions");
        assertThat(report).contains("trace_decision again with fact_id");
        assertThat(report).contains("other");
    }

    @Test
    @DisplayName("facts with no owner are reported as such, not left to be guessed at")
    void unownedFactsAreCounted() {
        String report = ToolReport.owners("who owns it", List.of(ownership()), 8, 5);

        assertThat(report).contains("Alex Chen");
        assertThat(report).contains("identity resolved by HANDLE");
        assertThat(report).contains("5 matching facts have no resolved owner");
    }

    @Test
    @DisplayName("when nothing has an owner the tool says so instead of returning an empty list")
    void noOwnersAtAllIsAnAnswer() {
        String report = ToolReport.owners("who owns it", List.of(), 8, 8);

        assertThat(report).contains("None of the 8 facts");
        // The instruction matters more than the count: the failure mode is reading a
        // plausible name out of a quote and reporting it as the owner.
        assertThat(report).contains("no OWNED_BY edge");
        assertThat(report).contains("wrong person gets assigned");
    }

    @Test
    @DisplayName("a path shows which way each edge points")
    void pathsCarryDirection() {
        KnowledgeQueries.Node person = new KnowledgeQueries.Node("Person", "Alex Chen", null, "p1");
        KnowledgeQueries.Node decision = new KnowledgeQueries.Node("Fact", "migrate the CMS", true, "f1");
        KnowledgeQueries.Node topic = new KnowledgeQueries.Node("Topic", "cms-migration", null, "t1");

        String report = ToolReport.paths(
                new KnowledgeQueries.Entity("Person", "p1", "Alex Chen"),
                new KnowledgeQueries.Entity("Topic", "t1", "cms-migration"),
                List.of(new KnowledgeQueries.Connection(
                        List.of(person, decision, topic),
                        // OWNED_BY runs fact-to-person, so walking it from the person is backwards.
                        List.of(new KnowledgeQueries.Hop("OWNED_BY", false), new KnowledgeQueries.Hop("ABOUT", true)))));

        assertThat(report).contains("<-[OWNED_BY]-");
        assertThat(report).contains("-[ABOUT]->");
        assertThat(report).contains("2 hops");
        assertThat(report).contains("(current)");
    }

    @Test
    @DisplayName("no path is an answer, and distinguishes 'unconnected' from 'not readable'")
    void noPathExplainsItself() {
        String report = ToolReport.paths(
                new KnowledgeQueries.Entity("Person", "p1", "Alex Chen"),
                new KnowledgeQueries.Entity("Person", "p2", "Dana Okonkwo"),
                List.of());

        assertThat(report).contains("No readable path");
        assertThat(report).contains("may not see");
    }

    @Test
    @DisplayName("an ambiguous name asks back instead of picking one")
    void ambiguityIsReturnedToTheCaller() {
        String report = ToolReport.ambiguous(
                "from",
                "Alex",
                List.of(
                        new KnowledgeQueries.Entity("Person", "p1", "Alex Chen"),
                        new KnowledgeQueries.Entity("Person", "p2", "Alexandra Petrova")));

        assertThat(report).contains("matches 2 things");
        assertThat(report).contains("Alex Chen").contains("Alexandra Petrova");
    }

    // ------------------------------------------------------------- fixtures

    private static ContextPack pack() {
        return ContextPack.of(RetrievalQuery.of("q"), List.of(fact("a")));
    }

    private static RetrievedFact fact(String id) {
        Instant occurred = Instant.parse("2026-05-06T09:59:00Z");
        return new RetrievedFact(
                id,
                "DECISION",
                "statement of " + id,
                "AGREED",
                occurred,
                occurred,
                null,
                "Alexandra Petrova",
                List.of(),
                0.5,
                List.of(),
                List.of(),
                null,
                "#frontier-launch",
                "SLACK");
    }

    private static KnowledgeQueries.ChainLink link(String id, boolean current) {
        Instant occurred = Instant.parse("2026-02-11T09:00:00Z");
        return new KnowledgeQueries.ChainLink(
                id,
                "DECISION",
                "statement of " + id,
                current ? "AGREED" : "SUPERSEDED",
                occurred,
                occurred,
                current ? null : Instant.parse("2026-04-08T00:00:00Z"),
                "Alexandra Petrova",
                "#frontier-launch",
                "SLACK",
                "https://example.invalid/1");
    }

    private static KnowledgeQueries.Ownership ownership() {
        return new KnowledgeQueries.Ownership(
                "person:alex-chen",
                "Alex Chen",
                "alex.chen@example.invalid",
                "Staff Engineer",
                List.of("HANDLE"),
                3,
                List.of(new KnowledgeQueries.OwnedFact(
                        "f1", "Alex Chen will run the video CMS migration", true, Instant.parse("2026-03-02T10:00:00Z"))));
    }
}