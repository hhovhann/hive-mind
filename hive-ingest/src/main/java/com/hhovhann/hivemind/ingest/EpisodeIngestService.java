package com.hhovhann.hivemind.ingest;

import com.hhovhann.hivemind.core.episode.Episode;
import com.hhovhann.hivemind.core.source.SourceSystem;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Collects episodes from every configured reader. */
@Service
public class EpisodeIngestService {

    private static final Logger log = LoggerFactory.getLogger(EpisodeIngestService.class);

    private final List<EpisodeReader> readers;

    public EpisodeIngestService(List<EpisodeReader> readers) {
        this.readers = List.copyOf(readers);
    }

    /**
     * Every episode across every source, oldest first.
     *
     * <p>Chronological order is not cosmetic: supersession is decided by comparing a
     * new fact against what is already in the graph, so ingesting a June decision
     * before the February one it replaces would invert the chain.
     */
    public List<Episode> readAll() {
        List<Episode> episodes = readers.stream()
                .flatMap(reader -> reader.readAll().stream())
                .sorted(Comparator.comparing(Episode::occurredAt))
                .toList();
        log.info("read {} episodes: {}", episodes.size(), countsBySystem(episodes));
        return episodes;
    }

    public Map<SourceSystem, Long> countsBySystem(List<Episode> episodes) {
        return episodes.stream()
                .collect(Collectors.groupingBy(episode -> episode.source().system(), Collectors.counting()));
    }
}
