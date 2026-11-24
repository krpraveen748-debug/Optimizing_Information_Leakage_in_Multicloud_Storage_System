package com.praveen.multicloud.repository;

import com.praveen.multicloud.entity.FileChunkEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FileChunkRepository extends JpaRepository<FileChunkEntity, Long> {
    List<FileChunkEntity> findByFile_IdOrderByChunkNumberAsc(Long fileId);
    List<FileChunkEntity> findByChunkHash(String chunkHash);
    long countByFile_Id(Long fileId);

    @Query("""
            select c.chunkHash as chunkHash,
                   m.similaritySignature as similaritySignature
              from FileChunkEntity c
              join c.chunkMetadata m
             where c.file.id = :fileId
             order by c.chunkNumber
            """)
    List<SimilarityChunkProjection> findSimilarityMetadataByFileId(@Param("fileId") Long fileId);

    interface SimilarityChunkProjection {
        String getChunkHash();
        String getSimilaritySignature();
    }
}
