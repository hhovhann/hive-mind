package com.hhovhann.hivemind.ingest;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the sample corpus lives.
 *
 * @param path root directory holding {@code slack/}, {@code notion/} and {@code zoom/}
 */
@ConfigurationProperties(prefix = "hive.corpus")
public record CorpusProperties(Path path) {

    public Path slack() {
        return path.resolve("slack");
    }

    public Path notion() {
        return path.resolve("notion");
    }

    public Path zoom() {
        return path.resolve("zoom");
    }
}
