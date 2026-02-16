package com.team.aiworkflow.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * E2E 模組映射設定檔讀取器。
 * 從 e2e-module-mapping.yml 讀取「檔案路徑 → 模組 → 測試流程」的對應關係。
 */
@Configuration
@Slf4j
public class ModuleMappingConfig {

    /**
     * 從 classpath 載入 e2e-module-mapping.yml 並解析為 ModuleMapping 物件。
     */
    @Bean
    public ModuleMapping moduleMapping() {
        try {
            Yaml yaml = new Yaml();
            InputStream inputStream = getClass().getClassLoader()
                    .getResourceAsStream("e2e-module-mapping.yml");

            if (inputStream == null) {
                log.warn("找不到 e2e-module-mapping.yml，使用空白映射");
                return new ModuleMapping();
            }

            Map<String, Object> raw = yaml.load(inputStream);
            return parseModuleMapping(raw);

        } catch (Exception e) {
            log.error("載入 e2e-module-mapping.yml 失敗：{}", e.getMessage());
            return new ModuleMapping();
        }
    }

    /**
     * 將 YAML 原始 Map 解析為結構化的 ModuleMapping 物件。
     */
    @SuppressWarnings("unchecked")
    private ModuleMapping parseModuleMapping(Map<String, Object> raw) {
        ModuleMapping mapping = new ModuleMapping();

        // 解析登入設定
        if (raw.containsKey("login")) {
            Map<String, Object> loginMap = (Map<String, Object>) raw.get("login");
            LoginConfig loginConfig = new LoginConfig();
            loginConfig.setUrl((String) loginMap.getOrDefault("url", "/login"));
            loginConfig.setUsernameField((String) loginMap.getOrDefault("username-field", "input[name='username']"));
            loginConfig.setPasswordField((String) loginMap.getOrDefault("password-field", "input[name='password']"));
            loginConfig.setSubmitButton((String) loginMap.getOrDefault("submit-button", "button[type='submit']"));
            loginConfig.setTestUsername((String) loginMap.getOrDefault("test-username", "admin"));
            loginConfig.setTestPassword((String) loginMap.getOrDefault("test-password", "admin"));
            loginConfig.setSuccessRedirect((String) loginMap.getOrDefault("success-redirect", "/"));

            // 解析角色帳號
            if (loginMap.containsKey("role-accounts")) {
                Map<String, Map<String, String>> roleAccounts =
                        (Map<String, Map<String, String>>) loginMap.get("role-accounts");
                loginConfig.setRoleAccounts(roleAccounts);
            }

            mapping.setLogin(loginConfig);
        }

        // 解析模組列表
        if (raw.containsKey("modules")) {
            List<Map<String, Object>> modulesList = (List<Map<String, Object>>) raw.get("modules");
            for (Map<String, Object> moduleMap : modulesList) {
                ModuleDefinition module = new ModuleDefinition();
                module.setId((String) moduleMap.get("id"));
                module.setName((String) moduleMap.get("name"));
                module.setCritical(Boolean.TRUE.equals(moduleMap.get("critical")));
                module.setFilePatterns((List<String>) moduleMap.get("file-patterns"));
                module.setRequiredRole((String) moduleMap.getOrDefault("required-role", "ADMIN"));

                // 解析測試流程
                if (moduleMap.containsKey("test-flows")) {
                    List<Map<String, Object>> flowsList =
                            (List<Map<String, Object>>) moduleMap.get("test-flows");
                    for (Map<String, Object> flowMap : flowsList) {
                        TestFlowDefinition flow = new TestFlowDefinition();
                        flow.setId((String) flowMap.get("id"));
                        flow.setName((String) flowMap.get("name"));
                        flow.setDescription((String) flowMap.get("description"));
                        flow.setRoute((String) flowMap.get("route"));
                        flow.setPriority(flowMap.get("priority") != null
                                ? ((Number) flowMap.get("priority")).intValue() : 5);
                        flow.setStepsHint((String) flowMap.getOrDefault("steps-hint", ""));
                        module.getTestFlows().add(flow);
                    }
                }

                mapping.getModules().add(module);
            }
        }

        log.info("已載入 {} 個模組映射定義", mapping.getModules().size());
        return mapping;
    }

    // ========== 內部資料類別 ==========

    /**
     * E2E 模組映射的頂層物件。
     */
    @Data
    public static class ModuleMapping {
        private LoginConfig login = new LoginConfig();
        private List<ModuleDefinition> modules = new java.util.ArrayList<>();
    }

    /**
     * 登入設定。
     */
    @Data
    public static class LoginConfig {
        private String url = "/login";
        private String usernameField = "input[name='username']";
        private String passwordField = "input[name='password']";
        private String submitButton = "button[type='submit']";
        private String testUsername = "admin";
        private String testPassword = "admin";
        private String successRedirect = "/";
        private Map<String, Map<String, String>> roleAccounts;
    }

    /**
     * 單一業務模組定義。
     */
    @Data
    public static class ModuleDefinition {
        private String id;                     // 模組 ID（如 "order"）
        private String name;                   // 模組名稱（如 "訂貨作業"）
        private boolean critical;              // 是否為關鍵模組（部署觸發時必測）
        private List<String> filePatterns;     // 原始碼檔案路徑 glob 模式
        private String requiredRole;           // 測試此模組需要的角色
        private List<TestFlowDefinition> testFlows = new java.util.ArrayList<>();
    }

    /**
     * 單一測試流程定義。
     */
    @Data
    public static class TestFlowDefinition {
        private String id;              // 測試流程 ID
        private String name;            // 測試流程名稱
        private String description;     // 測試流程描述（給 AI 看的）
        private String route;           // 測試的路由路徑
        private int priority;           // 優先級（1 最高）
        private String stepsHint;       // 測試步驟提示（給 AI 的 hint）
    }
}
