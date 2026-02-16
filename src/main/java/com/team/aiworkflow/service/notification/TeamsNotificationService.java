package com.team.aiworkflow.service.notification;

import com.team.aiworkflow.model.AnalysisResult;
import com.team.aiworkflow.model.stats.EngineerStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * 發送訊息到指定的 webhook URL（用於主管專用 webhook）。
     *
     * @param targetWebhookUrl 目標 webhook URL
     * @param message          訊息內容
     */
    public Mono<Void> sendToWebhook(String targetWebhookUrl, String message) {
        if (targetWebhookUrl == null || targetWebhookUrl.isBlank()) {
            log.warn("目標 webhook URL 為空，跳過通知");
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

        return webClient.post()
                .uri(URI.create(targetWebhookUrl))
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v -> log.info("Teams 通知已發送（目標 webhook）"))
                .doOnError(e -> log.error("發送 Teams 通知失敗（目標 webhook）：{}", e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * 發送每週/每月工程師品質摘要報告。
     * 如果提供 webhookUrl 則發到指定 webhook，否則發到一般頻道。
     *
     * @param webhookUrl  目標 webhook URL（null 則用一般頻道）
     * @param allStats    所有工程師的統計資料
     * @param reportType  報告類型（"每週" 或 "每月"）
     */
    public Mono<Void> sendStatsSummary(String webhookUrl, List<EngineerStats> allStats,
                                        String reportType) {
        if (allStats.isEmpty()) {
            log.info("{}摘要報告：無資料，跳過", reportType);
            return Mono.empty();
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("M/d");
        String project = allStats.get(0).getProject();
        String periodStart = allStats.get(0).getPeriodStart() != null
                ? allStats.get(0).getPeriodStart().format(fmt) : "?";
        String periodEnd = allStats.get(0).getPeriodEnd() != null
                ? allStats.get(0).getPeriodEnd().format(fmt) : "?";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\uD83D\uDCCA **%s工程師品質報告**（%s ~ %s）\n",
                reportType, periodStart, periodEnd));
        sb.append(String.format("**專案：** %s\n\n", project));

        // 表頭
        sb.append("| 工程師 | Push | 失敗 | Bug | AI 修復率 | 連續失敗 |\n");
        sb.append("|--------|------|------|-----|----------|--------|\n");

        // 每位工程師一行
        for (EngineerStats stats : allStats) {
            String streakWarning = stats.getCurrentConsecutiveFailures() >= 2 ? " \u26A0\uFE0F" : "";
            sb.append(String.format("| %s | %d | %d(%.0f%%) | %d | %.0f%% | %d%s |\n",
                    stats.getEngineerName(),
                    stats.getTotalPushes(),
                    stats.getFailedPushes(), stats.getFailureRate(),
                    stats.getTotalBugs(),
                    stats.getAiFixRate(),
                    stats.getCurrentConsecutiveFailures(), streakWarning));
        }

        // 整體統計
        int totalPushes = allStats.stream().mapToInt(EngineerStats::getTotalPushes).sum();
        int totalBugs = allStats.stream().mapToInt(EngineerStats::getTotalBugs).sum();
        int totalAiFixes = allStats.stream().mapToInt(EngineerStats::getAiFixedCount).sum();
        double overallAiFixRate = totalBugs > 0 ? (double) totalAiFixes / totalBugs * 100 : 0;

        sb.append(String.format("\n**整體統計：** %d 次 Push，%d 個 Bug，AI 修復率 %.0f%%",
                totalPushes, totalBugs, overallAiFixRate));

        String message = sb.toString();

        // 有指定 webhook 就用指定的，否則用一般頻道
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            return sendToWebhook(webhookUrl, message);
        }
        return sendSimpleMessage(message);
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
