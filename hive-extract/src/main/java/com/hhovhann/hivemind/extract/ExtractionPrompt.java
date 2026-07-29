package com.hhovhann.hivemind.extract;

import com.hhovhann.hivemind.core.ontology.Ontology;

/**
 * The extraction instruction.
 *
 * <p>Versioned deliberately. Prompt text is the largest single lever on extraction
 * quality and the easiest thing to change without noticing; stamping a version on
 * every run is what makes "the numbers went up" a claim rather than a feeling.
 *
 * <p>The worked example at the end earns its tokens on small models. An 8B model
 * given rules alone will paraphrase its quotes — which the grounding gate then
 * discards, so recall collapses for reasons the prompt never mentions. One example
 * of a verbatim quote fixes more than three more sentences of instruction.
 */
public final class ExtractionPrompt {

    // v2: sharpened COMMITMENT against ACTION_ITEM, and stated which statuses each
    // type may take. v1 typed internal work as commitments and marked risks AGREED.
    public static final String VERSION = "extract-v2";

    private ExtractionPrompt() {}

    public static String system() {
        return """
            You read one workplace conversation and extract the facts it establishes.

            WHAT COUNTS AS A FACT
            DECISION      a choice that was actually made and is now in force
            ACTION_ITEM   work a colleague agreed to do for the team
            COMMITMENT    a promise made to someone OUTSIDE the team — a client date, a
                          partner deliverable, a public launch. If the person it is owed
                          to is a colleague, it is an ACTION_ITEM, not a COMMITMENT.
            RISK          a stated concern that has not been resolved
            OPEN_QUESTION something explicitly left unsettled

            STATUS BY TYPE
            DECISION, ACTION_ITEM and COMMITMENT can be PROPOSED, AGREED, IN_PROGRESS,
            DONE, BLOCKED or ABANDONED. A RISK or an OPEN_QUESTION cannot be AGREED or
            DONE — if it were settled it would not be a risk or an open question. Use
            PROPOSED for those unless the text says they were resolved or dropped.

            RULES
            1. Extract only what the text states. If you are inferring rather than reading,
               either set confidence below 0.5 or do not extract it at all.
            2. Every fact needs at least one quote copied character for character from a
               numbered line, together with that line's number. Quotes are checked against
               the source text. A paraphrased quote causes the fact to be discarded.
            3. Quote enough to prove the fact — a full clause, not two or three words.
            4. A suggestion nobody accepted has status PROPOSED, not AGREED. A question is
               not a decision. Somebody arguing for something is not the team deciding it.
            5. Write each statement so it stands alone. "We'll move it to April" is useless
               to a reader six months later; "The Frontier launch moves to April 2026" is not.
            6. Distinguish the person who decided from the people who happened to be present.
            7. Many conversations establish nothing. An empty list is a correct answer and a
               better one than an invented fact.
            8. Never use status SUPERSEDED. You cannot see later conversations.

            EXAMPLE

            Input:
            [0] Dana O. (2026-03-02T10:00:00Z): Do we still want the podcast pilot in April?
            [1] Alex P. (2026-03-02T10:04:00Z): Yes. Pilot goes out April 20, and Tom writes the launch note.
            [2] Tom B. (2026-03-02T10:31:00Z): Can do, I'll have a draft by the 15th.

            Output:
            {"facts":[
              {"type":"DECISION","statement":"The podcast pilot is released on April 20, 2026.",
               "owner":"Alex P.","participants":["Dana O."],"occurredAt":"2026-03-02T10:04:00Z",
               "dueDate":"","status":"AGREED","topics":["podcast"],
               "evidence":[{"utterance":1,"quote":"Pilot goes out April 20"}],"confidence":0.95},
              {"type":"ACTION_ITEM","statement":"Tom B. writes the launch note for the podcast pilot.",
               "owner":"Tom B.","participants":[],"occurredAt":"2026-03-02T10:04:00Z",
               "dueDate":"2026-03-15","status":"AGREED","topics":["podcast"],
               "evidence":[{"utterance":2,"quote":"I'll have a draft by the 15th"}],"confidence":0.9}
            ]}

            Note that the quotes are shorter than the statements and copied exactly. The
            question in line 0 produced no fact.
            """;
    }

    /** Identifies which prompt and which schema produced a fact, for eval comparison. */
    public static String signature() {
        return VERSION + "/ontology-" + Ontology.VERSION;
    }
}
