package com.hhovhann.hivemind.app.config;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the model clients explicitly, instead of relying on LangChain4j's Spring
 * Boot starter.
 *
 * <p>The starter targets Spring Boot 3: its auto-configuration references
 * {@code RestClientAutoConfiguration} and {@code ClientHttpRequestFactorySettings},
 * both of which moved in Boot 4, so the context fails to start. Constructing the two
 * beans here removes that coupling entirely — the only thing the starter was doing
 * was reading properties we already own on {@link HiveLlmProperties}.
 *
 * <p>Worth keeping even once the starter catches up. Two visible beans built from one
 * properties record are easier to reason about than auto-configuration that binds a
 * parallel set of {@code langchain4j.open-ai.*} keys, and it removes a whole class of
 * "which config actually won" question.
 */
@Configuration
public class LlmConfiguration {

    /** ~10k vectors at 768 floats is roughly 30MB — cheap next to what it saves. */
    private static final int EMBEDDING_CACHE_ENTRIES = 10_000;

    private final HiveLlmProperties properties;
    private final boolean logCalls;

    public LlmConfiguration(HiveLlmProperties properties, @Value("${hive.llm.log-calls:false}") boolean logCalls) {
        this.properties = properties;
        this.logCalls = logCalls;
    }

    /**
     * Extraction and answering both want determinism, so temperature is pinned at zero.
     * The timeout is generous because a local model on a laptop can take a minute over
     * a long episode, and a timeout there looks exactly like an extraction failure.
     */
    /**
     * HTTP/1.1, explicitly.
     *
     * <p>The JDK client defaults to HTTP/2, and local OpenAI-compatible servers — LM
     * Studio, Ollama — do not answer the h2c upgrade, so every call hangs until it
     * times out. LangChain4j 1.0 hid this by using OkHttp; 1.18 uses the JDK client,
     * which makes it everyone's problem. Costs nothing against hosted providers, since
     * they negotiate HTTP/2 over TLS regardless of this setting.
     */
    private static JdkHttpClientBuilder httpClient() {
        return new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1));
    }

    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .httpClientBuilder(httpClient())
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .modelName(properties.chatModel())
                .temperature(0.0)
                .timeout(Duration.ofSeconds(180))
                .logRequests(logCalls)
                .logResponses(logCalls)
                .build();
    }

    /**
     * Wrapped in a cache because every retrieval embeds its question before it can
     * touch the index, turning a graph query into a network round trip.
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new CachingEmbeddingModel(
                OpenAiEmbeddingModel.builder()
                        .httpClientBuilder(httpClient())
                        .baseUrl(properties.baseUrl())
                        .apiKey(properties.apiKey())
                        .modelName(properties.embeddingModel())
                        .timeout(Duration.ofSeconds(60))
                        .build(),
                EMBEDDING_CACHE_ENTRIES);
    }
}
