package com.hhovhann.hivemind.ingest;

import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.source.SourceSystem;
import java.util.List;

/**
 * Turns one source system's raw content into episodes.
 *
 * <p>Readers are responsible for the boundary decision — what counts as one unit of
 * discourse — because only they know the system's structure. A Slack thread is
 * bounded by its {@code thread_ts}, a meeting by its recording, a Notion page by its
 * revision. Getting that boundary right is most of what separates this from
 * chunking.
 */
public interface EpisodeReader {

    SourceSystem system();

    /**
     * Reads everything available.
     *
     * <p>Returns a list rather than a stream because callers assemble, extract, and
     * count over the result, and the corpus is small enough that laziness buys
     * nothing. Incremental sync in M2 will take a cursor instead.
     */
    List<Episode> readAll();
}
