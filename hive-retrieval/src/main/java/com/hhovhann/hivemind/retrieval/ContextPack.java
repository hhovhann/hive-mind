package com.hhovhann.hivemind.retrieval;

import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The retrieved facts, rendered as numbered cards rather than concatenated prose.
 *
 * <p>This is where the anti-flattening actually happens. Handing a model eight
 * paragraphs of related text invites it to average them into something fluent and
 * wrong — the March decision, the May revision and the July reversal all read as
 * equally true when they arrive as undifferentiated context. A card states, on its
 * face, whether the fact still holds, what replaced it, who owns it, and which line
 * of which conversation it came from. The model is then answering a structured
 * question rather than summarising a pile.
 *
 * <p>The card number is the citation address, which is what makes an answer
 * checkable: every claim points at a card, and every card points at a quote with a
 * permalink.
 */
public record ContextPack(RetrievalQuery query, List<Card> cards) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    public record Card(int number, RetrievedFact fact) {}

    public ContextPack {
        cards = List.copyOf(cards);
    }

    public static ContextPack of(RetrievalQuery query, List<RetrievedFact> facts) {
        List<Card> cards = IntStream.range(0, facts.size())
                .mapToObj(index -> new Card(index + 1, facts.get(index)))
                .toList();
        return new ContextPack(query, cards);
    }

    public boolean isEmpty() {
        return cards.isEmpty();
    }

    public String render() {
        if (cards.isEmpty()) {
            return "(nothing in the knowledge base matches this question)";
        }
        StringBuilder out = new StringBuilder();
        for (Card card : cards) {
            out.append(renderCard(card)).append('\n');
        }
        return out.toString();
    }

    private String renderCard(Card card) {
        RetrievedFact fact = card.fact();
        StringBuilder out = new StringBuilder();

        out.append('[').append(card.number()).append("] ").append(fact.type());
        if (fact.isCurrent()) {
            out.append(" — CURRENT (").append(fact.status()).append(')');
        } else {
            // Stated first and loudly: a superseded fact read as current is the
            // failure this whole design exists to prevent.
            out.append(" — NO LONGER TRUE, superseded on ").append(DAY.format(fact.validTo()));
        }
        out.append('\n');

        out.append("    ").append(fact.statement()).append('\n');
        out.append("    decided ").append(DAY.format(fact.occurredAt()));
        if (fact.ownerName() != null) {
            out.append("  ·  owner ").append(fact.ownerName());
        }
        out.append("  ·  from ").append(fact.episodeSystem()).append(" \"").append(fact.episodeTitle()).append("\"\n");

        fact.replacedBy()
                .ifPresent(newer -> out.append("    replaced by: \"")
                        .append(newer.statement())
                        .append("\" (")
                        .append(DAY.format(newer.occurredAt()))
                        .append(")\n"));

        if (!fact.history().isEmpty()) {
            out.append("    this replaced:\n");
            fact.history()
                    .forEach(older -> out.append("      · \"")
                            .append(older.statement())
                            .append("\" (")
                            .append(DAY.format(older.occurredAt()))
                            .append(")\n"));
        }

        if (!fact.evidence().isEmpty()) {
            out.append("    evidence:\n");
            fact.evidence().forEach(evidence -> {
                out.append("      \"").append(evidence.span()).append("\"");
                if (evidence.speaker() != null) {
                    out.append(" — ").append(evidence.speaker());
                }
                if (evidence.at() != null) {
                    out.append(", ").append(DAY.format(evidence.at()));
                }
                out.append('\n');
            });
        }
        return out.toString();
    }
}
