package com.hhovhann.hivemind.retrieval;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

/**
 * The graph questions that are not "find me facts about this".
 *
 * <p>{@link HybridRetriever} answers one shape of question — rank the facts by
 * relevance to a sentence. The three here answer the shapes a graph is actually for:
 * how did this decision get to where it is, who is on the hook, and how are these two
 * things connected at all. Each is a traversal with an answer that a ranked list
 * cannot express, which is why they are separate queries rather than a re-ranking.
 *
 * <p><b>Access control is the same rule as everywhere else, applied in the harder
 * place.</b> Filtering a ranked list is easy: drop the rows the reader may not have.
 * Filtering a <em>path</em> is not, because a path through a fact you cannot read is
 * itself a disclosure — it asserts that two things are connected, and the only reason
 * to believe it is the hidden step in the middle. So every traversal here carries the
 * predicate into the expansion rather than applying it to the result: a chain stops at
 * the first link the reader cannot see, and a path that needs an unreadable node does
 * not exist as far as that reader is concerned.
 *
 * <p>Nodes that carry no {@code aclGrants} at all — {@code Person}, {@code Topic} —
 * are directory data and readable by everyone; the facts that connect them are not.
 */
@Service
public class KnowledgeQueries {

    /**
     * How far a supersession chain is walked in either direction.
     *
     * <p>Chains in practice are three or four links. The bound exists so a cycle
     * introduced by a bad adjudication cannot turn one tool call into a graph-wide scan.
     */
    private static final int MAX_CHAIN = 10;

    /** Longest path {@code path_between} will look for, and the most it will accept as an argument. */
    public static final int MAX_PATH_HOPS = 6;

    /**
     * A node is readable when it asks for nothing, or the reader holds everything it asks for.
     *
     * <p>Written once and interpolated into every traversal below, so that "did this
     * query filter?" is answerable by looking rather than by reading each statement.
     * {@code aclGrants IS NULL} is the {@code Person}/{@code Topic} case; on a
     * {@code Fact} or an {@code Episode} the property is always present, and empty
     * means public.
     */
    private static final String NODE_READABLE =
            "(n.aclGrants IS NULL OR ALL(g IN n.aclGrants WHERE g IN $grants))";

    /**
     * Every fact in one supersession chain, walked in both directions from a seed.
     *
     * <p>Both directions matter. Seeding on the question text lands wherever the
     * wording matched — often an old version, since a question phrased like the
     * original decision resembles it most — so the chain has to be walked forwards to
     * reach what holds now and backwards to reach what it replaced.
     */
    static final String CHAIN = """
            MATCH (seed:Fact {id: $factId})
            WHERE ALL(g IN seed.aclGrants WHERE g IN $grants)
            CALL {
              WITH seed
              MATCH path = (seed)-[:SUPERSEDES*0..%d]->(f:Fact)
              WHERE ALL(n IN nodes(path) WHERE %s)
              RETURN f
              UNION
              WITH seed
              MATCH path = (f:Fact)-[:SUPERSEDES*0..%d]->(seed)
              WHERE ALL(n IN nodes(path) WHERE %s)
              RETURN f
            }
            WITH DISTINCT f
            OPTIONAL MATCH (f)-[:OWNED_BY]->(owner:Person)
            OPTIONAL MATCH (f)-[:DERIVED_FROM]->(e:Episode)
            RETURN f.id AS id, f.type AS type, f.statement AS statement, f.status AS status,
                   f.occurredAt AS occurredAt, f.validFrom AS validFrom, f.validTo AS validTo,
                   owner.name AS ownerName,
                   e.title AS episodeTitle, e.system AS episodeSystem, e.permalink AS permalink
            ORDER BY occurredAt
            """
            .formatted(MAX_CHAIN, NODE_READABLE, MAX_CHAIN, NODE_READABLE);

    /**
     * Who owns the facts that matched, grouped by person rather than by fact.
     *
     * <p>The owner comes from the resolved {@code OWNED_BY} edge and never from the
     * text of the statement — which is the whole point on a corpus where the sentence
     * that decides something frequently names nobody, and where two people share a
     * first name. Identity is keyed on the person id, so two Alexes stay two Alexes
     * even when the answer only ever shows a display name.
     */
    static final String OWNERS = """
            UNWIND $ids AS factId
            MATCH (f:Fact {id: factId})-[o:OWNED_BY]->(p:Person)
            WHERE ALL(g IN f.aclGrants WHERE g IN $grants)
            WITH p, collect(DISTINCT o.method) AS methods,
                 collect(DISTINCT {
                   id: f.id, statement: f.statement,
                   current: f.validTo IS NULL, occurredAt: f.occurredAt
                 }) AS facts
            RETURN p.id AS personId, p.name AS name, p.email AS email, p.title AS title,
                   methods, size(facts) AS factCount, facts
            """;

    /** Exact resolution of a name, alias, email, topic, episode title or fact id to a node. */
    static final String RESOLVE_EXACT = """
            CALL {
              MATCH (n:Person)
              WHERE toLower(n.name) = $term OR toLower(n.email) = $term
                 OR ANY(a IN n.aliases WHERE toLower(a) = $term)
              RETURN 'Person' AS kind, elementId(n) AS ref, n.name AS label
              UNION
              MATCH (n:Topic) WHERE toLower(n.name) = $term
              RETURN 'Topic' AS kind, elementId(n) AS ref, n.name AS label
              UNION
              MATCH (n:Episode) WHERE toLower(n.title) = $term AND %s
              RETURN 'Episode' AS kind, elementId(n) AS ref, n.title AS label
              UNION
              MATCH (n:Fact) WHERE n.id = $rawTerm AND %s
              RETURN 'Fact' AS kind, elementId(n) AS ref, n.statement AS label
            }
            RETURN kind, ref, label LIMIT 10
            """
            .formatted(NODE_READABLE, NODE_READABLE);

    /** Substring fallback, so "Nordwind" finds the topic and "Alex" reports both Alexes. */
    static final String RESOLVE_FUZZY = """
            CALL {
              MATCH (n:Person)
              WHERE toLower(n.name) CONTAINS $term
                 OR ANY(a IN n.aliases WHERE toLower(a) CONTAINS $term)
              RETURN 'Person' AS kind, elementId(n) AS ref, n.name AS label
              UNION
              MATCH (n:Topic) WHERE toLower(n.name) CONTAINS $term
              RETURN 'Topic' AS kind, elementId(n) AS ref, n.name AS label
              UNION
              MATCH (n:Episode) WHERE toLower(n.title) CONTAINS $term AND %s
              RETURN 'Episode' AS kind, elementId(n) AS ref, n.title AS label
            }
            RETURN kind, ref, label ORDER BY kind, label LIMIT 10
            """
            .formatted(NODE_READABLE);

    /**
     * Shortest paths between two resolved nodes, over the edges that mean something.
     *
     * <p>The relationship whitelist is a correctness decision, not a performance one.
     * {@code EVIDENCED_BY} and {@code SPOKEN_BY} run through {@code Utterance}, which
     * carries no {@code aclGrants} of its own — it inherits them from its episode —
     * and the expansion cannot do that join while it walks. Rather than emit a path
     * whose readability we have not actually checked, those edges are left out, and
     * people connect to the graph through the facts they own, are involved in, and the
     * topics those facts are about.
     *
     * <p>The hop bound is interpolated because Cypher will not take a variable-length
     * bound as a parameter. It is an {@code int} clamped by the caller, never a string
     * from a tool argument.
     */
    static String pathsBetween(int maxHops) {
        return """
                MATCH (a) WHERE elementId(a) = $fromRef
                MATCH (b) WHERE elementId(b) = $toRef
                MATCH path = allShortestPaths(
                  (a)-[:SUPERSEDES|DUPLICATE_OF|OWNED_BY|INVOLVES|ABOUT|DERIVED_FROM*..%d]-(b))
                WHERE ALL(n IN nodes(path) WHERE %s)
                RETURN [n IN nodes(path) | {
                         kind: labels(n)[0],
                         label: coalesce(n.statement, n.name, n.title),
                         current: CASE WHEN n:Fact THEN n.validTo IS NULL ELSE NULL END,
                         ref: elementId(n)
                       }] AS nodes,
                       [r IN relationships(path) | {
                         type: type(r), from: elementId(startNode(r))
                       }] AS hops
                LIMIT $limit
                """
                .formatted(maxHops, NODE_READABLE);
    }

    /**
     * How many facts match the words of the question and are outside the reader's access.
     *
     * <p>This is the disclosure the retrieval filter cannot make for itself. A context
     * pack that has been filtered looks, to whatever model reads it, exactly like a
     * complete one — so a reader without the exec grants is handed the public fragments
     * of a restricted topic and reasons a confident answer out of them. Returning a
     * count puts "there is more here that you cannot see" in the context, where it can
     * be acted on, rather than leaving it to be inferred from an absence.
     *
     * <p>It is deliberately the keyword half of the seed and not the vector half: no
     * embedding round trip, and the number is a floor rather than an estimate — "at
     * least this many", which is the honest way to state it anyway. What it discloses
     * is a count and nothing else: no statement, no date, no topic, no source.
     */
    static final String WITHHELD = """
            CALL db.index.fulltext.queryNodes('factStatementText', $query) YIELD node AS f
            WHERE NOT ALL(g IN f.aclGrants WHERE g IN $grants)
              AND ($asOf IS NULL OR (f.validFrom <= $asOf
                   AND (f.validTo IS NULL OR f.validTo > $asOf)))
            RETURN count(DISTINCT f) AS withheld
            """;

    private final Driver driver;

    public KnowledgeQueries(Driver driver) {
        this.driver = driver;
    }

    // ------------------------------------------------------------------ trace

    /** One fact's supersession chain, oldest first, or empty when the id is unreadable or unknown. */
    public List<ChainLink> chainFrom(String factId, Principal principal) {
        try (Session session = driver.session()) {
            return session.run(CHAIN, Map.of("factId", factId, "grants", grantsOf(principal))).list().stream()
                    .map(KnowledgeQueries::toChainLink)
                    .toList();
        }
    }

    private static ChainLink toChainLink(Record row) {
        return new ChainLink(
                row.get("id").asString(),
                row.get("type").asString(null),
                row.get("statement").asString(null),
                row.get("status").asString(null),
                instant(row.get("occurredAt")),
                instant(row.get("validFrom")),
                instant(row.get("validTo")),
                row.get("ownerName").asString(null),
                row.get("episodeTitle").asString(null),
                row.get("episodeSystem").asString(null),
                row.get("permalink").asString(null));
    }

    /** One version of a decision, with what it was and when it stopped being that. */
    public record ChainLink(
            String id,
            String type,
            String statement,
            String status,
            Instant occurredAt,
            Instant validFrom,
            Instant validTo,
            String ownerName,
            String episodeTitle,
            String episodeSystem,
            String permalink) {

        public boolean isCurrent() {
            return validTo == null;
        }
    }

    // ----------------------------------------------------------------- owners

    /**
     * Owners of the given facts, most relevant first.
     *
     * <p>Relevance, not volume. Ordering by how many of the matched facts someone owns
     * puts whoever appears in the most loosely-related material at the top, and the
     * question was who owns <em>this</em>. So the ranking is inherited: {@code factIds}
     * arrives in retrieval order, and an owner sorts by the best-placed fact they hold.
     * Count breaks the tie.
     */
    public List<Ownership> ownersOf(List<String> factIds, Principal principal) {
        if (factIds.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (int index = 0; index < factIds.size(); index++) {
            rank.putIfAbsent(factIds.get(index), index);
        }
        try (Session session = driver.session()) {
            return session
                    .run(OWNERS, Map.of("ids", List.copyOf(factIds), "grants", grantsOf(principal)))
                    .list()
                    .stream()
                    .map(KnowledgeQueries::toOwnership)
                    .sorted(Comparator.comparingInt((Ownership owner) -> bestRank(owner, rank))
                            .thenComparing(Comparator.comparingInt(Ownership::factCount).reversed())
                            .thenComparing(Ownership::name))
                    .toList();
        }
    }

    private static int bestRank(Ownership owner, Map<String, Integer> rank) {
        return owner.facts().stream()
                .mapToInt(fact -> rank.getOrDefault(fact.id(), Integer.MAX_VALUE))
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private static Ownership toOwnership(Record row) {
        List<OwnedFact> facts = new ArrayList<>();
        for (Value value : row.get("facts").values()) {
            facts.add(new OwnedFact(
                    string(value.get("id")),
                    string(value.get("statement")),
                    value.get("current").asBoolean(false),
                    instant(value.get("occurredAt"))));
        }
        facts.sort((left, right) -> right.occurredAt().compareTo(left.occurredAt()));
        return new Ownership(
                row.get("personId").asString(),
                row.get("name").asString(null),
                emptyToNull(row.get("email").asString(null)),
                emptyToNull(row.get("title").asString(null)),
                row.get("methods").asList(Value::asString),
                row.get("factCount").asInt(),
                List.copyOf(facts));
    }

    /**
     * A person and what they are on the hook for.
     *
     * @param methods how the identity was resolved — a handle join and a model
     *                adjudication are not equally trustworthy, and a tool that hides
     *                the difference invites the caller to treat them as if they were
     */
    public record Ownership(
            String personId,
            String name,
            String email,
            String title,
            List<String> methods,
            int factCount,
            List<OwnedFact> facts) {}

    /** A fact someone owns. */
    public record OwnedFact(String id, String statement, boolean current, Instant occurredAt) {}

    // --------------------------------------------------------------- entities

    /**
     * Resolves a written name to nodes in the graph.
     *
     * <p>Exact first, substring only if exact found nothing. Returning several is a
     * real answer rather than a failure: "Alex" is two people in this corpus, and the
     * caller needs to be told that rather than handed whichever one sorted first.
     */
    public List<Entity> resolve(String term, Principal principal) {
        String needle = term.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params =
                Map.of("term", needle, "rawTerm", term.strip(), "grants", grantsOf(principal));
        try (Session session = driver.session()) {
            List<Entity> exact = session.run(RESOLVE_EXACT, params).list().stream()
                    .map(KnowledgeQueries::toEntity)
                    .toList();
            if (!exact.isEmpty()) {
                return exact;
            }
            return session.run(RESOLVE_FUZZY, params).list().stream()
                    .map(KnowledgeQueries::toEntity)
                    .toList();
        }
    }

    private static Entity toEntity(Record row) {
        return new Entity(
                row.get("kind").asString(), row.get("ref").asString(), row.get("label").asString(null));
    }

    /**
     * Something in the graph a path can start or end at.
     *
     * @param ref the Neo4j element id, so the path query re-finds exactly this node
     *            rather than re-running a name match that might land somewhere else
     */
    public record Entity(String kind, String ref, String label) {}

    // ------------------------------------------------------------------ paths

    /** Up to {@code limit} shortest readable paths between two nodes; empty when none is readable. */
    public List<Connection> pathsBetween(Entity from, Entity to, int maxHops, int limit, Principal principal) {
        int hops = Math.clamp(maxHops, 1, MAX_PATH_HOPS);
        try (Session session = driver.session()) {
            return session
                    .run(
                            pathsBetween(hops),
                            Map.of(
                                    "fromRef", from.ref(),
                                    "toRef", to.ref(),
                                    "limit", limit,
                                    "grants", grantsOf(principal)))
                    .list()
                    .stream()
                    .map(KnowledgeQueries::toConnection)
                    .toList();
        }
    }

    /**
     * Rebuilds edge direction from element ids.
     *
     * <p>{@code shortestPath} walks undirected, so the row says which node each
     * relationship starts at and the direction is recovered here. It is worth
     * recovering: "fact — owned by → person" and the same edge read backwards are
     * different claims, and a path rendered without arrows invites the second one.
     */
    private static Connection toConnection(Record row) {
        List<Node> nodes = new ArrayList<>();
        for (Value value : row.get("nodes").values()) {
            nodes.add(new Node(
                    string(value.get("kind")),
                    string(value.get("label")),
                    value.get("current").isNull() ? null : value.get("current").asBoolean(),
                    string(value.get("ref"))));
        }
        List<Hop> hops = new ArrayList<>();
        List<Value> raw = row.get("hops").asList(value -> value);
        for (int index = 0; index < raw.size(); index++) {
            String startRef = string(raw.get(index).get("from"));
            boolean forward = index < nodes.size() && nodes.get(index).ref().equals(startRef);
            hops.add(new Hop(string(raw.get(index).get("type")), forward));
        }
        return new Connection(List.copyOf(nodes), List.copyOf(hops));
    }

    /** A route through the graph: {@code n} nodes and the {@code n-1} edges between them. */
    public record Connection(List<Node> nodes, List<Hop> hops) {

        public int hopCount() {
            return hops.size();
        }
    }

    /** @param current null for anything that is not a fact, since only facts expire */
    public record Node(String kind, String label, Boolean current, String ref) {}

    /** @param forward true when the edge points from the previous node to the next one */
    public record Hop(String type, boolean forward) {}

    // -------------------------------------------------------------- withheld

    /** A floor on how many matching facts this reader may not see. */
    public long withheldCount(String question, Instant asOf, Principal principal) {
        String lucene = HybridRetriever.toLuceneQuery(question);
        if (lucene.isBlank()) {
            return 0;
        }
        try (Session session = driver.session()) {
            return session
                    .run(
                            WITHHELD,
                            params(
                                    "query", lucene,
                                    "grants", grantsOf(principal),
                                    "asOf", asOf == null ? null : asOf.atZone(ZoneOffset.UTC)))
                    .single()
                    .get("withheld")
                    .asLong();
        }
    }

    // ------------------------------------------------------------- utilities

    private static List<String> grantsOf(Principal principal) {
        return List.copyOf(principal.grants());
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

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant instant(Value value) {
        return value == null || value.isNull() ? null : value.asZonedDateTime().toInstant();
    }
}