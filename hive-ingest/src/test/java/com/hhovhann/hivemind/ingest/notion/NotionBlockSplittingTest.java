package com.hhovhann.hivemind.ingest.notion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotionBlockSplittingTest {

    @Test
    @DisplayName("a bare heading is folded into the block it introduces, so citations are provable")
    void headingsFoldIntoTheirBlock() {
        List<String> blocks = NotionPageReader.splitIntoBlocks(
                """
                # Title

                ## Decision

                We are keeping the newsletter.

                ## Why

                It converts.
                """);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.getFirst()).isEqualTo("# Title\n## Decision\nWe are keeping the newsletter.");
        assertThat(blocks.getLast()).isEqualTo("## Why\nIt converts.");
    }

    @Test
    @DisplayName("a trailing heading with nothing under it still becomes a block rather than vanishing")
    void trailingHeadingSurvives() {
        assertThat(NotionPageReader.splitIntoBlocks("Body text.\n\n## Open items"))
                .containsExactly("Body text.", "## Open items");
    }

    @Test
    @DisplayName("frontmatter values are unquoted, including titles containing colons")
    void parsesFrontmatter() {
        Map<String, String> frontmatter = NotionPageReader.parseFrontmatter(
                """
                ---
                id: P_FRONTIER_PLAN
                title: "Frontier: Launch Plan"
                visibility: PUBLIC
                ---

                Body.
                """);

        assertThat(frontmatter)
                .containsEntry("id", "P_FRONTIER_PLAN")
                .containsEntry("title", "Frontier: Launch Plan")
                .containsEntry("visibility", "PUBLIC");
    }
}
