package com.hhovhann.hivemind.retrieval;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Four stages, not top-k.
 *
 * <ol>
 *   <li><b>Seed</b> — vector kNN and full-text search, fused with reciprocal rank.
 *       Neither alone is enough: embeddings miss exact names and identifiers,
 *       keywords miss paraphrase, and the questions people actually ask contain both.
 *   <li><b>Resolve currency</b> — for every stale seed, pull in whatever superseded
 *       it. This is the stage that fixes temporal flattening. A question about the
 *       launch date matches all three decided dates about equally well, so the
 *       retriever must reach the current one even when the query text resembles an
 *       old one more closely.
 *   <li><b>Hydrate</b> — one query for owners, evidence, and the supersession chain
 *       in both directions, so the answer can cite and can show its history.
 *   <li><b>Rerank</b> — current facts first, then recency, with a cap per source
 *       episode so one talkative thread cannot fill the whole context.
 * </ol>
 *
 * <p>Access control is a query parameter throughout. Neo4j's vector index cannot
 * pre-filter, so seeding over-fetches and filters inside the same statement — the
 * results are still correct, they just cost more to obtain, and no unreadable fact
 * ever reaches the application.
 */
@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    /** Standard reciprocal-rank-fusion constant; damps the tail without tuning per query. */
    private static final int RRF_K = 60;

    /** The vector index cannot filter by ACL, so ask for more than we need and drop what we may not read. */
    private static final int OVERFETCH = 4;

    /** No more than this many facts from any one episode, so a long thread cannot crowd out other sources. */
    private static final int MAX_PER_EPISODE = 3;

    private final Driver driver;
    private final EmbeddingModel embeddingModel;

    public HybridRetriever(Driver driver, EmbeddingModel embeddingModel) {
        this.driver = driver;
        this.embeddingModel = embeddingModel;
    }

    public List<RetrievedFact> retrieve(RetrievalQuery query) {
        try (Session session = driver.session()) {
            Map<String, Double> fused = seed(session, query);
            if (fused.isEmpty()) {
                return List.of();
            }
            Set<String> withCurrent = resolveCurrency(session, fused.keySet(), query);
            List<RetrievedFact> hydrated = hydrate(session, withCurrent, fused, query);
            List<RetrievedFact> ranked = rerank(hydrated, query);
            log.debug(
                    "'{}' -> {} seeds, {} after currency, {} returned",
                    query.question(),
                    fused.size(),
                    withCurrent.size(),
                    ranked.size());
            return ranked;
        }
    }

    // ---------------------------------------------------------------- stage 1: seed

    private Map<String, Double> seed(Session session, RetrievalQuery query) {
        List<String> byVector = vectorSeed(session, query);
        List<String> byKeyword = keywordSeed(session, query);
        return fuse(List.of(byVector, byKeyword));
    }

    private List<String> vectorSeed(Session session, RetrievalQuery query) {
        float[] vector = embeddingModel.embed(TextSegment.from(query.question())).content().vector();
        Record[] rows = session
                .run(
                        """
                        CALL db.index.vector.queryNodes('factStatementEmbedding', $k, $vector)
                        YIELD node AS f, score
                        WHERE ALL(g IN f.aclGrants WHERE g IN $grants)
                          AND ($asOf IS NULL OR (f.validFrom <= $asOf
                               AND (f.validTo IS NULL OR f.validTo > $asOf)))
                        RETURN f.id AS id ORDER BY score DESC LIMIT $limit
                        """,
                        params(
                                "k", query.seedLimit() * OVERFETCH,
                                "vector", toList(vector),
                                "grants", List.copyOf(query.principal().grants()),
                                "asOf", asOfParam(query),
                                "limit", query.seedLimit()))
                .list()
                .toArray(Record[]::new);
        return Arrays.stream(rows).map(row -> row.get("id").asString()).toList();
    }

    private List<String> keywordSeed(Session session, RetrievalQuery query) {
        String lucene = toLuceneQuery(query.question());
        if (lucene.isBlank()) {
            return List.of();
        }
        return session
                .run(
                        """
                        CALL db.index.fulltext.queryNodes('factStatementText', $query)
                        YIELD node AS f, score
                        WHERE ALL(g IN f.aclGrants WHERE g IN $grants)
                          AND ($asOf IS NULL OR (f.validFrom <= $asOf
                               AND (f.validTo IS NULL OR f.validTo > $asOf)))
                        RETURN f.id AS id ORDER BY score DESC LIMIT $limit
                        """,
                        params(
                                "query", lucene,
                                "grants", List.copyOf(query.principal().grants()),
                                "asOf", asOfParam(query),
                                "limit", query.seedLimit()))
                .list()
                .stream()
                .map(row -> row.get("id").asString())
                .toList();
    }

    /**
     * Reciprocal rank fusion.
     *
     * <p>Combines rankings rather than scores, which matters because cosine similarity
     * and Lucene relevance are not on the same scale and never will be. Normalising
     * them against each other requires per-corpus tuning; using positions requires none.
     */
    static Map<String, Double> fuse(List<List<String>> rankings) {
        Map<String, Double> fused = new LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                fused.merge(ranking.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        return fused;
    }

    // ------------------------------------------------------ stage 2: resolve currency

    /**
     * Adds the current version of every stale seed.
     *
     * <p>Without this a question worded like an old decision retrieves only that old
     * decision, and the answer is confidently six months out of date.
     */
    private Set<String> resolveCurrency(Session session, Set<String> seedIds, RetrievalQuery query) {
        Set<String> resolved = new LinkedHashSet<>(seedIds);
        if (query.isHistorical()) {
            // An as-of question wants the state at that date, not today's revision.
            return resolved;
        }
        session
                .run(
                        """
                        UNWIND $ids AS seedId
                        MATCH (seed:Fact {id: seedId})
                        WHERE seed.validTo IS NOT NULL
                        MATCH (newer:Fact)-[:SUPERSEDES*1..5]->(seed)
                        WHERE newer.validTo IS NULL
                          AND ALL(g IN newer.aclGrants WHERE g IN $grants)
                        RETURN DISTINCT newer.id AS id
                        """,
                        Map.of("ids", List.copyOf(seedIds), "grants", List.copyOf(query.principal().grants())))
                .list()
                .forEach(row -> resolved.add(row.get("id").asString()));
        return resolved;
    }

    // ------------------------------------------------------------- stage 3: hydrate

    private List<RetrievedFact> hydrate(
            Session session, Set<String> ids, Map<String, Double> scores, RetrievalQuery query) {
        return session
                .run(
                        """
                        UNWIND $ids AS factId
                        MATCH (f:Fact {id: factId})-[:DERIVED_FROM]->(e:Episode)
                        OPTIONAL MATCH (f)-[:OWNED_BY]->(owner:Person)
                        OPTIONAL MATCH (f)-[ev:EVIDENCED_BY]->(u:Utterance)
                        OPTIONAL MATCH (f)-[:SUPERSEDES*1..5]->(older:Fact)
                        OPTIONAL MATCH (newer:Fact)-[:SUPERSEDES]->(f)
                        RETURN f.id AS id, f.type AS type, f.statement AS statement,
                               f.status AS status, f.occurredAt AS occurredAt,
                               f.validFrom AS validFrom, f.validTo AS validTo,
                               f.topics AS topics,
                               e.title AS episodeTitle, e.system AS episodeSystem,
                               owner.name AS ownerName,
                               collect(DISTINCT CASE WHEN ev IS NULL THEN NULL ELSE {
                                 span: ev.span, speaker: u.speakerLabel,
                                 at: u.at, permalink: u.permalink
                               } END) AS evidence,
                               collect(DISTINCT CASE WHEN older IS NULL THEN NULL ELSE {
                                 id: older.id, statement: older.statement, occurredAt: older.occurredAt
                               } END) AS history,
                               collect(DISTINCT CASE WHEN newer IS NULL THEN NULL ELSE {
                                 id: newer.id, statement: newer.statement, occurredAt: newer.occurredAt
                               } END) AS newer
                        """,
                        Map.of("ids", List.copyOf(ids)))
                .list()
                .stream()
                .map(row -> toRetrievedFact(row, scores, query))
                .toList();
    }

    private RetrievedFact toRetrievedFact(Record row, Map<String, Double> scores, RetrievalQuery query) {
        String id = row.get("id").asString();
        List<RetrievedFact.EvidenceRef> evidence = new ArrayList<>();
        for (Value value : row.get("evidence").values()) {
            if (value.isNull()) {
                continue;
            }
            evidence.add(new RetrievedFact.EvidenceRef(
                    string(value.get("span")),
                    string(value.get("speaker")),
                    instant(value.get("at")),
                    string(value.get("permalink")),
                    row.get("episodeTitle").asString(null)));
        }
        List<RetrievedFact.FactRef> history = factRefs(row.get("history"));
        List<RetrievedFact.FactRef> newer = factRefs(row.get("newer"));

        return new RetrievedFact(
                id,
                row.get("type").asString(null),
                row.get("statement").asString(null),
                row.get("status").asString(null),
                instant(row.get("occurredAt")),
                instant(row.get("validFrom")),
                instant(row.get("validTo")),
                row.get("ownerName").asString(null),
                row.get("topics").isNull() ? List.of() : row.get("topics").asList(Value::asString),
                // A seed added purely because it supersedes something scores as well as
                // the fact that pulled it in; it is the answer, not a footnote.
                scores.getOrDefault(id, bestScoreAmong(newer, history, scores)),
                evidence,
                history.stream()
                        .sorted(Comparator.comparing(RetrievedFact.FactRef::occurredAt).reversed())
                        .toList(),
                newer.isEmpty() ? null : newer.getFirst(),
                row.get("episodeTitle").asString(null),
                row.get("episodeSystem").asString(null));
    }

    private static double bestScoreAmong(
            List<RetrievedFact.FactRef> newer, List<RetrievedFact.FactRef> history, Map<String, Double> scores) {
        double best = 0;
        for (RetrievedFact.FactRef ref : history) {
            best = Math.max(best, scores.getOrDefault(ref.id(), 0.0));
        }
        for (RetrievedFact.FactRef ref : newer) {
            best = Math.max(best, scores.getOrDefault(ref.id(), 0.0));
        }
        return best;
    }

    private static List<RetrievedFact.FactRef> factRefs(Value collected) {
        List<RetrievedFact.FactRef> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Value value : collected.values()) {
            if (value.isNull()) {
                continue;
            }
            String id = string(value.get("id"));
            if (id == null || !seen.add(id)) {
                continue;
            }
            refs.add(new RetrievedFact.FactRef(id, string(value.get("statement")), instant(value.get("occurredAt"))));
        }
        return refs;
    }

    // -------------------------------------------------------------- stage 4: rerank

    /**
     * Current before stale, recent before old, and no more than a few facts per episode.
     *
     * <p>The diversity cap is not cosmetic. Retrieval that returns eight facts from one
     * long thread looks confident and answers narrowly; the cross-source questions this
     * system exists for need the second and third source to survive into the context.
     */
    static List<RetrievedFact> rerank(List<RetrievedFact> facts, RetrievalQuery query) {
        Map<String, Integer> perEpisode = new LinkedHashMap<>();
        List<RetrievedFact> kept = new ArrayList<>();
        facts.stream()
                .sorted(Comparator.comparingDouble(RetrievedFact::score)
                        .reversed()
                        .thenComparing(fact -> fact.isCurrent() ? 0 : 1)
                        .thenComparing(Comparator.comparing(RetrievedFact::occurredAt).reversed()))
                .forEach(fact -> {
                    String episode = fact.episodeTitle() == null ? "?" : fact.episodeTitle();
                    int used = perEpisode.getOrDefault(episode, 0);
                    if (used < MAX_PER_EPISODE) {
                        perEpisode.put(episode, used + 1);
                        kept.add(fact);
                    }
                });
        return kept.stream().limit(query.finalLimit()).toList();
    }

    // ------------------------------------------------------------------- utilities

    /**
     * Turns a natural question into a safe Lucene disjunction.
     *
     * <p>Punctuation in a question is a syntax error to Lucene, not a hint, so the
     * operators are stripped rather than escaped and the remaining words OR'd.
     */
    static String toLuceneQuery(String question) {
        return Arrays.stream(question.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() > 2)
                .filter(word -> !STOPWORDS.contains(word))
                .distinct()
                .reduce((left, right) -> left + " OR " + right)
                .orElse("");
    }

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "did", "does", "was", "are", "who", "what", "when", "where", "why", "how", "our",
            "with", "that", "this", "has", "have", "any", "still", "there", "yet", "about");

    private static ZonedDateTime asOfParam(RetrievalQuery query) {
        return query.asOf() == null ? null : query.asOf().atZone(ZoneOffset.UTC);
    }

    /** {@code Map.of} rejects nulls, and {@code asOf} is null for every present-tense question. */
    private static Map<String, Object> params(Object... keysAndValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            params.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return params;
    }

    private static String string(Value value) {
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Instant instant(Value value) {
        return value == null || value.isNull() ? null : value.asZonedDateTime().toInstant();
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }
}
