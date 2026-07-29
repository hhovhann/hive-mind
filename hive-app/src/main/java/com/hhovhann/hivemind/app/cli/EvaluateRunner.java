package com.hhovhann.hivemind.app.cli;

import com.hhovhann.hivemind.eval.AnswerScore;
import com.hhovhann.hivemind.eval.AnswerScorer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code ./gradlew :hive-app:bootRun --args='evaluate'} — asks every gold question
 * through the real retrieval path and grades the answers.
 *
 * <p>Exits non-zero when anything is forbidden or unsupported, so a leak fails a
 * build rather than being noticed by whoever reads the output. That distinction is
 * the point of having the harness at all: spot-checking three questions by hand
 * finds the failures you thought to look for.
 */
@Component
public class EvaluateRunner implements ApplicationRunner {

    private final AnswerScorer scorer;
    private final ConfigurableApplicationContext context;

    public EvaluateRunner(AnswerScorer scorer, ConfigurableApplicationContext context) {
        this.scorer = scorer;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!HiveCommand.EVALUATE.present(args.getSourceArgs())) {
            return;
        }
        scorer.score().ifPresentOrElse(
                score -> {
                    report(score);
                    boolean serious = score.count(AnswerScore.Verdict.FORBIDDEN)
                                    + score.count(AnswerScore.Verdict.UNSUPPORTED)
                            > 0;
                    System.exit(SpringApplication.exit(context, () -> serious ? 1 : 0));
                },
                () -> {
                    System.out.printf("%n  This corpus ships no gold questions.%n%n");
                    System.exit(SpringApplication.exit(context, () -> 1));
                });
    }

    private void report(AnswerScore score) {
        System.out.printf("%n  Answering the gold questions%n  %s%n", "-".repeat(76));
        for (AnswerScore.QuestionResult result : score.results()) {
            System.out.printf(
                    "  %-4s %-11s %-46s %s%n",
                    result.question().id(),
                    result.verdict(),
                    truncate(result.question().question(), 46),
                    detail(result));
        }
        System.out.printf("  %s%n", "-".repeat(76));
        System.out.printf(
                "  %d/%d pass  ·  %d forbidden  ·  %d missed  ·  %d uncited%n",
                score.count(AnswerScore.Verdict.PASS),
                score.results().size(),
                score.count(AnswerScore.Verdict.FORBIDDEN),
                score.count(AnswerScore.Verdict.MISSED),
                score.count(AnswerScore.Verdict.UNSUPPORTED));

        if (!score.failures().isEmpty()) {
            System.out.printf("%n  Failures in detail%n");
            for (AnswerScore.QuestionResult failure : score.failures()) {
                System.out.printf("%n    %s  (%s)%n", failure.question().id(), failure.question().trap());
                System.out.printf("      asked:    %s%n", failure.question().question());
                System.out.printf("      wanted:   %s%n", truncate(failure.question().expected(), 66));
                System.out.printf(
                        "      got:      %s%n",
                        truncate(failure.answer().text().replaceAll("\\s+", " "), 66));
                if (!failure.violations().isEmpty()) {
                    System.out.printf("      forbidden phrase used: %s%n", failure.violations());
                }
            }
        }
        System.out.println();
    }

    private static String detail(AnswerScore.QuestionResult result) {
        return switch (result.verdict()) {
            case PASS -> "%d cards, %d cited".formatted(
                    result.cardsRetrieved(), result.answer().citations().size());
            case FORBIDDEN -> result.violations().isEmpty()
                    ? "answered when it should have declined"
                    : "said " + String.join(", ", result.violations());
            case MISSED -> result.answer().refused()
                    ? "declined, but the answer was there"
                    : "off target (%.2f)".formatted(result.similarity());
            case UNSUPPORTED -> "no citation";
        };
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

}
