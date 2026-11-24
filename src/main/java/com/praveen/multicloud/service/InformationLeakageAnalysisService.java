package com.praveen.multicloud.service;

import com.praveen.multicloud.dto.ProviderLeakageAnalysisResponse;
import com.praveen.multicloud.dto.ProviderLeakageHistoryItem;
import com.praveen.multicloud.dto.ProviderPairComparisonResponse;
import com.praveen.multicloud.entity.CloudProviderEntity;
import com.praveen.multicloud.entity.ProviderLeakageAnalysisEntity;
import com.praveen.multicloud.entity.ProviderLeakagePairEntity;
import com.praveen.multicloud.entity.ProviderStatus;
import com.praveen.multicloud.repository.CloudProviderRepository;
import com.praveen.multicloud.repository.ProviderLeakageAnalysisRepository;
import com.praveen.multicloud.repository.ProviderLeakagePairRepository;
import com.praveen.multicloud.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class InformationLeakageAnalysisService {
    private static final List<String> REQUIRED_PROVIDERS = List.of("cloud1", "cloud2", "cloud3");

    private final JdbcTemplate jdbc;
    private final CloudProviderRepository providers;
    private final ProviderLeakageAnalysisRepository analyses;
    private final ProviderLeakagePairRepository pairs;
    private final UserRepository users;
    private final JaccardSimilarityService jaccard;
    private final double highSimilarityThreshold;

    public InformationLeakageAnalysisService(
            JdbcTemplate jdbc,
            CloudProviderRepository providers,
            ProviderLeakageAnalysisRepository analyses,
            ProviderLeakagePairRepository pairs,
            UserRepository users,
            JaccardSimilarityService jaccard,
            @Value("${app.leakage.high-similarity-threshold:0.75}") double highSimilarityThreshold) {
        if (!Double.isFinite(highSimilarityThreshold)
                || highSimilarityThreshold < 0.0 || highSimilarityThreshold > 1.0) {
            throw new IllegalArgumentException("High-similarity threshold must be between 0 and 1.");
        }
        this.jdbc = jdbc;
        this.providers = providers;
        this.analyses = analyses;
        this.pairs = pairs;
        this.users = users;
        this.jaccard = jaccard;
        this.highSimilarityThreshold = highSimilarityThreshold;
    }

    @Transactional
    public ProviderLeakageAnalysisResponse runAnalysis(long adminUserId) {
        List<CloudProviderEntity> activeProviders = providers.findByProviderNameInAndStatusOrderByProviderNameAsc(
                REQUIRED_PROVIDERS, ProviderStatus.ACTIVE);
        if (activeProviders.size() != REQUIRED_PROVIDERS.size()) {
            throw new ProviderConfigurationException("All three simulated providers must be active.");
        }

        Map<Long, Set<String>> fingerprintsByProvider = new HashMap<>();
        for (CloudProviderEntity provider : activeProviders) {
            fingerprintsByProvider.put(provider.getId(), new HashSet<>());
        }
        Long[] providerIds = activeProviders.stream().map(CloudProviderEntity::getId).toArray(Long[]::new);
        List<ProviderFingerprint> fingerprints = jdbc.query("""
                SELECT DISTINCT chunks.cloud_provider_id, chunks.chunk_hash
                  FROM file_chunks chunks
                  JOIN files stored_file ON stored_file.id = chunks.file_id
                 WHERE stored_file.upload_status = 'UPLOADED'
                   AND chunks.cloud_provider_id IN (?, ?, ?)
                """, (result, row) -> new ProviderFingerprint(
                result.getLong("cloud_provider_id"), result.getString("chunk_hash")),
                providerIds[0], providerIds[1], providerIds[2]);
        for (ProviderFingerprint fingerprint : fingerprints) {
            Set<String> providerFingerprints = fingerprintsByProvider.get(fingerprint.providerId());
            if (providerFingerprints != null) {
                providerFingerprints.add(fingerprint.chunkHash());
            }
        }

        List<PairMeasurement> measurements = measurePairs(activeProviders, fingerprintsByProvider);
        long comparablePairCount = measurements.stream().filter(PairMeasurement::dataAvailable).count();
        long highSimilarityPairCount = measurements.stream().filter(PairMeasurement::highSimilarity).count();
        Double overallJaccard = comparablePairCount == 0
                ? null
                : measurements.stream()
                        .filter(PairMeasurement::dataAvailable)
                        .mapToDouble(PairMeasurement::jaccardSimilarity)
                        .average()
                        .orElseThrow();

        ProviderLeakageAnalysisEntity newAnalysis = new ProviderLeakageAnalysisEntity();
        newAnalysis.setCreatedBy(users.getReferenceById(adminUserId));
        newAnalysis.setOverallJaccard(overallJaccard);
        newAnalysis.setHighSimilarityThreshold(highSimilarityThreshold);
        newAnalysis.setAnalyzedPairCount(Math.toIntExact(comparablePairCount));
        newAnalysis.setHighSimilarityPairCount(Math.toIntExact(highSimilarityPairCount));
        ProviderLeakageAnalysisEntity savedAnalysis = analyses.saveAndFlush(newAnalysis);

        List<ProviderLeakagePairEntity> pairEntities = measurements.stream()
                .map(measurement -> toEntity(savedAnalysis, measurement))
                .toList();
        pairs.saveAll(pairEntities);
        return toRunResponse(savedAnalysis, measurements);
    }

    @Transactional(readOnly = true)
    public ProviderLeakageAnalysisResponse latestAnalysis() {
        ProviderLeakageAnalysisEntity latest = analyses.findFirstByOrderByCreatedAtDesc()
                .orElseThrow(() -> new NoSuchElementException("No provider leakage analysis is available."));
        return toPersistedResponse(latest, pairs.findPairsByAnalysisId(latest.getId()));
    }

    @Transactional(readOnly = true)
    public List<ProviderLeakageHistoryItem> history() {
        return analyses.findTop50ByOrderByCreatedAtDesc().stream()
                .map(this::toHistoryItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProviderPairComparisonResponse> pairComparisons(long analysisId) {
        if (!analyses.existsById(analysisId)) {
            throw new NoSuchElementException("Provider leakage analysis not found.");
        }
        return pairs.findPairsByAnalysisId(analysisId).stream()
                .map(this::toPairResponse)
                .toList();
    }

    private List<PairMeasurement> measurePairs(
            List<CloudProviderEntity> activeProviders,
            Map<Long, Set<String>> fingerprintsByProvider) {
        List<PairMeasurement> measurements = new ArrayList<>(3);
        for (int first = 0; first < activeProviders.size(); first++) {
            for (int second = first + 1; second < activeProviders.size(); second++) {
                CloudProviderEntity providerA = activeProviders.get(first);
                CloudProviderEntity providerB = activeProviders.get(second);
                Set<String> firstSet = fingerprintsByProvider.get(providerA.getId());
                Set<String> secondSet = fingerprintsByProvider.get(providerB.getId());
                JaccardSimilarityService.Result result = jaccard.calculate(firstSet, secondSet);
                boolean dataAvailable = result.unionCount() > 0;
                Double similarity = dataAvailable ? result.similarity() : null;
                boolean highSimilarity = dataAvailable && similarity >= highSimilarityThreshold;
                measurements.add(new PairMeasurement(
                        providerA,
                        providerB,
                        firstSet.size(),
                        secondSet.size(),
                        result.intersectionCount(),
                        result.unionCount(),
                        similarity,
                        dataAvailable ? result.percentage() : null,
                        dataAvailable,
                        highSimilarity));
            }
        }
        return measurements;
    }

    private ProviderLeakagePairEntity toEntity(
            ProviderLeakageAnalysisEntity analysis, PairMeasurement measurement) {
        ProviderLeakagePairEntity pair = new ProviderLeakagePairEntity();
        pair.setAnalysis(analysis);
        pair.setProviderA(measurement.providerA());
        pair.setProviderB(measurement.providerB());
        pair.setFingerprintCountA(measurement.fingerprintCountA());
        pair.setFingerprintCountB(measurement.fingerprintCountB());
        pair.setSharedFingerprintCount(measurement.sharedFingerprintCount());
        pair.setUnionFingerprintCount(measurement.unionFingerprintCount());
        pair.setJaccardSimilarity(measurement.jaccardSimilarity());
        pair.setSharedFingerprintPercentage(measurement.sharedFingerprintPercentage());
        pair.setDataAvailable(measurement.dataAvailable());
        pair.setHighSimilarity(measurement.highSimilarity());
        return pair;
    }

    private ProviderLeakageAnalysisResponse toRunResponse(
            ProviderLeakageAnalysisEntity analysis, List<PairMeasurement> measurements) {
        return new ProviderLeakageAnalysisResponse(
                analysis.getId(),
                analysis.getCreatedAt(),
                analysis.getOverallJaccard() == null ? "NO_DATA" : "AVAILABLE",
                analysis.getOverallJaccard(),
                percentage(analysis.getOverallJaccard()),
                analysis.getHighSimilarityThreshold(),
                analysis.getAnalyzedPairCount(),
                analysis.getHighSimilarityPairCount(),
                measurements.stream().map(this::toPairResponse).toList());
    }

    private ProviderLeakageAnalysisResponse toPersistedResponse(
            ProviderLeakageAnalysisEntity analysis, List<ProviderLeakagePairEntity> entities) {
        return new ProviderLeakageAnalysisResponse(
                analysis.getId(),
                analysis.getCreatedAt(),
                analysis.getOverallJaccard() == null ? "NO_DATA" : "AVAILABLE",
                analysis.getOverallJaccard(),
                percentage(analysis.getOverallJaccard()),
                analysis.getHighSimilarityThreshold(),
                analysis.getAnalyzedPairCount(),
                analysis.getHighSimilarityPairCount(),
                entities.stream().map(this::toPairResponse).toList());
    }

    private ProviderLeakageHistoryItem toHistoryItem(ProviderLeakageAnalysisEntity analysis) {
        return new ProviderLeakageHistoryItem(
                analysis.getId(),
                analysis.getCreatedAt(),
                analysis.getOverallJaccard() == null ? "NO_DATA" : "AVAILABLE",
                analysis.getOverallJaccard(),
                percentage(analysis.getOverallJaccard()),
                analysis.getHighSimilarityThreshold(),
                analysis.getAnalyzedPairCount(),
                analysis.getHighSimilarityPairCount());
    }

    private ProviderPairComparisonResponse toPairResponse(PairMeasurement measurement) {
        return new ProviderPairComparisonResponse(
                measurement.providerA().getProviderName(),
                measurement.providerB().getProviderName(),
                measurement.fingerprintCountA(),
                measurement.fingerprintCountB(),
                measurement.sharedFingerprintCount(),
                measurement.unionFingerprintCount(),
                measurement.jaccardSimilarity(),
                measurement.sharedFingerprintPercentage(),
                measurement.dataAvailable(),
                measurement.highSimilarity());
    }

    private ProviderPairComparisonResponse toPairResponse(ProviderLeakagePairEntity pair) {
        return new ProviderPairComparisonResponse(
                pair.getProviderA().getProviderName(),
                pair.getProviderB().getProviderName(),
                pair.getFingerprintCountA(),
                pair.getFingerprintCountB(),
                pair.getSharedFingerprintCount(),
                pair.getUnionFingerprintCount(),
                pair.getJaccardSimilarity(),
                pair.getSharedFingerprintPercentage(),
                pair.getDataAvailable(),
                pair.getHighSimilarity());
    }

    private static Double percentage(Double similarity) {
        return similarity == null ? null : similarity * 100.0;
    }

    private record ProviderFingerprint(long providerId, String chunkHash) {}

    private record PairMeasurement(
            CloudProviderEntity providerA,
            CloudProviderEntity providerB,
            long fingerprintCountA,
            long fingerprintCountB,
            long sharedFingerprintCount,
            long unionFingerprintCount,
            Double jaccardSimilarity,
            Double sharedFingerprintPercentage,
            boolean dataAvailable,
            boolean highSimilarity) {}

    public static class ProviderConfigurationException extends RuntimeException {
        public ProviderConfigurationException(String message) {
            super(message);
        }
    }
}
