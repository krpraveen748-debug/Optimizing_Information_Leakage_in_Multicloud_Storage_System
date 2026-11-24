package com.praveen.multicloud.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "cloud_providers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cloud_providers_name",
                columnNames = "provider_name"),
        indexes = @Index(name = "idx_cloud_providers_status", columnList = "status"))
public class CloudProviderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(name = "provider_name", nullable = false, length = 100)
    private String providerName;

    @NotBlank
    @Size(max = 1000)
    @Column(name = "storage_directory", nullable = false, length = 1000)
    private String storageDirectory;

    @NotNull
    @PositiveOrZero
    @Column(name = "total_chunks", nullable = false)
    private Long totalChunks = 0L;

    @NotNull
    @PositiveOrZero
    @Column(name = "total_storage_used", nullable = false)
    private Long totalStorageUsed = 0L;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderStatus status = ProviderStatus.ACTIVE;

    @OneToMany(mappedBy = "cloudProvider")
    private List<FileChunkEntity> chunks = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }
    public String getStorageDirectory() { return storageDirectory; }
    public void setStorageDirectory(String storageDirectory) { this.storageDirectory = storageDirectory; }
    public Long getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Long totalChunks) { this.totalChunks = totalChunks; }
    public Long getTotalStorageUsed() { return totalStorageUsed; }
    public void setTotalStorageUsed(Long totalStorageUsed) { this.totalStorageUsed = totalStorageUsed; }
    public ProviderStatus getStatus() { return status; }
    public void setStatus(ProviderStatus status) { this.status = status; }
    public List<FileChunkEntity> getChunks() { return chunks; }
}
