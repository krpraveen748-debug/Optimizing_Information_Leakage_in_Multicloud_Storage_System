package com.praveen.multicloud.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Educational, deterministic greedy placement for three local cloud providers.
 *
 * This is not a reproduction of BFS MinHash/SPClustering. It uses the project's
 * serialized similarity signatures as fingerprint sets and greedily assigns each
 * unique chunk to the provider that minimizes overlap with already placed datasets
 * while applying a storage-load penalty.
 */
@Service
public class LeakageAwarePlacementService {
    public static final List<String> PROVIDERS = List.of("cloud1", "cloud2", "cloud3");

    private final JaccardSimilarityService jaccard;
    private final double overlapWeight;
    private final double balanceWeight;
    private final long randomSeed;

    public LeakageAwarePlacementService(
            JaccardSimilarityService jaccard,
            @Value("${app.placement.overlap-weight:1.0}") double overlapWeight,
            @Value("${app.placement.balance-weight:0.25}") double balanceWeight,
            @Value("${app.placement.random-seed:20261119}") long randomSeed) {
        if (!Double.isFinite(overlapWeight) || overlapWeight < 0.0
                || !Double.isFinite(balanceWeight) || balanceWeight < 0.0) {
            throw new IllegalArgumentException("Placement weights must be finite and non-negative.");
        }
        this.jaccard = jaccard;
        this.overlapWeight = overlapWeight;
        this.balanceWeight = balanceWeight;
        this.randomSeed = randomSeed;
    }

    public PlacementPlan createPlan(Strategy strategy, Collection<ChunkCandidate> chunks) {
        return createPlan(strategy, chunks, randomSeed);
    }

    public PlacementPlan createPlan(
            Strategy strategy, Collection<ChunkCandidate> chunks, long seed) {
        if (strategy == null) throw new IllegalArgumentException("Placement strategy is required.");
        if (chunks == null) throw new IllegalArgumentException("Chunks are required.");

        LinkedHashSet<ChunkCandidate> unique = new LinkedHashSet<>();
        Set<String> seenHashes = new LinkedHashSet<>();
        for (ChunkCandidate chunk : chunks) {
            if (chunk == null) throw new IllegalArgumentException("Chunk candidates must not be null.");
            if (chunk.hash() == null || chunk.hash().isBlank()) {
                throw new IllegalArgumentException("Chunk hash is required.");
            }
            if (chunk.sizeBytes() < 0) throw new IllegalArgumentException("Chunk size must not be negative.");
            if (chunk.fingerprint() == null || chunk.fingerprint().isBlank()) {
                throw new IllegalArgumentException("Chunk fingerprint is required.");
            }
            if (seenHashes.add(chunk.hash())) {
                unique.add(chunk);
            }
        }

        List<ChunkCandidate> input = new ArrayList<>(unique);
        Map<String, String> assignments = new LinkedHashMap<>();
        for (ChunkCandidate chunk : input) {
            if (assignments.containsKey(chunk.hash())) continue;
            assignments.put(chunk.hash(), "");
        }

        switch (strategy) {
            case ROUND_ROBIN -> placeRoundRobin(assignments);
            case RANDOM -> placeRandom(assignments, seed);
            case LEAKAGE_AWARE -> placeLeakageAware(assignments, input);
        }

        Map<String, ProviderMeasurement> providers = measurements(assignments, input);
        return new PlacementPlan(strategy, seed, Map.copyOf(assignments),
                Map.copyOf(providers), pairMeasurements(providers));
    }

    private void placeRoundRobin(Map<String, String> assignments) {
        int index = 0;
        for (String hash : assignments.keySet()) {
            assignments.put(hash, PROVIDERS.get(index++ % PROVIDERS.size()));
        }
    }

    private void placeRandom(Map<String, String> assignments, long seed) {
        Random random = new Random(seed);
        for (String hash : assignments.keySet()) {
            assignments.put(hash, PROVIDERS.get(random.nextInt(PROVIDERS.size())));
        }
    }

    private void placeLeakageAware(
            Map<String, String> assignments, List<ChunkCandidate> input) {
        Map<String, Set<String>> fingerprints = new LinkedHashMap<>();
        Map<String, Long> loads = new LinkedHashMap<>();
        for (String provider : PROVIDERS) {
            fingerprints.put(provider, new LinkedHashSet<>());
            loads.put(provider, 0L);
        }

        long totalBytes = input.stream().mapToLong(ChunkCandidate::sizeBytes).sum();
        for (ChunkCandidate chunk : input) {
            if (!assignments.get(chunk.hash()).isEmpty()) continue;
            String selected = null;
            double selectedScore = Double.POSITIVE_INFINITY;
            for (String provider : PROVIDERS) {
                Set<String> prospective = new LinkedHashSet<>(fingerprints.get(provider));
                prospective.add(chunk.fingerprint());
                double overlapPenalty = 0.0;
                for (String other : PROVIDERS) {
                    if (other.equals(provider)) continue;
                    JaccardSimilarityService.Result result =
                            jaccard.calculate(prospective, fingerprints.get(other));
                    overlapPenalty += result.similarity();
                }
                double averageLoad = (loads.values().stream().mapToLong(Long::longValue).sum()
                        + chunk.sizeBytes()) / 3.0;
                double prospectiveLoad = loads.get(provider) + chunk.sizeBytes();
                double loadPenalty = totalBytes == 0
                        ? Math.abs(prospectiveLoad - averageLoad) / 1.0
                        : Math.abs(prospectiveLoad - averageLoad) / (double) totalBytes;
                double score = overlapWeight * overlapPenalty + balanceWeight * loadPenalty;
                if (score < selectedScore - 1e-12
                        || (Math.abs(score - selectedScore) <= 1e-12
                        && provider.compareTo(selected == null ? "" : selected) < 0)) {
                    selected = provider;
                    selectedScore = score;
                }
            }
            assignments.put(chunk.hash(), selected);
            fingerprints.get(selected).add(chunk.fingerprint());
            loads.put(selected, loads.get(selected) + chunk.sizeBytes());
        }
    }

    private Map<String, ProviderMeasurement> measurements(
            Map<String, String> assignments, List<ChunkCandidate> input) {
        Map<String, Set<String>> fingerprints = new LinkedHashMap<>();
        Map<String, Long> totalBytes = new LinkedHashMap<>();
        Map<String, Long> chunkCounts = new LinkedHashMap<>();
        for (String provider : PROVIDERS) {
            fingerprints.put(provider, new LinkedHashSet<>());
            totalBytes.put(provider, 0L);
            chunkCounts.put(provider, 0L);
        }
        Set<String> counted = new LinkedHashSet<>();
        for (ChunkCandidate chunk : input) {
            if (!counted.add(chunk.hash())) continue;
            String provider = assignments.get(chunk.hash());
            fingerprints.get(provider).add(chunk.fingerprint());
            totalBytes.put(provider, totalBytes.get(provider) + chunk.sizeBytes());
            chunkCounts.put(provider, chunkCounts.get(provider) + 1);
        }
        Map<String, ProviderMeasurement> result = new LinkedHashMap<>();
        for (String provider : PROVIDERS) {
            result.put(provider, new ProviderMeasurement(
                    chunkCounts.get(provider), totalBytes.get(provider),
                    Set.copyOf(fingerprints.get(provider))));
        }
        return result;
    }

    private List<PairMeasurement> pairMeasurements(
            Map<String, ProviderMeasurement> providers) {
        List<PairMeasurement> pairs = new ArrayList<>();
        for (int i = 0; i < PROVIDERS.size(); i++) {
            for (int j = i + 1; j < PROVIDERS.size(); j++) {
                String first = PROVIDERS.get(i);
                String second = PROVIDERS.get(j);
                JaccardSimilarityService.Result result = jaccard.calculate(
                        providers.get(first).fingerprints(),
                        providers.get(second).fingerprints());
                pairs.add(new PairMeasurement(first, second, result.intersectionCount(),
                        result.unionCount(), result.similarity()));
            }
        }
        return pairs;
    }

    public enum Strategy {
        ROUND_ROBIN,
        RANDOM,
        LEAKAGE_AWARE
    }

    public record ChunkCandidate(String hash, int sizeBytes, String fingerprint) {}

    public record ProviderMeasurement(
            long chunkCount, long storageBytes, Set<String> fingerprints) {}

    public record PairMeasurement(
            String providerA, String providerB, long intersectionCount,
            long unionCount, double jaccardSimilarity) {}

    public record PlacementPlan(
            Strategy strategy,
            long randomSeed,
            Map<String, String> assignments,
            Map<String, ProviderMeasurement> providers,
            List<PairMeasurement> pairMeasurements) {}
}
