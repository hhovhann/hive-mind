rootProject.name = "hive-mind"

include(
    "hive-core",       // domain model, ontology, ports — depends on nothing
    "hive-ingest",     // connectors + episode assembly
    "hive-extract",    // LLM extraction, grounding gate, entity resolution
    "hive-graph",      // Neo4j persistence, bi-temporal writes
    "hive-retrieval",  // hybrid search, expansion, rerank, context assembly
    "hive-eval",       // gold set, extraction + answer metrics
    "hive-app",        // Spring Boot app: REST, CLI, wiring
)
