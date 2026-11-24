package com.praveen.multicloud.service;

import com.praveen.multicloud.controller.PlacementController;
import com.praveen.multicloud.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class AdminAnalyticsService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ActivityLogService activities;
    private final InformationLeakageAnalysisService leakage;
    private final PlacementController placement;

    public AdminAnalyticsService(
            JdbcTemplate jdbc,
            UserRepository users,
            ActivityLogService activities,
            InformationLeakageAnalysisService leakage,
            PlacementController placement) {
        this.jdbc = jdbc;
        this.users = users;
        this.activities = activities;
        this.leakage = leakage;
        this.placement = placement;
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", users.count());
        result.put("totalFiles", jdbc.queryForObject(
                "SELECT COUNT(*) FROM files WHERE upload_status = 'UPLOADED'", Long.class));
        result.put("totalUniqueChunks", jdbc.queryForObject(
                "SELECT COUNT(*) FROM chunk_metadata", Long.class));
        result.put("totalChunkReferences", jdbc.queryForObject(
                "SELECT COUNT(*) FROM file_chunks c JOIN files f ON f.id = c.file_id "
                        + "WHERE f.upload_status = 'UPLOADED'", Long.class));
        long references = ((Number) result.get("totalChunkReferences")).longValue();
        long unique = ((Number) result.get("totalUniqueChunks")).longValue();
        result.put("deduplicatedChunks", Math.max(0L, references - unique));

        long originalBytes = queryLong(
                "SELECT COALESCE(SUM(file_size), 0) FROM files WHERE upload_status = 'UPLOADED'");
        long uniqueChunkBytes = queryLong(
                "SELECT COALESCE(SUM(chunk_size), 0) FROM chunk_metadata");
        result.put("totalOriginalBytes", originalBytes);
        result.put("totalUniqueChunkStorageBytes", uniqueChunkBytes);
        result.put("deduplicationSavingsBytes", Math.max(0L, originalBytes - uniqueChunkBytes));
        result.put("providerDistribution", providerDistribution());
        result.put("uploadTimeline", uploadTimeline());
        result.put("recentUploads", recentUploads());
        result.put("recentActivity", activities.recentGlobal(20));
        result.put("storageAnalytics", storageAnalytics());
        result.put("latestLeakageAnalysis", latestLeakage());
        result.put("latestPlacementExperiment", placement.latest());
        return result;
    }

    public Map<String, Object> storageAnalytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        long original = queryLong(
                "SELECT COALESCE(SUM(file_size), 0) FROM files WHERE upload_status = 'UPLOADED'");
        long uniqueStorage = queryLong(
                "SELECT COALESCE(SUM(chunk_size), 0) FROM chunk_metadata");
        long references = queryLong(
                "SELECT COUNT(*) FROM file_chunks c JOIN files f ON f.id = c.file_id "
                        + "WHERE f.upload_status = 'UPLOADED'");
        long uniqueCount = queryLong("SELECT COUNT(*) FROM chunk_metadata");
        result.put("totalOriginalFileSizeBytes", original);
        result.put("totalUniqueChunkStorageBytes", uniqueStorage);
        result.put("deduplicatedChunkCount", Math.max(0L, references - uniqueCount));
        result.put("deduplicationSavingsBytes", Math.max(0L, original - uniqueStorage));
        result.put("providerDistribution", providerDistribution());
        return result;
    }

    public Map<String, Object> leakageAnalytics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("history", leakage.history());
        result.put("latest", latestLeakage());
        return result;
    }

    private Object latestLeakage() {
        try {
            return leakage.latestAnalysis();
        } catch (NoSuchElementException e) {
            return Map.of("status", "NO_DATA",
                    "message", "No provider leakage analysis is available.");
        }
    }

    public List<Map<String, Object>> providerDistribution() {
        return jdbc.query("""
                SELECT p.provider_name,
                       COUNT(c.id) AS chunk_count,
                       COALESCE(SUM(c.chunk_size), 0) AS storage_bytes
                  FROM cloud_providers p
                  LEFT JOIN (
                      SELECT c.id, c.cloud_provider_id, c.chunk_size
                        FROM file_chunks c
                        JOIN files f ON f.id = c.file_id
                       WHERE f.upload_status = 'UPLOADED'
                  ) c ON c.cloud_provider_id = p.id
                 WHERE p.provider_name IN ('cloud1', 'cloud2', 'cloud3')
                 GROUP BY p.id, p.provider_name
                 ORDER BY p.provider_name
                """, (row, index) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("provider", row.getString("provider_name"));
                    item.put("chunkCount", row.getLong("chunk_count"));
                    item.put("storageBytes", row.getLong("storage_bytes"));
                    return item;
                });
    }

    public List<Map<String, Object>> uploadTimeline() {
        return jdbc.query("""
                SELECT CAST(created_at AS DATE) AS upload_date, COUNT(*) AS file_count
                  FROM files
                 WHERE upload_status = 'UPLOADED'
                 GROUP BY CAST(created_at AS DATE)
                 ORDER BY upload_date
                """, (row, index) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("date", row.getObject("upload_date").toString());
                    item.put("fileCount", row.getLong("file_count"));
                    return item;
                });
    }

    public List<Map<String, Object>> recentUploads() {
        return jdbc.query("""
                SELECT f.id, f.original_filename, f.file_size, f.total_chunks,
                       f.created_at, u.email
                  FROM files f
                  JOIN users u ON u.id = f.user_id
                 WHERE f.upload_status = 'UPLOADED'
                 ORDER BY f.created_at DESC
                 LIMIT 20
                """, (row, index) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("fileId", row.getLong("id"));
                    item.put("fileName", row.getString("original_filename"));
                    item.put("sizeBytes", row.getLong("file_size"));
                    item.put("chunkCount", row.getInt("total_chunks"));
                    item.put("createdAt", row.getTimestamp("created_at").toLocalDateTime());
                    item.put("email", row.getString("email"));
                    return item;
                });
    }

    private long queryLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
