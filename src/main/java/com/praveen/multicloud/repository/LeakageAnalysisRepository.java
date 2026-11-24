package com.praveen.multicloud.repository;

import com.praveen.multicloud.entity.LeakageAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeakageAnalysisRepository extends JpaRepository<LeakageAnalysisEntity, Long> {
    List<LeakageAnalysisEntity> findByFile_IdOrderByAnalysisDateDesc(Long fileId);
    List<LeakageAnalysisEntity> findTop100ByOrderByAnalysisDateDesc();
}
