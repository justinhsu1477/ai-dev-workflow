package com.team.aiworkflow.controller;

import com.team.aiworkflow.config.AutoFixConfig;
import com.team.aiworkflow.model.autofix.AutoFixResult;
import com.team.aiworkflow.model.e2e.E2ETestRequest;
import com.team.aiworkflow.model.e2e.E2ETestResult;
import com.team.aiworkflow.service.autofix.AutoFixOrchestrator;
import com.team.aiworkflow.service.e2e.E2ETestOrchestrator;
import com.team.aiworkflow.service.e2e.TestScopeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 自動修復 API 端點。
 * 提供 re-test 功能：在手動重啟 app 後觸發 E2E 重測，驗證 AI 修復是否有效。
 *
 * 使用方式：
 * 1. E2E 測試發現 bug → 自動修復 → 建立 ai-fix/* 分支 + PR
 * 2. 你在 IDE 切到 ai-fix/* 分支 → 重啟 OCDS Web app
 * 3. 呼叫 POST /api/e2e/autofix/retest/{workItemId}
 * 4. 測試通過 → 自動關閉 Work Item / 測試失敗 → 加註失敗原因
 */
@RestController
@RequestMapping("/api/e2e/autofix")
@Slf4j
@RequiredArgsConstructor
public class AutoFixController {

    private final AutoFixOrchestrator autoFixOrchestrator;
    private final E2ETestOrchestrator e2eTestOrchestrator;
    private final TestScopeResolver testScopeResolver;
    private final AutoFixConfig autoFixConfig;

    @Value("${workflow.e2e-testing.staging-url:}")
    private String stagingUrl;

    @Value("${workflow.e2e-testing.app-description:}")
    private String appDescription;

    /**
     * 觸發 re-test，驗證 AI 修復是否有效。
     *
     * 前置條件：
     * 1. 已切到 ai-fix/{workItemId} 分支
     * 2. 已重啟目標 app（OCDS Web）
     *
     * @param workItemId 要驗證的 Work Item ID
     */
    @PostMapping("/retest/{workItemId}")
    public ResponseEntity<Map<String, Object>> reTest(@PathVariable int workItemId) {
        log.info("收到 re-test 請求：Work Item #{}", workItemId);

        if (!autoFixConfig.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "status", "disabled",
                    "message", "Auto-fix 功能未啟用"));
        }

        if (stagingUrl == null || stagingUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "staging-url 未設定，無法執行 re-test"));
        }

        try {
            // 建立測試請求
            E2ETestRequest request = E2ETestRequest.builder()
                    .appUrl(stagingUrl)
                    .appDescription(appDescription)
                    .maxSteps(30)
                    .timeoutSeconds(300)
                    .triggeredBy("autofix-retest")
                    .build();

            // 使用 deployment scope（測試所有 critical 模組）
            TestScopeResolver.TestScope scope = testScopeResolver.resolveDeploymentScope();

            // 執行 E2E 測試
            log.info("開始 re-test Work Item #{}：{}", workItemId, stagingUrl);
            E2ETestResult testResult = e2eTestOrchestrator.runScopedTest(request, scope);

            // 根據測試結果更新 Work Item
            AutoFixResult fixResult = autoFixOrchestrator.handleReTestResult(workItemId, testResult);

            return ResponseEntity.ok(Map.of(
                    "workItemId", workItemId,
                    "status", fixResult.getStatus().name(),
                    "testStatus", testResult.getStatus().name(),
                    "bugsFound", testResult.getBugsFound().size(),
                    "stepsExecuted", testResult.getTotalSteps(),
                    "stepsPassed", testResult.getPassedSteps(),
                    "message", fixResult.getStatus() == AutoFixResult.AutoFixStatus.VERIFIED
                            ? "Re-test 通過，Work Item 已關閉"
                            : "Re-test 失敗，Work Item 仍開啟"));

        } catch (Exception e) {
            log.error("Re-test Work Item #{} 發生錯誤：{}", workItemId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Re-test 執行失敗：" + e.getMessage()));
        }
    }
}
