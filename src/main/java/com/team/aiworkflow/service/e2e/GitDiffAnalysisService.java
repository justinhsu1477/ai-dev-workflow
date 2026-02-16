package com.team.aiworkflow.service.e2e;

import com.team.aiworkflow.config.ModuleMappingConfig.ModuleDefinition;
import com.team.aiworkflow.config.ModuleMappingConfig.ModuleMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Git Diff 分析服務。
 * 分析 git diff 的變更檔案清單，比對模組映射表，
 * 判斷哪些業務模組受到影響，進而決定需要執行哪些 E2E 測試。
 *
 * 使用方式：
 * 1. 從 Azure DevOps Push webhook 或 API 取得變更檔案清單
 * 2. 呼叫 analyzeChangedFiles() 取得受影響的模組
 * 3. 將結果傳給 TestScopeResolver 決定測試範圍
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GitDiffAnalysisService {

    private final ModuleMapping moduleMapping;

    /**
     * 分析變更檔案清單，回傳受影響的模組 ID 集合。
     *
     * @param changedFiles 變更的檔案路徑清單（相對路徑，如 "src/main/java/com/soetek/ods/views/order/SalesOrderD2View.java"）
     * @return 受影響的模組 ID 集合（如 ["order", "sale"]）
     */
    public Set<String> analyzeChangedFiles(List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            log.info("沒有變更檔案，不需要測試");
            return Collections.emptySet();
        }

        log.info("分析 {} 個變更檔案...", changedFiles.size());

        Set<String> affectedModules = new LinkedHashSet<>();

        for (String filePath : changedFiles) {
            // 標準化路徑分隔符號
            String normalizedPath = filePath.replace('\\', '/');

            for (ModuleDefinition module : moduleMapping.getModules()) {
                if (isFileMatchingModule(normalizedPath, module)) {
                    affectedModules.add(module.getId());
                    log.debug("檔案 '{}' 匹配模組 '{}'", normalizedPath, module.getId());
                }
            }
        }

        if (affectedModules.isEmpty()) {
            log.info("變更檔案未匹配到任何模組，將執行基本冒煙測試");
        } else {
            log.info("受影響的模組：{}", affectedModules);
        }

        return affectedModules;
    }

    /**
     * 從 git diff 原始輸出中解析變更檔案清單。
     * 支援 git diff 的標準格式：
     * - diff --git a/path/to/file b/path/to/file
     * - 以及 git diff --name-only 格式
     *
     * @param gitDiffOutput git diff 的原始輸出文字
     * @return 變更的檔案路徑清單
     */
    public List<String> parseGitDiffOutput(String gitDiffOutput) {
        if (gitDiffOutput == null || gitDiffOutput.isBlank()) {
            return Collections.emptyList();
        }

        List<String> changedFiles = new ArrayList<>();

        for (String line : gitDiffOutput.split("\n")) {
            String trimmed = line.trim();

            // 格式 1：diff --git a/path b/path
            if (trimmed.startsWith("diff --git")) {
                String[] parts = trimmed.split(" ");
                if (parts.length >= 4) {
                    // 取 b/ 後面的路徑
                    String bPath = parts[3];
                    if (bPath.startsWith("b/")) {
                        changedFiles.add(bPath.substring(2));
                    }
                }
                continue;
            }

            // 格式 2：git diff --name-only 或 git diff --name-status 的輸出
            // name-only: path/to/file
            // name-status: M\tpath/to/file 或 A\tpath/to/file
            if (!trimmed.isEmpty() && !trimmed.startsWith("---") && !trimmed.startsWith("+++")
                    && !trimmed.startsWith("@@") && !trimmed.startsWith("index ")
                    && !trimmed.startsWith("new file") && !trimmed.startsWith("deleted file")
                    && !trimmed.startsWith("similarity") && !trimmed.startsWith("rename")
                    && !trimmed.startsWith("Binary")) {

                // 處理 name-status 格式（M\tpath）
                if (trimmed.length() > 2 && trimmed.charAt(1) == '\t') {
                    changedFiles.add(trimmed.substring(2));
                }
                // 處理 name-only 格式（pure path）
                else if (trimmed.contains("/") && !trimmed.contains(" ")) {
                    changedFiles.add(trimmed);
                }
            }
        }

        log.info("從 git diff 解析出 {} 個變更檔案", changedFiles.size());
        return changedFiles.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 從 Azure DevOps Push webhook payload 中解析變更檔案。
     * Azure DevOps 的 push event 包含 commits 陣列，
     * 每個 commit 有 changes 陣列（含 item.path）。
     *
     * @param filePaths 從 webhook payload 中提取的檔案路徑清單
     * @return 去重後的變更檔案清單
     */
    public List<String> parseWebhookChangedFiles(List<String> filePaths) {
        if (filePaths == null) return Collections.emptyList();

        return filePaths.stream()
                .map(path -> {
                    // Azure DevOps 路徑可能以 / 開頭，移除它
                    if (path.startsWith("/")) {
                        return path.substring(1);
                    }
                    return path;
                })
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 取得所有標記為 critical 的模組（用於部署觸發的完整測試）。
     *
     * @return 關鍵模組的 ID 集合
     */
    public Set<String> getCriticalModules() {
        return moduleMapping.getModules().stream()
                .filter(ModuleDefinition::isCritical)
                .map(ModuleDefinition::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 取得所有模組 ID（用於全面測試）。
     *
     * @return 所有模組的 ID 集合
     */
    public Set<String> getAllModules() {
        return moduleMapping.getModules().stream()
                .map(ModuleDefinition::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 檢查檔案路徑是否匹配模組的任一 file-pattern。
     * 使用 glob 模式比對（支援 **、* 等通配符）。
     */
    private boolean isFileMatchingModule(String filePath, ModuleDefinition module) {
        if (module.getFilePatterns() == null) return false;

        for (String pattern : module.getFilePatterns()) {
            if (matchGlobPattern(filePath, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Glob 模式比對。
     * 支援 ** (任意層級目錄) 和 * (單層匹配) 通配符。
     *
     * 範例：
     * - "**\/views/order/**" 匹配 "src/main/java/com/soetek/ods/views/order/SalesOrderD2View.java"
     * - "**\/service/order/**" 匹配 "src/main/java/com/soetek/ods/service/order/CustomerOrderService.java"
     */
    private boolean matchGlobPattern(String filePath, String pattern) {
        try {
            // 使用 Java NIO PathMatcher（glob 模式）
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(Paths.get(filePath));
        } catch (Exception e) {
            // 若 PathMatcher 失敗，改用簡單的字串比對
            return simpleGlobMatch(filePath, pattern);
        }
    }

    /**
     * 簡單的 glob 匹配實作（備用方案）。
     * 將 glob 模式轉換為正規表達式進行比對。
     */
    private boolean simpleGlobMatch(String filePath, String pattern) {
        // 將 glob 模式轉換為 regex
        String regex = pattern
                .replace(".", "\\.")
                .replace("**/", "(.+/)?")
                .replace("/**", "(/.*)?")
                .replace("*", "[^/]*");

        try {
            return filePath.matches(regex) || filePath.matches(".*" + regex);
        } catch (Exception e) {
            log.warn("Glob 模式比對失敗：pattern='{}', file='{}'", pattern, filePath);
            return false;
        }
    }
}
