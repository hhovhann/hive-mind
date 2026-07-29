package com.hhovhann.hivemind.app.cli;

import com.hhovhann.hivemind.graph.export.ObsidianExporter;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code ./gradlew :hive-app:bootRun --args='export'} — writes the graph out as an
 * Obsidian vault, by default into {@code vault/}.
 */
@Component
public class ExportRunner implements ApplicationRunner {

    private final ObsidianExporter exporter;
    private final ConfigurableApplicationContext context;

    public ExportRunner(ObsidianExporter exporter, ConfigurableApplicationContext context) {
        this.exporter = exporter;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!HiveCommand.EXPORT.present(args.getSourceArgs())) {
            return;
        }
        Path vault = HiveCommand.option(args.getSourceArgs(), "to").map(Path::of).orElse(Path.of("vault"));
        ObsidianExporter.ExportReport report = exporter.export(vault);

        System.out.printf("%n  Vault written to %s%n", report.vault().toAbsolutePath());
        System.out.printf(
                "  %d notes — %d facts, %d people, %d episodes, %d topics%n",
                report.notes(), report.facts(), report.people(), report.episodes(), report.topics());
        System.out.printf("%n  Open the folder in Obsidian and turn on the graph view.%n%n");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
