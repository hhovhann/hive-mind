package com.hhovhann.hivemind.app.cli;

import com.hhovhann.hivemind.retrieval.Answer;
import com.hhovhann.hivemind.retrieval.Principal;
import com.hhovhann.hivemind.retrieval.RetrievalQuery;
import com.hhovhann.hivemind.retrieval.RetrievalService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code ./gradlew :hive-app:bootRun --args='ask When does Frontier premiere'}
 *
 * <p>The question is the remaining words, unquoted, because Gradle splits
 * {@code --args} on whitespace and quoting through two layers of shell is a worse
 * experience than joining the tokens back together here.
 *
 * <p>Options that matter for demonstrating the design:
 *
 * <ul>
 *   <li>{@code --as-of=2026-04-15} answers as of a date instead of today
 *   <li>{@code --grants=slack:C_EXEC,zoom:M_EXEC_OFFSITE} asks as a reader who holds
 *       those grants — the same question answers differently, which is the point
 *   <li>{@code --cards} prints the context pack the answer was written from
 * </ul>
 */
@Component
public class AskRunner implements ApplicationRunner {

    private final RetrievalService retrieval;
    private final ConfigurableApplicationContext context;

    public AskRunner(RetrievalService retrieval, ConfigurableApplicationContext context) {
        this.retrieval = retrieval;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] raw = args.getSourceArgs();
        if (!HiveCommand.ASK.present(raw)) {
            return;
        }
        String question = questionFrom(raw);
        if (question.isBlank()) {
            System.out.printf("%n  Usage: ask <question> [--as-of=YYYY-MM-DD] [--grants=a,b] [--cards]%n%n");
            System.exit(SpringApplication.exit(context, () -> 1));
            return;
        }

        RetrievalQuery query = RetrievalQuery.of(question).as(principalFrom(raw));
        query = HiveCommand.option(raw, "as-of")
                .map(date -> LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant())
                .map(query::asOf)
                .orElse(query);

        RetrievalService.Result result = retrieval.ask(query);
        print(result, Arrays.asList(raw).contains("--cards"));
        System.exit(SpringApplication.exit(context, () -> result.answer().refused() ? 2 : 0));
    }

    private void print(RetrievalService.Result result, boolean showCards) {
        RetrievalQuery query = result.query();
        Answer answer = result.answer();

        System.out.printf("%n  Q: %s%n", query.question());
        System.out.printf(
                "     asked as %s%s%n",
                query.principal().grants().isEmpty()
                        ? "a reader with no special access"
                        : "a reader holding " + query.principal().grants(),
                query.isHistorical() ? ", as of " + query.asOf().toString().substring(0, 10) : "");

        if (showCards) {
            System.out.printf("%n%s%n", indent(result.pack().render()));
        }

        System.out.printf("%n  A: %s%n", indent(answer.text()).strip());
        if (answer.refused()) {
            System.out.printf(
                    "     (refused — %d cards were retrieved and none answered it)%n",
                    answer.cardsConsidered());
        } else if (answer.citations().isEmpty()) {
            System.out.printf("     (warning: answered without citing any card)%n");
        } else {
            System.out.printf("%n  Sources:%n");
            answer.citations()
                    .forEach(citation -> System.out.printf(
                            "    [%d] %s%s%n",
                            citation.card(),
                            truncate(citation.statement()),
                            citation.permalink() == null ? "" : "\n        " + citation.permalink()));
        }
        System.out.println();
    }

    static String questionFrom(String[] args) {
        List<String> words = Arrays.stream(args)
                .filter(arg -> !arg.startsWith("--"))
                .filter(arg -> !arg.equals(HiveCommand.ASK.token()))
                .toList();
        return String.join(" ", words).strip();
    }

    private static Principal principalFrom(String[] args) {
        Set<String> grants = HiveCommand.option(args, "grants")
                .map(value -> Arrays.stream(value.split(","))
                        .map(String::strip)
                        .filter(grant -> !grant.isBlank())
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
        return grants.isEmpty() ? Principal.ANONYMOUS : new Principal("cli", grants);
    }

    private static String indent(String text) {
        return text.lines().map(line -> "     " + line).collect(Collectors.joining("\n"));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 62 ? text : text.substring(0, 59) + "...";
    }
}
