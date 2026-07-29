package com.hhovhann.hivemind.app.mcp;

import com.hhovhann.hivemind.retrieval.Principal;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * The same four tools at {@code POST /mcp}, for when Hive Mind is running as a server.
 *
 * <p>Streamable HTTP rather than the deprecated SSE pair, and mounted alongside
 * {@code /api/ask} on the existing port rather than in a second process — the tools
 * are a view of the same graph the REST endpoints read, so a second deployment would
 * be two things to keep in step for no gain.
 *
 * <p><b>One reader, for the whole server.</b> Grants come from
 * {@code hive.mcp.grants} at startup, exactly as they do on stdio, and no header or
 * request field moves them. That makes this endpoint single-principal, which is the
 * truthful state of things until the RBAC milestone lands: real per-principal grants
 * have to be materialised from the source systems and cached, and inventing a header
 * to carry them in the meantime would look like authentication without being any.
 * Until then this is a developer surface — bind it to localhost or put it behind
 * something that knows who is calling.
 */
@Configuration
@ConditionalOnWebApplication
public class McpHttpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpHttpConfiguration.class);

    @Bean
    public WebMvcStreamableServerTransportProvider mcpTransport() {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapperSupplier().get())
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcStreamableServerTransportProvider transport) {
        return transport.getRouterFunction();
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpServer(
            WebMvcStreamableServerTransportProvider transport,
            HiveMcpTools tools,
            @Value("${hive.mcp.grants:}") String grants) {
        Principal principal = Principal.parse("mcp-http", grants);
        log.info(
                "MCP server at POST /mcp, as {}",
                principal.grants().isEmpty() ? "a reader with no special access" : principal.grants());
        return McpServer.sync(transport)
                .serverInfo("hive-mind", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.specifications(principal))
                .build();
    }
}