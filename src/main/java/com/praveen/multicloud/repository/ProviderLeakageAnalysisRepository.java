package com.praveen.multicloud.repository;

import com.praveen.multicloud.entity.ProviderLeakageAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProviderLeakageAnalysisRepository extends JpaRepository<ProviderLeakageAnalysisEntity, Long> {
    Optional<ProviderLeakageAnalysisEntity> findFirstByOrderByCreatedAtDesc();
    List<ProviderLeakageAnalysisEntity> findTop50ByOrderByCreatedAtDesc();
}
