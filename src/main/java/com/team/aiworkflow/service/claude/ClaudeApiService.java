package com.team.aiworkflow.service.claude;

import com.team.aiworkflow.config.ClaudeApiConfig;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Service for calling Claude API via Anthropic's Messages API.
 * Uses WebClient for non-blocking HTTP calls with rate limiting.
 */
@Service
@Slf4j
public class ClaudeApiService {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ClaudeApiConfig config;
    private final Bucket rateLimiter;
    private final WebClient webClient;

    public ClaudeApiService(ClaudeApiConfig config,
                            @Qualifier("claudeApiRateLimiter") Bucket rateLimiter) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.webClient = WebClient.builder()
                .baseUrl(ANTHROPIC_API_URL)
                .defaultHeader("x-api-key", config.getApiKey())
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Send a prompt to Claude API and get the response text.
     * Uses the default model (Sonnet) for standard analysis.
     */
    public Mono<String> analyze(String prompt) {
        return callApi(prompt, config.getModel());
    }

    /**
     * Send a prompt to Claude API using the complex model (Opus).
     * Use this for complex bug analysis or code generation.
     */
    public Mono<String> analyzeComplex(String prompt) {
        return callApi(prompt, config.getModelComplex());
    }

    private Mono<String> callApi(String prompt, String model) {
        if (!rateLimiter.tryConsume(1)) {
            log.warn("Claude API rate limit exceeded. Skipping request.");
            return Mono.error(new RuntimeException("Rate limit exceeded for Claude API"));
        }

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", config.getMaxTokens(),
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                )
        );

        log.info("Calling Claude API with model: {}", model);

        return webClient.post()
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .map(this::extractResponseText)
                .doOnSuccess(response -> log.info("Claude API response received ({} chars)", response.length()))
                .doOnError(error -> log.error("Claude API call failed: {}", error.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private String extractResponseText(Map<String, Object> response) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null || content.isEmpty()) {
            throw new RuntimeException("Empty response from Claude API");
        }
        return (String) content.get(0).get("text");
    }
}
