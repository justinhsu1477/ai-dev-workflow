package com.team.aiworkflow.service.notification;

import com.team.aiworkflow.model.AnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Service for sending notifications to Microsoft Teams via Incoming Webhook.
 * Sends formatted Adaptive Card messages for AI analysis results.
 */
@Service
@Slf4j
public class TeamsNotificationService {

    private final WebClient webClient;
    private final String webhookUrl;

    public TeamsNotificationService(@Value("${teams.webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.webClient = WebClient.create();
        log.info("Teams webhook URL 長度：{}", webhookUrl != null ? webhookUrl.length() : 0);
    }

    /**
     * Send a notification about test failure analysis to Teams.
     */
    public Mono<Void> notifyAnalysisResult(AnalysisResult result) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("Teams webhook URL not configured. Skipping notification.");
            return Mono.empty();
        }

        log.info("Sending Teams notification for build #{}", result.getBuildNumber());

        Map<String, Object> card = buildAdaptiveCard(result);

        return webClient.post()
                .uri(URI.create(webhookUrl))
                .bodyValue(card)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v -> log.info("Teams notification sent for build #{}", result.getBuildNumber()))
                .doOnError(e -> log.error("Failed to send Teams notification: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Send a simple text notification to Teams.
     */
    public Mono<Void> sendSimpleMessage(String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("Teams webhook URL 未設定，跳過 AI Test Agent通知");
            return Mono.empty();
        }

        Map<String, Object> payload = Map.of(
                "type", "message",
                "attachments", List.of(Map.of(
                        "contentType", "application/vnd.microsoft.card.adaptive",
                        "content", Map.of(
                                "$schema", "http://adaptivecards.io/schemas/adaptive-card.json",
                                "type", "AdaptiveCard",
                                "version", "1.4",
                                "body", List.of(
                                        Map.of("type", "TextBlock", "text", message, "wrap", true)
                                )
                        )
                ))
        );

        log.info("正在發送 Teams AI Test Agent通知...");

        return webClient.post()
                .uri(URI.create(webhookUrl))
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v -> log.info("Teams AI Test Agent通知已發送"))
                .doOnError(e -> log.error("發送 Teams AI Test Agent通知失敗：{}", e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    private Map<String, Object> buildAdaptiveCard(AnalysisResult result) {
        String severityColor = switch (result.getSeverity()) {
            case CRITICAL -> "attention";
            case HIGH -> "warning";
            case MEDIUM -> "accent";
            case LOW -> "good";
            default -> "default";
        };

        return Map.of(
                "type", "message",
                "attachments", List.of(Map.of(
                        "contentType", "application/vnd.microsoft.card.adaptive",
                        "content", Map.of(
                                "$schema", "http://adaptivecards.io/schemas/adaptive-card.json",
                                "type", "AdaptiveCard",
                                "version", "1.4",
                                "body", List.of(
                                        Map.of(
                                                "type", "TextBlock",
                                                "text", "🔴 Test Failure Detected - Build #" + result.getBuildNumber(),
                                                "weight", "bolder",
                                                "size", "medium"
                                        ),
                                        Map.of(
                                                "type", "FactSet",
                                                "facts", List.of(
                                                        Map.of("title", "Branch", "value", result.getBranch()),
                                                        Map.of("title", "Severity", "value", result.getSeverity().name()),
                                                        Map.of("title", "Work Item", "value",
                                                                result.getWorkItemId() > 0 ? "#" + result.getWorkItemId() : "N/A")
                                                )
                                        ),
                                        Map.of(
                                                "type", "TextBlock",
                                                "text", "**Root Cause:** " + truncate(result.getRootCause(), 300),
                                                "wrap", true
                                        ),
                                        Map.of(
                                                "type", "TextBlock",
                                                "text", "**Suggested Fix:** " + truncate(result.getSuggestedFix(), 300),
                                                "wrap", true
                                        )
                                )
                        )
                ))
        );
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "N/A";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
