package com.praveen.multicloud.repository;

import com.praveen.multicloud.entity.ChunkMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChunkMetadataRepository extends JpaRepository<ChunkMetadataEntity, Long> {
    Optional<ChunkMetadataEntity> findByChunkHash(String chunkHash);
    boolean existsByChunkHash(String chunkHash);
}
