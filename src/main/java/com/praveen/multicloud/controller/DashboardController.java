package com.praveen.multicloud.controller;

import com.praveen.multicloud.security.AuthenticatedUser;
import com.praveen.multicloud.service.ActivityLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final JdbcTemplate jdbc;
    private final ActivityLogService activities;
    public DashboardController(JdbcTemplate jdbc, ActivityLogService activities) {
        this.jdbc = jdbc;
        this.activities = activities;
    }

    @GetMapping("/summary")
    public Map<String,Object> summary(@AuthenticationPrincipal AuthenticatedUser user) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("totalFiles", jdbc.queryForObject(
            "SELECT COUNT(*) FROM files WHERE user_id=? AND upload_status='UPLOADED'",
            Long.class, user.getId()));
        result.put("totalChunks", jdbc.queryForObject(
            "SELECT COUNT(*) FROM file_chunks c JOIN files f ON f.id=c.file_id " +
                "WHERE f.user_id=? AND f.upload_status='UPLOADED'",
            Long.class, user.getId()));
        result.put("totalBytes", jdbc.queryForObject(
            "SELECT COALESCE(SUM(file_size),0) FROM files WHERE user_id=? AND upload_status='UPLOADED'",
            Long.class, user.getId()));
        result.put("cloudDistribution", jdbc.queryForList(
            "SELECT p.provider_name AS cloud_name,COUNT(c.id) AS chunk_count," +
            "COALESCE(SUM(c.chunk_size),0) AS storage_bytes " +
            "FROM cloud_providers p LEFT JOIN (" +
            "SELECT c.id,c.cloud_provider_id,c.chunk_size FROM file_chunks c " +
            "JOIN files f ON f.id=c.file_id WHERE f.user_id=? AND f.upload_status='UPLOADED'" +
            ") c ON c.cloud_provider_id=p.id " +
                "GROUP BY p.id,p.provider_name ORDER BY p.provider_name",
            user.getId()));
        result.put("recentFiles", jdbc.queryForList(
            "SELECT id,original_filename AS original_name,file_size AS size_bytes,created_at " +
            "FROM files WHERE user_id=? AND upload_status='UPLOADED' ORDER BY id DESC LIMIT 10",
            user.getId()));
        result.put("personalStorageBytes", jdbc.queryForObject(
            "SELECT COALESCE(SUM(owner_chunks.chunk_size),0) FROM (" +
                "SELECT DISTINCT c.chunk_hash,c.chunk_size FROM file_chunks c " +
                "JOIN files f ON f.id=c.file_id " +
                "WHERE f.user_id=? AND f.upload_status='UPLOADED') owner_chunks",
            Long.class, user.getId()));
        result.put("activity", activities.recentForUser(user.getId(), 20));
        return result;
    }

    @GetMapping("/activity")
    public List<Map<String, Object>> activity(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "50") int limit) {
        return activities.recentForUser(user.getId(), limit);
    }
}
