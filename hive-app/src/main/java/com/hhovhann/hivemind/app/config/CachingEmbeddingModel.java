package com.hhovhann.hivemind.app.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers vectors it has already computed.
 *
 * <p>Embedding is deterministic for a given model and text, so recomputing it is
 * pure waste — and it is waste on the hot path: every retrieval embeds the incoming
 * question before it can touch the index, which turns a graph query into a network
 * round trip to the model server.
 *
 * <p>The gain is real but bounded, and worth being honest about. It removes repeated
 * work, which helps most where questions repeat — a Slack bot answering "what did we
 * decide about X" for the fifth time — and helps least on a stream of unique
 * questions. It does nothing at all for generation, which is where the actual
 * bottleneck lives.
 *
 * <p>An LRU rather than an unbounded map: question text is user-supplied, so an
 * unbounded cache is a slow memory leak wearing a performance costume.
 */
public class CachingEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(CachingEmbeddingModel.class);

    private final EmbeddingModel delegate;
    private final Map<String, Embedding> cache;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public CachingEmbeddingModel(EmbeddingModel delegate, int maxEntries) {
        this.delegate = delegate;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Embedding> eldest) {
                return size() > maxEntries;
            }
        });
    }

    @Override
    public Response<Embedding> embed(String text) {
        Embedding cached = cache.get(text);
        if (cached != null) {
            hits.incrementAndGet();
            return Response.from(cached);
        }
        misses.incrementAndGet();
        Response<Embedding> response = delegate.embed(text);
        cache.put(text, response.content());
        return response;
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        return embed(segment.text());
    }

    /**
     * Batches only the misses.
     *
     * <p>Matters for extraction and scoring, where a run embeds a hundred statements
     * of which many recur between runs; sending the whole list every time would make
     * the cache useless exactly where the batches are largest.
     */
    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> results = new ArrayList<>(Collections.nCopies(segments.size(), null));
        List<TextSegment> missing = new ArrayList<>();
        List<Integer> missingAt = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            Embedding cached = cache.get(segments.get(i).text());
            if (cached == null) {
                missing.add(segments.get(i));
                missingAt.add(i);
            } else {
                results.set(i, cached);
                hits.incrementAndGet();
            }
        }

        if (!missing.isEmpty()) {
            misses.addAndGet(missing.size());
            List<Embedding> computed = delegate.embedAll(missing).content();
            for (int i = 0; i < computed.size(); i++) {
                results.set(missingAt.get(i), computed.get(i));
                cache.put(missing.get(i).text(), computed.get(i));
            }
        }
        return Response.from(results);
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    /** Hit rate so far, for reporting whether the cache is earning its memory. */
    public double hitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0 : (double) hits.get() / total;
    }

    public void logStats() {
        // SLF4J placeholders take no format specifiers, so the percentage is formatted first.
        log.info(
                "embedding cache: {} hits, {} misses ({}% hit rate), {} entries",
                hits.get(), misses.get(), Math.round(hitRate() * 100), cache.size());
    }
}
