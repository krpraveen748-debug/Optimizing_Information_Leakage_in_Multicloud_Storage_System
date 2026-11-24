package com.praveen.multicloud.service;

import com.praveen.multicloud.entity.ActivityLogEntity;
import com.praveen.multicloud.entity.UserEntity;
import com.praveen.multicloud.repository.ActivityLogRepository;
import com.praveen.multicloud.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ActivityLogService {
    private final ActivityLogRepository logs;
    private final UserRepository users;

    public ActivityLogService(ActivityLogRepository logs, UserRepository users) {
        this.logs = logs;
        this.users = users;
    }

    @Transactional
    public void record(long userId, String type, String description) {
        record(userId, type, description, null, "COMPLETED");
    }

    @Transactional
    public void record(
            long userId,
            String type,
            String description,
            String fileName,
            String status) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Activity type is required.");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Activity description is required.");
        }
        if (fileName != null && fileName.length() > 255) {
            throw new IllegalArgumentException("Activity file name is too long.");
        }
        if (status == null || status.isBlank() || status.length() > 24) {
            throw new IllegalArgumentException("Activity status is invalid.");
        }
        UserEntity user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated account no longer exists."));
        ActivityLogEntity log = new ActivityLogEntity();
        log.setUser(user);
        log.setActivityType(type.trim().toUpperCase(Locale.ROOT).substring(
                0, Math.min(type.trim().length(), 50)));
        log.setDescription(description.length() > 2000
                ? description.substring(0, 2000) : description);
        log.setFileName(fileName);
        log.setStatus(status.trim().toUpperCase(Locale.ROOT));
        logs.save(log);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentGlobal(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return logs.findTop100ByOrderByActivityTimeDesc().stream()
                .limit(safeLimit)
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentForUser(long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return logs.findTop100ByUser_IdOrderByActivityTimeDesc(userId).stream()
                .limit(safeLimit)
                .map(this::view)
                .toList();
    }

    private Map<String, Object> view(ActivityLogEntity log) {
        return Map.of(
                "id", log.getId(),
                "activityType", log.getActivityType(),
                "description", log.getDescription(),
                "technicalDetails", log.getDescription(),
                "fileName", log.getFileName() == null ? "" : log.getFileName(),
                "status", log.getStatus(),
                "activityTime", log.getActivityTime(),
                "userId", log.getUser().getId(),
                "email", log.getUser().getEmail());
    }
}
