package com.hhovhann.hivemind.app.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.ontology.ExtractedFact;
import com.hhovhann.hivemind.eval.ExtractionScore;
import com.hhovhann.hivemind.eval.ExtractionScorer;
import com.hhovhann.hivemind.eval.GoldFact;
import com.hhovhann.hivemind.extract.ExtractionResult;
import com.hhovhann.hivemind.extract.FactExtractor;
import com.hhovhann.hivemind.ingest.EpisodeIngestService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code ./gradlew :hive-app:bootRun --args='extract'} — runs extraction over the
 * whole corpus and reports what came out, including what was thrown away.
 *
 * <p>Accepts {@code --limit=N} to try a handful of episodes without waiting for a
 * full pass, which is how you iterate on a prompt without burning twenty minutes per
 * edit.
 */
@Component
public class ExtractRunner implements ApplicationRunner {

    private static final DateTimeFormatter RUN_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    private final EpisodeIngestService ingest;
    private final FactExtractor extractor;
    private final ExtractionScorer scorer;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext context;

    public ExtractRunner(
            EpisodeIngestService ingest,
            FactExtractor extractor,
            ExtractionScorer scorer,
            ObjectMapper objectMapper,
            ConfigurableApplicationContext context) {
        this.ingest = ingest;
        this.extractor = extractor;
        this.scorer = scorer;
        this.objectMapper = objectMapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!HiveCommand.EXTRACT.present(args.getSourceArgs())) {
            return;
        }
        int limit = HiveCommand.option(args.getSourceArgs(), "limit")
                .map(Integer::parseInt)
                .orElse(Integer.MAX_VALUE);

        List<Episode> episodes = ingest.readAll().stream().limit(limit).toList();
        System.out.printf("%n  Extracting from %d episodes%n%n", episodes.size());

        List<ExtractionResult> results = new ArrayList<>();
        Instant startedAt = Instant.now();
        for (int i = 0; i < episodes.size(); i++) {
            Episode episode = episodes.get(i);
            ExtractionResult result = extractor.extract(episode);
            results.add(result);
            System.out.printf(
                    "  [%2d/%2d] %-11s %-52s %2d kept  %2d dropped  %5dms%n",
                    i + 1,
                    episodes.size(),
                    episode.source().system(),
                    truncate(episode.title(), 52),
                    result.accepted().size(),
                    result.rejected().size(),
                    result.elapsed().toMillis());
        }

        summarise(results, Duration.between(startedAt, Instant.now()));
        // Only meaningful over a full pass — a --limit run has not seen the episodes
        // most of the answer key lives in, so its recall would be noise.
        if (limit == Integer.MAX_VALUE) {
            scorer.score(results).ifPresent(this::reportScore);
        }
        Path output = write(results);
        System.out.printf("  Full output: %s%n%n", output);
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private void summarise(List<ExtractionResult> results, Duration wallClock) {
        int accepted = results.stream().mapToInt(result -> result.accepted().size()).sum();
        int rejected = results.stream().mapToInt(result -> result.rejected().size()).sum();
        int warnings = results.stream().mapToInt(result -> result.warnings().size()).sum();
        int inputTokens = results.stream().mapToInt(ExtractionResult::inputTokens).sum();
        int outputTokens = results.stream().mapToInt(ExtractionResult::outputTokens).sum();

        Map<String, Integer> byType = new TreeMap<>();
        for (ExtractionResult result : results) {
            for (ExtractedFact fact : result.accepted()) {
                byType.merge(fact.type().name(), 1, Integer::sum);
            }
        }

        Map<String, Integer> rejectionReasons = new LinkedHashMap<>();
        for (ExtractionResult result : results) {
            for (ExtractionResult.RejectedFact drop : result.rejected()) {
                rejectionReasons.merge(classify(drop.reason()), 1, Integer::sum);
            }
        }

        System.out.printf("%n  %s%n", "-".repeat(72));
        System.out.printf("  %d facts kept, %d dropped, %d warnings%n", accepted, rejected, warnings);
        if (accepted + rejected > 0) {
            System.out.printf("  acceptance rate: %.0f%%%n", 100.0 * accepted / (accepted + rejected));
        }
        System.out.printf("  by type: %s%n", byType.isEmpty() ? "none" : byType);
        if (!rejectionReasons.isEmpty()) {
            System.out.printf("  dropped because: %s%n", rejectionReasons);
        }
        System.out.printf(
                "  tokens: %d in, %d out%n  wall clock: %ds%n",
                inputTokens, outputTokens, wallClock.toSeconds());
        System.out.printf("  %s%n%n", "-".repeat(72));
    }

    private void reportScore(ExtractionScore score) {
        System.out.printf("  Against the gold set%n");
        System.out.printf(
                "  recall: %.0f%% strict (%d/%d), %.0f%% content (%d/%d)%n",
                100 * score.strictRecall(),
                score.matched(),
                score.goldTotal(),
                100 * score.contentRecall(),
                score.matched() + score.mistyped().size(),
                score.goldTotal());
        System.out.printf(
                "  fabricated quotes: %d of %d proposed (%.0f%%)%n",
                score.groundingRejections(), score.proposed(), 100 * score.groundingRejectRate());
        for (ExtractionScore.Match match : score.mistyped()) {
            System.out.printf(
                    "  mistyped: %s wanted %s, got %s%n",
                    match.gold().id(), match.gold().type(), match.found().type());
        }
        for (GoldFact miss : score.missed()) {
            System.out.printf("  missed:   %-4s %s%n", miss.id(), truncate(miss.statement(), 60));
        }
        System.out.printf("  Run 'score' for detail. %s%n%n", "-".repeat(40));
    }

    /** Groups rejection messages so the histogram shows causes, not individual quotes. */
    private static String classify(String reason) {
        if (reason.contains("ungrounded")) {
            return "ungrounded quote";
        }
        if (reason.contains("requires")) {
            return "missing required field";
        }
        if (reason.contains("SUPERSEDED")) {
            return "illegal status";
        }
        if (reason.contains("too short")) {
            return "statement too short";
        }
        return reason.length() > 40 ? reason.substring(0, 40) : reason;
    }

    private Path write(List<ExtractionResult> results) {
        Path directory = Path.of("eval-runs", RUN_STAMP.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)));
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("extraction.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), results);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write extraction output to " + directory, e);
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
