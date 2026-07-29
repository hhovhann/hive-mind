package com.hhovhann.hivemind.app.mcp;

import com.hhovhann.hivemind.app.cli.HiveCommand;
import com.hhovhann.hivemind.retrieval.Principal;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code ./gradlew :hive-app:bootRun --args='mcp'} — Hive Mind as an MCP server on stdio.
 *
 * <p>This is the transport agent clients actually launch: the client starts the
 * process, speaks JSON-RPC over its stdin and stdout, and kills it when the session
 * ends. Which makes stdout a wire, not a console. Anything else that writes there —
 * a startup log line, a stray {@code println}, a banner — lands in the middle of a
 * JSON-RPC frame and the session dies with a parse error that names none of them.
 * {@link com.hhovhann.hivemind.app.HiveMindApplication} points {@code System.out} at
 * stderr before Spring starts, and the transport takes the real descriptor here, so
 * the protocol has stdout to itself and everything else is loud on stderr where a
 * client will show it.
 *
 * <p>The reader is set once, from {@code --grants=}, and every tool call is made as
 * them. That is not a shortcut around the missing RBAC — it is the honest model for
 * this transport, since the client launched this process on one person's behalf and
 * there is nobody else on the other end of the pipe.
 */
@Component
public class McpStdioRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpStdioRunner.class);

    private final HiveMcpTools tools;
    private final ConfigurableApplicationContext context;

    public McpStdioRunner(HiveMcpTools tools, ConfigurableApplicationContext context) {
        this.tools = tools;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        String[] raw = args.getSourceArgs();
        if (!HiveCommand.MCP.present(raw)) {
            return;
        }
        Principal principal = Principal.parse("mcp", HiveCommand.option(raw, "grants").orElse(null));

        CountDownLatch clientWentAway = new CountDownLatch(1);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                new JacksonMcpJsonMapperSupplier().get(), closeAware(clientWentAway), protocolOut());

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("hive-mind", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .instructions(INSTRUCTIONS)
                .tools(tools.specifications(principal))
                .build();

        log.info(
                "MCP server on stdio, as {}",
                principal.grants().isEmpty() ? "a reader with no special access" : principal.grants());

        // Nothing else ends this process. There is no port to release and no request to
        // finish; the session is over when the client closes our stdin, and the only
        // place that is observable is a read returning -1.
        clientWentAway.await();
        server.closeGracefully();
        log.info("client disconnected, shutting down");
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** The real stdout, regardless of where {@code System.out} has since been pointed. */
    private static FileOutputStream protocolOut() {
        return new FileOutputStream(FileDescriptor.out);
    }

    private static InputStream closeAware(CountDownLatch latch) {
        return new FilterInputStream(new FileInputStream(FileDescriptor.in)) {
            @Override
            public int read() throws IOException {
                return signalOnEof(super.read());
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return signalOnEof(super.read(buffer, offset, length));
            }

            private int signalOnEof(int read) {
                if (read < 0) {
                    latch.countDown();
                }
                return read;
            }
        };
    }

    /**
     * Shown to the calling model once, when it connects.
     *
     * <p>Worth spending: it is the only place to say what this corpus is and what the
     * cards mean, and saying it here costs one paragraph rather than a repetition in
     * every one of the four tool descriptions.
     */
    private static final String INSTRUCTIONS =
            """
            Hive Mind is a temporal knowledge graph built from one team's Slack threads, \
            meeting recordings and Notion pages. It holds decisions, commitments, action \
            items, risks and open questions, each with the verbatim quote it was extracted \
            from and a permalink back to the conversation.

            Two things about it change how you should read a result. Facts expire: a card \
            marked NO LONGER TRUE was superseded by a later one, and reporting it as current \
            is the commonest way to be confidently wrong here — the launch date in this corpus \
            moved twice. And results are filtered to what this reader may see, so a tool that \
            returns nothing may mean the record is empty or may mean it is closed to them; \
            when a result says facts were withheld, say the record is incomplete rather than \
            reasoning a conclusion out of the fragments that remain.

            Cite the card numbers you used. Every card carries a quote and a permalink, which \
            is what lets someone check you.\
            """;
}