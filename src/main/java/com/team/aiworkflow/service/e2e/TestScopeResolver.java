package com.team.aiworkflow.service.e2e;

import com.team.aiworkflow.config.ModuleMappingConfig.LoginConfig;
import com.team.aiworkflow.config.ModuleMappingConfig.ModuleDefinition;
import com.team.aiworkflow.config.ModuleMappingConfig.ModuleMapping;
import com.team.aiworkflow.config.ModuleMappingConfig.TestFlowDefinition;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 測試範圍解析器。
 * 根據受影響的模組 ID 集合，從映射表中查詢出具體的測試流程，
 * 組裝成 AI Agent 可執行的測試計畫。
 *
 * 支援兩層精準匹配：
 * 1. 模組層級：git diff → file-patterns → 匹配模組
 * 2. Flow 層級：如果 test-flow 有定義 file-patterns，只在檔案匹配時才跑該 flow
 *
 * 職責：
 * 1. 將模組 ID → 測試流程清單
 * 2. 依優先級排序測試流程
 * 3. 提供登入資訊（不同模組可能需要不同角色）
 * 4. 產出「測試範圍描述」供 AI Planner 參考
 */

@Service
@Slf4j
@RequiredArgsConstructor
public class TestScopeResolver {

    private final ModuleMapping moduleMapping;
    private final GitDiffAnalysisService gitDiffAnalysisService;

    /**
     * 根據受影響的模組 ID 集合，解析出完整的測試範圍。
     * 支援 flow-level 精準匹配：如果 test-flow 定義了 file-patterns，
     * 只在變更檔案匹配 flow patterns 時才加入該 flow。
     *
     * @param affectedModuleIds 受影響的模組 ID 集合（如 ["order", "inventory"]）
     * @param changedFiles 變更的檔案路徑清單（用於 flow-level 精準匹配，可為 null）
     * @return 測試範圍，包含所有需要執行的測試流程和登入資訊
     */
    public TestScope resolveScope(Set<String> affectedModuleIds, List<String> changedFiles) {
        if (affectedModuleIds == null || affectedModuleIds.isEmpty()) {
            log.info("沒有受影響的模組，回傳基本冒煙測試範圍");
            return buildSmokeTestScope();
        }

        // 查找受影響的模組定義
        List<ModuleDefinition> affectedModules = moduleMapping.getModules().stream()
                .filter(m -> affectedModuleIds.contains(m.getId()))
                .collect(Collectors.toList());

        if (affectedModules.isEmpty()) {
            log.warn("模組 ID {} 在映射表中找不到對應定義", affectedModuleIds);
            return buildSmokeTestScope();
        }

        // 收集測試流程，支援 flow-level 精準匹配
        List<ResolvedTestFlow> allFlows = new ArrayList<>();
        Set<String> requiredRoles = new LinkedHashSet<>();

        for (ModuleDefinition module : affectedModules) {
            requiredRoles.add(module.getRequiredRole());

            for (TestFlowDefinition flow : module.getTestFlows()) {
                // flow-level 精準匹配：如果 flow 有定義自己的 file-patterns，
                // 只在變更檔案匹配時才加入
                if (flow.getFilePatterns() != null && !flow.getFilePatterns().isEmpty()
                        && changedFiles != null && !changedFiles.isEmpty()) {
                    if (!gitDiffAnalysisService.matchesAnyPattern(changedFiles, flow.getFilePatterns())) {
                        log.debug("Flow '{}' 的 file-patterns 未匹配任何變更檔案，跳過", flow.getName());
                        continue;
                    }
                }

                allFlows.add(ResolvedTestFlow.builder()
                        .flowId(flow.getId())
                        .flowName(flow.getName())
                        .description(flow.getDescription())
                        .route(flow.getRoute())
                        .priority(flow.getPriority())
                        .stepsHint(flow.getStepsHint())
                        .moduleName(module.getName())
                        .moduleId(module.getId())
                        .requiredRole(module.getRequiredRole())
                        .build());
            }
        }

        // 如果所有 flow 都被 flow-level 篩選掉了，回傳冒煙測試
        if (allFlows.isEmpty()) {
            log.info("模組 {} 的所有 flow 都未匹配變更檔案，回傳基本冒煙測試", affectedModuleIds);
            return buildSmokeTestScope();
        }

        // 依優先級排序（數字越小越優先）
        allFlows.sort(Comparator.comparingInt(ResolvedTestFlow::getPriority));

        // 組裝測試範圍描述（給 AI 的上下文）
        String scopeDescription = buildScopeDescription(affectedModules, allFlows);

        // 決定登入帳號（使用最高權限的角色）
        LoginConfig login = moduleMapping.getLogin();
        String testRole = determineTestRole(requiredRoles);

        TestScope scope = TestScope.builder()
                .triggerType("push")
                .testFlows(allFlows)
                .affectedModuleIds(new ArrayList<>(affectedModuleIds))
                .affectedModuleNames(affectedModules.stream()
                        .map(ModuleDefinition::getName)
                        .collect(Collectors.toList()))
                .scopeDescription(scopeDescription)
                .loginUrl(login.getUrl())
                .loginUsername(getLoginUsername(login, testRole))
                .loginPassword(getLoginPassword(login, testRole))
                .loginUsernameField(login.getUsernameField())
                .loginPasswordField(login.getPasswordField())
                .loginSubmitButton(login.getSubmitButton())
                .loginSuccessRedirect(login.getSuccessRedirect())
                .testRole(testRole)
                .totalFlows(allFlows.size())
                .build();

        log.info("測試範圍解析完成：{} 個模組，{} 個測試流程，角色={}",
                affectedModuleIds.size(), allFlows.size(), testRole);

        return scope;
    }

    /**
     * 解析部署觸發的完整測試範圍（所有 critical 模組）。
     *
     * @return 完整測試範圍
     */
    public TestScope resolveDeploymentScope() {
        Set<String> criticalModuleIds = moduleMapping.getModules().stream()
                .filter(ModuleDefinition::isCritical)
                .map(ModuleDefinition::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        log.info("部署觸發：解析 {} 個關鍵模組的測試範圍", criticalModuleIds.size());

        // 部署觸發不做 flow-level 篩選，跑所有 critical 模組的全部 flow
        TestScope scope = resolveScope(criticalModuleIds, null);
        scope.setTriggerType("deployment");
        return scope;
    }

    /**
     * 產生測試範圍描述文字（提供給 AI Planner 作為上下文）。
     * 這段描述會加入到 AI prompt 中，幫助 AI 了解該測試什麼。
     */
    private String buildScopeDescription(List<ModuleDefinition> modules,
                                          List<ResolvedTestFlow> flows) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 本次測試範圍\n\n");
        sb.append("根據程式碼變更分析，以下模組受到影響，需要進行 AI Test Agent：\n\n");

        for (ModuleDefinition module : modules) {
            sb.append(String.format("### %s (%s)%s\n",
                    module.getName(), module.getId(),
                    module.isCritical() ? " ⚠️ 關鍵模組" : ""));

            for (TestFlowDefinition flow : module.getTestFlows()) {
                sb.append(String.format("- **%s** (%s): %s\n",
                        flow.getName(), flow.getRoute(), flow.getDescription()));
                if (flow.getStepsHint() != null && !flow.getStepsHint().isEmpty()) {
                    sb.append(String.format("  測試步驟提示：%s\n", flow.getStepsHint()));
                }
            }
            sb.append("\n");
        }

        sb.append("請依照上述測試流程的順序和提示，規劃具體的 AI Test Agent步驟。\n");
        sb.append("重點驗證：頁面是否正常載入、按鈕是否可點擊、表單是否可提交、資料是否正確顯示。\n");

        return sb.toString();
    }

    /**
     * 建立基本冒煙測試範圍（無法確定受影響模組時的預設）。
     */
    private TestScope buildSmokeTestScope() {
        LoginConfig login = moduleMapping.getLogin();

        List<ResolvedTestFlow> smokeFlows = List.of(
                ResolvedTestFlow.builder()
                        .flowId("smoke-home")
                        .flowName("首頁冒煙測試")
                        .description("驗證應用程式首頁可正常載入")
                        .route("/")
                        .priority(1)
                        .stepsHint("導航到首頁 → 確認頁面載入成功 → 驗證主要 UI 元件存在")
                        .moduleName("基本冒煙測試")
                        .moduleId("smoke")
                        .requiredRole("ADMIN")
                        .build()
        );

        return TestScope.builder()
                .triggerType("smoke")
                .testFlows(smokeFlows)
                .affectedModuleIds(List.of("smoke"))
                .affectedModuleNames(List.of("基本冒煙測試"))
                .scopeDescription("無法判斷受影響的模組，執行基本冒煙測試：驗證首頁載入和基本功能。")
                .loginUrl(login.getUrl())
                .loginUsername(login.getTestUsername())
                .loginPassword(login.getTestPassword())
                .loginUsernameField(login.getUsernameField())
                .loginPasswordField(login.getPasswordField())
                .loginSubmitButton(login.getSubmitButton())
                .loginSuccessRedirect(login.getSuccessRedirect())
                .testRole("ADMIN")
                .totalFlows(1)
                .build();
    }

    /**
     * 決定測試使用的角色。
     * 優先使用 ADMIN（最高權限，可存取所有頁面）。
     */
    private String determineTestRole(Set<String> requiredRoles) {
        // ADMIN 可以存取所有頁面，優先使用
        if (requiredRoles.contains("ADMIN")) return "ADMIN";
        // 其他角色取第一個
        return requiredRoles.stream().findFirst().orElse("ADMIN");
    }

    /**
     * 根據角色取得對應的測試帳號使用者名稱。
     */
    private String getLoginUsername(LoginConfig login, String role) {
        if (login.getRoleAccounts() != null && login.getRoleAccounts().containsKey(role)) {
            return login.getRoleAccounts().get(role).getOrDefault("username", login.getTestUsername());
        }
        return login.getTestUsername();
    }

    /**
     * 根據角色取得對應的測試帳號密碼。
     */
    private String getLoginPassword(LoginConfig login, String role) {
        if (login.getRoleAccounts() != null && login.getRoleAccounts().containsKey(role)) {
            return login.getRoleAccounts().get(role).getOrDefault("password", login.getTestPassword());
        }
        return login.getTestPassword();
    }

    // ========== 資料類別 ==========

    /**
     * 已解析的測試範圍。
     * 包含所有要執行的測試流程、登入資訊、範圍描述等。
     */
    @Data
    @Builder
    public static class TestScope {
        private String triggerType;                  // 觸發類型：push / deployment / smoke
        private List<ResolvedTestFlow> testFlows;    // 所有要執行的測試流程
        private List<String> affectedModuleIds;      // 受影響的模組 ID
        private List<String> affectedModuleNames;    // 受影響的模組名稱
        private String scopeDescription;             // 測試範圍描述（給 AI 的）
        private int totalFlows;                      // 總測試流程數

        // 登入資訊
        private String loginUrl;
        private String loginUsername;
        private String loginPassword;
        private String loginUsernameField;
        private String loginPasswordField;
        private String loginSubmitButton;
        private String loginSuccessRedirect;
        private String testRole;
    }

    /**
     * 已解析的單一測試流程。
     * 從映射表中查詢出的完整測試流程資訊。
     */
    @Data
    @Builder
    public static class ResolvedTestFlow {
        private String flowId;           // 測試流程 ID
        private String flowName;         // 測試流程名稱
        private String description;      // 測試流程描述
        private String route;            // 測試的路由路徑
        private int priority;            // 優先級
        private String stepsHint;        // 測試步驟提示
        private String moduleName;       // 所屬模組名稱
        private String moduleId;         // 所屬模組 ID
        private String requiredRole;     // 需要的角色
    }
}
