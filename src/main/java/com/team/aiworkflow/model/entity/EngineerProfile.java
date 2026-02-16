package com.team.aiworkflow.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工程師主檔。
 * 根據 push event 中的 pushedBy 自動建立，以 email 為唯一識別。
 */
@Entity
@Table(name = "engineer_profile")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EngineerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    private String displayName;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastActiveAt;
}
