package com.hhovhann.hivemind.app.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The JSON mapper used for the corpus, extraction runs and eval output.
 *
 * <p>Declared explicitly because Spring Boot 4 auto-configures Jackson 3
 * ({@code tools.jackson}) and no longer supplies a Jackson 2 {@code ObjectMapper}.
 * Boot still manages a Jackson 2 BOM, so the library is supported — it simply has to
 * be asked for.
 *
 * <p>Owning the mapper is not a bad outcome. Instants and Durations cross every one
 * of these files, and the settings that make them round-trip — the time module, and
 * dates as ISO strings rather than epoch decimals — belong somewhere visible rather
 * than in whatever the framework defaulted to this release.
 */
@Configuration
public class JsonConfiguration {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Extraction runs are read back by `score` and `load`; epoch decimals
                // would make them unreadable to a human diffing two runs.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                // The corpus and gold set carry fields this code does not model yet.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
