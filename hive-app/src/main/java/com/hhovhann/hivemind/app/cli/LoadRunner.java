package com.hhovhann.hivemind.app.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.core.entity.MentionResolution;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.ontology.ExtractedFact;
import com.hhovhann.hivemind.core.ontology.Ontology;
import com.hhovhann.hivemind.extract.ExtractionResult;
import com.hhovhann.hivemind.extract.resolve.EntityResolver;
import com.hhovhann.hivemind.graph.GraphLoadReport;
import com.hhovhann.hivemind.graph.GraphSchema;
import com.hhovhann.hivemind.graph.KnowledgeGraphWriter;
import com.hhovhann.hivemind.graph.ResolvedFact;
import com.hhovhann.hivemind.graph.SupersessionAdjudicator;
import com.hhovhann.hivemind.graph.SupersessionDetector;
import com.hhovhann.hivemind.ingest.EpisodeIngestService;
import com.hhovhann.hivemind.ingest.directory.Directory;
import com.hhovhann.hivemind.ingest.directory.DirectoryReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code ./gradlew :hive-app:bootRun --args='load'} — resolves identities and writes
 * a saved extraction run into Neo4j.
 *
 * <p>Reads facts from disk rather than re-extracting. A full pass costs minutes and
 * nothing about it changes when the graph schema does, so coupling the two would make
 * every graph experiment cost an extraction.
 */
@Component
public class LoadRunner implements ApplicationRunner {

    private static final Path EVAL_RUNS = Path.of("eval-runs");

    private final EpisodeIngestService ingest;
    private final DirectoryReader directoryReader;
    private final EntityResolver resolver;
    private final KnowledgeGraphWriter writer;
    private final GraphSchema schema;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext context;

    public LoadRunner(
            EpisodeIngestService ingest,
            DirectoryReader directoryReader,
            EntityResolver resolver,
            KnowledgeGraphWriter writer,
            GraphSchema schema,
            ObjectMapper objectMapper,
            ConfigurableApplicationContext context) {
        this.ingest = ingest;
        this.directoryReader = directoryReader;
        this.resolver = resolver;
        this.writer = writer;
        this.schema = schema;
        this.objectMapper = objectMapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!HiveCommand.LOAD.present(args.getSourceArgs())) {
            return;
        }
        Path runFile = HiveCommand.option(args.getSourceArgs(), "run")
                .map(directory -> Path.of(directory).resolve("extraction.json"))
                .orElseGet(LoadRunner::mostRecentRun);
        if (!Files.exists(runFile)) {
            System.out.printf("%n  No extraction run at %s — run 'extract' first.%n%n", runFile);
            System.exit(SpringApplication.exit(context, () -> 1));
            return;
        }

        if (java.util.Arrays.asList(args.getSourceArgs()).contains("--fresh")) {
            schema.clear();
        }

        List<Episode> episodes = ingest.readAll();
        Map<String, Episode> byId = new LinkedHashMap<>();
        episodes.forEach(episode -> byId.put(episode.id(), episode));
        List<ExtractionResult> results = read(runFile);
        Directory directory = directoryReader.read();

        System.out.printf("%n  Resolving identities across %d episodes%n", episodes.size());
        Map<String, Map<String, MentionResolution>> speakers = new LinkedHashMap<>();
        for (Episode episode : episodes) {
            speakers.put(episode.id(), resolver.resolveSpeakers(episode, directory));
        }
        reportSpeakerResolution(speakers);

        List<ResolvedFact> facts = new ArrayList<>();
        List<MentionResolution> allMentions = new ArrayList<>();
        for (ExtractionResult result : results) {
            Episode episode = byId.get(result.episodeId());
            if (episode == null) {
                continue;
            }
            Map<String, MentionResolution> episodeSpeakers = speakers.getOrDefault(episode.id(), Map.of());
            for (ExtractedFact fact : result.accepted()) {
                MentionResolution owner = fact.owner()
                        .map(mention -> resolver.resolveMention(mention, episode, directory, episodeSpeakers))
                        .orElseGet(() -> MentionResolution.unresolved(null, "no owner stated"));
                List<MentionResolution> participants = fact.participantMentions().stream()
                        .map(mention -> resolver.resolveMention(mention, episode, directory, episodeSpeakers))
                        .toList();
                if (fact.owner().isPresent()) {
                    allMentions.add(owner);
                }
                allMentions.addAll(participants);
                facts.add(ResolvedFact.of(
                        fact, episode, owner, participants, Ontology.VERSION, result.promptSignature()));
            }
        }

        GraphLoadReport report = writer.write(directory.people(), episodes, speakers, facts);
        reportLoad(report, allMentions);
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private void reportSpeakerResolution(Map<String, Map<String, MentionResolution>> speakers) {
        Map<String, Integer> byMethod = new TreeMap<>();
        List<MentionResolution> adjudicated = new ArrayList<>();
        for (Map<String, MentionResolution> episode : speakers.values()) {
            for (MentionResolution resolution : episode.values()) {
                byMethod.merge(resolution.method().name(), 1, Integer::sum);
                if (resolution.method() == MentionResolution.Method.LLM_ADJUDICATED) {
                    adjudicated.add(resolution);
                }
            }
        }
        System.out.printf("  speakers by method: %s%n", byMethod);
        for (MentionResolution resolution : adjudicated) {
            System.out.printf(
                    "    %-12s -> %s%n",
                    resolution.mention(),
                    resolution.person().map(person -> person.canonicalName()).orElse("?"));
        }
        speakers.forEach((episodeId, episode) -> episode.values().stream()
                .filter(resolution -> !resolution.isResolved())
                .forEach(resolution -> System.out.printf(
                        "    unresolved: %-12s in %-28s (%s)%n",
                        resolution.mention(), episodeId, resolution.rationale())));
    }

    private void reportLoad(GraphLoadReport report, List<MentionResolution> mentions) {
        Map<String, Integer> byMethod = new TreeMap<>();
        mentions.forEach(mention -> byMethod.merge(mention.method().name(), 1, Integer::sum));

        System.out.printf("%n  %s%n", "-".repeat(72));
        System.out.printf(
                "  %d people, %d episodes, %d utterances, %d facts%n",
                report.people(), report.episodes(), report.utterances(), report.facts());
        System.out.printf(
                "  %d evidence edges, %d owners resolved, %d owners unresolved%n",
                report.evidenceEdges(), report.ownedEdges(), report.unresolvedOwners());
        System.out.printf("  fact mentions by method: %s%n", byMethod);
        System.out.printf(
                "  deterministic share: %.0f%%%n", 100 * EntityResolver.deterministicShare(mentions));

        System.out.printf(
                "%n  %d similar pairs adjudicated: %d revisions, %d duplicates, %d unrelated%n",
                report.judgements().size(),
                report.supersessionCount(),
                report.duplicateCount(),
                report.withVerdict(SupersessionAdjudicator.Verdict.UNRELATED).size());

        System.out.printf("%n  SUPERSESSION CHAINS%n");
        report.withVerdict(SupersessionAdjudicator.Verdict.SUPERSEDES).stream()
                .sorted(Comparator.comparing(judgement -> judgement.candidate().newer().fact().occurredAt()))
                .forEach(judgement -> {
                    var chain = judgement.candidate();
                    System.out.printf(
                            "    %s%n      replaces  %s%n      closed %s — %s%n",
                            truncate(chain.newer().statement(), 62),
                            truncate(chain.older().statement(), 60),
                            SupersessionDetector.closedAt(chain).toString().substring(0, 10),
                            truncate(judgement.rationale(), 58));
                });
        System.out.printf("  %s%n%n", "-".repeat(72));
        System.out.printf("  Browse it at http://localhost:7474 — try:%n");
        System.out.printf("    MATCH p=(:Fact)-[:SUPERSEDES*]->(:Fact) RETURN p%n%n");
    }

    private static Path mostRecentRun() {
        if (!Files.isDirectory(EVAL_RUNS)) {
            return EVAL_RUNS.resolve("none").resolve("extraction.json");
        }
        try (Stream<Path> runs = Files.list(EVAL_RUNS)) {
            return runs.filter(Files::isDirectory)
                    .max(Comparator.comparing(Path::getFileName))
                    .orElse(EVAL_RUNS.resolve("none"))
                    .resolve("extraction.json");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + EVAL_RUNS, e);
        }
    }

    private List<ExtractionResult> read(Path runFile) {
        try {
            return objectMapper.readValue(runFile.toFile(), new TypeReference<List<ExtractionResult>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read extraction run " + runFile, e);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "(no statement)";
        }
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
