package com.team.aiworkflow.service.stats;

import com.team.aiworkflow.config.EngineerStatsConfig;
import com.team.aiworkflow.model.stats.EngineerStats;
import com.team.aiworkflow.service.notification.TeamsNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 工程師品質報告定時排程。
 * 每週一早上 9 點發送週報，每月 1 號早上 9 點發送月報。
 * 透過 Teams webhook 發送到主管頻道。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EngineerStatsScheduler {

    private final EngineerStatsConfig config;
    private final EngineerStatsService statsService;
    private final TeamsNotificationService teamsNotificationService;

    /**
     * 每週一早上 9 點發送上週的品質報告。
     */
    @Scheduled(cron = "${workflow.engineer-stats.cron-weekly-summary:0 0 9 * * MON}")
    public void sendWeeklySummary() {
        if (!config.isEnabled()) {
            log.debug("工程師績效追蹤未啟用，跳過週報");
            return;
        }

        log.info("開始產生每週工程師品質報告...");

        // 上週一 00:00 ~ 上週日 23:59
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekEnd = now.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .with(LocalTime.MIN); // 本週一 00:00
        LocalDateTime weekStart = weekEnd.minusDays(7); // 上週一 00:00

        sendSummaryForAllProjects(weekStart, weekEnd.minusSeconds(1), "每週");
    }

    /**
     * 每月 1 號早上 9 點發送上月的品質報告。
     */
    @Scheduled(cron = "${workflow.engineer-stats.cron-monthly-summary:0 0 9 1 * *}")
    public void sendMonthlySummary() {
        if (!config.isEnabled()) {
            log.debug("工程師績效追蹤未啟用，跳過月報");
            return;
        }

        log.info("開始產生每月工程師品質報告...");

        // 上個月 1 號 ~ 上個月最後一天
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime monthStart = now.minusMonths(1)
                .with(TemporalAdjusters.firstDayOfMonth())
                .with(LocalTime.MIN);
        LocalDateTime monthEnd = now.with(TemporalAdjusters.firstDayOfMonth())
                .with(LocalTime.MIN).minusSeconds(1);

        sendSummaryForAllProjects(monthStart, monthEnd, "每月");
    }

    /**
     * 對所有有記錄的專案發送摘要報告。
     * 優先用主管專用 webhook，沒設定則用一般頻道 webhook。
     */
    private void sendSummaryForAllProjects(LocalDateTime from, LocalDateTime to,
                                            String reportType) {
        try {
            // 優先用主管 webhook，沒設定則用一般頻道 webhook
            String managerUrl = config.getManagerTeamsWebhookUrl();
            boolean useManagerWebhook = managerUrl != null && !managerUrl.isBlank();

            List<String> projects = statsService.getAllProjects();
            log.info("{}報告：共 {} 個專案", reportType, projects.size());

            for (String project : projects) {
                List<EngineerStats> stats = statsService.getAllStats(project, from, to);
                if (!stats.isEmpty()) {
                    if (useManagerWebhook) {
                        teamsNotificationService.sendStatsSummary(managerUrl, stats, reportType)
                                .block();
                    } else {
                        // 用一般頻道 webhook 發送（組裝簡化訊息）
                        teamsNotificationService.sendStatsSummary(null, stats, reportType)
                                .block();
                    }
                    log.info("{}報告已發送：專案 {}，{} 位工程師",
                            reportType, project, stats.size());
                }
            }
        } catch (Exception e) {
            log.error("發送{}報告失敗：{}", reportType, e.getMessage(), e);
        }
    }
}
