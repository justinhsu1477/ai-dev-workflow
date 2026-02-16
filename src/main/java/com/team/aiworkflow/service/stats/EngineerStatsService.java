package com.team.aiworkflow.service.stats;

import com.team.aiworkflow.config.EngineerStatsConfig;
import com.team.aiworkflow.model.entity.BugRecord.ResolutionMethod;
import com.team.aiworkflow.model.entity.BugRecord.Severity;
import com.team.aiworkflow.model.entity.EngineerProfile;
import com.team.aiworkflow.model.entity.PushRecord;
import com.team.aiworkflow.model.entity.PushRecord.TestOutcome;
import com.team.aiworkflow.model.stats.EngineerStats;
import com.team.aiworkflow.repository.BugRecordRepository;
import com.team.aiworkflow.repository.PushRecordRepository;
import com.team.aiworkflow.service.notification.TeamsNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工程師品質統計核心服務。
 * 從 DB 查詢資料並組裝成 EngineerStats DTO。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EngineerStatsService {

    private final EngineerStatsConfig config;
    private final PushRecordRepository pushRecordRepo;
    private final BugRecordRepository bugRecordRepo;
    private final TeamsNotificationService teamsNotificationService;

    /**
     * 查詢單一工程師在特定專案的統計資料。
     *
     * @param email   工程師 email
     * @param project 專案名
     * @param from    起始時間
     * @param to      結束時間
     * @return 統計結果
     */
    public EngineerStats getStats(String email, String project,
                                   LocalDateTime from, LocalDateTime to) {
        // Push 統計
        int totalPushes = pushRecordRepo.countPushes(email, project, from, to);
        int failedPushes = pushRecordRepo.countPushesByOutcome(
                email, project, TestOutcome.FAILED, from, to);

        double failureRate = totalPushes > 0
                ? (double) failedPushes / totalPushes : 0.0;

        // Bug 統計
        int totalBugs = bugRecordRepo.countBugs(email, project, from, to);
        double avgBugsPerPush = totalPushes > 0
                ? (double) totalBugs / totalPushes : 0.0;

        // 修復方式統計
        Map<ResolutionMethod, Long> resolutionCounts = parseResolutionCounts(
                bugRecordRepo.countByResolution(email, project, from, to));
        int aiFixedCount = resolutionCounts.getOrDefault(ResolutionMethod.AI_AUTO_FIX, 0L).intValue();
        int unresolvedCount = resolutionCounts.getOrDefault(ResolutionMethod.UNRESOLVED, 0L).intValue();
        double aiFixRate = totalBugs > 0 ? (double) aiFixedCount / totalBugs : 0.0;

        // 嚴重程度分布
        Map<String, Integer> severityDist = parseSeverityCounts(
                bugRecordRepo.countBySeverity(email, project, from, to));

        // 連續失敗
        int currentStreak = getCurrentStreak(email, project);

        // 最後 push 時間（從最近記錄取得）
        List<PushRecord> recentPush = pushRecordRepo.findRecentPushes(
                email, project, PageRequest.of(0, 1));
        LocalDateTime lastPushAt = recentPush.isEmpty()
                ? null : recentPush.get(0).getPushedAt();

        return EngineerStats.builder()
                .engineerName(getDisplayName(email, project, from, to))
                .engineerEmail(email)
                .project(project)
                .totalPushes(totalPushes)
                .failedPushes(failedPushes)
                .failureRate(Math.round(failureRate * 10000) / 100.0) // 百分比，保留兩位小數
                .avgBugsPerPush(Math.round(avgBugsPerPush * 100) / 100.0)
                .totalBugs(totalBugs)
                .aiFixedCount(aiFixedCount)
                .unresolvedCount(unresolvedCount)
                .aiFixRate(Math.round(aiFixRate * 10000) / 100.0) // 百分比
                .severityDistribution(severityDist)
                .currentConsecutiveFailures(currentStreak)
                .maxConsecutiveFailures(currentStreak) // 目前只計算 current
                .periodStart(from)
                .periodEnd(to)
                .lastPushAt(lastPushAt)
                .build();
    }

    /**
     * 查詢某專案內所有工程師的統計資料。
     *
     * @param project 專案名
     * @param from    起始時間
     * @param to      結束時間
     * @return 所有工程師的統計結果（按失敗率排序，高的在前）
     */
    public List<EngineerStats> getAllStats(String project,
                                            LocalDateTime from, LocalDateTime to) {
        List<EngineerProfile> engineers = pushRecordRepo.findActiveEngineers(project, from, to);

        return engineers.stream()
                .map(eng -> getStats(eng.getEmail(), project, from, to))
                .sorted(Comparator.comparingDouble(EngineerStats::getFailureRate).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 取得所有專案名稱。
     */
    public List<String> getAllProjects() {
        return pushRecordRepo.findAllProjects();
    }

    /**
     * 計算工程師目前的連續測試失敗次數。
     * 從最近的 push 開始，遇到 PASSED 就中斷；PENDING / ERROR / TIMEOUT 不計入。
     *
     * @param email   工程師 email
     * @param project 專案名
     * @return 連續失敗次數
     */
    public int getCurrentStreak(String email, String project) {
        List<PushRecord> recent = pushRecordRepo.findRecentPushes(
                email, project, PageRequest.of(0, 50));

        int streak = 0;
        for (PushRecord push : recent) {
            if (push.getTestOutcome() == TestOutcome.FAILED) {
                streak++;
            } else if (push.getTestOutcome() == TestOutcome.PASSED) {
                break; // 遇到通過就中斷
            }
            // PENDING / ERROR / TIMEOUT 不計入，繼續往前看
        }
        return streak;
    }

    /**
     * 檢查並處理連續失敗警報。
     * 在 E2ETestOrchestrator 測試失敗後呼叫。
     *
     * @param email   工程師 email
     * @param project 專案名
     */
    public void checkAndAlertStreak(String email, String project) {
        if (!config.isEnabled()) return;

        int streak = getCurrentStreak(email, project);
        int threshold = config.getConsecutiveFailureThreshold();

        if (streak >= threshold) {
            log.warn("工程師 {} 在專案 {} 連續失敗 {} 次（閾值：{}），發送主管警報",
                    email, project, streak, threshold);

            // 取最近 30 天的統計
            LocalDateTime now = LocalDateTime.now();
            EngineerStats stats = getStats(email, project,
                    now.minusDays(30), now);

            sendManagerAlert(stats, streak);
        }
    }

    /**
     * 發送主管即時警報（連續失敗）。
     */
    private void sendManagerAlert(EngineerStats stats, int streak) {
        String message = String.format(
                "\uD83D\uDEA8 **工程師連續測試失敗警報**\n\n" +
                "**工程師：** %s（%s）\n" +
                "**專案：** %s\n" +
                "**連續失敗 Push：** %d 次（閾值：%d）\n\n" +
                "**近 30 天統計：**\n" +
                "- Push 次數：%d | 失敗率：%.1f%%\n" +
                "- Bug 總數：%d | AI 修復率：%.1f%%\n" +
                "- 嚴重程度分布：%s\n\n" +
                "_請關注該工程師的程式碼品質。_",
                stats.getEngineerName(), stats.getEngineerEmail(),
                stats.getProject(),
                streak, config.getConsecutiveFailureThreshold(),
                stats.getTotalPushes(), stats.getFailureRate(),
                stats.getTotalBugs(), stats.getAiFixRate(),
                formatSeverityDistribution(stats.getSeverityDistribution()));

        try {
            // 優先用主管專用 webhook，沒設定則用一般頻道 webhook
            String managerUrl = config.getManagerTeamsWebhookUrl();
            if (managerUrl != null && !managerUrl.isBlank()) {
                teamsNotificationService.sendToWebhook(managerUrl, message).block();
            } else {
                teamsNotificationService.sendSimpleMessage(message).block();
            }
            log.info("已發送連續失敗警報：{} @ {}", stats.getEngineerEmail(), stats.getProject());
        } catch (Exception e) {
            log.error("發送主管警報失敗：{}", e.getMessage());
        }
    }

    // ========== Private Helpers ==========

    /**
     * 解析修復方式統計查詢結果。
     */
    private Map<ResolutionMethod, Long> parseResolutionCounts(List<Object[]> results) {
        Map<ResolutionMethod, Long> counts = new EnumMap<>(ResolutionMethod.class);
        for (Object[] row : results) {
            ResolutionMethod method = (ResolutionMethod) row[0];
            Long count = (Long) row[1];
            counts.put(method, count);
        }
        return counts;
    }

    /**
     * 解析嚴重程度統計查詢結果。
     */
    private Map<String, Integer> parseSeverityCounts(List<Object[]> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        // 初始化所有等級為 0
        for (Severity s : Severity.values()) {
            counts.put(s.name(), 0);
        }
        for (Object[] row : results) {
            Severity severity = (Severity) row[0];
            Long count = (Long) row[1];
            counts.put(severity.name(), count.intValue());
        }
        return counts;
    }

    /**
     * 格式化嚴重程度分布為字串。
     */
    private String formatSeverityDistribution(Map<String, Integer> dist) {
        if (dist == null || dist.isEmpty()) return "無";
        return dist.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    /**
     * 取得工程師顯示名稱。
     */
    private String getDisplayName(String email, String project,
                                   LocalDateTime from, LocalDateTime to) {
        List<EngineerProfile> engineers = pushRecordRepo.findActiveEngineers(project, from, to);
        return engineers.stream()
                .filter(e -> email.equals(e.getEmail()))
                .map(EngineerProfile::getDisplayName)
                .findFirst()
                .orElse(email);
    }
}
