package com.praveen.multicloud.controller;

import com.praveen.multicloud.dto.ProviderLeakageAnalysisResponse;
import com.praveen.multicloud.dto.ProviderPairComparisonResponse;
import com.praveen.multicloud.service.LeakageAwarePlacementService;
import com.praveen.multicloud.service.ActivityLogService;
import com.praveen.multicloud.service.AdminAnalyticsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminAnalyticsController {
    private final AdminAnalyticsService analytics;
    private final ActivityLogService activities;

    public AdminAnalyticsController(
            AdminAnalyticsService analytics,
            ActivityLogService activities) {
        this.analytics = analytics;
        this.activities = activities;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return analytics.dashboard();
    }

    @GetMapping("/analytics/storage")
    public Map<String, Object> storage() {
        return analytics.storageAnalytics();
    }

    @GetMapping("/analytics/leakage")
    public Map<String, Object> leakage() {
        return analytics.leakageAnalytics();
    }

    @GetMapping("/analytics/activity")
    public List<Map<String, Object>> activity(
            @RequestParam(defaultValue = "50") int limit) {
        return activities.recentGlobal(limit);
    }

    @GetMapping("/reports/storage.csv")
    public ResponseEntity<byte[]> storageCsv() {
        Map<String, Object> data = analytics.storageAnalytics();
        String csv = "metric,value\n"
                + "total_original_file_size_bytes," + data.get("totalOriginalFileSizeBytes") + "\n"
                + "total_unique_chunk_storage_bytes," + data.get("totalUniqueChunkStorageBytes") + "\n"
                + "deduplicated_chunk_count," + data.get("deduplicatedChunkCount") + "\n"
                + "deduplication_savings_bytes," + data.get("deduplicationSavingsBytes") + "\n";
        return csvResponse("storage-usage-report.csv", csv);
    }

    @GetMapping("/reports/deduplication.csv")
    public ResponseEntity<byte[]> deduplicationCsv() {
        Map<String, Object> data = analytics.storageAnalytics();
        String csv = "metric,value\n"
                + "deduplicated_chunk_count," + data.get("deduplicatedChunkCount") + "\n"
                + "deduplication_savings_bytes," + data.get("deduplicationSavingsBytes") + "\n";
        return csvResponse("deduplication-report.csv", csv);
    }

    @GetMapping("/reports/distribution.csv")
    public ResponseEntity<byte[]> distributionCsv() {
        StringBuilder csv = new StringBuilder("provider,chunk_count,storage_bytes\n");
        for (Map<String, Object> provider : analytics.providerDistribution()) {
            csv.append(csvCell(provider.get("provider"))).append(',')
                    .append(provider.get("chunkCount")).append(',')
                    .append(provider.get("storageBytes")).append('\n');
        }
        return csvResponse("cloud-provider-distribution.csv", csv.toString());
    }

    @GetMapping("/reports/leakage.csv")
    public ResponseEntity<byte[]> leakageCsv() {
        StringBuilder csv = new StringBuilder(
                "analysis_id,created_at,status,overall_jaccard,overall_similarity_percent,"
                        + "provider_a,provider_b,fingerprint_count_a,fingerprint_count_b,"
                        + "shared_fingerprint_count,union_fingerprint_count,jaccard_similarity,"
                        + "shared_fingerprint_percentage,data_available,high_similarity\n");
        Object latest = analytics.leakageAnalytics().get("latest");
        if (latest instanceof ProviderLeakageAnalysisResponse analysis) {
            for (ProviderPairComparisonResponse pair : analysis.pairComparisons()) {
                appendCsvRow(csv,
                        analysis.analysisId(), analysis.createdAt(), analysis.status(),
                        analysis.overallJaccardSimilarity(), analysis.overallSimilarityPercent(),
                        pair.providerA(), pair.providerB(),
                        pair.distinctFingerprintCountA(), pair.distinctFingerprintCountB(),
                        pair.sharedFingerprintCount(), pair.unionFingerprintCount(),
                        pair.jaccardSimilarity(), pair.sharedFingerprintPercentage(),
                        pair.dataAvailable(), pair.highSimilarity());
            }
        }
        return csvResponse("information-leakage-report.csv", csv.toString());
    }

    @GetMapping("/reports/placement.csv")
    public ResponseEntity<byte[]> placementCsv() {
        Object latest = analytics.dashboard().get("latestPlacementExperiment");
        StringBuilder csv = new StringBuilder(
                "ran_at,ran_by_user_id,seed,input_chunk_count,strategy,provider,chunk_count,storage_bytes\n");
        if (latest instanceof PlacementController.ExperimentHistory experiment) {
            for (var entry : experiment.comparison().entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> strategyResult)
                        || !(strategyResult.get("providers") instanceof Map<?, ?> providers)) {
                    throw new IllegalStateException("Persisted placement results are invalid.");
                }
                for (String providerName : LeakageAwarePlacementService.PROVIDERS) {
                    Object providerResult = providers.get(providerName);
                    long chunks;
                    long bytes;
                    if (providerResult instanceof LeakageAwarePlacementService.ProviderMeasurement measurement) {
                        chunks = measurement.chunkCount();
                        bytes = measurement.storageBytes();
                    } else if (providerResult instanceof Map<?, ?> measurement) {
                        chunks = numericValue(measurement.get("chunkCount"));
                        bytes = numericValue(measurement.get("storageBytes"));
                    } else {
                        throw new IllegalStateException("Persisted provider measurements are invalid.");
                    }
                    appendCsvRow(csv, experiment.ranAt(), experiment.ranByUserId(),
                            experiment.seed(), experiment.inputChunkCount(),
                            entry.getKey(), providerName, chunks, bytes);
                }
            }
        }
        return csvResponse("placement-strategy-comparison.csv", csv.toString());
    }

    private static ResponseEntity<byte[]> csvResponse(String filename, String csv) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static String csvCell(Object value) {
        return csvSafe(value == null ? "" : String.valueOf(value), !(value instanceof Number));
    }

    private static String csvSafe(String value, boolean protectFormula) {
        String normalized = value.replace("\r", " ").replace("\n", " ");
        int firstNonWhitespace = 0;
        while (firstNonWhitespace < normalized.length()
                && Character.isWhitespace(normalized.charAt(firstNonWhitespace))) {
            firstNonWhitespace++;
        }
        if (protectFormula && firstNonWhitespace < normalized.length()
                && "=+-@".indexOf(normalized.charAt(firstNonWhitespace)) >= 0) {
            normalized = "'" + normalized;
        }
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    private static void appendCsvRow(StringBuilder csv, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) csv.append(',');
            csv.append(csvCell(values[index]));
        }
        csv.append('\n');
    }

    private static long numericValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        throw new IllegalStateException("Persisted provider measurement is invalid.");
    }
}
