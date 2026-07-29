package com.hhovhann.hivemind.app.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.eval.ExtractionScore;
import com.hhovhann.hivemind.eval.ExtractionScorer;
import com.hhovhann.hivemind.eval.GoldFact;
import com.hhovhann.hivemind.extract.ExtractionResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code ./gradlew :hive-app:bootRun --args='score'} — scores a saved extraction run
 * against the answer key without calling the model again.
 *
 * <p>Separated from {@code extract} because a full pass costs minutes and the scorer
 * changes far more often than the extraction does. Tuning a matching threshold should
 * not mean re-running the model, and comparing two prompt versions means scoring two
 * runs that already exist.
 *
 * <p>Defaults to the most recent run; {@code --run=<dir>} picks another.
 */
@Component
public class ScoreRunner implements ApplicationRunner {

    private static final Path EVAL_RUNS = Path.of("eval-runs");

    private final ExtractionScorer scorer;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext context;

    public ScoreRunner(ExtractionScorer scorer, ObjectMapper objectMapper, ConfigurableApplicationContext context) {
        this.scorer = scorer;
        this.objectMapper = objectMapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!HiveCommand.SCORE.present(args.getSourceArgs())) {
            return;
        }
        Path runFile = HiveCommand.option(args.getSourceArgs(), "run")
                .map(directory -> Path.of(directory).resolve("extraction.json"))
                .orElseGet(ScoreRunner::mostRecentRun);

        if (!Files.exists(runFile)) {
            System.out.printf("%n  No extraction run at %s — run 'extract' first.%n%n", runFile);
            System.exit(SpringApplication.exit(context, () -> 1));
            return;
        }

        List<ExtractionResult> results = read(runFile);
        System.out.printf("%n  Scoring %s — %d episodes%n%n", runFile, results.size());

        scorer.score(results).ifPresentOrElse(
                score -> {
                    report(score, results);
                    System.exit(SpringApplication.exit(context, () -> 0));
                },
                () -> {
                    System.out.printf("  This corpus ships no answer key, so there is nothing to score.%n%n");
                    System.exit(SpringApplication.exit(context, () -> 1));
                });
    }

    private void report(ExtractionScore score, List<ExtractionResult> results) {
        Map<String, Integer> distribution = new TreeMap<>();
        results.forEach(result ->
                result.accepted().forEach(fact -> distribution.merge(fact.type().name(), 1, Integer::sum)));
        int kept = distribution.values().stream().mapToInt(Integer::intValue).sum();

        System.out.printf("  RECALL against the answer key%n");
        System.out.printf(
                "  strict  %d/%d (%.0f%%)   found and typed correctly%n",
                score.matched(), score.goldTotal(), 100 * score.strictRecall());
        System.out.printf(
                "  content %d/%d (%.0f%%)   found at all — the gap is what type confusion costs%n",
                score.matched() + score.mistyped().size(), score.goldTotal(), 100 * score.contentRecall());
        score.byType().forEach((type, recall) -> System.out.printf(
                "    %-14s %d/%d%n", type.toLowerCase(Locale.ROOT), recall.matched(), recall.gold()));

        if (!score.mistyped().isEmpty()) {
            System.out.printf("%n  FOUND BUT MISTYPED — fix in the prompt, not in retrieval%n");
            for (ExtractionScore.Match match : score.mistyped()) {
                System.out.printf(
                        "    %-4s want %-13s got %-13s  %s%n",
                        match.gold().id(),
                        match.gold().type(),
                        match.found().type(),
                        truncate(match.found().statement(), 44));
            }
        }

        if (!score.missed().isEmpty()) {
            System.out.printf("%n  MISSED ENTIRELY%n");
            for (GoldFact miss : score.missed()) {
                System.out.printf("    %-4s [%-13s] %s%n", miss.id(), miss.type(), truncate(miss.statement(), 62));
            }
        }

        System.out.printf("%n  GROUNDING%n");
        System.out.printf(
                "  %d of %d proposed facts quoted something that was not there (%.0f%%)%n",
                score.groundingRejections(), score.proposed(), 100 * score.groundingRejectRate());

        System.out.printf("%n  WHAT WAS EXTRACTED  (%d facts, not all of them in the key)%n", kept);
        distribution.forEach((type, count) -> System.out.printf(
                "    %-14s %3d  %5.1f%%%n", type.toLowerCase(Locale.ROOT), count, 100.0 * count / kept));

        System.out.printf(
                "%n  Recall is measured; precision is not. The key lists what must be found, "
                        + "not everything%n  the corpus contains, so facts absent from it are "
                        + "not necessarily wrong.%n%n");
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
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
