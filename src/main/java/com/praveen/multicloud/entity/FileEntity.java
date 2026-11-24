package com.praveen.multicloud.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "files",
        indexes = {
                @Index(name = "idx_files_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_files_upload_status", columnList = "upload_status")
        })
public class FileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_files_user"))
    private UserEntity user;

    @NotBlank
    @Size(max = 255)
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @NotBlank
    @Size(max = 255)
    @Column(name = "stored_filename", nullable = false, length = 255)
    private String storedFilename;

    @NotNull
    @PositiveOrZero
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Size(max = 150)
    @Column(name = "content_type", length = 150)
    private String contentType;

    @Size(min = 64, max = 64)
    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    @Size(max = 20)
    @Column(name = "placement_strategy", length = 20)
    private String placementStrategy;

    @NotNull
    @PositiveOrZero
    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks = 0;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false, length = 20)
    private UploadStatus uploadStatus = UploadStatus.UPLOADING;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "file")
    private List<FileChunkEntity> chunks = new ArrayList<>();

    @OneToMany(mappedBy = "file")
    private List<LeakageAnalysisEntity> leakageAnalyses = new ArrayList<>();

    @PrePersist
    void setCreationTimestamp() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UserEntity getUser() { return user; }
    public void setUser(UserEntity user) { this.user = user; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getStoredFilename() { return storedFilename; }
    public void setStoredFilename(String storedFilename) { this.storedFilename = storedFilename; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getFileSha256() { return fileSha256; }
    public void setFileSha256(String fileSha256) { this.fileSha256 = fileSha256; }
    public String getPlacementStrategy() { return placementStrategy; }
    public void setPlacementStrategy(String placementStrategy) { this.placementStrategy = placementStrategy; }
    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
    public UploadStatus getUploadStatus() { return uploadStatus; }
    public void setUploadStatus(UploadStatus uploadStatus) { this.uploadStatus = uploadStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<FileChunkEntity> getChunks() { return chunks; }
    public List<LeakageAnalysisEntity> getLeakageAnalyses() { return leakageAnalyses; }
}
