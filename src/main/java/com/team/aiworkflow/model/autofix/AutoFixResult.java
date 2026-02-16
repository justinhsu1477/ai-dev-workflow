package com.team.aiworkflow.model.autofix;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 自動修復的執行結果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoFixResult {

    private int workItemId;
    private AutoFixStatus status;
    private String fixDescription;
    private String branchName;
    private Integer pullRequestId;
    private String failureReason;
    private long durationMs;

    public enum AutoFixStatus {
        FIX_APPLIED,         // 階段 1 完成：修復已套用，PR 已建立，等待 re-test
        VERIFIED,            // 階段 2 完成：re-test 通過，Work Item 已關閉
        FIX_FAILED_TESTS,    // re-test 失敗
        FIX_APPLY_ERROR,     // 無法套用 AI 的修改
        AI_GENERATION_ERROR, // AI 無法產生修復方案
        NO_SOURCE_FILES,     // 找不到相關原始碼
        DISABLED,            // Auto-fix 未啟用
        ERROR                // 未預期錯誤
    }
}
