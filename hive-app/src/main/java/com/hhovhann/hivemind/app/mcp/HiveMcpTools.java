package com.hhovhann.hivemind.app.mcp;

import com.hhovhann.hivemind.retrieval.ContextPack;
import com.hhovhann.hivemind.retrieval.HybridRetriever;
import com.hhovhann.hivemind.retrieval.KnowledgeQueries;
import com.hhovhann.hivemind.retrieval.Principal;
import com.hhovhann.hivemind.retrieval.RetrievalQuery;
import com.hhovhann.hivemind.retrieval.RetrievedFact;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The four tools, over the graph that is already there.
 *
 * <p><b>These tools retrieve; they do not answer.</b> That is the whole shape of the
 * thing. {@code /api/ask} runs retrieval and then generation, because an HTTP caller
 * wants a sentence. An MCP caller <em>is</em> the model — generation is on the other
 * side of the protocol — so the useful half to expose is the one that stops at the
 * context pack. This is the same seam {@code /api/retrieve} was already split along
 * for load testing, which is a good sign it is a real seam and not a convenience.
 *
 * <p><b>The caller does not get to name its own grants.</b> {@code /api/ask} takes
 * them in the request body and says in its own Javadoc that this is a development
 * affordance; that affordance cannot come along here. A tool argument is written by a
 * model, from text that may have come from the corpus, so a {@code grants} parameter
 * is an instruction to the model to escalate its own access and the model has no
 * reason to refuse. The reader is fixed when the server starts and there is no
 * argument that moves it.
 *
 * <p>Four tools rather than one, because they are four different traversals and a
 * single {@code query} tool would push the choice between them into a prompt. The
 * descriptions say when each applies, since that text is the only documentation the
 * calling model ever reads.
 */
@Component
public class HiveMcpTools {

    private static final Logger log = LoggerFactory.getLogger(HiveMcpTools.class);

    /** A tool call that fans out further than this is answering a different question than it was asked. */
    private static final int MAX_LIMIT = 25;

    private static final int DEFAULT_LIMIT = 8;

    /** More than a handful of equally short routes is noise; the caller wanted to know *whether* they connect. */
    private static final int MAX_PATHS = 3;

    private final HybridRetriever retriever;
    private final KnowledgeQueries queries;

    public HiveMcpTools(HybridRetriever retriever, KnowledgeQueries queries) {
        this.retriever = retriever;
        this.queries = queries;
    }

    /**
     * @param principal the reader every tool call is made as, fixed for the life of the
     *                  server rather than supplied per call
     */
    public List<SyncToolSpecification> specifications(Principal principal) {
        return List.of(
                tool(
                        "search_knowledge",
                        """
                        Search the team's decisions, commitments, action items, risks and open \
                        questions, drawn from Slack threads, meeting recordings and Notion pages. \
                        Returns numbered fact cards, each stating whether it still holds, what \
                        replaced it if not, who owns it, and the verbatim quote it came from with \
                        a permalink. Use this first for any question about what the team decided, \
                        agreed, committed to or is worried about. Cite the card numbers in your \
                        answer; the cards are the only support you have, and a claim that no card \
                        makes is a claim this knowledge base does not support.\
                        """,
                        schema(
                                Map.of(
                                        "query", string("The question, in full. Complete sentences retrieve better "
                                                + "than keywords, because both a vector index and a full-text "
                                                + "index are searched and fused."),
                                        "as_of", string("Optional ISO date (YYYY-MM-DD). Answers as the record "
                                                + "stood that day rather than today — use it for 'what did we "
                                                + "think in April', not for 'what holds now'."),
                                        "limit", integer("Cards to return. Default " + DEFAULT_LIMIT
                                                + ", maximum " + MAX_LIMIT + ".")),
                                List.of("query")),
                        (exchange, args) -> searchKnowledge(args, principal)),
                tool(
                        "trace_decision",
                        """
                        Follow one decision through every revision it has been through, oldest \
                        first, with the date each version started and stopped being true. Use this \
                        when the question is about change — when something moved, how many times, \
                        what it used to be, whether it still stands. search_knowledge returns the \
                        current version and its immediate history; this returns the whole chain, \
                        walked in both directions, so a question worded like the original decision \
                        still reaches what replaced it.\
                        """,
                        schema(
                                Map.of(
                                        "query", string("What the decision is about, e.g. 'the Frontier launch "
                                                + "date'. Ignored when fact_id is given."),
                                        "fact_id", string("Optional exact fact id to trace, as returned by a "
                                                + "previous call. Use this to follow a specific fact rather than "
                                                + "whichever one the query matched best.")),
                                List.of()),
                        (exchange, args) -> traceDecision(args, principal)),
                tool(
                        "find_owner",
                        """
                        Find who is on the hook for whatever the question is about. Answers from \
                        the resolved ownership edges in the graph, not from the wording of the \
                        statements — which matters, because the sentence that assigns a piece of \
                        work often names nobody, and the corpus contains people with similar \
                        names who are not the same person. Returns each owner with their email, \
                        title, how their identity was resolved, and what they own. If the facts \
                        have no recorded owner it says so; do not fill that gap with a name read \
                        out of a quote.\
                        """,
                        schema(
                                Map.of(
                                        "query", string("What you want the owner of, e.g. 'the video CMS "
                                                + "migration'."),
                                        "limit", integer("Facts to consider before grouping by owner. Default "
                                                + DEFAULT_LIMIT + ", maximum " + MAX_LIMIT + ".")),
                                List.of("query")),
                        (exchange, args) -> findOwner(args, principal)),
                tool(
                        "path_between",
                        """
                        Show how two things in the graph are connected — two people, a person and \
                        a topic, a topic and a project. Returns the shortest chains of facts, \
                        ownership and topics that link them, with each edge's direction. Use it \
                        for 'how is X involved in Y', 'what connects these two', or to check \
                        whether a connection exists at all before asserting one. An empty result \
                        means no readable connection within the hop limit, which is an answer.\
                        """,
                        schema(
                                Map.of(
                                        "from", string("A person's name, email or handle; a topic; an episode "
                                                + "title; or a fact id."),
                                        "to", string("The other end, in the same forms."),
                                        "max_hops", integer("How far to look. Default " + KnowledgeQueries.MAX_PATH_HOPS
                                                + ", maximum " + KnowledgeQueries.MAX_PATH_HOPS + ".")),
                                List.of("from", "to")),
                        (exchange, args) -> pathBetween(args, principal)));
    }

    // ------------------------------------------------------------ the tools

    private McpSchema.CallToolResult searchKnowledge(Map<String, Object> args, Principal principal) {
        String question = requireText(args, "query");
        Instant asOf = date(args, "as_of");
        RetrievalQuery query = query(question, principal, asOf, limit(args));

        List<RetrievedFact> facts = retriever.retrieve(query);
        ContextPack pack = ContextPack.of(query, facts);
        return text(ToolReport.search(pack, principal, asOf, queries.withheldCount(question, asOf, principal)));
    }

    private McpSchema.CallToolResult traceDecision(Map<String, Object> args, Principal principal) {
        String factId = optionalText(args, "fact_id");
        if (factId != null) {
            List<KnowledgeQueries.ChainLink> chain = queries.chainFrom(factId, principal);
            return chain.isEmpty()
                    ? text("No readable fact has id " + factId + ".")
                    : text(ToolReport.trace(factId, chain, List.of()));
        }

        String question = requireText(args, "query");
        List<RetrievedFact> facts = retriever.retrieve(query(question, principal, null, DEFAULT_LIMIT));
        if (facts.isEmpty()) {
            return text(ToolReport.trace(question, List.of(), List.of()));
        }
        // Seed on the best match and let the chain walk outwards. The seed is often a
        // stale version — a question phrased like the original decision resembles the
        // original decision — which is exactly why the walk goes both ways.
        List<KnowledgeQueries.ChainLink> chain = queries.chainFrom(facts.getFirst().id(), principal);
        return text(ToolReport.trace(question, chain, facts.subList(1, facts.size())));
    }

    private McpSchema.CallToolResult findOwner(Map<String, Object> args, Principal principal) {
        String question = requireText(args, "query");
        List<RetrievedFact> facts = retriever.retrieve(query(question, principal, null, limit(args)));
        List<String> ids = facts.stream().map(RetrievedFact::id).toList();

        List<KnowledgeQueries.Ownership> owners = queries.ownersOf(ids, principal);
        Set<String> owned = new LinkedHashSet<>();
        owners.forEach(owner -> owner.facts().forEach(fact -> owned.add(fact.id())));
        return text(ToolReport.owners(question, owners, facts.size(), facts.size() - owned.size()));
    }

    private McpSchema.CallToolResult pathBetween(Map<String, Object> args, Principal principal) {
        String fromTerm = requireText(args, "from");
        String toTerm = requireText(args, "to");

        List<KnowledgeQueries.Entity> from = queries.resolve(fromTerm, principal);
        List<KnowledgeQueries.Entity> to = queries.resolve(toTerm, principal);
        // Resolution is where this tool most often ends, and the two failures need
        // different answers: nothing matched is a dead end, several matched is a
        // question back. Guessing between candidates would silently answer about the
        // wrong Alex.
        if (from.isEmpty()) {
            return text(ToolReport.unresolved("from", fromTerm));
        }
        if (to.isEmpty()) {
            return text(ToolReport.unresolved("to", toTerm));
        }
        if (from.size() > 1) {
            return text(ToolReport.ambiguous("from", fromTerm, from));
        }
        if (to.size() > 1) {
            return text(ToolReport.ambiguous("to", toTerm, to));
        }

        int hops = args.get("max_hops") == null ? KnowledgeQueries.MAX_PATH_HOPS : (int) number(args, "max_hops");
        List<KnowledgeQueries.Connection> connections =
                queries.pathsBetween(from.getFirst(), to.getFirst(), hops, MAX_PATHS, principal);
        return text(ToolReport.paths(from.getFirst(), to.getFirst(), connections));
    }

    // ---------------------------------------------------------- tool plumbing

    /**
     * Wraps a handler so a thrown exception becomes a tool error rather than a dead session.
     *
     * <p>An MCP error result is addressed to the model: it is the model that passed a
     * date it invented or a fact id that does not exist, and it is the model that can
     * try something else. A stack trace is addressed to us, so that goes to the log —
     * which on the stdio transport is not stdout, because stdout belongs to the
     * protocol.
     */
    private SyncToolSpecification tool(
            String name,
            String description,
            McpSchema.JsonSchema schema,
            BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        return handler.apply(exchange, request.arguments());
                    } catch (IllegalArgumentException invalid) {
                        return error(invalid.getMessage());
                    } catch (RuntimeException failure) {
                        log.warn("{} failed", name, failure);
                        return error(name + " failed against the graph: " + failure.getMessage());
                    }
                })
                .build();
    }

    private static RetrievalQuery query(String question, Principal principal, Instant asOf, int limit) {
        RetrievalQuery query = RetrievalQuery.of(question).as(principal).limitedTo(limit);
        return asOf == null ? query : query.asOf(asOf);
    }

    static McpSchema.JsonSchema schema(Map<String, Object> properties, List<String> required) {
        // additionalProperties false, so a model that invents an argument is told rather
        // than having it silently dropped.
        return new McpSchema.JsonSchema("object", properties, required, false, null, null);
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static McpSchema.CallToolResult text(String body) {
        return McpSchema.CallToolResult.builder().addTextContent(body).build();
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    // ------------------------------------------------------ argument reading

    static String requireText(Map<String, Object> args, String name) {
        String value = optionalText(args, name);
        if (value == null) {
            throw new IllegalArgumentException("'" + name + "' is required and must be a non-empty string.");
        }
        return value;
    }

    static String optionalText(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    /** Clamped rather than rejected: an out-of-range limit is a preference, not a mistake worth failing on. */
    static int limit(Map<String, Object> args) {
        Object value = args == null ? null : args.get("limit");
        return value == null ? DEFAULT_LIMIT : Math.clamp((long) number(args, "limit"), 1, MAX_LIMIT);
    }

    static double number(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value instanceof Number found) {
            return found.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).strip());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("'" + name + "' must be a number, got: " + value);
        }
    }

    static Instant date(Map<String, Object> args, String name) {
        String value = optionalText(args, name);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException notADate) {
            throw new IllegalArgumentException("'" + name + "' must be an ISO date (YYYY-MM-DD), got: " + value);
        }
    }
}