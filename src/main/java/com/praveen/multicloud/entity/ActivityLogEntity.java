package com.praveen.multicloud.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "activity_logs",
        indexes = {
                @Index(name = "idx_activity_time", columnList = "activity_time"),
                @Index(name = "idx_activity_user_time", columnList = "user_id, activity_time")
        })
public class ActivityLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_activity_logs_user"))
    private UserEntity user;

    @NotBlank
    @Size(max = 50)
    @Column(name = "activity_type", nullable = false, length = 50)
    private String activityType;

    @NotBlank
    @Size(max = 2000)
    @Column(nullable = false, length = 2000)
    private String description;

    @Size(max = 255)
    @Column(name = "file_name", length = 255)
    private String fileName;

    @NotBlank
    @Size(max = 24)
    @Column(name = "activity_status", nullable = false, length = 24)
    private String status = "RECORDED";

    @NotNull
    @Column(name = "activity_time", nullable = false)
    private LocalDateTime activityTime;

    @PrePersist
    void setActivityTime() {
        if (activityTime == null) activityTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UserEntity getUser() { return user; }
    public void setUser(UserEntity user) { this.user = user; }
    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getActivityTime() { return activityTime; }
    public void setActivityTime(LocalDateTime activityTime) { this.activityTime = activityTime; }
}
