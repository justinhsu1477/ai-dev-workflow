package com.team.aiworkflow.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.team.aiworkflow.model.e2e.E2ETestRequest;
import com.team.aiworkflow.service.e2e.E2ETestOrchestrator;
import com.team.aiworkflow.service.e2e.GitDiffAnalysisService;
import com.team.aiworkflow.service.e2e.TestScopeResolver;
import com.team.aiworkflow.service.e2e.TestScopeResolver.TestScope;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接收 Azure DevOps「程式碼 Push」Service Hook 事件。
 * Push 到指定分支後，分析 git diff 判斷受影響的模組，
 * 自動觸發對應範圍的 AI E2E 測試（精準測試，非全量）。
 *
 * 設定步驟：
 * 1. Azure DevOps → Project Settings → Service Hooks
 * 2. 建立 Subscription → Web Hooks
 * 3. 觸發條件：「Code pushed」
 * 4. URL：https://your-app/webhook/push
 *
 * 也支援手動 API 呼叫：
 * POST /webhook/push/manual
 * Body: { "changedFiles": ["src/main/java/.../OrderView.java", ...], "branch": "develop" }
 */
@RestController
@RequestMapping("/webhook")
@Slf4j
@RequiredArgsConstructor
public class PushWebhookController {

    private final GitDiffAnalysisService gitDiffAnalysisService;
    private final TestScopeResolver testScopeResolver;
    private final E2ETestOrchestrator orchestrator;

    @Value("${workflow.e2e-testing.enabled:false}")
    private boolean e2eTestingEnabled;

    @Value("${workflow.e2e-testing.staging-url:}")
    private String stagingUrl;

    @Value("${workflow.e2e-testing.app-description:}")
    private String appDescription;

    @Value("${workflow.e2e-testing.max-steps:30}")
    private int maxSteps;

    @Value("${workflow.e2e-testing.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${workflow.e2e-testing.repository:}")
    private String allowedRepository;

    @Value("${workflow.e2e-testing.branches:}")
    private String allowedBranches;

    /**
     * 接收 Azure DevOps Push 事件，分析變更檔案並觸發精準 E2E 測試。
     */
    @PostMapping("/push")
    public ResponseEntity<Map<String, Object>> handlePushEvent(
            @RequestBody(required = false) PushEvent event) {

        log.info("收到程式碼 Push 事件");

        if (!e2eTestingEnabled) {
            return ResponseEntity.ok(Map.of("status", "disabled",
                    "message", "AI Test Agent功能未啟用"));
        }

        if (stagingUrl == null || stagingUrl.isBlank()) {
            log.warn("AI Test Agent已啟用，但未設定 staging URL");
            return ResponseEntity.ok(Map.of("status", "error",
                    "message", "staging-url 未設定"));
        }

        // 檢查 Repository 是否符合
        String repoName = extractRepositoryName(event);
        if (allowedRepository != null && !allowedRepository.isBlank()
                && !allowedRepository.equals(repoName)) {
            log.info("略過非目標 Repository 的 Push 事件：{}（期望：{}）", repoName, allowedRepository);
            return ResponseEntity.ok(Map.of("status", "skipped",
                    "message", String.format("非目標 Repository（%s），略過", repoName)));
        }

        // 檢查 Branch 是否在允許清單中
        String branch = extractBranch(event);
        if (allowedBranches != null && !allowedBranches.isBlank()) {
            Set<String> branchSet = Set.of(allowedBranches.split(","));
            if (!branchSet.contains(branch)) {
                log.info("略過非目標分支的 Push 事件：{}（允許：{}）", branch, allowedBranches);
                return ResponseEntity.ok(Map.of("status", "skipped",
                        "message", String.format("非目標分支（%s），略過", branch)));
            }
        }

        // 從 Push event 中提取變更檔案
        List<String> changedFiles = extractChangedFiles(event);

        if (changedFiles.isEmpty()) {
            log.info("Push 事件中沒有變更檔案");
            return ResponseEntity.ok(Map.of("status", "skipped",
                    "message", "沒有偵測到程式碼變更"));
        }

        // 分析受影響的模組
        Set<String> affectedModules = gitDiffAnalysisService.analyzeChangedFiles(changedFiles);

        // 解析測試範圍（傳入 changedFiles 做 flow-level 精準匹配）
        TestScope scope = testScopeResolver.resolveScope(affectedModules, changedFiles);

        // 建立 E2E 測試請求（branch 已在上方過濾時提取）
        E2ETestRequest request = E2ETestRequest.builder()
                .appUrl(stagingUrl)
                .appDescription(buildScopedDescription(scope))
                .buildNumber(event != null ? extractPushId(event) : "manual")
                .branch(branch)
                .maxSteps(maxSteps)
                .timeoutSeconds(timeoutSeconds)
                .triggeredBy("push-webhook")
                .build();

        // 非同步執行測試
        orchestrator.runScopedTestAsync(request, scope);

        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "message", String.format("AI Test Agent已觸發（%s）：%s",
                        scope.getTriggerType(), stagingUrl),
                "affectedModules", new ArrayList<>(affectedModules),
                "testFlowCount", scope.getTotalFlows(),
                "changedFileCount", changedFiles.size()));
    }

    /**
     * 手動提供變更檔案清單觸發精準 E2E 測試。
     * 適用於本機開發測試或不透過 Azure DevOps 的場景。
     *
     * 範例請求：
     * POST /webhook/push/manual
     * {
     *   "changedFiles": [
     *     "src/main/java/com/soetek/ods/views/order/SalesOrderD2View.java",
     *     "src/main/java/com/soetek/ods/service/order/CustomerOrderService.java"
     *   ],
     *   "branch": "feature/order-fix"
     * }
     */
    @PostMapping("/push/manual")
    public ResponseEntity<Map<String, Object>> handleManualPush(
            @RequestBody ManualPushRequest request) {

        log.info("收到手動 Push 測試請求，{} 個變更檔案", request.getChangedFiles().size());

        if (!e2eTestingEnabled) {
            return ResponseEntity.ok(Map.of("status", "disabled",
                    "message", "AI Test Agent功能未啟用"));
        }

        if (stagingUrl == null || stagingUrl.isBlank()) {
            return ResponseEntity.ok(Map.of("status", "error",
                    "message", "staging-url 未設定"));
        }

        // 分析受影響的模組
        List<String> changedFiles = request.getChangedFiles();
        Set<String> affectedModules = gitDiffAnalysisService
                .analyzeChangedFiles(changedFiles);

        // 解析測試範圍（傳入 changedFiles 做 flow-level 精準匹配）
        TestScope scope = testScopeResolver.resolveScope(affectedModules, changedFiles);

        // 建立測試請求
        E2ETestRequest testRequest = E2ETestRequest.builder()
                .appUrl(stagingUrl)
                .appDescription(buildScopedDescription(scope))
                .buildNumber("manual")
                .branch(request.getBranch() != null ? request.getBranch() : "unknown")
                .maxSteps(maxSteps)
                .timeoutSeconds(timeoutSeconds)
                .triggeredBy("manual-push")
                .build();

        orchestrator.runScopedTestAsync(testRequest, scope);

        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "message", String.format("精準 AI Test Agent已觸發：%d 個模組，%d 個測試流程",
                        affectedModules.size(), scope.getTotalFlows()),
                "affectedModules", new ArrayList<>(affectedModules),
                "testFlows", scope.getTestFlows().stream()
                        .map(f -> f.getFlowName() + " (" + f.getRoute() + ")")
                        .toList()));
    }

    /**
     * 查詢分析結果（不實際執行測試）。
     * 用於預覽 git diff 分析會觸發哪些模組和測試。
     */
    @PostMapping("/push/analyze")
    public ResponseEntity<Map<String, Object>> analyzeOnly(
            @RequestBody ManualPushRequest request) {

        List<String> analyzeFiles = request.getChangedFiles();
        Set<String> affectedModules = gitDiffAnalysisService
                .analyzeChangedFiles(analyzeFiles);

        TestScope scope = testScopeResolver.resolveScope(affectedModules, analyzeFiles);

        return ResponseEntity.ok(Map.of(
                "affectedModules", new ArrayList<>(affectedModules),
                "affectedModuleNames", scope.getAffectedModuleNames(),
                "testFlows", scope.getTestFlows().stream()
                        .map(f -> Map.of(
                                "id", f.getFlowId(),
                                "name", f.getFlowName(),
                                "route", f.getRoute(),
                                "module", f.getModuleName(),
                                "priority", f.getPriority()))
                        .toList(),
                "totalFlows", scope.getTotalFlows(),
                "scopeDescription", scope.getScopeDescription(),
                "testRole", scope.getTestRole()));
    }

    /**
     * 組合應用程式描述和測試範圍描述。
     */
    private String buildScopedDescription(TestScope scope) {
        StringBuilder desc = new StringBuilder();
        if (appDescription != null && !appDescription.isBlank()) {
            desc.append(appDescription).append("\n\n");
        }
        desc.append(scope.getScopeDescription());
        return desc.toString();
    }

    /**
     * 從 Azure DevOps Push event 中提取變更檔案。
     */
    private List<String> extractChangedFiles(PushEvent event) {
        if (event == null || event.getResource() == null
                || event.getResource().getCommits() == null) {
            return List.of();
        }

        List<String> allFiles = new ArrayList<>();
        for (PushCommit commit : event.getResource().getCommits()) {
            if (commit.getChanges() != null) {
                for (PushChange change : commit.getChanges()) {
                    if (change.getItem() != null && change.getItem().getPath() != null) {
                        allFiles.add(change.getItem().getPath());
                    }
                }
            }
        }

        return gitDiffAnalysisService.parseWebhookChangedFiles(allFiles);
    }

    /**
     * 從 Push event 中提取分支名稱。
     */
    private String extractBranch(PushEvent event) {
        if (event.getResource() != null && event.getResource().getRefUpdates() != null
                && !event.getResource().getRefUpdates().isEmpty()) {
            String refName = event.getResource().getRefUpdates().get(0).getName();
            // refs/heads/develop → develop
            if (refName != null && refName.startsWith("refs/heads/")) {
                return refName.substring("refs/heads/".length());
            }
            return refName;
        }
        return "unknown";
    }

    /**
     * 從 Push event 中提取 Repository 名稱。
     */
    private String extractRepositoryName(PushEvent event) {
        if (event != null && event.getResource() != null
                && event.getResource().getRepository() != null) {
            return event.getResource().getRepository().getName();
        }
        return "unknown";
    }

    /**
     * 從 Push event 中提取 Push ID。
     */
    private String extractPushId(PushEvent event) {
        if (event.getResource() != null && event.getResource().getPushId() != null) {
            return event.getResource().getPushId().toString();
        }
        return "unknown";
    }

    // ========== DTO 類別 ==========

    /**
     * Azure DevOps Push 事件 DTO。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PushEvent {
        private String eventType;
        private PushResource resource;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PushResource {
        private Integer pushId;
        private List<PushCommit> commits;
        private List<RefUpdate> refUpdates;
        private PushRepository repository;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PushRepository {
        private String id;
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PushCommit {
        private String commitId;
        private String comment;
        private List<PushChange> changes;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PushChange {
        private ChangeItem item;
        private String changeType; // add, edit, delete
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChangeItem {
        private String path;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RefUpdate {
        private String name; // refs/heads/develop
        private String oldObjectId;
        private String newObjectId;
    }

    /**
     * 手動 Push 測試請求 DTO。
     */
    @Data
    public static class ManualPushRequest {
        private List<String> changedFiles;
        private String branch;
    }
}
