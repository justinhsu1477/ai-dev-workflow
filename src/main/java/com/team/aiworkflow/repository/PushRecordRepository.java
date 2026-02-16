package com.team.aiworkflow.repository;

import com.team.aiworkflow.model.entity.EngineerProfile;
import com.team.aiworkflow.model.entity.PushRecord;
import com.team.aiworkflow.model.entity.PushRecord.TestOutcome;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PushRecordRepository extends JpaRepository<PushRecord, Long> {

    Optional<PushRecord> findByPushId(String pushId);

    /** 取得某工程師在特定專案的最近 push 記錄（按時間倒序，用於計算連續失敗） */
    @Query("SELECT p FROM PushRecord p WHERE p.engineer.email = :email AND p.project = :project ORDER BY p.pushedAt DESC")
    List<PushRecord> findRecentPushes(@Param("email") String email,
                                       @Param("project") String project,
                                       Pageable pageable);

    /** 某工程師在某專案某時段的 push 總數 */
    @Query("SELECT COUNT(p) FROM PushRecord p WHERE p.engineer.email = :email AND p.project = :project AND p.pushedAt BETWEEN :from AND :to")
    int countPushes(@Param("email") String email,
                    @Param("project") String project,
                    @Param("from") LocalDateTime from,
                    @Param("to") LocalDateTime to);

    /** 某工程師在某專案某時段的失敗 push 數 */
    @Query("SELECT COUNT(p) FROM PushRecord p WHERE p.engineer.email = :email AND p.project = :project AND p.testOutcome = :outcome AND p.pushedAt BETWEEN :from AND :to")
    int countPushesByOutcome(@Param("email") String email,
                             @Param("project") String project,
                             @Param("outcome") TestOutcome outcome,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to);

    /** 取得某專案在某時段內有記錄的所有工程師 */
    @Query("SELECT DISTINCT p.engineer FROM PushRecord p WHERE p.project = :project AND p.pushedAt BETWEEN :from AND :to")
    List<EngineerProfile> findActiveEngineers(@Param("project") String project,
                                               @Param("from") LocalDateTime from,
                                               @Param("to") LocalDateTime to);

    /** 取得所有專案名稱 */
    @Query("SELECT DISTINCT p.project FROM PushRecord p")
    List<String> findAllProjects();
}
