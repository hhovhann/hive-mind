package com.hhovhann.hivemind.retrieval;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes the answer from the cards, and only from the cards.
 *
 * <p>Two instructions carry most of the weight. Cite every claim by card number,
 * which makes the answer checkable and makes fabrication visible — a sentence with no
 * citation is the tell. And treat a card marked {@code NO LONGER TRUE} as history
 * rather than as an answer, because the retriever deliberately supplies stale facts
 * so the model can explain what changed, and a model that cannot tell the difference
 * will confidently quote a decision that was reversed months ago.
 *
 * <p>Refusal is a first-class outcome. On a corpus with access control it is
 * sometimes the only correct one: a reader without the exec grant sees only the
 * public fragments of the hiring freeze, and reconstructing the decision from them
 * would be a leak dressed as a good answer.
 */
@Service
public class AnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerator.class);

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    private static final String NOT_FOUND = "NOT_IN_KNOWLEDGE_BASE";

    private static final String SYSTEM_PROMPT =
            """
            You answer questions about an organisation from numbered cards. Each card holds
            one fact taken from a real conversation.

            Answer the way a well-informed colleague would say it out loud: the answer
            first, then the citation. Never describe the cards. Do not write "card [5]
            states", "according to the cards" or "it is not explicitly stated" — say the
            thing and put the number after it.

            1. Open with the answer itself. Asked when, give the date. Asked who, give the
               name. Never open with history, caveats, or what you could not find.
            2. Cite each claim with its card number, like [5].
            3. Cards marked CURRENT are true now; cards marked NO LONGER TRUE are history.
               Never give a superseded fact as the answer. Mention one only afterwards, to
               say what changed and when.
            4. Every card names an owner. That is part of the fact and answers "who owns
               this" or "who is responsible" even when no sentence on the card names anyone.
            5. Use only the cards, and claim only what a card literally says. Never turn a
               small fact into a big one — a card saying one role is paused does not mean
               there is a hiring freeze.
               If a card speaks to the question, answer from it and cite it. That includes
               cards stating a negative: "not agreed", "still open", "in effect".
               Decline only when no card speaks to the question at all.
            6. Write prose. Never copy a card's "decided", "owner" or "evidence" lines into
               the answer; work what you need from them into the sentence.
            7. If no card answers the question, reply with exactly %s and
               nothing else. Never hedge in prose — either answer, or output that token
               alone. An absent answer is a correct answer; a vague one is not.
            8. Two or three sentences, unless asked for a history.

            Example — the cards answer the question
            Q: When does the podcast pilot go out?
            A: April 20, 2026 [1]. Alexandra Petrova set that date on 2 March, moving it
            from the original 30 March [3].

            Example — the cards are about the subject but do not answer it
            Cards: [1] The studio booking for March is cancelled.
            Q: Are we cancelling the podcast?
            A: %s

            The second example is the important one. A card about a related thing is not
            an answer, and "no" would have been a guess dressed as a fact.
            """
                    .formatted(NOT_FOUND, NOT_FOUND);

    private final ChatModel chatModel;

    public AnswerGenerator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public Answer answer(ContextPack pack) {
        if (pack.isEmpty()) {
            return Answer.refusal("Nothing in the knowledge base matches this question.", 0);
        }
        String request =
                """
                Question: %s

                Cards:
                %s
                """
                        .formatted(pack.query().question(), pack.render());

        String text;
        try {
            text = chatModel
                    .chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                            .messages(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(request))
                            .build())
                    .aiMessage()
                    .text();
        } catch (RuntimeException e) {
            log.warn("answer generation failed: {}", e.toString());
            return Answer.refusal("The model could not be reached.", pack.cards().size());
        }

        if (text == null || text.isBlank() || text.contains(NOT_FOUND)) {
            return Answer.refusal(
                    "Nothing in the knowledge base answers this question.",
                    pack.cards().size());
        }
        return new Answer(text.strip(), citationsIn(text, pack), false, pack.cards().size());
    }

    /**
     * Resolves the card numbers the model cited back to facts.
     *
     * <p>A citation naming a card that was never supplied is dropped rather than
     * repaired: it means the model invented the reference, and inventing a citation is
     * worse than omitting one.
     */
    static List<Answer.Citation> citationsIn(String text, ContextPack pack) {
        Map<Integer, ContextPack.Card> byNumber = new LinkedHashMap<>();
        pack.cards().forEach(card -> byNumber.put(card.number(), card));

        List<Answer.Citation> citations = new ArrayList<>();
        List<Integer> seen = new ArrayList<>();
        Matcher matcher = CITATION.matcher(text);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (seen.contains(number)) {
                continue;
            }
            seen.add(number);
            ContextPack.Card card = byNumber.get(number);
            if (card == null) {
                log.warn("answer cited card [{}], which was not supplied", number);
                continue;
            }
            String permalink = card.fact().evidence().stream()
                    .map(RetrievedFact.EvidenceRef::permalink)
                    .filter(link -> link != null && !link.isBlank())
                    .findFirst()
                    .orElse(null);
            citations.add(new Answer.Citation(
                    number, card.fact().id(), card.fact().statement(), permalink));
        }
        return citations;
    }
}
