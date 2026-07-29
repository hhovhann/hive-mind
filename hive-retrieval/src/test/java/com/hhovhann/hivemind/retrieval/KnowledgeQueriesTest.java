package com.hhovhann.hivemind.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural tests over the Cypher itself.
 *
 * <p>These cannot be checked by asking the graph a question, because the failure they
 * guard against is invisible from the outside: a traversal that forgot its access
 * filter returns <em>more</em> results, and more results look like better retrieval
 * right up until someone reads a fact they should not have. So the statements are
 * asserted on directly.
 */
class KnowledgeQueriesTest {

    private static final List<String> EVERY_TRAVERSAL = List.of(
            KnowledgeQueries.CHAIN,
            KnowledgeQueries.OWNERS,
            KnowledgeQueries.RESOLVE_EXACT,
            KnowledgeQueries.RESOLVE_FUZZY,
            KnowledgeQueries.WITHHELD,
            KnowledgeQueries.pathsBetween(4));

    @Test
    @DisplayName("no query in this class can run without the reader's grants")
    void everyStatementFilters() {
        assertThat(EVERY_TRAVERSAL).allSatisfy(cypher -> assertThat(cypher).contains("$grants"));
    }

    @Test
    @DisplayName("the chain filters every node it walks through, not just the ends")
    void chainFiltersMidPath() {
        // A fact reachable only through one the reader may not see is not reachable.
        // Checking the endpoints alone would connect two public facts through a
        // private revision and silently disclose that the revision happened.
        assertThat(KnowledgeQueries.CHAIN).contains("ALL(n IN nodes(path)");
        assertThat(KnowledgeQueries.CHAIN).doesNotContain("SUPERSEDES*]");
    }

    @Test
    @DisplayName("a path is checked at every node it passes through")
    void pathFiltersMidPath() {
        assertThat(KnowledgeQueries.pathsBetween(6)).contains("WHERE ALL(n IN nodes(path)");
    }

    @Test
    @DisplayName("paths never route through an utterance, whose readability cannot be checked in the expansion")
    void pathAvoidsUnfilterableEdges() {
        String cypher = KnowledgeQueries.pathsBetween(6);

        // Utterance carries no aclGrants of its own — it inherits its episode's — and
        // the expansion cannot do that join, so these edges stay out of the whitelist.
        assertThat(cypher).doesNotContain("EVIDENCED_BY").doesNotContain("SPOKEN_BY").doesNotContain("PART_OF");
        assertThat(cypher).contains("OWNED_BY").contains("ABOUT").contains("SUPERSEDES");
    }

    @Test
    @DisplayName("the hop bound reaches the query as a number, since Cypher will not take it as a parameter")
    void hopBoundIsInterpolated() {
        assertThat(KnowledgeQueries.pathsBetween(3)).contains("*..3]-(b)");
        assertThat(KnowledgeQueries.pathsBetween(6)).contains("*..6]-(b)");
    }

    @Test
    @DisplayName("the withheld count asks for a number and nothing else")
    void withheldDisclosesOnlyACount() {
        // The point of the disclosure is that the reader learns something exists, not
        // what it says. Returning a statement here would leak exactly what the ACL
        // filter just refused to hand over.
        assertThat(KnowledgeQueries.WITHHELD).contains("count(DISTINCT f) AS withheld");
        assertThat(KnowledgeQueries.WITHHELD).doesNotContain("f.statement").doesNotContain("f.topics");
        assertThat(KnowledgeQueries.WITHHELD).contains("NOT ALL(g IN f.aclGrants WHERE g IN $grants)");
    }
}