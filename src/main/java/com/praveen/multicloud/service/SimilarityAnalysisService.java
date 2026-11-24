package com.praveen.multicloud.service;

import com.praveen.multicloud.dto.SimilarityComparisonResponse;
import com.praveen.multicloud.entity.FileEntity;
import com.praveen.multicloud.entity.UploadStatus;
import com.praveen.multicloud.repository.FileChunkRepository;
import com.praveen.multicloud.repository.FileChunkRepository.SimilarityChunkProjection;
import com.praveen.multicloud.repository.FileRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class SimilarityAnalysisService {
    private final FileRepository files;
    private final FileChunkRepository chunks;
    private final SimilaritySignatureService similaritySignatures;
    private final MinHashService minHash;
    private final BloomFilterService bloomFilters;
    private final ActivityLogService activities;

    public SimilarityAnalysisService(
            FileRepository files,
            FileChunkRepository chunks,
            SimilaritySignatureService similaritySignatures,
            MinHashService minHash,
            BloomFilterService bloomFilters,
            ActivityLogService activities) {
        this.files = files;
        this.chunks = chunks;
        this.similaritySignatures = similaritySignatures;
        this.minHash = minHash;
        this.bloomFilters = bloomFilters;
        this.activities = activities;
    }

    @Transactional
    public SimilarityComparisonResponse compare(long userId, long fileId, long candidateFileId) {
        FileEntity sourceFile = requireOwnedUpload(fileId, userId);
        FileEntity candidateFile = requireOwnedUpload(candidateFileId, userId);
        if (fileId == candidateFileId) {
            throw new IllegalArgumentException("Choose two different uploaded files to compare.");
        }

        List<SimilarityChunkProjection> sourceChunks = chunks.findSimilarityMetadataByFileId(fileId);
        List<SimilarityChunkProjection> candidateChunks = chunks.findSimilarityMetadataByFileId(candidateFileId);
        Set<String> sourceHashes = hashes(sourceChunks);
        Set<String> candidateHashes = hashes(candidateChunks);

        BloomFilterService.Filter candidateFilter = bloomFilters.createFilter();
        candidateHashes.forEach(candidateFilter::add);
        int bloomPositiveCount = (int) sourceHashes.stream().filter(candidateFilter::mightContain).count();
        int exactMatchingChunkCount = (int) sourceHashes.stream().filter(candidateHashes::contains).count();

        List<MinHashService.Signature> sourceSignatures = parseSignatures(sourceChunks);
        List<MinHashService.Signature> candidateSignatures = parseSignatures(candidateChunks);
        boolean signaturesAvailable = sourceSignatures != null && candidateSignatures != null
                && !sourceSignatures.isEmpty() && !candidateSignatures.isEmpty();
        Double similarity = null;
        String status;
        String message;
        if (signaturesAvailable) {
            similarity = minHash.estimateJaccard(
                    minHash.union(sourceSignatures), minHash.union(candidateSignatures));
            status = "AVAILABLE";
            message = "Similarity is estimated from sampled plaintext byte shingles using MinHash.";
        } else {
            status = "SIGNATURES_UNAVAILABLE";
            message = sourceChunks.isEmpty() || candidateChunks.isEmpty()
                    ? "At least one file has no chunks to compare. Choose non-empty files."
                    : "Signatures are missing or incompatible. Upload both files again to refresh them.";
        }

        SimilarityComparisonResponse response = new SimilarityComparisonResponse(
                fileId,
                candidateFileId,
                status,
                message,
                similarity,
                similarity == null ? null : similarity * 100.0,
                minHash.permutationCount(),
                sourceChunks.size(),
                candidateChunks.size(),
                bloomPositiveCount,
                exactMatchingChunkCount,
                candidateFilter.estimatedFalsePositiveProbability());
        activities.record(userId, "SIMILARITY_ANALYSIS",
                "Compared " + sourceFile.getOriginalFilename() + " with "
                        + candidateFile.getOriginalFilename() + "; result "
                        + response.status() + "; estimated similarity "
                        + (response.similarityPercent() == null
                                ? "unavailable"
                                : response.similarityPercent() + "%")
                        + "; exact matching chunks " + response.exactMatchingChunkCount() + ".",
                sourceFile.getOriginalFilename(), response.status());
        return response;
    }

    private FileEntity requireOwnedUpload(long fileId, long userId) {
        return files.findByIdAndUser_IdAndUploadStatus(fileId, userId, UploadStatus.UPLOADED)
                .orElseThrow(() -> new NoSuchElementException("File not found."));
    }

    private static Set<String> hashes(List<SimilarityChunkProjection> chunks) {
        Set<String> hashes = new HashSet<>();
        for (SimilarityChunkProjection chunk : chunks) {
            String hash = chunk.getChunkHash();
            if (hash == null || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("Stored chunk fingerprint is invalid.");
            }
            hashes.add(hash);
        }
        return hashes;
    }

    private List<MinHashService.Signature> parseSignatures(List<SimilarityChunkProjection> chunks) {
        if (chunks.isEmpty()) {
            return null;
        }
        List<MinHashService.Signature> signatures = new ArrayList<>(chunks.size());
        for (SimilarityChunkProjection chunk : chunks) {
            String serialized = chunk.getSimilaritySignature();
            if (serialized == null) {
                return null;
            }
            try {
                signatures.add(similaritySignatures.parse(serialized));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return signatures;
    }
}
