package com.praveen.multicloud.service;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class PlacementService {
    private final List<String> clouds = List.of("cloud1", "cloud2", "cloud3");

    // Baseline: simple round-robin placement.
    public String roundRobin(int chunkIndex) {
        return clouds.get(chunkIndex % clouds.size());
    }

    // Educational leakage-aware heuristic:
    // keep chunks with the same signature together; spread different signatures.
    // This is NOT an exact reproduction of the paper's BFS MinHash/SPClustering.
    public String leakageAware(String signature) {
        int bucket = Math.floorMod(signature.hashCode(), clouds.size());
        return clouds.get(bucket);
    }

    public double estimateLeakage(List<String> signatures, List<String> placements) {
        if (signatures.size() < 2) return 0.0;
        int similarPairs = 0, sameCloudPairs = 0;
        for (int i = 0; i < signatures.size(); i++) {
            for (int j = i + 1; j < signatures.size(); j++) {
                // Exact duplicate signatures are treated as maximally similar.
                if (signatures.get(i).equals(signatures.get(j))) {
                    similarPairs++;
                    if (placements.get(i).equals(placements.get(j))) sameCloudPairs++;
                }
            }
        }
        return similarPairs == 0 ? 0.0 : (100.0 * sameCloudPairs / similarPairs);
    }
}
