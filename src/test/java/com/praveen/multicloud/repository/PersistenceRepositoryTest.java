package com.praveen.multicloud.repository;

import com.praveen.multicloud.entity.ActivityLogEntity;
import com.praveen.multicloud.entity.ChunkMetadataEntity;
import com.praveen.multicloud.entity.CloudProviderEntity;
import com.praveen.multicloud.entity.EncryptionStatus;
import com.praveen.multicloud.entity.FileChunkEntity;
import com.praveen.multicloud.entity.FileEntity;
import com.praveen.multicloud.entity.LeakageAnalysisEntity;
import com.praveen.multicloud.entity.UploadStatus;
import com.praveen.multicloud.entity.UserEntity;
import com.praveen.multicloud.entity.UserRole;
import com.praveen.multicloud.entity.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "DB_USERNAME=sa",
        "DB_PASSWORD="
})
class PersistenceRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private FileRepository files;
    @Autowired private FileChunkRepository chunks;
    @Autowired private CloudProviderRepository providers;
    @Autowired private ChunkMetadataRepository metadata;
    @Autowired private LeakageAnalysisRepository leakage;
    @Autowired private ActivityLogRepository activity;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistsRelationshipsAndRunsRequiredRepositoryQueries() {
        UserEntity user = users.saveAndFlush(newUser("praveen", "praveen@example.test"));
        FileEntity file = files.saveAndFlush(newFile(user, "report.pdf"));
        CloudProviderEntity provider = providers.saveAndFlush(newProvider("cloud1"));
        ChunkMetadataEntity signature = metadata.saveAndFlush(newMetadata("a".repeat(64), 5));

        FileChunkEntity chunk = new FileChunkEntity();
        chunk.setFile(file);
        chunk.setChunkNumber(0);
        chunk.setChunkHash(signature.getChunkHash());
        chunk.setChunkSize(5);
        chunk.setCloudProvider(provider);
        chunk.setStoragePath("cloud1/file-1/chunk-0.bin");
        chunk.setEncryptionStatus(EncryptionStatus.NOT_ENCRYPTED);
        chunks.saveAndFlush(chunk);

        LeakageAnalysisEntity analysis = new LeakageAnalysisEntity();
        analysis.setFile(file);
        analysis.setPlacementAlgorithm("LEAKAGE_AWARE_HEURISTIC");
        analysis.setEstimatedLeakage(0.0);
        leakage.saveAndFlush(analysis);

        ActivityLogEntity log = new ActivityLogEntity();
        log.setUser(user);
        log.setActivityType("FILE_UPLOAD");
        log.setDescription("Uploaded report.pdf");
        activity.saveAndFlush(log);

        assertEquals(user.getId(), users.findByUsername("praveen").orElseThrow().getId());
        assertEquals(user.getId(), users.findByEmail("praveen@example.test").orElseThrow().getId());
        assertEquals(1, files.findByUser_IdOrderByCreatedAtDesc(user.getId()).size());
        assertEquals(1, files.countByUser_Id(user.getId()));
        assertEquals(1, files.count());
        assertEquals(1, chunks.findByFile_IdOrderByChunkNumberAsc(file.getId()).size());
        assertEquals(1, chunks.findByChunkHash(signature.getChunkHash()).size());
        assertEquals(1, chunks.countByFile_Id(file.getId()));
        assertTrue(metadata.existsByChunkHash(signature.getChunkHash()));
        assertEquals(provider.getId(), providers.findByProviderName("cloud1").orElseThrow().getId());
        CloudProviderStorageUsage usage = providers.findStorageUsageByProvider().getFirst();
        assertEquals(1L, usage.getTotalChunks());
        assertEquals(5L, usage.getTotalStorageUsed());
        assertEquals(1, leakage.findByFile_IdOrderByAnalysisDateDesc(file.getId()).size());
        assertEquals(1, leakage.findTop100ByOrderByAnalysisDateDesc().size());
        assertEquals(1, activity.findTop100ByOrderByActivityTimeDesc().size());
        assertEquals(1, activity.findTop100ByUser_IdOrderByActivityTimeDesc(user.getId()).size());
        assertNotNull(file.getCreatedAt());
    }

    @Test
    void enforcesUniqueUsername() {
        users.saveAndFlush(newUser("unique-user", "unique@example.test"));

        assertThrows(DataIntegrityViolationException.class,
                () -> users.saveAndFlush(newUser("unique-user", "other@example.test")));
    }

    @Test
    void enforcesUniqueEmail() {
        users.saveAndFlush(newUser("unique-user", "unique@example.test"));

        assertThrows(DataIntegrityViolationException.class,
                () -> users.saveAndFlush(newUser("other-user", "unique@example.test")));
    }

    @Test
    void enforcesUniqueChunkHash() {
        ChunkMetadataEntity first = metadata.saveAndFlush(newMetadata("b".repeat(64), 8));
        assertNotNull(first.getId());
        assertThrows(DataIntegrityViolationException.class,
                () -> metadata.saveAndFlush(newMetadata("b".repeat(64), 8)));
    }

    @Test
    void enforcesFileUserForeignKey() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO files (
                    user_id, original_filename, stored_filename, file_size,
                    content_type, total_chunks, upload_status, created_at
                ) VALUES (999999, 'orphan.bin', 'orphan.bin', 1, NULL, 0, 'UPLOADED', CURRENT_TIMESTAMP)
                """));
    }

    private static UserEntity newUser(String username, String email) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword("not-a-real-password");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private static FileEntity newFile(UserEntity user, String filename) {
        FileEntity file = new FileEntity();
        file.setUser(user);
        file.setOriginalFilename(filename);
        file.setStoredFilename(filename);
        file.setFileSize(5L);
        file.setContentType("application/pdf");
        file.setTotalChunks(1);
        file.setUploadStatus(UploadStatus.UPLOADED);
        return file;
    }

    private static CloudProviderEntity newProvider(String name) {
        CloudProviderEntity provider = new CloudProviderEntity();
        provider.setProviderName(name);
        provider.setStorageDirectory("./storage/" + name);
        return provider;
    }

    private static ChunkMetadataEntity newMetadata(String hash, int size) {
        ChunkMetadataEntity chunkMetadata = new ChunkMetadataEntity();
        chunkMetadata.setChunkHash(hash);
        chunkMetadata.setChunkSize(size);
        chunkMetadata.setReferenceCount(1L);
        return chunkMetadata;
    }
}
