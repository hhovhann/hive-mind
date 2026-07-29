package com.hhovhann.hivemind.graph;

import com.hhovhann.hivemind.core.entity.MentionResolution;
import com.hhovhann.hivemind.core.entity.Person;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.episode.Utterance;
import com.hhovhann.hivemind.core.ontology.Evidence;
import com.hhovhann.hivemind.core.ontology.FactStatus;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes people, episodes, utterances and facts into Neo4j.
 *
 * <p>Everything is a {@code MERGE} on a content-derived id, so a second load of the
 * same corpus updates in place rather than doubling the graph.
 *
 * <p>Supersession is applied last, once every fact exists, because closing a fact
 * requires knowing what replaced it. The predecessor keeps its node and its evidence
 * and gains a {@code validTo} and a {@code SUPERSEDES} edge pointing at it — so
 * "what holds now" filters on {@code validTo IS NULL} and "what did we think in
 * April" filters on the interval, from the same data.
 */
@Service
public class KnowledgeGraphWriter {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphWriter.class);

    private final Driver driver;
    private final GraphSchema schema;
    private final SupersessionDetector supersessionDetector;
    private final EmbeddingModel embeddingModel;

    public KnowledgeGraphWriter(
            Driver driver,
            GraphSchema schema,
            SupersessionDetector supersessionDetector,
            EmbeddingModel embeddingModel) {
        this.driver = driver;
        this.schema = schema;
        this.supersessionDetector = supersessionDetector;
        this.embeddingModel = embeddingModel;
    }

    /**
     * @param speakers episode id to speaker key to who that speaker turned out to be
     */
    public GraphLoadReport write(
            List<Person> people,
            List<Episode> episodes,
            Map<String, Map<String, MentionResolution>> speakers,
            List<ResolvedFact> facts) {

        List<ResolvedFact> chronological = facts.stream()
                .sorted(Comparator.comparing(fact -> fact.fact().occurredAt()))
                .toList();
        Map<String, float[]> embeddings = embed(chronological);
        int dimension = embeddings.values().stream()
                .findFirst()
                .map(vector -> vector.length)
                .orElse(768);
        schema.apply(dimension);

        List<SupersessionAdjudicator.Judgement> judgements = supersessionDetector.resolve(chronological, embeddings);
        Map<String, Closure> closures = closuresFrom(judgements);
        List<Propagated> propagated = propagateAcrossDuplicates(judgements, closures);
        Map<String, Instant> closedAt = new HashMap<>();
        closures.forEach((factId, closure) -> closedAt.put(factId, closure.at()));

        int utterances = 0;
        int evidenceEdges = 0;
        int ownedEdges = 0;
        int unresolvedOwners = 0;

        try (Session session = driver.session()) {
            writePeople(session, people);
            utterances = writeEpisodes(session, episodes, speakers);
            writeFacts(session, chronological, embeddings, closedAt);
            evidenceEdges = writeEvidence(session, chronological);

            for (ResolvedFact fact : chronological) {
                if (fact.owner() != null && fact.owner().isResolved()) {
                    ownedEdges++;
                } else if (fact.fact().owner().isPresent()) {
                    unresolvedOwners++;
                }
            }
            writeOwnership(session, chronological);
            writeTopics(session, chronological);
            writeJudgements(session, judgements);
            writePropagated(session, propagated);
        }

        GraphLoadReport report = new GraphLoadReport(
                people.size(),
                episodes.size(),
                utterances,
                chronological.size(),
                evidenceEdges,
                ownedEdges,
                unresolvedOwners,
                judgements);
        log.info(
                "loaded {} facts across {} episodes, {} supersessions, {} duplicates",
                report.facts(),
                report.episodes(),
                report.supersessionCount(),
                report.duplicateCount());
        return report;
    }

    /** A fact's closure: when it stopped being true, and what replaced it. */
    private record Closure(Instant at, String supersededById) {}

    /** A closure inferred through a duplicate rather than stated directly. */
    private record Propagated(String supersededById, String closedFactId) {}

    private static Map<String, Closure> closuresFrom(List<SupersessionAdjudicator.Judgement> judgements) {
        Map<String, Closure> closures = new LinkedHashMap<>();
        judgements.stream()
                .filter(judgement -> judgement.verdict() == SupersessionAdjudicator.Verdict.SUPERSEDES)
                .forEach(judgement -> closures.put(
                        judgement.candidate().older().id(),
                        new Closure(
                                SupersessionDetector.closedAt(judgement.candidate()),
                                judgement.candidate().newer().id())));
        return closures;
    }

    /**
     * Closes the twins of anything that was superseded.
     *
     * <p>The same decision is often recorded twice — once in the meeting where it was
     * made and again in the chat recap. When a later decision replaces one of those
     * copies, it replaces the other too; they were never two facts. Without this the
     * untouched copy keeps {@code validTo IS NULL}, retrieval reports it as current,
     * and the answer confidently states a date that was abandoned months ago. That is
     * exactly how it first behaved: the graph held a correct three-link chain and a
     * stale duplicate sitting outside it, marked CURRENT.
     *
     * <p>Runs to a fixpoint because duplicates can chain, and takes the earliest
     * closure when a fact is reachable more than one way — the moment it stopped being
     * true does not depend on which copy we noticed first.
     */
    private static List<Propagated> propagateAcrossDuplicates(
            List<SupersessionAdjudicator.Judgement> judgements, Map<String, Closure> closures) {
        List<String[]> duplicatePairs = judgements.stream()
                .filter(judgement -> judgement.verdict() == SupersessionAdjudicator.Verdict.DUPLICATE)
                .map(judgement -> new String[] {
                    judgement.candidate().newer().id(), judgement.candidate().older().id()
                })
                .toList();

        List<Propagated> propagated = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String[] pair : duplicatePairs) {
                changed |= spread(pair[0], pair[1], closures, propagated);
                changed |= spread(pair[1], pair[0], closures, propagated);
            }
        }
        if (!propagated.isEmpty()) {
            log.info("closed {} duplicate facts whose twin was superseded", propagated.size());
        }
        return propagated;
    }

    private static boolean spread(String from, String to, Map<String, Closure> closures, List<Propagated> propagated) {
        Closure source = closures.get(from);
        if (source == null) {
            return false;
        }
        Closure existing = closures.get(to);
        if (existing != null && !source.at().isBefore(existing.at())) {
            return false;
        }
        closures.put(to, source);
        propagated.add(new Propagated(source.supersededById(), to));
        return true;
    }

    private Map<String, float[]> embed(List<ResolvedFact> facts) {
        List<ResolvedFact> withStatements =
                facts.stream().filter(fact -> fact.statement() != null).toList();
        if (withStatements.isEmpty()) {
            return Map.of();
        }
        var embeddings = embeddingModel
                .embedAll(withStatements.stream()
                        .map(fact -> TextSegment.from(fact.statement()))
                        .toList())
                .content();
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (int i = 0; i < withStatements.size(); i++) {
            vectors.put(withStatements.get(i).id(), embeddings.get(i).vector());
        }
        return vectors;
    }

    private void writePeople(Session session, List<Person> people) {
        List<Map<String, Object>> rows = people.stream()
                .map(person -> Map.<String, Object>of(
                        "id", person.id(),
                        "name", person.canonicalName(),
                        "email", nullToEmpty(person.email()),
                        "title", nullToEmpty(person.title()),
                        "aliases", person.aliases().stream().map(alias -> alias.key()).sorted().toList()))
                .toList();
        session.executeWrite(tx -> tx.run(
                        """
                        UNWIND $rows AS row
                        MERGE (p:Person {id: row.id})
                        SET p.name = row.name, p.email = row.email,
                            p.title = row.title, p.aliases = row.aliases
                        """,
                        Map.of("rows", rows))
                .consume());
    }

    private int writeEpisodes(
            Session session, List<Episode> episodes, Map<String, Map<String, MentionResolution>> speakers) {
        List<Map<String, Object>> episodeRows = new ArrayList<>();
        List<Map<String, Object>> utteranceRows = new ArrayList<>();

        for (Episode episode : episodes) {
            episodeRows.add(Map.of(
                    "id", episode.id(),
                    "system", episode.source().system().name(),
                    "kind", episode.kind().name(),
                    "title", nullToEmpty(episode.title()),
                    "occurredAt", utc(episode.occurredAt()),
                    "ingestedAt", utc(episode.ingestedAt()),
                    "permalink", nullToEmpty(episode.source().permalink()),
                    "aclGrants", List.copyOf(episode.acl().requiredGrants()),
                    "visibility", episode.acl().effectiveVisibility().name(),
                    "contentHash", episode.contentHash()));

            Map<String, MentionResolution> episodeSpeakers = speakers.getOrDefault(episode.id(), Map.of());
            for (Utterance utterance : episode.utterances()) {
                MentionResolution resolution = episodeSpeakers.get(utterance.speaker().key());
                utteranceRows.add(mapOfNullable(
                        "id", utteranceId(episode.id(), utterance.ordinal()),
                        "episodeId", episode.id(),
                        "ordinal", utterance.ordinal(),
                        "text", utterance.text(),
                        "at", utc(utterance.at()),
                        "permalink", nullToEmpty(utterance.permalink()),
                        "speakerLabel", utterance.speaker().label(),
                        "personId",
                                resolution == null || !resolution.isResolved()
                                        ? null
                                        : resolution.person().orElseThrow().id(),
                        "resolutionMethod", resolution == null ? "UNRESOLVED" : resolution.method().name()));
            }
        }

        session.executeWrite(tx -> tx.run(
                        """
                        UNWIND $rows AS row
                        MERGE (e:Episode {id: row.id})
                        SET e.system = row.system, e.kind = row.kind, e.title = row.title,
                            e.occurredAt = row.occurredAt, e.ingestedAt = row.ingestedAt,
                            e.permalink = row.permalink, e.aclGrants = row.aclGrants,
                            e.visibility = row.visibility, e.contentHash = row.contentHash
                        """,
                        Map.of("rows", episodeRows))
                .consume());

        session.executeWrite(tx -> tx.run(
                        """
                        UNWIND $rows AS row
                        MATCH (e:Episode {id: row.episodeId})
                        MERGE (u:Utterance {id: row.id})
                        SET u.ordinal = row.ordinal, u.text = row.text, u.at = row.at,
                            u.permalink = row.permalink, u.speakerLabel = row.speakerLabel,
                            u.resolutionMethod = row.resolutionMethod
                        MERGE (u)-[:PART_OF]->(e)
                        FOREACH (_ IN CASE WHEN row.personId IS NULL THEN [] ELSE [1] END |
                          MERGE (p:Person {id: row.personId})
                          MERGE (u)-[:SPOKEN_BY]->(p)
                        )
                        """,
                        Map.of("rows", utteranceRows))
                .consume());
        return utteranceRows.size();
    }

    private void writeFacts(
            Session session,
            List<ResolvedFact> facts,
            Map<String, float[]> embeddings,
            Map<String, Instant> closedAt) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ResolvedFact fact : facts) {
            Instant closed = closedAt.get(fact.id());
            float[] vector = embeddings.get(fact.id());
            rows.add(mapOfNullable(
                    "id", fact.id(),
                    "episodeId", fact.episode().id(),
                    "type", fact.fact().type().name(),
                    "statement", nullToEmpty(fact.statement()),
                    // A superseded fact says so on its face, so a query that forgets
                    // the temporal filter still shows something obviously historical.
                    "status", closed == null ? fact.fact().status().name() : FactStatus.SUPERSEDED.name(),
                    "validFrom", utc(fact.validity().validFrom()),
                    "validTo", closed == null ? null : utc(closed),
                    "ingestedAt", utc(fact.validity().ingestedAt()),
                    "occurredAt", utc(fact.fact().occurredAt()),
                    "confidence", fact.fact().confidence(),
                    "dueDate", fact.fact().dueDate() == null ? null : fact.fact().dueDate().toString(),
                    "aclGrants", List.copyOf(fact.acl().requiredGrants()),
                    "visibility", fact.acl().effectiveVisibility().name(),
                    "ontologyVersion", fact.ontologyVersion(),
                    "promptVersion", fact.promptVersion(),
                    "topics", fact.fact().topics(),
                    "embedding", vector == null ? null : toList(vector)));
        }

        session.executeWrite(tx -> tx.run(
                        """
                        UNWIND $rows AS row
                        MATCH (e:Episode {id: row.episodeId})
                        MERGE (f:Fact {id: row.id})
                        SET f.type = row.type, f.statement = row.statement, f.status = row.status,
                            f.validFrom = row.validFrom, f.validTo = row.validTo,
                            f.ingestedAt = row.ingestedAt, f.occurredAt = row.occurredAt,
                            f.confidence = row.confidence, f.aclGrants = row.aclGrants,
                            f.visibility = row.visibility, f.topics = row.topics,
                            f.ontologyVersion = row.ontologyVersion, f.promptVersion = row.promptVersion,
                            f.dueDate = CASE WHEN row.dueDate IS NULL THEN NULL ELSE date(row.dueDate) END
                        MERGE (f)-[:DERIVED_FROM]->(e)
                        WITH f, row
                        WHERE row.embedding IS NOT NULL
                        CALL db.create.setNodeVectorProperty(f, 'embedding', row.embedding)
                        """,
                        Map.of("rows", rows))
                .consume());
    }

    private int writeEvidence(Session session, List<ResolvedFact> facts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ResolvedFact fact : facts) {
            for (Evidence evidence : fact.fact().evidence()) {
                rows.add(Map.of(
                        "factId", fact.id(),
                        "utteranceId", utteranceId(fact.episode().id(), evidence.utteranceOrdinal()),
                        "span", evidence.verbatimSpan()));
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }
        session.executeWrite(tx -> tx.run(
                        """
                        UNWIND $rows AS row
                        MATCH (f:Fact {id: row.factId})
                        MATCH (u:Utterance {id: row.utteranceId})
                        MERGE (f)-[e:EVIDENCED_BY]->(u)
                        SET e.span = row.span
                        """,
                        Map.of("rows", rows))
                .consume());
        return rows.size();
    }

    private void writeOwnership(Session session, List<ResolvedFact> facts) {
        List<Map<String, Object>> owners = new ArrayList<>();
        List<Map<String, Object>> involved = new ArrayList<>();
        for (ResolvedFact fact : facts) {
            if (fact.owner() != null && fact.owner().isResolved()) {
                owners.add(Map.of(
                        "factId", fact.id(),
                        "personId", fact.owner().person().orElseThrow().id(),
                        "method", fact.owner().method().name(),
                        "confidence", fact.owner().confidence(),
                        "mention", fact.owner().mention()));
            }
            for (MentionResolution participant : fact.participants()) {
                if (participant.isResolved()) {
                    involved.add(Map.of(
                            "factId", fact.id(),
                            "personId", participant.person().orElseThrow().id(),
                            "method", participant.method().name()));
                }
            }
        }
        if (!owners.isEmpty()) {
            session.executeWrite(tx -> tx.run(
                            """
                            UNWIND $rows AS row
                            MATCH (f:Fact {id: row.factId})
                            MATCH (p:Person {id: row.personId})
                            MERGE (f)-[r:OWNED_BY]->(p)
                            SET r.method = row.method, r.confidence = row.confidence, r.mention = row.mention
                            """,
                            Map.of("rows", owners))
                    .consume());
        }
        if (!involved.isEmpty()) {
            session.executeWrite(tx -> tx.run(
                            """
                            UNWIND $rows AS row
                            MATCH (f:Fact {id: row.factId})
                            MATCH (p:Person {id: row.personId})
                            MERGE (f)-[r:INVOLVES]->(p)
                            SET r.method = row.method
                            """,
                            Map.of("rows", involved))
                    .consume());
        }
    }

    private void writeTopics(Session session, List<ResolvedFact> facts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ResolvedFact fact : facts) {
            for (String topic : fact.fact().topics()) {
                if (topic != null && !topic.isBlank()) {
                    rows.add(Map.of("factId", fact.id(), "topic", topic.strip().toLowerCase(java.util.Locale.ROOT)));
                }
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        session.executeWrite(tx -> tx.run(
                        """
                        UNWIND $rows AS row
                        MATCH (f:Fact {id: row.factId})
                        MERGE (t:Topic {name: row.topic})
                        MERGE (f)-[:ABOUT]->(t)
                        """,
                        Map.of("rows", rows))
                .consume());
    }

    /**
     * Writes the adjudicated relationships.
     *
     * <p>Duplicates get their own edge rather than being merged or dropped. The same
     * decision recorded in a meeting and again in a Slack recap is two independent
     * pieces of evidence for one fact, and retrieval will want to collapse them in the
     * answer while still being able to cite both.
     */
    private void writeJudgements(Session session, List<SupersessionAdjudicator.Judgement> judgements) {
        writeRelationship(
                session,
                judgements,
                SupersessionAdjudicator.Verdict.SUPERSEDES,
                """
                UNWIND $rows AS row
                MATCH (newer:Fact {id: row.newerId})
                MATCH (older:Fact {id: row.olderId})
                MERGE (newer)-[r:SUPERSEDES]->(older)
                SET r.similarity = row.similarity, r.rationale = row.rationale
                """);
        writeRelationship(
                session,
                judgements,
                SupersessionAdjudicator.Verdict.DUPLICATE,
                """
                UNWIND $rows AS row
                MATCH (newer:Fact {id: row.newerId})
                MATCH (older:Fact {id: row.olderId})
                MERGE (newer)-[r:DUPLICATE_OF]->(older)
                SET r.similarity = row.similarity, r.rationale = row.rationale
                """);
    }

    /**
     * Links a superseding fact to the duplicates it also replaced.
     *
     * <p>Written as a real {@code SUPERSEDES} edge so history is walkable from any
     * copy, and so retrieval needs no special case for duplicates.
     */
    private void writePropagated(Session session, List<Propagated> propagated) {
        if (propagated.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = propagated.stream()
                .map(entry -> Map.<String, Object>of(
                        "newerId", entry.supersededById(), "olderId", entry.closedFactId()))
                .distinct()
                .toList();
        session.executeWrite(tx -> tx.run(
                        """
                        UNWIND $rows AS row
                        MATCH (newer:Fact {id: row.newerId})
                        MATCH (older:Fact {id: row.olderId})
                        WHERE newer <> older
                        MERGE (newer)-[r:SUPERSEDES]->(older)
                        SET r.viaDuplicate = true
                        """,
                        Map.of("rows", rows))
                .consume());
    }

    private void writeRelationship(
            Session session,
            List<SupersessionAdjudicator.Judgement> judgements,
            SupersessionAdjudicator.Verdict verdict,
            String cypher) {
        List<Map<String, Object>> rows = judgements.stream()
                .filter(judgement -> judgement.verdict() == verdict)
                .map(judgement -> Map.<String, Object>of(
                        "newerId", judgement.candidate().newer().id(),
                        "olderId", judgement.candidate().older().id(),
                        "similarity", judgement.candidate().similarity(),
                        "rationale", judgement.rationale() == null ? "" : judgement.rationale()))
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        session.executeWrite(tx -> tx.run(cypher, Map.of("rows", rows)).consume());
    }

    static String utteranceId(String episodeId, int ordinal) {
        return episodeId + "#" + ordinal;
    }

    private static ZonedDateTime utc(Instant instant) {
        return instant.atZone(ZoneOffset.UTC);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    /** {@code Map.of} rejects null values, and several of these columns are legitimately absent. */
    private static Map<String, Object> mapOfNullable(Object... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return row;
    }
}
