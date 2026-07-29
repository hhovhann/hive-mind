package com.hhovhann.hivemind.graph;

import java.util.List;

/**
 * What a load put into the graph.
 *
 * @param judgements every adjudicated pair, verdicts included — worth reading rather
 *                   than counting, since a wrong supersession removes a live fact
 *                   from every current-state query and shows up nowhere as an error
 */
public record GraphLoadReport(
        int people,
        int episodes,
        int utterances,
        int facts,
        int evidenceEdges,
        int ownedEdges,
        int unresolvedOwners,
        List<SupersessionAdjudicator.Judgement> judgements) {

    public List<SupersessionAdjudicator.Judgement> withVerdict(SupersessionAdjudicator.Verdict verdict) {
        return judgements.stream()
                .filter(judgement -> judgement.verdict() == verdict)
                .toList();
    }

    public int supersessionCount() {
        return withVerdict(SupersessionAdjudicator.Verdict.SUPERSEDES).size();
    }

    public int duplicateCount() {
        return withVerdict(SupersessionAdjudicator.Verdict.DUPLICATE).size();
    }

    /** Pairs similarity flagged that turned out not to be revisions — the noise it saved us from. */
    public int rejectedCandidates() {
        return judgements.size() - supersessionCount();
    }
}
