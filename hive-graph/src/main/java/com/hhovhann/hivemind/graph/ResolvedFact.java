package com.hhovhann.hivemind.graph;

import com.hhovhann.hivemind.core.acl.AclScope;
import com.hhovhann.hivemind.core.entity.MentionResolution;
import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.ontology.ExtractedFact;
import com.hhovhann.hivemind.core.temporal.Validity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * An extracted fact with its people identified and its lifetime established — ready
 * to become a node.
 *
 * <p>The id is derived from content, not generated, so loading the same corpus twice
 * updates the same nodes instead of doubling the graph. Statement text is part of the
 * hash: a re-extraction that words a fact differently is a different fact, which is
 * the honest outcome — it has different evidence and deserves its own row in an audit.
 *
 * @param acl inherited from the source episodes, most restrictive wins
 */
public record ResolvedFact(
        String id,
        ExtractedFact fact,
        Episode episode,
        Validity validity,
        MentionResolution owner,
        List<MentionResolution> participants,
        AclScope acl,
        String ontologyVersion,
        String promptVersion) {

    public ResolvedFact {
        participants = List.copyOf(participants);
    }

    public static ResolvedFact of(
            ExtractedFact fact,
            Episode episode,
            MentionResolution owner,
            List<MentionResolution> participants,
            String ontologyVersion,
            String promptVersion) {
        return new ResolvedFact(
                idFor(episode.id(), fact),
                fact,
                episode,
                // Facts are true from when they were decided, and stay true until
                // something supersedes them. ingestedAt is when we found out, which
                // is a different clock and has to stay separate.
                Validity.openFrom(fact.occurredAt(), episode.ingestedAt()),
                owner,
                participants,
                AclScope.inheritedFrom(List.of(episode.acl())),
                ontologyVersion,
                promptVersion);
    }

    public static String idFor(String episodeId, ExtractedFact fact) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(episodeId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(fact.type().name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(fact.statement()).getBytes(StandardCharsets.UTF_8));
            return "fact:" + HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS and must be present", e);
        }
    }

    public ResolvedFact supersededAt(java.time.Instant moment) {
        return new ResolvedFact(
                id,
                fact,
                episode,
                validity.closedAt(moment),
                owner,
                participants,
                acl,
                ontologyVersion,
                promptVersion);
    }

    public String statement() {
        return fact.statement();
    }
}
