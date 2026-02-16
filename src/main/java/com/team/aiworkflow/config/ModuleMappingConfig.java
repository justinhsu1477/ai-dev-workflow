package com.team.aiworkflow.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * E2E 模組映射設定檔讀取器。
 * 透過 Spring @ConfigurationProperties 自動綁定 e2e-module-mapping.yml。
 * YAML 透過 application.yml 的 spring.config.import 載入。
 * ${} 環境變數佔位符由 Spring 自動解析。
 */
@Configuration
@ConfigurationProperties(prefix = "e2e-mapping")
@Getter
@Setter
@Slf4j
public class ModuleMappingConfig {

    private LoginConfig login = new LoginConfig();
    private List<ModuleDefinition> modules = new ArrayList<>();

    /**
     * 產出 ModuleMapping bean，供 TestScopeResolver 和 GitDiffAnalysisService 注入。
     * 保持與既有消費者相同的型別，無需修改任何 import。
     */
    @Bean
    public ModuleMapping moduleMapping() {
        ModuleMapping mapping = new ModuleMapping();
        mapping.setLogin(this.login);
        mapping.setModules(this.modules);
        log.info("已載入 {} 個模組映射定義", this.modules.size());
        return mapping;
    }

    // ========== 內部資料類別 ==========

    /**
     * E2E 模組映射的頂層物件。
     */
    @Data
    public static class ModuleMapping {
        private LoginConfig login = new LoginConfig();
        private List<ModuleDefinition> modules = new ArrayList<>();
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
        private String requiredRole = "ADMIN"; // 測試此模組需要的角色
        private List<TestFlowDefinition> testFlows = new ArrayList<>();
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
        private int priority = 5;       // 優先級（1 最高）
        private String stepsHint;       // 測試步驟提示（給 AI 的 hint）
        private List<String> filePatterns;  // flow 層級的 file patterns（可選，精準匹配用）
    }
}
