package com.team.aiworkflow.service.autofix;

import com.team.aiworkflow.config.AutoFixConfig;
import com.team.aiworkflow.config.ModuleMappingConfig;
import com.team.aiworkflow.config.ModuleMappingConfig.ModuleDefinition;
import com.team.aiworkflow.config.ModuleMappingConfig.TestFlowDefinition;
import com.team.aiworkflow.model.e2e.E2ETestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 根據 bug 資訊找到目標 repo 中相關的原始碼檔案。
 * 透過路由比對模組、從技術描述提取元件名稱、掃描檔案系統。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SourceCodeResolver {

    private final AutoFixConfig autoFixConfig;
    private final ModuleMappingConfig.ModuleMapping moduleMapping;

    /** 提取 PascalCase 元件名稱的 regex */
    private static final Pattern COMPONENT_NAME_PATTERN = Pattern.compile(
            "\\b([A-Z][a-zA-Z0-9]*(?:View|Component|Service|Grid|Dialog|Search|Form|Layout))\\b");

    /**
     * 根據 bug 找到相關原始碼檔案並讀取。
     *
     * @return filePath（相對於 repo root）→ 檔案內容
     */
    public Map<String, String> resolveSourceFiles(E2ETestResult.BugFound bug) {
        Map<String, String> result = new LinkedHashMap<>();

        Path repoRoot = Path.of(autoFixConfig.getTargetRepoPath());
        Path sourceRoot = repoRoot.resolve(autoFixConfig.getSourceBasePath());

        if (!Files.isDirectory(sourceRoot)) {
            log.warn("原始碼目錄不存在：{}", sourceRoot);
            return result;
        }

        // 1. 從 bug 的 technicalDetail 提取元件名稱
        List<String> componentNames = extractComponentNames(bug.getActualBehavior());
        log.info("從 bug 描述提取到元件名稱：{}", componentNames);

        // 2. 從路由找到對應的 module，取得 filePatterns
        ModuleDefinition module = findModuleByRoute(bug.getPageUrl());
        List<String> filePatterns = module != null ? module.getFilePatterns() : List.of();
        log.info("對應模組：{}，filePatterns：{}", module != null ? module.getName() : "未找到", filePatterns);

        // 3. 先找明確提到的元件檔案（優先級最高）
        for (String name : componentNames) {
            if (result.size() >= autoFixConfig.getMaxFilesToRead()) break;
            findFilesByClassName(sourceRoot, repoRoot, name, result);
        }

        // 4. 再根據 module filePatterns 找其他相關檔案
        for (String pattern : filePatterns) {
            if (result.size() >= autoFixConfig.getMaxFilesToRead()) break;
            findFilesByPattern(repoRoot, pattern, result);
        }

        log.info("共找到 {} 個相關原始碼檔案", result.size());
        return result;
    }

    /**
     * 組裝成 prompt 用的 code context 字串。
     */
    public String buildCodeContext(Map<String, String> fileContents) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            sb.append("=== ").append(entry.getKey()).append(" ===\n");
            sb.append(entry.getValue());
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 從 technicalDetail 中提取 PascalCase 元件名稱。
     */
    List<String> extractComponentNames(String technicalDetail) {
        if (technicalDetail == null) return List.of();

        List<String> names = new ArrayList<>();
        Matcher matcher = COMPONENT_NAME_PATTERN.matcher(technicalDetail);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 根據 bug 的 pageUrl 提取路由，在 moduleMapping 中找到對應的 Module。
     */
    ModuleDefinition findModuleByRoute(String pageUrl) {
        if (pageUrl == null) return null;

        // 提取路由部分：http://host:port/order/d2 → /order/d2
        String route;
        try {
            route = URI.create(pageUrl).getPath();
        } catch (Exception e) {
            route = pageUrl;
        }

        for (ModuleDefinition module : moduleMapping.getModules()) {
            for (TestFlowDefinition flow : module.getTestFlows()) {
                if (flow.getRoute() != null && route.contains(flow.getRoute())) {
                    return module;
                }
            }
        }
        return null;
    }

    /**
     * 在 sourceRoot 下找到類別名稱對應的 .java 檔。
     */
    private void findFilesByClassName(Path sourceRoot, Path repoRoot,
                                       String className, Map<String, String> result) {
        String fileName = className + ".java";
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            walk.filter(p -> p.getFileName().toString().equals(fileName))
                    .forEach(p -> {
                        String relativePath = repoRoot.relativize(p).toString();
                        if (!result.containsKey(relativePath)) {
                            try {
                                result.put(relativePath, Files.readString(p));
                                log.debug("讀取元件檔案：{}", relativePath);
                            } catch (IOException e) {
                                log.warn("讀取檔案失敗：{}", p);
                            }
                        }
                    });
        } catch (IOException e) {
            log.warn("掃描目錄失敗：{}", e.getMessage());
        }
    }

    /**
     * 根據 glob pattern 找到匹配的 .java 檔案。
     */
    private void findFilesByPattern(Path repoRoot, String pattern, Map<String, String> result) {
        FileSystem fs = FileSystems.getDefault();
        // 將 pattern 轉為完整的 glob（處理相對路徑）
        String globPattern = "glob:" + pattern;
        PathMatcher matcher = fs.getPathMatcher(globPattern);

        try (Stream<Path> walk = Files.walk(repoRoot.resolve("src"))) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String relative = repoRoot.relativize(p).toString();
                        return matcher.matches(Path.of(relative));
                    })
                    .limit(autoFixConfig.getMaxFilesToRead() - result.size())
                    .forEach(p -> {
                        String relativePath = repoRoot.relativize(p).toString();
                        if (!result.containsKey(relativePath)) {
                            try {
                                result.put(relativePath, Files.readString(p));
                                log.debug("讀取 pattern 匹配檔案：{}", relativePath);
                            } catch (IOException e) {
                                log.warn("讀取檔案失敗：{}", p);
                            }
                        }
                    });
        } catch (IOException e) {
            log.warn("掃描目錄失敗：{}", e.getMessage());
        }
    }
}
