package com.hhovhann.hivemind.app.doctor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hhovhann.hivemind.app.config.HiveLlmProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Checks the OpenAI-compatible endpoint, and — more usefully — that the specific
 * models we are configured to call are actually loaded.
 *
 * <p>LM Studio answers {@code /models} happily while serving nothing, so a bare
 * reachability check passes right up until the first extraction call fails.
 */
@Component
public class LlmProbe implements HealthProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HiveLlmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmProbe(HiveLlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // HTTP/1.1 explicitly. HttpClient defaults to HTTP/2, and local OpenAI-compatible
        // servers — LM Studio among them — do not answer the h2c upgrade, so the request
        // hangs until it times out while curl against the same URL succeeds instantly.
        // The probe then reports the model as unreachable while it is serving happily.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public String component() {
        return "llm";
    }

    @Override
    public CheckResult probe() {
        long start = System.nanoTime();
        String endpoint = properties.baseUrl() + "/models";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsedMs = elapsedMs(start);

            if (response.statusCode() != 200) {
                return CheckResult.down(
                        component(), "HTTP %d from %s".formatted(response.statusCode(), endpoint), elapsedMs);
            }

            List<String> available = modelIds(response.body());
            List<String> missing = new ArrayList<>();
            if (!available.contains(properties.chatModel())) {
                missing.add("chat=" + properties.chatModel());
            }
            if (!available.contains(properties.embeddingModel())) {
                missing.add("embedding=" + properties.embeddingModel());
            }

            if (missing.isEmpty()) {
                return CheckResult.ok(
                        component(), "%s — %d models loaded".formatted(properties.baseUrl(), available.size()),
                        elapsedMs);
            }
            return CheckResult.degraded(
                    component(),
                    "reachable but not serving %s (loaded: %s)".formatted(String.join(", ", missing), available),
                    elapsedMs);
        } catch (java.io.IOException e) {
            return CheckResult.down(component(), "cannot reach %s — %s".formatted(endpoint, describe(e)),
                    elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.down(component(), "interrupted", elapsedMs(start));
        }
    }

    private List<String> modelIds(String body) {
        try {
            JsonNode data = objectMapper.readTree(body).path("data");
            List<String> ids = new ArrayList<>();
            data.forEach(node -> ids.add(node.path("id").asText()));
            return ids;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** ConnectException and friends often carry no message; the type is the useful part. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
