package com.hhovhann.hivemind.graph.export;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes the graph out as an Obsidian vault.
 *
 * <p>Neo4j is the store; this is a view for humans. The two are not competitors and
 * neither replaces the other — Obsidian has one untyped link and no query language,
 * so it cannot answer "which decisions were reversed after March and who owns the
 * follow-up", while a Cypher result set is a poor thing to browse on a Sunday. Facts
 * become notes, typed edges become wikilinks, and Obsidian's graph view then shows
 * the clustering the corpus actually has.
 *
 * <p>A superseded decision keeps its note and links forward to what replaced it, so
 * the history is walkable by clicking rather than only by querying. That is the
 * property most knowledge bases lose, and it is the one people actually want when
 * they ask why something changed.
 */
@Service
public class ObsidianExporter {

    private static final Logger log = LoggerFactory.getLogger(ObsidianExporter.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Driver driver;

    public ObsidianExporter(Driver driver) {
        this.driver = driver;
    }

    public ExportReport export(Path vault) {
        try (Session session = driver.session()) {
            prepare(vault);
            int facts = writeFacts(session, vault);
            int people = writePeople(session, vault);
            int episodes = writeEpisodes(session, vault);
            int topics = writeTopics(session, vault);
            writeIndex(session, vault, facts, people, episodes, topics);
            log.info("vault written to {}", vault.toAbsolutePath());
            return new ExportReport(vault, facts, people, episodes, topics);
        }
    }

    public record ExportReport(Path vault, int facts, int people, int episodes, int topics) {
        public int notes() {
            return facts + people + episodes + topics + 1;
        }
    }

    private void prepare(Path vault) {
        try {
            for (String folder : List.of("Facts", "People", "Episodes", "Topics")) {
                Files.createDirectories(vault.resolve(folder));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare vault at " + vault, e);
        }
    }

    private int writeFacts(Session session, Path vault) {
        List<Record> rows = session.run(
                        """
                        MATCH (f:Fact)-[:DERIVED_FROM]->(e:Episode)
                        OPTIONAL MATCH (f)-[:OWNED_BY]->(owner:Person)
                        OPTIONAL MATCH (f)-[ev:EVIDENCED_BY]->(u:Utterance)
                        OPTIONAL MATCH (f)-[:SUPERSEDES]->(older:Fact)
                        OPTIONAL MATCH (newer:Fact)-[:SUPERSEDES]->(f)
                        OPTIONAL MATCH (f)-[:ABOUT]->(t:Topic)
                        RETURN f.id AS id, f.type AS type, f.statement AS statement,
                               f.status AS status, f.occurredAt AS occurredAt,
                               f.validTo AS validTo, f.visibility AS visibility,
                               f.confidence AS confidence,
                               e.title AS episodeTitle, e.system AS episodeSystem,
                               owner.name AS owner,
                               collect(DISTINCT t.name) AS topics,
                               collect(DISTINCT CASE WHEN ev IS NULL THEN NULL ELSE
                                 {span: ev.span, speaker: u.speakerLabel, permalink: u.permalink} END) AS evidence,
                               collect(DISTINCT CASE WHEN older IS NULL THEN NULL ELSE older.statement END) AS replaced,
                               collect(DISTINCT CASE WHEN newer IS NULL THEN NULL ELSE newer.statement END) AS replacedBy
                        """)
                .list();

        for (Record row : rows) {
            String statement = row.get("statement").asString("(no statement)");
            boolean current = row.get("validTo").isNull();
            StringBuilder note = new StringBuilder();

            note.append("---\n")
                    .append("type: ")
                    .append(row.get("type").asString("").toLowerCase(Locale.ROOT))
                    .append('\n')
                    .append("status: ")
                    .append(row.get("status").asString(""))
                    .append('\n')
                    .append("current: ")
                    .append(current)
                    .append('\n')
                    .append("decided: ")
                    .append(day(row.get("occurredAt")))
                    .append('\n')
                    .append("visibility: ")
                    .append(row.get("visibility").asString("PUBLIC"))
                    .append("\ntags: [fact, ")
                    .append(row.get("type").asString("").toLowerCase(Locale.ROOT))
                    .append(current ? ", current]" : ", superseded]")
                    .append("\n---\n\n");

            note.append("# ").append(statement).append("\n\n");
            if (!current) {
                note.append("> [!warning] No longer true\n> Superseded on ")
                        .append(day(row.get("validTo")))
                        .append(".\n\n");
            }

            String owner = row.get("owner").asString(null);
            if (owner != null) {
                note.append("**Owner** ").append(link(owner)).append("\n");
            }
            note.append("**Source** ").append(link(row.get("episodeTitle").asString("?"))).append(" (")
                    .append(row.get("episodeSystem").asString("?"))
                    .append(")\n\n");

            appendList(note, "Replaced by", row.get("replacedBy"));
            appendList(note, "This replaced", row.get("replaced"));

            List<String> topics = strings(row.get("topics"));
            if (!topics.isEmpty()) {
                note.append("**Topics** ")
                        .append(topics.stream().map(ObsidianExporter::link).reduce((a, b) -> a + " " + b).orElse(""))
                        .append("\n\n");
            }

            List<Value> evidence = new ArrayList<>();
            row.get("evidence").values().forEach(value -> {
                if (!value.isNull()) {
                    evidence.add(value);
                }
            });
            if (!evidence.isEmpty()) {
                note.append("## Evidence\n\n");
                for (Value span : evidence) {
                    note.append("> ").append(span.get("span").asString("")).append('\n');
                    note.append("> — ").append(span.get("speaker").asString("?"));
                    String permalink = span.get("permalink").asString(null);
                    if (permalink != null && !permalink.isBlank()) {
                        note.append(" · [source](").append(permalink).append(')');
                    }
                    note.append("\n\n");
                }
            }
            write(vault.resolve("Facts").resolve(slug(statement) + ".md"), note.toString());
        }
        return rows.size();
    }

    private int writePeople(Session session, Path vault) {
        List<Record> rows = session.run(
                        """
                        MATCH (p:Person)
                        OPTIONAL MATCH (p)<-[:OWNED_BY]-(f:Fact)
                        RETURN p.name AS name, p.email AS email, p.title AS title,
                               p.aliases AS aliases,
                               collect(DISTINCT CASE WHEN f IS NULL THEN NULL ELSE
                                 {statement: f.statement, current: f.validTo IS NULL, type: f.type} END) AS owned
                        """)
                .list();

        for (Record row : rows) {
            String name = row.get("name").asString("?");
            StringBuilder note = new StringBuilder("---\ntype: person\ntags: [person]\n---\n\n");
            note.append("# ").append(name).append("\n\n");
            String title = row.get("title").asString(null);
            if (title != null && !title.isBlank()) {
                note.append('*').append(title).append("*\n\n");
            }
            String email = row.get("email").asString(null);
            if (email != null && !email.isBlank()) {
                note.append("**Email** ").append(email).append("\n\n");
            }

            List<String> aliases = strings(row.get("aliases"));
            if (!aliases.isEmpty()) {
                note.append("**Known as** ").append(String.join(", ", aliases)).append("\n\n");
            }

            List<Value> owned = new ArrayList<>();
            row.get("owned").values().forEach(value -> {
                if (!value.isNull()) {
                    owned.add(value);
                }
            });
            if (!owned.isEmpty()) {
                note.append("## Owns\n\n");
                owned.stream()
                        .sorted(Comparator.comparing(value -> !value.get("current").asBoolean(true)))
                        .forEach(value -> note.append("- ")
                                .append(link(value.get("statement").asString("")))
                                .append(value.get("current").asBoolean(true) ? "" : " *(superseded)*")
                                .append('\n'));
                note.append('\n');
            }
            write(vault.resolve("People").resolve(slug(name) + ".md"), note.toString());
        }
        return rows.size();
    }

    private int writeEpisodes(Session session, Path vault) {
        List<Record> rows = session.run(
                        """
                        MATCH (e:Episode)
                        OPTIONAL MATCH (u:Utterance)-[:PART_OF]->(e)
                        OPTIONAL MATCH (f:Fact)-[:DERIVED_FROM]->(e)
                        OPTIONAL MATCH (u)-[:SPOKEN_BY]->(p:Person)
                        RETURN e.title AS title, e.system AS system, e.occurredAt AS occurredAt,
                               e.visibility AS visibility, e.permalink AS permalink,
                               count(DISTINCT u) AS utterances,
                               collect(DISTINCT CASE WHEN f IS NULL THEN NULL ELSE f.statement END) AS facts,
                               collect(DISTINCT CASE WHEN p IS NULL THEN NULL ELSE p.name END) AS people
                        """)
                .list();

        for (Record row : rows) {
            String title = row.get("title").asString("?");
            StringBuilder note = new StringBuilder("---\ntype: episode\nsource: ")
                    .append(row.get("system").asString("?"))
                    .append("\noccurred: ")
                    .append(day(row.get("occurredAt")))
                    .append("\nvisibility: ")
                    .append(row.get("visibility").asString("PUBLIC"))
                    .append("\ntags: [episode]\n---\n\n");
            note.append("# ").append(title).append("\n\n");
            note.append(row.get("utterances").asInt(0)).append(" contributions");
            String permalink = row.get("permalink").asString(null);
            if (permalink != null && !permalink.isBlank()) {
                note.append(" · [open](").append(permalink).append(')');
            }
            note.append("\n\n");

            appendWikiList(note, "People", strings(row.get("people")));
            appendWikiList(note, "Facts established here", strings(row.get("facts")));
            write(vault.resolve("Episodes").resolve(slug(title) + ".md"), note.toString());
        }
        return rows.size();
    }

    private int writeTopics(Session session, Path vault) {
        List<Record> rows = session.run(
                        """
                        MATCH (t:Topic)<-[:ABOUT]-(f:Fact)
                        RETURN t.name AS name,
                               collect(DISTINCT f.statement) AS facts
                        """)
                .list();

        for (Record row : rows) {
            String name = row.get("name").asString("?");
            StringBuilder note = new StringBuilder("---\ntype: topic\ntags: [topic]\n---\n\n");
            note.append("# ").append(name).append("\n\n");
            appendWikiList(note, "Facts", strings(row.get("facts")));
            write(vault.resolve("Topics").resolve(slug(name) + ".md"), note.toString());
        }
        return rows.size();
    }

    private void writeIndex(Session session, Path vault, int facts, int people, int episodes, int topics) {
        List<Record> chains = session.run(
                        """
                        MATCH path=(newest:Fact)-[:SUPERSEDES*]->(oldest:Fact)
                        WHERE NOT ()-[:SUPERSEDES]->(newest)
                        WITH newest, path ORDER BY length(path) DESC
                        WITH newest, head(collect(path)) AS longest
                        RETURN newest.statement AS statement, length(longest) AS hops,
                               [f IN nodes(longest) | f.statement] AS chain
                        ORDER BY hops DESC LIMIT 5
                        """)
                .list();

        StringBuilder note = new StringBuilder("---\ntags: [index]\n---\n\n# Meridian Media\n\n");
        note.append("Exported from the Hive Mind graph — ")
                .append(facts)
                .append(" facts, ")
                .append(people)
                .append(" people, ")
                .append(episodes)
                .append(" episodes, ")
                .append(topics)
                .append(" topics.\n\n")
                .append("Open the graph view to see how they cluster. Superseded facts keep their\n")
                .append("notes and link forward, so a decision's history is walkable by clicking.\n\n");

        if (!chains.isEmpty()) {
            note.append("## Decisions that changed\n\n");
            for (Record chain : chains) {
                note.append("- **").append(link(chain.get("statement").asString(""))).append("**");
                note.append(" — revised ").append(chain.get("hops").asInt(0)).append(" time(s)\n");
                List<String> steps = strings(chain.get("chain"));
                for (int i = 1; i < steps.size(); i++) {
                    note.append("  - replaced ").append(link(steps.get(i))).append("\n");
                }
            }
            note.append('\n');
        }
        note.append("## Folders\n\n- `Facts/` — one note per fact\n- `People/`\n- `Episodes/`\n- `Topics/`\n");
        write(vault.resolve("Meridian Media.md"), note.toString());
    }

    // ------------------------------------------------------------------ utilities

    private static void appendList(StringBuilder note, String heading, Value collected) {
        List<String> values = strings(collected);
        if (values.isEmpty()) {
            return;
        }
        note.append("**").append(heading).append("** ");
        note.append(values.stream().map(ObsidianExporter::link).reduce((a, b) -> a + ", " + b).orElse(""));
        note.append("\n\n");
    }

    private static void appendWikiList(StringBuilder note, String heading, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        note.append("## ").append(heading).append("\n\n");
        values.forEach(value -> note.append("- ").append(link(value)).append("\n"));
        note.append('\n');
    }

    private static List<String> strings(Value collected) {
        List<String> values = new ArrayList<>();
        collected.values().forEach(value -> {
            if (!value.isNull() && !value.asString().isBlank()) {
                values.add(value.asString());
            }
        });
        return values;
    }

    private static String day(Value value) {
        if (value == null || value.isNull()) {
            return "";
        }
        ZonedDateTime moment = value.asZonedDateTime();
        return DAY.format(moment);
    }

    /**
     * Obsidian resolves {@code [[a wikilink]]} by note filename, so a note's file name
     * has to be the statement itself — trimmed of the characters a filesystem refuses,
     * and nothing else.
     */
    /** A wikilink whose target matches the note's filename. */
    static String link(String text) {
        return "[[" + slug(text) + "]]";
    }

    static String slug(String text) {
        String cleaned = text.replaceAll("[\\\\/:*?\"<>|#^\\[\\]]", "").replaceAll("\\s+", " ").strip();
        if (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.length() <= 100 ? cleaned : cleaned.substring(0, 100).strip();
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /** Kept for callers that want a stable ordering of the folders. */
    public static Map<String, String> folders() {
        Map<String, String> folders = new LinkedHashMap<>();
        folders.put("Facts", "one note per fact, superseded ones included");
        folders.put("People", "who owns what");
        folders.put("Episodes", "the conversations facts came from");
        folders.put("Topics", "cross-cutting subjects");
        return folders;
    }
}
