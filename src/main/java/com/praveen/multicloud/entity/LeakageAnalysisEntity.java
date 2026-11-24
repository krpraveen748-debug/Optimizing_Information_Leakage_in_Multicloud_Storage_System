package com.praveen.multicloud.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "leakage_analysis",
        indexes = {
                @Index(name = "idx_leakage_file_date", columnList = "file_id, analysis_date"),
                @Index(name = "idx_leakage_analysis_date", columnList = "analysis_date")
        })
public class LeakageAnalysisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false, foreignKey = @ForeignKey(name = "fk_leakage_analysis_file"))
    private FileEntity file;

    @NotBlank
    @Size(max = 64)
    @Column(name = "placement_algorithm", nullable = false, length = 64)
    private String placementAlgorithm;

    @NotNull
    @Column(name = "estimated_leakage", nullable = false)
    private Double estimatedLeakage;

    @Column(name = "similarity_score")
    private Double similarityScore;

    @NotNull
    @Column(name = "analysis_date", nullable = false)
    private LocalDateTime analysisDate;

    @PrePersist
    void setAnalysisDate() {
        if (analysisDate == null) analysisDate = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public FileEntity getFile() { return file; }
    public void setFile(FileEntity file) { this.file = file; }
    public String getPlacementAlgorithm() { return placementAlgorithm; }
    public void setPlacementAlgorithm(String placementAlgorithm) { this.placementAlgorithm = placementAlgorithm; }
    public Double getEstimatedLeakage() { return estimatedLeakage; }
    public void setEstimatedLeakage(Double estimatedLeakage) { this.estimatedLeakage = estimatedLeakage; }
    public Double getSimilarityScore() { return similarityScore; }
    public void setSimilarityScore(Double similarityScore) { this.similarityScore = similarityScore; }
    public LocalDateTime getAnalysisDate() { return analysisDate; }
    public void setAnalysisDate(LocalDateTime analysisDate) { this.analysisDate = analysisDate; }
}
