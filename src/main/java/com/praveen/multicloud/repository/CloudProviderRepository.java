package com.praveen.multicloud.repository;

import com.praveen.multicloud.entity.CloudProviderEntity;
import com.praveen.multicloud.entity.ProviderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CloudProviderRepository extends JpaRepository<CloudProviderEntity, Long> {
    Optional<CloudProviderEntity> findByProviderName(String providerName);
    List<CloudProviderEntity> findByProviderNameInAndStatusOrderByProviderNameAsc(
            List<String> providerNames, ProviderStatus status);

    @Query("""
            select provider.id as providerId,
                   provider.providerName as providerName,
                   count(chunk.id) as totalChunks,
                   coalesce(sum(chunk.chunkSize), 0L) as totalStorageUsed
            from CloudProviderEntity provider
            left join provider.chunks chunk
            group by provider.id, provider.providerName
            order by provider.providerName
            """)
    List<CloudProviderStorageUsage> findStorageUsageByProvider();
}
