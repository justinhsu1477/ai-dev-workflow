package com.team.aiworkflow.service.azuredevops;

import com.team.aiworkflow.model.AnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Azure DevOps Work Item 管理服務。
 * 建立 Bug Work Item 並附加截圖附件。
 *
 * 附件上傳流程（兩步驟）：
 * 1. uploadAttachment() — POST 二進位資料到 /_apis/wit/attachments，取得附件 URL
 * 2. attachToWorkItem() — PATCH Work Item 加入附件關聯
 */
@Service
@Slf4j
public class WorkItemService {

    private final WebClient webClient;

    public WorkItemService(@Qualifier("azureDevOpsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * 根據 AI 分析結果建立 Bug Work Item。
     *
     * @param result AI 分析結果
     * @return 建立的 Work Item ID
     */
    public Mono<Integer> createBugFromAnalysis(AnalysisResult result) {
        log.info("正在建立 Bug Work Item：Build #{}", result.getBuildNumber());

        String title = String.format("[AI] %s - Build #%s", result.getSummary(), result.getBuildNumber());
        String description = formatDescription(result);

        // Azure DevOps 使用 JSON Patch 格式建立 Work Item
        List<Map<String, Object>> patchDocument = List.of(
                createPatchOp("/fields/System.Title", truncate(title, 255)),
                createPatchOp("/fields/System.Description", description),
                createPatchOp("/fields/Microsoft.VSTS.Common.Severity",
                        mapSeverity(result.getSeverity())),
                createPatchOp("/fields/System.Tags", "AI-Analyzed"),
                createPatchOp("/fields/Microsoft.VSTS.TCM.ReproSteps",
                        formatReproSteps(result))
        );

        return webClient.post()
                .uri("/_apis/wit/workitems/$Bug?api-version=7.1")
                .contentType(MediaType.valueOf("application/json-patch+json"))
                .bodyValue(patchDocument)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> (Integer) response.get("id"))
                .doOnSuccess(id -> log.info("已建立 Bug Work Item #{} - Build #{}", id, result.getBuildNumber()))
                .doOnError(e -> log.error("建立 Work Item 失敗：{}", e.getMessage()));
    }

    /**
     * 上傳二進位檔案到 Azure DevOps 附件儲存區。
     *
     * API：POST /_apis/wit/attachments?fileName={fileName}&api-version=7.1
     * Content-Type: application/octet-stream
     *
     * @param data     二進位資料（例如 PNG 截圖）
     * @param fileName 檔案名稱（例如 "e2e-abc123-step1.png"）
     * @return 附件的 URL（用於後續關聯到 Work Item）
     */
    public Mono<String> uploadAttachment(byte[] data, String fileName) {
        log.info("正在上傳附件：{} ({} bytes)", fileName, data.length);

        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/_apis/wit/attachments")
                        .queryParam("fileName", fileName)
                        .queryParam("api-version", "7.1")
                        .build())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(data)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> (String) response.get("url"))
                .doOnSuccess(url -> log.info("附件已上傳：{} → {}", fileName, url))
                .doOnError(e -> log.error("上傳附件失敗：{} - {}", fileName, e.getMessage()));
    }

    /**
     * 更新 Work Item 的 ReproSteps 欄位（用於嵌入截圖到重現步驟中）。
     *
     * @param workItemId Work Item ID
     * @param reproStepsHtml 新的 ReproSteps HTML 內容
     */
    public Mono<Void> updateReproSteps(int workItemId, String reproStepsHtml) {
        log.info("正在更新 Work Item #{} 的 ReproSteps", workItemId);

        List<Map<String, Object>> patchDocument = List.of(
                Map.of(
                        "op", "replace",
                        "path", "/fields/Microsoft.VSTS.TCM.ReproSteps",
                        "value", reproStepsHtml
                )
        );

        return webClient.patch()
                .uri("/_apis/wit/workitems/{id}?api-version=7.1", workItemId)
                .contentType(MediaType.valueOf("application/json-patch+json"))
                .bodyValue(patchDocument)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v -> log.info("Work Item #{} 的 ReproSteps 已更新（含截圖）", workItemId))
                .doOnError(e -> log.error("更新 Work Item #{} ReproSteps 失敗：{}", workItemId, e.getMessage()));
    }

    /**
     * 將已上傳的附件關聯到 Work Item。
     *
     * API：PATCH /_apis/wit/workitems/{id}?api-version=7.1
     * Content-Type: application/json-patch+json
     *
     * @param workItemId    Work Item ID
     * @param attachmentUrl 附件 URL（由 uploadAttachment 回傳）
     * @param comment       附件說明文字
     */
    public Mono<Void> attachToWorkItem(int workItemId, String attachmentUrl, String comment) {
        log.info("正在將附件關聯到 Work Item #{}：{}", workItemId, comment);

        List<Map<String, Object>> patchDocument = List.of(
                Map.of(
                        "op", "add",
                        "path", "/relations/-",
                        "value", Map.of(
                                "rel", "AttachedFile",
                                "url", attachmentUrl,
                                "attributes", Map.of("comment", comment)
                        )
                )
        );

        return webClient.patch()
                .uri("/_apis/wit/workitems/{id}?api-version=7.1", workItemId)
                .contentType(MediaType.valueOf("application/json-patch+json"))
                .bodyValue(patchDocument)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v -> log.info("附件已關聯到 Work Item #{}", workItemId))
                .doOnError(e -> log.error("關聯附件到 Work Item #{} 失敗：{}", workItemId, e.getMessage()));
    }

    /**
     * 根據 ID 取得 Work Item 詳細資訊。
     */
    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> getWorkItem(int workItemId) {
        return webClient.get()
                .uri("/_apis/wit/workitems/{id}?api-version=7.1&$expand=all", workItemId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> createPatchOp(String path, Object value) {
        return Map.of(
                "op", "add",
                "path", path,
                "value", value
        );
    }

    private String formatDescription(AnalysisResult result) {
        return String.format("""
                <h2>AI 分析報告</h2>
                <p><strong>Build：</strong>%s（分支：%s）</p>
                <p><strong>Commit：</strong>%s</p>
                <p><strong>嚴重程度：</strong>%s</p>

                <h3>根本原因</h3>
                <p>%s</p>

                <h3>建議修復</h3>
                <pre>%s</pre>

                <h3>受影響的檔案</h3>
                <ul>%s</ul>

                <hr>
                <p><em>此 Work Item 由 AI Dev Workflow 自動建立。</em></p>
                """,
                result.getBuildNumber(),
                result.getBranch(),
                result.getCommitId() != null ? result.getCommitId() : "N/A",
                result.getSeverity(),
                result.getRootCause(),
                result.getSuggestedFix() != null ? result.getSuggestedFix() : "無建議",
                formatAffectedFiles(result.getAffectedFiles())
        );
    }

    private String formatReproSteps(AnalysisResult result) {
        return String.format("""
                <h3>重現步驟</h3>
                <ol>
                  <li>切換到分支：%s</li>
                  <li>執行 CI Pipeline</li>
                  <li>觀察 Build #%s 的測試失敗</li>
                </ol>
                <h3>根本原因</h3>
                <p>%s</p>
                """,
                result.getBranch(),
                result.getBuildNumber(),
                result.getRootCause()
        );
    }

    private String formatAffectedFiles(List<String> files) {
        if (files == null || files.isEmpty()) return "<li>未知</li>";
        StringBuilder sb = new StringBuilder();
        for (String file : files) {
            sb.append("<li>").append(file).append("</li>");
        }
        return sb.toString();
    }

    private String mapSeverity(AnalysisResult.Severity severity) {
        if (severity == null) return "3 - Medium";
        return switch (severity) {
            case CRITICAL -> "1 - Critical";
            case HIGH -> "2 - High";
            case MEDIUM -> "3 - Medium";
            case LOW -> "4 - Low";
        };
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength - 3) + "..." : text;
    }
}
