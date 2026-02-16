package com.team.aiworkflow.service.autofix;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.aiworkflow.config.AutoFixConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 解析 AI 的修復方案 JSON 並套用到目標 repo 的檔案。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FixApplicator {

    private final AutoFixConfig config;
    private final ObjectMapper objectMapper;

    /**
     * 解析 AI 回傳的修復方案 JSON。
     */
    @SuppressWarnings("unchecked")
    public FixResult parseFixResponse(String aiResponse) {
        try {
            String json = extractJson(aiResponse);
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            String fixDescription = (String) parsed.getOrDefault("fixDescription", "");
            String explanation = (String) parsed.getOrDefault("explanation", "");
            String testSuggestion = (String) parsed.getOrDefault("testSuggestion", "");

            List<Map<String, String>> changesRaw =
                    (List<Map<String, String>>) parsed.getOrDefault("changes", List.of());

            List<FixChange> changes = new ArrayList<>();
            for (Map<String, String> c : changesRaw) {
                changes.add(new FixChange(
                        c.getOrDefault("filePath", ""),
                        c.getOrDefault("originalCode", ""),
                        c.getOrDefault("fixedCode", "")));
            }

            log.info("AI 修復方案：{} 個檔案變更 — {}", changes.size(), fixDescription);
            return new FixResult(fixDescription, changes, explanation, testSuggestion);

        } catch (Exception e) {
            log.error("解析 AI 修復方案失敗：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 套用所有修改到目標 repo 的檔案。
     *
     * @return true 如果所有變更都成功套用
     */
    public boolean applyChanges(FixResult fixResult) {
        if (fixResult == null || fixResult.changes().isEmpty()) {
            log.warn("沒有要套用的變更");
            return false;
        }

        for (FixChange change : fixResult.changes()) {
            try {
                Path filePath = resolveFilePath(change.filePath());
                if (filePath == null || !Files.exists(filePath)) {
                    log.error("找不到檔案：{}", change.filePath());
                    return false;
                }

                String content = Files.readString(filePath);

                // 嘗試精確替換
                if (content.contains(change.originalCode())) {
                    content = content.replace(change.originalCode(), change.fixedCode());
                    Files.writeString(filePath, content);
                    log.info("已套用修改：{}", change.filePath());
                    continue;
                }

                // 嘗試 fuzzy match（忽略每行的前後空白）
                String fuzzyResult = fuzzyReplace(content, change.originalCode(), change.fixedCode());
                if (fuzzyResult != null) {
                    Files.writeString(filePath, fuzzyResult);
                    log.info("已套用修改（fuzzy match）：{}", change.filePath());
                    continue;
                }

                log.error("在 {} 中找不到要替換的程式碼片段", change.filePath());
                return false;

            } catch (IOException e) {
                log.error("套用修改失敗：{} — {}", change.filePath(), e.getMessage());
                return false;
            }
        }

        log.info("所有 {} 個檔案變更已成功套用", fixResult.changes().size());
        return true;
    }

    /**
     * 將 AI 回傳的 filePath 解析為目標 repo 中的絕對路徑。
     */
    Path resolveFilePath(String aiFilePath) {
        if (aiFilePath == null || aiFilePath.isBlank()) return null;

        Path repoRoot = Path.of(config.getTargetRepoPath());

        // 情況 1：已經是完整相對路徑（src/main/java/...）
        Path candidate = repoRoot.resolve(aiFilePath);
        if (Files.exists(candidate)) return candidate;

        // 情況 2：只有類別名稱（D2GridComponent.java）
        if (!aiFilePath.contains("/")) {
            return searchFile(repoRoot, aiFilePath);
        }

        // 情況 3：部分路徑（com/soetek/ods/views/...）
        Path srcCandidate = repoRoot.resolve("src/main/java").resolve(aiFilePath);
        if (Files.exists(srcCandidate)) return srcCandidate;

        // 情況 4：在整個 repo 中搜尋
        String fileName = Path.of(aiFilePath).getFileName().toString();
        return searchFile(repoRoot, fileName);
    }

    /**
     * 在 repo 中搜尋指定檔名的檔案。
     */
    private Path searchFile(Path repoRoot, String fileName) {
        try (Stream<Path> walk = Files.walk(repoRoot.resolve("src"))) {
            return walk.filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("搜尋檔案失敗：{}", fileName);
            return null;
        }
    }

    /**
     * Fuzzy 替換：忽略每行前後空白進行比對。
     *
     * @return 替換後的內容，或 null 如果找不到匹配
     */
    private String fuzzyReplace(String content, String originalCode, String fixedCode) {
        // 將 original code 的每一行 trim 後作為搜尋模式
        String[] originalLines = originalCode.split("\n");
        String[] contentLines = content.split("\n");

        // 找到第一行匹配的位置
        String firstTrimmed = originalLines[0].trim();
        if (firstTrimmed.isEmpty()) return null;

        for (int i = 0; i <= contentLines.length - originalLines.length; i++) {
            if (!contentLines[i].trim().equals(firstTrimmed)) continue;

            // 檢查後續行是否都匹配
            boolean allMatch = true;
            for (int j = 1; j < originalLines.length; j++) {
                if (!contentLines[i + j].trim().equals(originalLines[j].trim())) {
                    allMatch = false;
                    break;
                }
            }

            if (allMatch) {
                // 用原始內容的行替換
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < i; k++) {
                    sb.append(contentLines[k]).append("\n");
                }
                sb.append(fixedCode);
                if (!fixedCode.endsWith("\n")) sb.append("\n");
                for (int k = i + originalLines.length; k < contentLines.length; k++) {
                    sb.append(contentLines[k]);
                    if (k < contentLines.length - 1) sb.append("\n");
                }
                return sb.toString();
            }
        }
        return null;
    }

    /**
     * 從 AI 回應中提取 JSON。
     */
    private String extractJson(String text) {
        int start = text.indexOf("```json");
        if (start >= 0) {
            start = text.indexOf('\n', start) + 1;
            int end = text.indexOf("```", start);
            if (end > start) return text.substring(start, end).trim();
        }
        start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    // DTO
    public record FixResult(String fixDescription, List<FixChange> changes,
                             String explanation, String testSuggestion) {}

    public record FixChange(String filePath, String originalCode, String fixedCode) {}
}
