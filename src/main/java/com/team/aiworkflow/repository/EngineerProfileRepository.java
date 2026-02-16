package com.team.aiworkflow.repository;

import com.team.aiworkflow.model.entity.EngineerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EngineerProfileRepository extends JpaRepository<EngineerProfile, Long> {

    Optional<EngineerProfile> findByEmail(String email);
}
