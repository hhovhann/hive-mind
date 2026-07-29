package com.hhovhann.hivemind.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hhovhann.hivemind.retrieval.Principal;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HiveMcpToolsTest {

    // The declarations are pure: building them touches neither the graph nor the
    // embedding model, which is what makes this checkable without either running.
    private final HiveMcpTools tools = new HiveMcpTools(null, null);

    @Test
    @DisplayName("all four tools are declared, each with a description a model can choose from")
    void declaresFourTools() {
        List<McpSchema.Tool> declared = tools.specifications(Principal.ANONYMOUS).stream()
                .map(SyncToolSpecification::tool)
                .toList();

        assertThat(declared)
                .extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("search_knowledge", "trace_decision", "find_owner", "path_between");
        assertThat(declared).allSatisfy(tool -> assertThat(tool.description()).hasSizeGreaterThan(120));
    }

    @Test
    @DisplayName("no tool lets the caller name its own grants")
    void noToolTakesGrants() {
        // The argument list is written by a model, from text that may itself have come
        // out of the corpus. A `grants` parameter would be an instruction to escalate
        // that the model has no reason to decline, so the reader is fixed at startup
        // and there is nothing here that moves it.
        assertThat(tools.specifications(Principal.withGrants("exec", "slack:C_EXEC")))
                .allSatisfy(spec -> assertThat(spec.tool().inputSchema().properties())
                        .doesNotContainKeys("grants", "principal", "as_user", "acl"));
    }

    @Test
    @DisplayName("an argument the model invented is rejected rather than silently dropped")
    void schemasRejectUnknownArguments() {
        assertThat(tools.specifications(Principal.ANONYMOUS))
                .allSatisfy(spec ->
                        assertThat(spec.tool().inputSchema().additionalProperties()).isFalse());
    }

    @Test
    @DisplayName("search and find_owner require a query; trace accepts either a query or a fact id")
    void requiredArgumentsMatchTheTool() {
        Map<String, List<String>> required = new HashMap<>();
        tools.specifications(Principal.ANONYMOUS)
                .forEach(spec -> required.put(spec.tool().name(), spec.tool().inputSchema().required()));

        assertThat(required.get("search_knowledge")).containsExactly("query");
        assertThat(required.get("find_owner")).containsExactly("query");
        assertThat(required.get("path_between")).containsExactlyInAnyOrder("from", "to");
        // Neither is required alone, because fact_id is the way to follow a specific
        // chain after a first call has named it.
        assertThat(required.get("trace_decision")).isEmpty();
    }

    @Test
    @DisplayName("a limit outside the range is clamped, not refused")
    void limitIsClamped() {
        assertThat(HiveMcpTools.limit(Map.of())).isEqualTo(8);
        assertThat(HiveMcpTools.limit(Map.of("limit", 3))).isEqualTo(3);
        assertThat(HiveMcpTools.limit(Map.of("limit", 500))).isEqualTo(25);
        assertThat(HiveMcpTools.limit(Map.of("limit", 0))).isEqualTo(1);
        // JSON numbers arrive as Double often enough that treating them as Integer breaks.
        assertThat(HiveMcpTools.limit(Map.of("limit", 4.0))).isEqualTo(4);
    }

    @Test
    @DisplayName("a date the model invented fails with a message addressed to the model")
    void badDatesAreReportedNotThrown() {
        assertThat(HiveMcpTools.date(Map.of("as_of", "2026-04-15"), "as_of"))
                .isEqualTo(Instant.parse("2026-04-15T00:00:00Z"));
        assertThat(HiveMcpTools.date(Map.of(), "as_of")).isNull();

        assertThatThrownBy(() -> HiveMcpTools.date(Map.of("as_of", "last April"), "as_of"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM-DD")
                .hasMessageContaining("last April");
    }

    @Test
    @DisplayName("a blank query counts as missing")
    void blankTextIsMissing() {
        assertThat(HiveMcpTools.optionalText(Map.of("query", "   "), "query")).isNull();
        assertThatThrownBy(() -> HiveMcpTools.requireText(Map.of("query", "  "), "query"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }
}