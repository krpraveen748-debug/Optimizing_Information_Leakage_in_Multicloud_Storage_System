package com.praveen.multicloud.repository;

import com.praveen.multicloud.entity.FileEntity;
import com.praveen.multicloud.entity.UploadStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    List<FileEntity> findByUser_IdOrderByCreatedAtDesc(Long userId);
    long countByUser_Id(Long userId);
    Optional<FileEntity> findByIdAndUser_IdAndUploadStatus(Long id, Long userId, UploadStatus uploadStatus);
}
