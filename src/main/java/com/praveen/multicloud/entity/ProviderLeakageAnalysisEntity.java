package com.praveen.multicloud.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "provider_leakage_analyses",
        indexes = @Index(name = "idx_provider_leakage_created", columnList = "created_at"))
public class ProviderLeakageAnalysisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "created_by_user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_provider_leakage_created_by"))
    private UserEntity createdBy;

    @Column(name = "overall_jaccard")
    private Double overallJaccard;

    @NotNull
    @Column(name = "high_similarity_threshold", nullable = false)
    private Double highSimilarityThreshold;

    @NotNull
    @PositiveOrZero
    @Column(name = "analyzed_pair_count", nullable = false)
    private Integer analyzedPairCount = 0;

    @NotNull
    @PositiveOrZero
    @Column(name = "high_similarity_pair_count", nullable = false)
    private Integer highSimilarityPairCount = 0;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "analysis")
    private List<ProviderLeakagePairEntity> providerPairs = new ArrayList<>();

    @PrePersist
    void setCreationTimestamp() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UserEntity getCreatedBy() { return createdBy; }
    public void setCreatedBy(UserEntity createdBy) { this.createdBy = createdBy; }
    public Double getOverallJaccard() { return overallJaccard; }
    public void setOverallJaccard(Double overallJaccard) { this.overallJaccard = overallJaccard; }
    public Double getHighSimilarityThreshold() { return highSimilarityThreshold; }
    public void setHighSimilarityThreshold(Double highSimilarityThreshold) {
        this.highSimilarityThreshold = highSimilarityThreshold;
    }
    public Integer getAnalyzedPairCount() { return analyzedPairCount; }
    public void setAnalyzedPairCount(Integer analyzedPairCount) { this.analyzedPairCount = analyzedPairCount; }
    public Integer getHighSimilarityPairCount() { return highSimilarityPairCount; }
    public void setHighSimilarityPairCount(Integer highSimilarityPairCount) {
        this.highSimilarityPairCount = highSimilarityPairCount;
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<ProviderLeakagePairEntity> getProviderPairs() { return providerPairs; }
}
