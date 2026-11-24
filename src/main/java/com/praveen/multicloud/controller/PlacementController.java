package com.praveen.multicloud.controller;

import com.praveen.multicloud.security.AuthenticatedUser;
import com.praveen.multicloud.service.LeakageAwarePlacementService;
import com.praveen.multicloud.service.ActivityLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.Statement;

@RestController
@RequestMapping("/api")
public class PlacementController {
    private final JdbcTemplate jdbc;
    private final LeakageAwarePlacementService placement;
    private final ActivityLogService activities;
    private final ObjectMapper objectMapper;
    private final Map<Long, LeakageAwarePlacementService.Strategy> selections =
            new ConcurrentHashMap<>();
    private final List<ExperimentHistory> history = new ArrayList<>();

    public PlacementController(
            JdbcTemplate jdbc,
            LeakageAwarePlacementService placement,
            ActivityLogService activities,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.placement = placement;
        this.activities = activities;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadPersistedHistory() {
        List<ExperimentHistory> persisted = jdbc.query("""
                    SELECT id, ran_by_user_id, random_seed, input_chunk_count, ran_at
                      FROM placement_experiments
                     ORDER BY ran_at DESC
                     LIMIT 50
                    """, (experiment, index) -> {
                        long experimentId = experiment.getLong("id");
                        Map<String, Object> comparison = new LinkedHashMap<>();
                        for (Map<String, Object> result : jdbc.queryForList("""
                                SELECT strategy, cloud1_chunk_count, cloud2_chunk_count,
                                       cloud3_chunk_count, cloud1_storage_bytes,
                                       cloud2_storage_bytes, cloud3_storage_bytes,
                                       pairwise_jaccard_json
                                  FROM placement_strategy_results
                                 WHERE experiment_id = ?
                                """, experimentId)) {
                            Map<String, Object> providerMap = new LinkedHashMap<>();
                            providerMap.put("cloud1", Map.of(
                                    "chunkCount", result.get("cloud1_chunk_count"),
                                    "storageBytes", result.get("cloud1_storage_bytes")));
                            providerMap.put("cloud2", Map.of(
                                    "chunkCount", result.get("cloud2_chunk_count"),
                                    "storageBytes", result.get("cloud2_storage_bytes")));
                            providerMap.put("cloud3", Map.of(
                                    "chunkCount", result.get("cloud3_chunk_count"),
                                    "storageBytes", result.get("cloud3_storage_bytes")));
                            Map<String, Object> strategyResult = new LinkedHashMap<>();
                            strategyResult.put("strategy", result.get("strategy"));
                            strategyResult.put("seed", experiment.getLong("random_seed"));
                            strategyResult.put("providers", providerMap);
                            try {
                                strategyResult.put("pairwiseJaccard", objectMapper.readValue(
                                        String.valueOf(result.get("pairwise_jaccard_json")),
                                        new TypeReference<List<LeakageAwarePlacementService.PairMeasurement>>() {}));
                            } catch (JsonProcessingException e) {
                                throw new IllegalStateException(
                                        "Persisted placement comparison data is invalid.", e);
                            }
                            comparison.put(String.valueOf(result.get("strategy")), strategyResult);
                        }
                        return new ExperimentHistory(
                                experiment.getTimestamp("ran_at").toLocalDateTime(),
                                experiment.getLong("ran_by_user_id"),
                                experiment.getLong("random_seed"),
                                experiment.getInt("input_chunk_count"),
                                comparison);
                    });
        synchronized (history) {
            history.clear();
            history.addAll(persisted);
        }
    }

    @PostMapping("/placement/strategy")
    public Map<String, Object> selectStrategy(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody Map<String, Object> request) {
        String raw = String.valueOf(request.getOrDefault("strategy", ""));
        LeakageAwarePlacementService.Strategy strategy;
        try {
            strategy = LeakageAwarePlacementService.Strategy.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Strategy must be ROUND_ROBIN, RANDOM, or LEAKAGE_AWARE.");
        }
        selections.put(user.getId(), strategy);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategy.name());
        result.put("selected", true);
        return result;
    }

    @PostMapping("/placement/preview")
    public Map<String, Object> preview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody(required = false) Map<String, Object> request) {
        LeakageAwarePlacementService.Strategy strategy = strategyFrom(
                request, selections.getOrDefault(
                        user.getId(), LeakageAwarePlacementService.Strategy.LEAKAGE_AWARE));
        long seed = seedFrom(request);
        List<LeakageAwarePlacementService.ChunkCandidate> chunks =
                ownedCandidates(user.getId());
        LeakageAwarePlacementService.PlacementPlan plan =
                placement.createPlan(strategy, chunks, seed);
        return planResponse(plan);
    }

    @GetMapping("/placement/distribution")
    public List<Map<String, Object>> distribution(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return jdbc.query("""
                SELECT p.provider_name, COUNT(c.id) AS chunk_count,
                       COALESCE(SUM(c.chunk_size), 0) AS storage_bytes
                  FROM cloud_providers p
                  LEFT JOIN (
                      SELECT c.id, c.cloud_provider_id, c.chunk_size
                        FROM file_chunks c
                        JOIN files f ON f.id = c.file_id
                       WHERE f.user_id = ? AND f.upload_status = 'UPLOADED'
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
                }, user.getId());
    }

    @PostMapping("/admin/placement/experiments")
    @Transactional
    public Map<String, Object> experiment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody(required = false) Map<String, Object> request) {
        long seed = seedFrom(request);
        List<LeakageAwarePlacementService.ChunkCandidate> chunks = allCandidates();
        Map<String, Object> comparison = new LinkedHashMap<>();
        for (LeakageAwarePlacementService.Strategy strategy :
                LeakageAwarePlacementService.Strategy.values()) {
            comparison.put(strategy.name(),
                    planResponse(placement.createPlan(strategy, chunks, seed)));
        }

        ExperimentHistory entry = new ExperimentHistory(
                LocalDateTime.now(), user.getId(), seed, chunks.size(), comparison);
        persistExperiment(entry, comparison);
        activities.record(user.getId(), "PLACEMENT_EXPERIMENT",
                "Ran placement strategy comparison with " + chunks.size() + " chunks.");
        synchronized (history) {
            history.add(0, entry);
            if (history.size() > 50) history.remove(history.size() - 1);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ranByUserId", user.getId());
        result.put("seed", seed);
        result.put("inputChunkCount", chunks.size());
        result.put("strategies", comparison);
        return result;
    }

    @GetMapping("/placement/comparison")
    public Map<String, Object> comparison() {
        synchronized (history) {
            if (history.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("history", List.of());
                result.put("message", "No placement experiment has been run.");
                return result;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("latest", history.getFirst().comparison());
            result.put("history", history);
            return result;
        }
    }

    @GetMapping("/placement/history")
    public List<ExperimentHistory> experimentHistory() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    public Object latest() {
        synchronized (history) {
            return history.isEmpty()
                    ? Map.of("status", "NO_DATA",
                            "message", "No placement experiment has been run.")
                    : history.getFirst();
        }
    }

    private void persistExperiment(
            ExperimentHistory entry, Map<String, Object> comparison) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO placement_experiments (
                        ran_by_user_id, random_seed, input_chunk_count, ran_at
                    ) VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, entry.ranByUserId());
            statement.setLong(2, entry.seed());
            statement.setInt(3, entry.inputChunkCount());
            statement.setTimestamp(4, java.sql.Timestamp.valueOf(entry.ranAt()));
            return statement;
        }, keyHolder);
        Number experimentId = keyHolder.getKey();
        if (experimentId == null) {
            throw new IllegalStateException("Database did not return a placement experiment ID.");
        }
        try {
            for (var strategyEntry : comparison.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> plan =
                        (Map<String, Object>) strategyEntry.getValue();
                @SuppressWarnings("unchecked")
                Map<String, LeakageAwarePlacementService.ProviderMeasurement> providers =
                        (Map<String, LeakageAwarePlacementService.ProviderMeasurement>)
                                plan.get("providers");
                String pairwiseJson = objectMapper.writeValueAsString(
                        plan.get("pairwiseJaccard"));
                var cloud1 = providers.get("cloud1");
                var cloud2 = providers.get("cloud2");
                var cloud3 = providers.get("cloud3");
                jdbc.update("""
                        INSERT INTO placement_strategy_results (
                            experiment_id, strategy,
                            cloud1_chunk_count, cloud2_chunk_count, cloud3_chunk_count,
                            cloud1_storage_bytes, cloud2_storage_bytes, cloud3_storage_bytes,
                            pairwise_jaccard_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        experimentId.longValue(), strategyEntry.getKey(),
                        cloud1.chunkCount(), cloud2.chunkCount(), cloud3.chunkCount(),
                        cloud1.storageBytes(), cloud2.storageBytes(), cloud3.storageBytes(),
                        pairwiseJson);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Placement experiment results could not be serialized.", e);
        }
    }

    private List<LeakageAwarePlacementService.ChunkCandidate> ownedCandidates(long userId) {
        return candidates("""
                SELECT DISTINCT c.chunk_hash, c.chunk_size,
                       COALESCE(m.similarity_signature, c.chunk_hash) AS fingerprint
                  FROM file_chunks c
                  JOIN files f ON f.id = c.file_id
                  JOIN chunk_metadata m ON m.chunk_hash = c.chunk_hash
                 WHERE f.user_id = ? AND f.upload_status = 'UPLOADED'
                """, userId);
    }

    private List<LeakageAwarePlacementService.ChunkCandidate> allCandidates() {
        return candidates("""
                SELECT DISTINCT c.chunk_hash, c.chunk_size,
                       COALESCE(m.similarity_signature, c.chunk_hash) AS fingerprint
                  FROM file_chunks c
                  JOIN files f ON f.id = c.file_id
                  JOIN chunk_metadata m ON m.chunk_hash = c.chunk_hash
                 WHERE f.upload_status = 'UPLOADED'
                """);
    }

    private List<LeakageAwarePlacementService.ChunkCandidate> candidates(
            String sql, Object... args) {
        Set<String> seen = new LinkedHashSet<>();
        List<LeakageAwarePlacementService.ChunkCandidate> result = new ArrayList<>();
        for (var row : jdbc.queryForList(sql, args)) {
            String hash = String.valueOf(row.get("chunk_hash"));
            if (!seen.add(hash)) continue;
            result.add(new LeakageAwarePlacementService.ChunkCandidate(
                    hash,
                    ((Number) row.get("chunk_size")).intValue(),
                    String.valueOf(row.get("fingerprint"))));
        }
        return result;
    }

    private LeakageAwarePlacementService.Strategy strategyFrom(
            Map<String, Object> request,
            LeakageAwarePlacementService.Strategy fallback) {
        if (request == null || !request.containsKey("strategy")) return fallback;
        try {
            return LeakageAwarePlacementService.Strategy.valueOf(
                    String.valueOf(request.get("strategy")).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Strategy must be ROUND_ROBIN, RANDOM, or LEAKAGE_AWARE.");
        }
    }

    private long seedFrom(Map<String, Object> request) {
        if (request == null || request.get("seed") == null) return 20261119L;
        try {
            return Long.parseLong(String.valueOf(request.get("seed")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Placement seed must be an integer.");
        }
    }

    private Map<String, Object> planResponse(
            LeakageAwarePlacementService.PlacementPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", plan.strategy().name());
        result.put("seed", plan.randomSeed());
        result.put("assignments", plan.assignments());
        result.put("providers", plan.providers());
        result.put("pairwiseJaccard", plan.pairMeasurements());
        result.put("validChunkCount", plan.assignments().size());
        return result;
    }

    public record ExperimentHistory(
            LocalDateTime ranAt,
            long ranByUserId,
            long seed,
            int inputChunkCount,
            Map<String, Object> comparison) {}
}
