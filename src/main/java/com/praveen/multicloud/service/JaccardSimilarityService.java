package com.praveen.multicloud.service;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Service
public class JaccardSimilarityService {
    public Result calculate(Collection<String> first, Collection<String> second) {
        Set<String> firstSet = distinct(first);
        Set<String> secondSet = distinct(second);
        Set<String> intersection = new HashSet<>(firstSet);
        intersection.retainAll(secondSet);
        Set<String> union = new HashSet<>(firstSet);
        union.addAll(secondSet);
        double similarity = union.isEmpty() ? 1.0 : (double) intersection.size() / union.size();
        return new Result(intersection.size(), union.size(), similarity);
    }

    private static Set<String> distinct(Collection<String> values) {
        Objects.requireNonNull(values, "fingerprints");
        Set<String> distinct = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Fingerprints must not be blank.");
            }
            distinct.add(value);
        }
        return distinct;
    }

    public record Result(long intersectionCount, long unionCount, double similarity) {
        public double percentage() {
            return similarity * 100.0;
        }
    }
}
