package com.team.aiworkflow.repository;

import com.team.aiworkflow.model.entity.BugRecord;
import com.team.aiworkflow.model.entity.BugRecord.ResolutionMethod;
import com.team.aiworkflow.model.entity.BugRecord.Severity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BugRecordRepository extends JpaRepository<BugRecord, Long> {

    Optional<BugRecord> findByWorkItemId(int workItemId);

    /** 某工程師某專案某時段的 bug 總數 */
    @Query("SELECT COUNT(b) FROM BugRecord b WHERE b.engineer.email = :email AND b.project = :project AND b.createdAt BETWEEN :from AND :to")
    int countBugs(@Param("email") String email,
                  @Param("project") String project,
                  @Param("from") LocalDateTime from,
                  @Param("to") LocalDateTime to);

    /** 按修復方式統計 */
    @Query("SELECT b.resolution, COUNT(b) FROM BugRecord b WHERE b.engineer.email = :email AND b.project = :project AND b.createdAt BETWEEN :from AND :to GROUP BY b.resolution")
    List<Object[]> countByResolution(@Param("email") String email,
                                      @Param("project") String project,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    /** 按嚴重程度統計 */
    @Query("SELECT b.severity, COUNT(b) FROM BugRecord b WHERE b.engineer.email = :email AND b.project = :project AND b.createdAt BETWEEN :from AND :to GROUP BY b.severity")
    List<Object[]> countBySeverity(@Param("email") String email,
                                    @Param("project") String project,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);
}
