package com.hhovhann.hivemind.extract;

import com.hhovhann.hivemind.core.episode.Episode;

/** Reads one episode and returns the facts it establishes, already validated. */
public interface FactExtractor {

    ExtractionResult extract(Episode episode);
}
