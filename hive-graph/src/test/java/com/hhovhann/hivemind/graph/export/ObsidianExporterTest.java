package com.hhovhann.hivemind.graph.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ObsidianExporterTest {

    @Test
    @DisplayName("characters a filesystem or Obsidian would choke on are stripped from note names")
    void slugRemovesUnsafeCharacters() {
        assertThat(ObsidianExporter.slug("#content-prod — We have a problem with Frontier"))
                .isEqualTo("content-prod — We have a problem with Frontier");
        // '?' goes too — it is not a legal filename character on Windows.
        assertThat(ObsidianExporter.slug("Q2/Q3 planning: what next?")).isEqualTo("Q2Q3 planning what next");
    }

    @Test
    @DisplayName("a link target always matches the note filename it points at")
    void linksResolveToTheirNote() {
        String title = "#frontier-launch — Heads up [Halcyon]";

        assertThat(ObsidianExporter.link(title)).isEqualTo("[[" + ObsidianExporter.slug(title) + "]]");
        // The brackets that would break the wikilink syntax are gone from both sides.
        assertThat(ObsidianExporter.link(title)).doesNotContain("[Halcyon]");
    }

    @Test
    @DisplayName("a trailing full stop is dropped so the filename is not hidden on some systems")
    void trailingPeriodIsDropped() {
        assertThat(ObsidianExporter.slug("The Frontier launch moves to June 1, 2026."))
                .isEqualTo("The Frontier launch moves to June 1, 2026");
    }

    @Test
    @DisplayName("very long statements are truncated to a usable filename")
    void longStatementsAreTruncated() {
        String long1 = "a".repeat(200);

        assertThat(ObsidianExporter.slug(long1)).hasSize(100);
    }
}
