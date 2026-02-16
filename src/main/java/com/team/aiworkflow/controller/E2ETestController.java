package com.team.aiworkflow.controller;

import com.team.aiworkflow.model.e2e.E2ETestRequest;
import com.team.aiworkflow.model.e2e.E2ETestResult;
import com.team.aiworkflow.service.e2e.E2ETestOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI E2E 測試 API 端點。
 * 提供手動觸發和同步執行兩種模式。
 *
 * 端點：
 * - POST /api/e2e/run      非同步執行（立即回傳，背景執行）
 * - POST /api/e2e/run-sync  同步執行（等待完成後回傳完整結果）
 */
@RestController
@RequestMapping("/api/e2e")
@Slf4j
@RequiredArgsConstructor
public class E2ETestController {

    private final E2ETestOrchestrator orchestrator;

    @Value("${workflow.e2e-testing.enabled:false}")
    private boolean e2eTestingEnabled;

    /**
     * 手動觸發 AI E2E 測試（非同步）。
     *
     * 範例請求：
     * POST /api/e2e/run
     * {
     *   "appUrl": "https://staging.myapp.com",
     *   "appDescription": "CRUD 使用者管理系統，支援新增、編輯、刪除",
     *   "maxSteps": 20,
     *   "timeoutSeconds": 180
     * }
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> runTest(@RequestBody E2ETestRequest request) {
        if (!e2eTestingEnabled) {
            return ResponseEntity.ok(Map.of("status", "disabled",
                    "message", "E2E 測試功能未啟用"));
        }

        log.info("收到手動 E2E 測試請求：{}", request.getAppUrl());

        if (request.getAppUrl() == null || request.getAppUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "appUrl 為必填欄位"));
        }

        request.setTriggeredBy("manual");
        orchestrator.runTestAsync(request);

        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "message", "E2E 測試已啟動：" + request.getAppUrl()));
    }

    /**
     * 同步觸發 E2E 測試並回傳完整結果。
     * 適用於測試/除錯 E2E 模組本身。
     */
    @PostMapping("/run-sync")
    public ResponseEntity<E2ETestResult> runTestSync(@RequestBody E2ETestRequest request) {
        if (!e2eTestingEnabled) {
            return ResponseEntity.ok(E2ETestResult.builder()
                    .summary("E2E 測試功能未啟用")
                    .status(E2ETestResult.TestRunStatus.ERROR)
                    .build());
        }

        log.info("收到同步 E2E 測試請求：{}", request.getAppUrl());
        E2ETestResult result = orchestrator.runTest(request);
        return ResponseEntity.ok(result);
    }
}
