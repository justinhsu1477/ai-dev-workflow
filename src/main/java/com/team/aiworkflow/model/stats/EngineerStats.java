package com.team.aiworkflow.model.stats;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工程師品質統計 DTO。
 * 由 EngineerStatsService 查詢 DB 後組裝，供 API 回傳和 Teams 通知使用。
 */
@Data
@Builder
public class EngineerStats {

    // ===== 工程師資訊 =====
    private String engineerName;
    private String engineerEmail;
    private String project;

    // ===== Push 統計 =====
    /** 期間內 push 總次數 */
    private int totalPushes;
    /** 測試失敗的 push 次數 */
    private int failedPushes;
    /** 失敗率 = failedPushes / totalPushes */
    private double failureRate;
    /** 平均每次 push 產生的 bug 數 */
    private double avgBugsPerPush;

    // ===== Bug 統計 =====
    /** 期間內 bug 總數 */
    private int totalBugs;
    /** AI 自動修復成功的數量 */
    private int aiFixedCount;
    /** 未修復（含人工修復）的數量 */
    private int unresolvedCount;
    /** AI 修復率 = aiFixedCount / totalBugs */
    private double aiFixRate;
    /** 嚴重程度分布：{CRITICAL: 2, HIGH: 5, MEDIUM: 3, LOW: 1} */
    private Map<String, Integer> severityDistribution;

    // ===== 連續失敗 =====
    /** 目前連續失敗次數 */
    private int currentConsecutiveFailures;
    /** 歷史最大連續失敗次數 */
    private int maxConsecutiveFailures;

    // ===== 時間範圍 =====
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private LocalDateTime lastPushAt;
}
