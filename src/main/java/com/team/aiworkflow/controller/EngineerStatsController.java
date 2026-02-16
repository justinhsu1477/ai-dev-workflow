package com.team.aiworkflow.controller;

import com.team.aiworkflow.model.stats.EngineerStats;
import com.team.aiworkflow.service.stats.EngineerStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 工程師品質統計 REST API。
 *
 * 提供以下端點：
 * - GET /api/report/engineer-stats          單一工程師統計
 * - GET /api/report/engineer-stats/summary  專案內所有工程師摘要
 * - GET /api/report/engineer-stats/{email}/streak  連續失敗次數
 * - GET /api/report/engineer-stats/projects  所有專案清單
 */
@RestController
@RequestMapping("/api/report/engineer-stats")
@Slf4j
@RequiredArgsConstructor
public class EngineerStatsController {

    private final EngineerStatsService statsService;

    /**
     * 查詢單一工程師的統計資料。
     *
     * @param email   工程師 email
     * @param project 專案名
     * @param from    起始日期（預設：30 天前）
     * @param to      結束日期（預設：今天）
     */
    @GetMapping
    public ResponseEntity<EngineerStats> getStats(
            @RequestParam String email,
            @RequestParam String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDateTime fromDateTime = from != null
                ? from.atStartOfDay()
                : LocalDateTime.now().minusDays(30);
        LocalDateTime toDateTime = to != null
                ? to.atTime(LocalTime.MAX)
                : LocalDateTime.now();

        log.info("查詢工程師統計：email={}, project={}, from={}, to={}",
                email, project, fromDateTime, toDateTime);

        EngineerStats stats = statsService.getStats(email, project, fromDateTime, toDateTime);
        return ResponseEntity.ok(stats);
    }

    /**
     * 查詢專案內所有工程師的統計摘要。
     *
     * @param project 專案名
     * @param from    起始日期（預設：30 天前）
     * @param to      結束日期（預設：今天）
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary(
            @RequestParam String project,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDateTime fromDateTime = from != null
                ? from.atStartOfDay()
                : LocalDateTime.now().minusDays(30);
        LocalDateTime toDateTime = to != null
                ? to.atTime(LocalTime.MAX)
                : LocalDateTime.now();

        log.info("查詢專案統計摘要：project={}, from={}, to={}", project, fromDateTime, toDateTime);

        List<EngineerStats> allStats = statsService.getAllStats(project, fromDateTime, toDateTime);

        // 計算專案整體統計
        int totalPushes = allStats.stream().mapToInt(EngineerStats::getTotalPushes).sum();
        int totalBugs = allStats.stream().mapToInt(EngineerStats::getTotalBugs).sum();
        int totalAiFixes = allStats.stream().mapToInt(EngineerStats::getAiFixedCount).sum();

        return ResponseEntity.ok(Map.of(
                "project", project,
                "periodStart", fromDateTime.toString(),
                "periodEnd", toDateTime.toString(),
                "engineerCount", allStats.size(),
                "totalPushes", totalPushes,
                "totalBugs", totalBugs,
                "totalAiFixes", totalAiFixes,
                "overallAiFixRate", totalBugs > 0
                        ? Math.round((double) totalAiFixes / totalBugs * 10000) / 100.0 : 0.0,
                "engineers", allStats));
    }

    /**
     * 查詢工程師目前的連續失敗次數。
     *
     * @param email   工程師 email
     * @param project 專案名
     */
    @GetMapping("/{email}/streak")
    public ResponseEntity<Map<String, Object>> getStreak(
            @PathVariable String email,
            @RequestParam String project) {

        int streak = statsService.getCurrentStreak(email, project);

        return ResponseEntity.ok(Map.of(
                "email", email,
                "project", project,
                "currentConsecutiveFailures", streak));
    }

    /**
     * 取得所有有記錄的專案清單。
     */
    @GetMapping("/projects")
    public ResponseEntity<List<String>> getProjects() {
        return ResponseEntity.ok(statsService.getAllProjects());
    }
}
