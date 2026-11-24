package com.praveen.multicloud.repository;

import com.praveen.multicloud.entity.ProviderLeakagePairEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProviderLeakagePairRepository extends JpaRepository<ProviderLeakagePairEntity, Long> {
    @Query("""
            select pair
              from ProviderLeakagePairEntity pair
              join fetch pair.providerA providerA
              join fetch pair.providerB providerB
             where pair.analysis.id = :analysisId
             order by providerA.providerName, providerB.providerName
            """)
    List<ProviderLeakagePairEntity> findPairsByAnalysisId(@Param("analysisId") Long analysisId);
}
