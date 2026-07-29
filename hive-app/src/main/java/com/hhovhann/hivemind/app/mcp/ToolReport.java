package com.hhovhann.hivemind.app.mcp;

import com.hhovhann.hivemind.retrieval.ContextPack;
import com.hhovhann.hivemind.retrieval.KnowledgeQueries;
import com.hhovhann.hivemind.retrieval.Principal;
import com.hhovhann.hivemind.retrieval.RetrievedFact;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * What each tool actually hands back.
 *
 * <p>An MCP tool result is read by a model, so this is the same problem
 * {@link ContextPack} solves and it is solved the same way: state the thing that
 * changes the reading — whether a fact still holds, which direction an edge points,
 * how an identity was resolved — before the content, not after it. Prose that buries
 * "superseded" in the third clause gets averaged into the answer as if it were
 * current.
 *
 * <p>The one thing these reports say that {@code /api/ask} does not is how much the
 * reader could not see. The generating model is on the other side of the protocol
 * here, so there is no prompt to tighten and no refusal to enforce — the only lever
 * left is to put the absence in the context as a fact of its own.
 */
final class ToolReport {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private ToolReport() {}

    // -------------------------------------------------------- search_knowledge

    static String search(ContextPack pack, Principal principal, Instant asOf, long withheld) {
        StringBuilder out = new StringBuilder();
        out.append(pack.cards().size())
                .append(pack.cards().size() == 1 ? " fact" : " facts")
                .append(", read as ")
                .append(reader(principal));
        if (asOf != null) {
            out.append(", as the record stood on ").append(DAY.format(asOf));
        }
        out.append(".\n");
        appendWithheld(out, withheld);
        out.append('\n').append(pack.render());
        return out.toString();
    }

    /**
     * The disclosure, phrased as an instruction rather than a note.
     *
     * <p>"Some results were filtered" is the kind of sentence a model acknowledges and
     * then ignores, because it does not say what to do differently. Naming the wrong
     * behaviour — answering as though this were everything — is what makes it
     * actionable, and it is the specific failure that was measured: the reader without
     * the exec grants reconstructs a confident answer out of the public fragments.
     */
    private static void appendWithheld(StringBuilder out, long withheld) {
        if (withheld <= 0) {
            return;
        }
        out.append("At least ")
                .append(withheld)
                .append(
                        withheld == 1
                                ? " further fact matches this question and is"
                                : " further facts match this question and are")
                .append(" outside this reader's access.\n")
                .append("Answer only from the cards below, and say the record is incomplete rather than\n")
                .append("inferring what the withheld facts might say.\n");
    }

    // --------------------------------------------------------- trace_decision

    static String trace(String query, List<KnowledgeQueries.ChainLink> chain, List<RetrievedFact> alternatives) {
        if (chain.isEmpty()) {
            return "Nothing readable matches \"" + query + "\".";
        }
        StringBuilder out = new StringBuilder();
        out.append(chain.size())
                .append(chain.size() == 1 ? " version" : " versions")
                .append(" of this decision, oldest first.\n");

        for (KnowledgeQueries.ChainLink link : chain) {
            out.append('\n')
                    .append("  ")
                    .append(DAY.format(link.occurredAt()))
                    .append("  ")
                    .append(link.isCurrent() ? "CURRENT   " : "SUPERSEDED")
                    .append("  ")
                    .append(link.statement())
                    .append('\n');
            out.append("              ").append(link.type()).append(" · ").append(link.status());
            if (link.ownerName() != null) {
                out.append(" · owner ").append(link.ownerName());
            }
            out.append(" · ").append(link.episodeSystem()).append(" \"").append(link.episodeTitle()).append("\"\n");
            out.append("              true from ").append(DAY.format(link.validFrom()));
            out.append(link.isCurrent() ? " and still holding" : " until " + DAY.format(link.validTo()));
            out.append("\n              id ").append(link.id());
            if (link.permalink() != null && !link.permalink().isBlank()) {
                out.append("  ·  ").append(link.permalink());
            }
            out.append('\n');
        }

        // A one-link chain is ambiguous: either this decision was never revised, or the
        // seed landed on the wrong fact. The caller can tell the difference; give it
        // the ids it needs to check rather than an answer that hides the doubt.
        if (chain.size() == 1 && !alternatives.isEmpty()) {
            out.append("\nThis decision has no recorded revisions. Other facts matched the same question;")
                    .append("\ncall trace_decision again with fact_id to follow one of them instead:\n");
            alternatives.stream()
                    .limit(5)
                    .forEach(fact -> out.append("  ")
                            .append(fact.id())
                            .append("  ")
                            .append(truncate(fact.statement()))
                            .append('\n'));
        }
        return out.toString();
    }

    // ------------------------------------------------------------- find_owner

    static String owners(String query, List<KnowledgeQueries.Ownership> owners, int factsMatched, int unowned) {
        if (factsMatched == 0) {
            return "Nothing readable matches \"" + query + "\".";
        }
        StringBuilder out = new StringBuilder();
        if (owners.isEmpty()) {
            out.append("None of the ")
                    .append(factsMatched)
                    .append(" facts matching this question has a resolved owner.\n")
                    .append("The graph records no OWNED_BY edge for them — say so rather than reading a\n")
                    .append("name out of the statements, which is how the wrong person gets assigned.\n");
            return out.toString();
        }

        out.append(owners.size() == 1 ? "1 owner" : owners.size() + " owners")
                .append(" across the ")
                .append(factsMatched)
                .append(" facts matching this question.\n");

        for (KnowledgeQueries.Ownership owner : owners) {
            out.append('\n').append("  ").append(owner.name());
            if (owner.title() != null) {
                out.append(" — ").append(owner.title());
            }
            if (owner.email() != null) {
                out.append(" · ").append(owner.email());
            }
            out.append('\n');
            out.append("  owns ")
                    .append(owner.factCount())
                    .append(owner.factCount() == 1 ? " matching fact" : " matching facts")
                    .append(" · identity resolved by ")
                    .append(String.join(", ", owner.methods()))
                    .append("\n");
            owner.facts()
                    .forEach(fact -> out.append("    · ")
                            .append(DAY.format(fact.occurredAt()))
                            .append(fact.current() ? "  CURRENT     " : "  SUPERSEDED  ")
                            .append(truncate(fact.statement()))
                            .append('\n'));
        }

        if (unowned > 0) {
            out.append('\n')
                    .append(unowned)
                    .append(unowned == 1 ? " matching fact has" : " matching facts have")
                    .append(" no resolved owner.\n");
        }
        return out.toString();
    }

    // ----------------------------------------------------------- path_between

    static String paths(
            KnowledgeQueries.Entity from,
            KnowledgeQueries.Entity to,
            List<KnowledgeQueries.Connection> connections) {
        if (connections.isEmpty()) {
            return "No readable path connects %s and %s within the hop limit.%n".formatted(named(from), named(to))
                    + "Either they are genuinely unconnected, or every route between them runs\n"
                    + "through a fact this reader may not see.";
        }
        StringBuilder out = new StringBuilder();
        out.append(connections.size() == 1 ? "1 path" : connections.size() + " shortest paths")
                .append(" between ")
                .append(named(from))
                .append(" and ")
                .append(named(to))
                .append(", ")
                .append(connections.getFirst().hopCount())
                .append(connections.getFirst().hopCount() == 1 ? " hop.\n" : " hops.\n");

        for (int index = 0; index < connections.size(); index++) {
            KnowledgeQueries.Connection connection = connections.get(index);
            out.append('\n').append("  ").append(index + 1).append(". ");
            out.append(node(connection.nodes().getFirst())).append('\n');
            List<String> arrows = connection.hops().stream().map(ToolReport::arrow).toList();
            int width = arrows.stream().mapToInt(String::length).max().orElse(0);
            for (int hop = 0; hop < arrows.size(); hop++) {
                out.append("       ")
                        .append(pad(arrows.get(hop), width))
                        .append("  ")
                        .append(node(connection.nodes().get(hop + 1)))
                        .append('\n');
            }
        }
        return out.toString();
    }

    static String ambiguous(String side, String term, List<KnowledgeQueries.Entity> candidates) {
        StringBuilder out = new StringBuilder();
        out.append('"')
                .append(term)
                .append("\" (")
                .append(side)
                .append(") matches ")
                .append(candidates.size())
                .append(" things in the graph. Call again with one of these exactly:\n");
        candidates.forEach(entity ->
                out.append("  ").append(entity.kind()).append("  ").append(entity.label()).append('\n'));
        return out.toString();
    }

    static String unresolved(String side, String term) {
        return "\"%s\" (%s) matches nothing readable in the graph. Try a person's full name, a topic, or an episode title."
                .formatted(term, side);
    }

    // ------------------------------------------------------------- utilities

    private static String reader(Principal principal) {
        return principal.grants().isEmpty()
                ? "a reader with no special access"
                : "a reader holding " + String.join(", ", principal.grants().stream().sorted().toList());
    }

    private static String named(KnowledgeQueries.Entity entity) {
        return "%s \"%s\"".formatted(entity.kind(), entity.label());
    }

    private static String node(KnowledgeQueries.Node node) {
        String label = "%s \"%s\"".formatted(node.kind(), truncate(node.label()));
        if (node.current() == null) {
            return label;
        }
        return label + (node.current() ? "  (current)" : "  (superseded)");
    }

    private static String arrow(KnowledgeQueries.Hop hop) {
        return hop.forward() ? "-[%s]->".formatted(hop.type()) : "<-[%s]-".formatted(hop.type());
    }

    /** Aligned to the widest edge on this path, so the node column reads as a column. */
    private static String pad(String arrow, int width) {
        return arrow.length() >= width ? arrow : arrow + " ".repeat(width - arrow.length());
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 72 ? text : text.substring(0, 69) + "...";
    }
}