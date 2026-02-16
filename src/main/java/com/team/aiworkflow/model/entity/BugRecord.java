package com.team.aiworkflow.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 每個 Bug 的記錄。
 * 追蹤 bug 的歸屬工程師、嚴重程度、和修復狀態（僅追蹤 AI 自動修復）。
 */
@Entity
@Table(name = "bug_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BugRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "push_record_id")
    private PushRecord pushRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "engineer_id")
    private EngineerProfile engineer;

    /** 專案名（多專案支援） */
    private String project;

    /** Azure DevOps Work Item ID */
    private int workItemId;

    private String bugTitle;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    /** 只追蹤 AI 修復成功，人工修復保持 UNRESOLVED */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ResolutionMethod resolution = ResolutionMethod.UNRESOLVED;

    private LocalDateTime createdAt;

    /** AI 修復成功時間（nullable） */
    private LocalDateTime resolvedAt;

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public enum ResolutionMethod {
        AI_AUTO_FIX, UNRESOLVED
    }
}
