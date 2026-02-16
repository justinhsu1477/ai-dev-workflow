package com.team.aiworkflow.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 每次 Push 事件的記錄。
 * 關聯推送者（EngineerProfile）和測試結果。
 */
@Entity
@Table(name = "push_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "engineer_id")
    private EngineerProfile engineer;

    /** 專案名（多專案支援，如 OCDS、WMS） */
    private String project;

    private String repositoryName;

    private String branch;

    private String commitId;

    /** Azure DevOps push ID */
    private String pushId;

    private int changedFileCount;

    private LocalDateTime pushedAt;

    /** E2E 測試結果（測試完成後更新） */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TestOutcome testOutcome = TestOutcome.PENDING;

    /** 此次 push 發現的 bug 數量 */
    private int bugsFound;

    public enum TestOutcome {
        PENDING, PASSED, FAILED, ERROR, TIMEOUT
    }
}
