package com.hhovhann.hivemind.core.temporal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Bi-temporal stamp: when a fact was true, and when we found out.
 *
 * <p>This is what answers "what did we decide six months ago" without also
 * answering it wrongly today. A decision made in March, revised in May, reversed
 * in July is three facts, not one — flat retrieval returns all three with equal
 * weight and lets the model pick. Instead, a superseding fact <em>closes</em> the
 * one before it: {@code validTo} is set, the node stays, and a
 * {@code SUPERSEDES} edge records the lineage. Current state is
 * {@code validTo IS NULL}; history is the chain behind it.
 *
 * <p>Keeping {@code ingestedAt} separate matters because the two clocks disagree:
 * a decision made in March that we only ingest in July is still a March decision,
 * and an audit needs to see both dates.
 *
 * @param validFrom  when the fact became true in the world
 * @param validTo    when it stopped being true; null while it still holds
 * @param ingestedAt when Hive Mind learned it
 */
public record Validity(Instant validFrom, Instant validTo, Instant ingestedAt) {

    public Validity {
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(ingestedAt, "ingestedAt");
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("validTo %s precedes validFrom %s".formatted(validTo, validFrom));
        }
    }

    /** A fact that holds from {@code validFrom} until something supersedes it. */
    public static Validity openFrom(Instant validFrom, Instant ingestedAt) {
        return new Validity(validFrom, null, ingestedAt);
    }

    public boolean isOpen() {
        return validTo == null;
    }

    /** Whether this fact held at {@code moment} — the "as of" query. */
    public boolean heldAt(Instant moment) {
        return !moment.isBefore(validFrom) && (validTo == null || moment.isBefore(validTo));
    }

    /**
     * Closes this validity as of {@code moment}, for when a newer fact supersedes it.
     *
     * <p>Closing before {@code validFrom} would invert the interval, which happens
     * when sources disagree about dates; the fact is clamped to zero length rather
     * than rejected, so ingestion does not fail on a messy timestamp.
     */
    public Validity closedAt(Instant moment) {
        return new Validity(validFrom, moment.isBefore(validFrom) ? validFrom : moment, ingestedAt);
    }

    public Optional<Instant> validToIfClosed() {
        return Optional.ofNullable(validTo);
    }
}
