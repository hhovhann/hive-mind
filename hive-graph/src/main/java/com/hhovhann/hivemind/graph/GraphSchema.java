package com.hhovhann.hivemind.graph;

import java.util.List;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Constraints and indexes, applied idempotently on every load.
 *
 * <p>The uniqueness constraints are load-bearing rather than hygiene: every write is
 * a {@code MERGE} on a content-derived id, so re-running a load updates in place
 * instead of duplicating the graph. Without them a second run silently doubles
 * everything and every count downstream becomes a lie.
 *
 * <p>The vector index is what lets one Cypher query do semantic seeding and graph
 * expansion together, instead of an application-side join between a vector store and
 * a graph store. Its dimension has to match the embedding model exactly, so it is
 * taken from a real embedding rather than from configuration — a config value that
 * drifts from the model produces an index that accepts writes and returns nothing.
 */
@Component
public class GraphSchema {

    private static final Logger log = LoggerFactory.getLogger(GraphSchema.class);

    public static final String FACT_VECTOR_INDEX = "factStatementEmbedding";

    private static final List<String> CONSTRAINTS = List.of(
            "CREATE CONSTRAINT person_id IF NOT EXISTS FOR (p:Person) REQUIRE p.id IS UNIQUE",
            "CREATE CONSTRAINT episode_id IF NOT EXISTS FOR (e:Episode) REQUIRE e.id IS UNIQUE",
            "CREATE CONSTRAINT utterance_id IF NOT EXISTS FOR (u:Utterance) REQUIRE u.id IS UNIQUE",
            "CREATE CONSTRAINT fact_id IF NOT EXISTS FOR (f:Fact) REQUIRE f.id IS UNIQUE",
            "CREATE CONSTRAINT topic_name IF NOT EXISTS FOR (t:Topic) REQUIRE t.name IS UNIQUE");

    private static final List<String> INDEXES = List.of(
            // Current-state queries filter on validTo IS NULL constantly.
            "CREATE INDEX fact_valid_to IF NOT EXISTS FOR (f:Fact) ON (f.validTo)",
            "CREATE INDEX fact_occurred_at IF NOT EXISTS FOR (f:Fact) ON (f.occurredAt)",
            "CREATE INDEX episode_occurred_at IF NOT EXISTS FOR (e:Episode) ON (e.occurredAt)",
            // Hybrid retrieval fuses vector hits with keyword hits; this is the keyword half.
            "CREATE FULLTEXT INDEX factStatementText IF NOT EXISTS FOR (f:Fact) ON EACH [f.statement]",
            "CREATE FULLTEXT INDEX utteranceText IF NOT EXISTS FOR (u:Utterance) ON EACH [u.text]");

    private final Driver driver;

    public GraphSchema(Driver driver) {
        this.driver = driver;
    }

    /** @param embeddingDimension length of a real vector from the configured model */
    public void apply(int embeddingDimension) {
        try (var session = driver.session()) {
            CONSTRAINTS.forEach(statement -> session.run(statement).consume());
            INDEXES.forEach(statement -> session.run(statement).consume());
            session.run(
                            """
                            CREATE VECTOR INDEX %s IF NOT EXISTS
                            FOR (f:Fact) ON (f.embedding)
                            OPTIONS { indexConfig: {
                              `vector.dimensions`: $dimensions,
                              `vector.similarity_function`: 'cosine'
                            }}
                            """
                                    .formatted(FACT_VECTOR_INDEX),
                            org.neo4j.driver.Values.parameters("dimensions", embeddingDimension))
                    .consume();
        }
        log.info("schema applied, vector index at {} dimensions", embeddingDimension);
    }

    /** Wipes the graph. Used by {@code load --fresh} and by tests. */
    public void clear() {
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
        log.info("graph cleared");
    }
}
