package com.hhovhann.hivemind.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single source of truth for the model endpoint. The LangChain4j starter's own
 * properties are wired from these in {@code application.yml}, so switching from
 * LM Studio to a hosted provider is one block of config, not a search-and-replace.
 *
 * @param baseUrl        OpenAI-compatible base URL, including {@code /v1}
 * @param apiKey         ignored by LM Studio, required by hosted providers
 * @param chatModel      model id used for extraction and generation
 * @param embeddingModel model id used for vector search
 */
@ConfigurationProperties(prefix = "hive.llm")
public record HiveLlmProperties(String baseUrl, String apiKey, String chatModel, String embeddingModel) {
}
