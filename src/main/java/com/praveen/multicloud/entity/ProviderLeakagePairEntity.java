package com.praveen.multicloud.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Entity
@Table(
        name = "provider_leakage_pairs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_provider_leakage_pair",
                columnNames = {"analysis_id", "provider_a_id", "provider_b_id"}),
        indexes = @Index(name = "idx_provider_leakage_pair_analysis", columnList = "analysis_id"))
public class ProviderLeakagePairEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "analysis_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_provider_leakage_pair_analysis"))
    private ProviderLeakageAnalysisEntity analysis;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "provider_a_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_provider_leakage_pair_provider_a"))
    private CloudProviderEntity providerA;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "provider_b_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_provider_leakage_pair_provider_b"))
    private CloudProviderEntity providerB;

    @NotNull
    @PositiveOrZero
    @Column(name = "fingerprint_count_a", nullable = false)
    private Long fingerprintCountA;

    @NotNull
    @PositiveOrZero
    @Column(name = "fingerprint_count_b", nullable = false)
    private Long fingerprintCountB;

    @NotNull
    @PositiveOrZero
    @Column(name = "shared_fingerprint_count", nullable = false)
    private Long sharedFingerprintCount;

    @NotNull
    @PositiveOrZero
    @Column(name = "union_fingerprint_count", nullable = false)
    private Long unionFingerprintCount;

    @Column(name = "jaccard_similarity")
    private Double jaccardSimilarity;

    @Column(name = "shared_fingerprint_percentage")
    private Double sharedFingerprintPercentage;

    @NotNull
    @Column(name = "data_available", nullable = false)
    private Boolean dataAvailable;

    @NotNull
    @Column(name = "high_similarity", nullable = false)
    private Boolean highSimilarity;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ProviderLeakageAnalysisEntity getAnalysis() { return analysis; }
    public void setAnalysis(ProviderLeakageAnalysisEntity analysis) { this.analysis = analysis; }
    public CloudProviderEntity getProviderA() { return providerA; }
    public void setProviderA(CloudProviderEntity providerA) { this.providerA = providerA; }
    public CloudProviderEntity getProviderB() { return providerB; }
    public void setProviderB(CloudProviderEntity providerB) { this.providerB = providerB; }
    public Long getFingerprintCountA() { return fingerprintCountA; }
    public void setFingerprintCountA(Long fingerprintCountA) { this.fingerprintCountA = fingerprintCountA; }
    public Long getFingerprintCountB() { return fingerprintCountB; }
    public void setFingerprintCountB(Long fingerprintCountB) { this.fingerprintCountB = fingerprintCountB; }
    public Long getSharedFingerprintCount() { return sharedFingerprintCount; }
    public void setSharedFingerprintCount(Long sharedFingerprintCount) {
        this.sharedFingerprintCount = sharedFingerprintCount;
    }
    public Long getUnionFingerprintCount() { return unionFingerprintCount; }
    public void setUnionFingerprintCount(Long unionFingerprintCount) {
        this.unionFingerprintCount = unionFingerprintCount;
    }
    public Double getJaccardSimilarity() { return jaccardSimilarity; }
    public void setJaccardSimilarity(Double jaccardSimilarity) { this.jaccardSimilarity = jaccardSimilarity; }
    public Double getSharedFingerprintPercentage() { return sharedFingerprintPercentage; }
    public void setSharedFingerprintPercentage(Double sharedFingerprintPercentage) {
        this.sharedFingerprintPercentage = sharedFingerprintPercentage;
    }
    public Boolean getDataAvailable() { return dataAvailable; }
    public void setDataAvailable(Boolean dataAvailable) { this.dataAvailable = dataAvailable; }
    public Boolean getHighSimilarity() { return highSimilarity; }
    public void setHighSimilarity(Boolean highSimilarity) { this.highSimilarity = highSimilarity; }
}
